package de.craftplay.samsungtvbridge.data

import de.craftplay.samsungtvbridge.model.ActionResponse
import de.craftplay.samsungtvbridge.model.AppEntry
import de.craftplay.samsungtvbridge.model.DeviceEntry
import de.craftplay.samsungtvbridge.model.DeviceRegistryResponse
import de.craftplay.samsungtvbridge.model.DeviceScanSummary
import de.craftplay.samsungtvbridge.model.SourceEntry
import de.craftplay.samsungtvbridge.model.StatusResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SamsungDirectTvClient(private val store: DirectTvStore) {
    private val standardHttpClient = OkHttpClient.Builder()
        .connectTimeout(800, TimeUnit.MILLISECONDS)
        .readTimeout(1800, TimeUnit.MILLISECONDS)
        .callTimeout(2500, TimeUnit.MILLISECONDS)
        .build()
    private val insecureHttpClient = OkHttpClient.Builder()
        .connectTimeout(1000, TimeUnit.MILLISECONDS)
        .readTimeout(2200, TimeUnit.MILLISECONDS)
        .callTimeout(3000, TimeUnit.MILLISECONDS)
        .sslSocketFactory(insecureSslSocketFactory, insecureTrustManager)
        .hostnameVerifier(allHostsValid)
        .build()

    suspend fun getStatus(): StatusResponse {
        val device = store.getActiveDevice()
            ?: return StatusResponse(name = "Kein TV ausgewählt", selectionRequired = true)

        val status = when (device.deviceType.lowercase()) {
            "lg" -> probeLgStatus(device)
            "tivo" -> probeTivoStatus(device)
            else -> probeSamsungStatus(device)
        }

        return StatusResponse(
            online = status.online,
            port = status.port,
            tvIp = device.ip,
            name = device.name
        )
    }

    suspend fun scanDevices(): Pair<DeviceRegistryResponse, DeviceScanSummary> = coroutineScope {
        val prefix = inferSubnetPrefix() ?: throw IllegalStateException("Kein lokales IPv4-Heimnetz erkannt.")
        val localIp = inferLocalIpv4()
        val semaphore = Semaphore(32)
        val addresses = (1..254).map { "$prefix.$it" }.filterNot { it == localIp }
        val now = Instant.now().toString()

        val found = addresses.map { ip ->
            async(Dispatchers.IO) {
                semaphore.withPermit { discoverDevice(ip, now) }
            }
        }.awaitAll().filterNotNull()

        val registry = store.upsertDevices(found)
        registry to DeviceScanSummary(scannedCandidateCount = addresses.size, foundCount = found.size)
    }

    fun getRegistry(): DeviceRegistryResponse = store.getRegistry()

    fun selectDevice(deviceId: String): DeviceEntry = store.selectDevice(deviceId)

    fun removeDevice(deviceId: String): DeviceRegistryResponse = store.removeDevice(deviceId)

    suspend fun powerOn(): ActionResponse = withSelectedDevice { device ->
        val mac = device.mac?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Für dieses Gerät ist keine MAC-Adresse bekannt. Bitte zuerst scannen.")
        sendWakeOnLan(mac)
        ActionResponse(message = "Wake-on-LAN wurde gesendet.")
    }

    suspend fun powerOff(): ActionResponse = withSelectedDevice { device ->
        val port = when (device.deviceType.lowercase()) {
            "lg" -> lgTurnOff(device)
            "tivo" -> {
                sendTivoCode(device, "POWER")
                31339
            }
            else -> samsungPowerOff(device)
        }
        ActionResponse(message = "Ausschalten gesendet.", port = port)
    }

    suspend fun sendRemoteKey(key: String): ActionResponse = withSelectedDevice { device ->
        val port = when (device.deviceType.lowercase()) {
            "lg" -> lgSendKey(device, key)
            "tivo" -> {
                sendTivoCode(device, mapTivoKey(key))
                31339
            }
            else -> samsungSendKey(device, key)
        }
        ActionResponse(message = "Taste gesendet.", key = key, port = port)
    }

    suspend fun setSource(name: String): ActionResponse = withSelectedDevice { device ->
        when (device.deviceType.lowercase()) {
            "lg" -> {
                if (normalizeName(name) == normalizeName("Home Dashboard")) {
                    lgSendKey(device, "KEY_HOME")
                } else if (normalizeName(name) == normalizeName("Live TV")) {
                    lgSendKey(device, "KEY_TV")
                } else {
                    throw IllegalStateException("Quelle für LG derzeit nicht unterstützt.")
                }
            }
            "tivo" -> throw IllegalStateException("Quellenwechsel für TiVo/Vestel ist noch experimentell nicht verfügbar.")
            else -> samsungSetSource(device, name)
        }
        ActionResponse(message = "Quelle gewechselt: $name", source = name)
    }

    suspend fun launchApp(name: String): ActionResponse = withSelectedDevice { device ->
        val app = resolveApp(device.deviceType, name)
        when (device.deviceType.lowercase()) {
            "lg" -> lgLaunchApp(device, app)
            "tivo" -> throw IllegalStateException("App-Start für TiVo/Vestel ist lokal noch nicht belastbar verfügbar.")
            else -> samsungLaunchApp(device, app)
        }
        ActionResponse(message = "App gestartet: ${app.name}", app = app.name, appId = app.appId)
    }

    suspend fun getDiagnostics(): String {
        val device = store.getActiveDevice()
            ?: return JSONObject()
                .put("selectionRequired", true)
                .put("message", "Bitte zuerst ein TV-Gerät auswählen.")
                .toString(2)

        val diagnostics = when (device.deviceType.lowercase()) {
            "lg" -> probeLgStatus(device)
            "tivo" -> probeTivoStatus(device)
            else -> probeSamsungStatus(device)
        }

        return JSONObject()
            .put("name", device.name)
            .put("deviceType", device.deviceType)
            .put("tvIp", device.ip)
            .put("online", diagnostics.online)
            .put("port", diagnostics.port ?: JSONObject.NULL)
            .put("modelName", device.modelName ?: JSONObject.NULL)
            .put("mac", device.mac ?: JSONObject.NULL)
            .toString(2)
    }

    private suspend fun discoverDevice(ip: String, now: String): DeviceEntry? {
        val samsung = probeSamsungIdentity(ip)
        if (samsung != null) {
            return DeviceEntry(
                id = samsung.duid ?: "samsung:$ip",
                ip = ip,
                name = samsung.name ?: "[Samsung] $ip",
                deviceType = "samsung",
                modelName = samsung.modelName,
                mac = samsung.mac,
                duid = samsung.duid,
                networkType = samsung.networkType,
                firstSeenAt = now,
                lastSeenAt = now
            )
        }

        if (isPortOpen(ip, 3000) || isPortOpen(ip, 3001)) {
            return DeviceEntry(
                id = "lg:$ip",
                ip = ip,
                name = "[LG] $ip",
                deviceType = "lg",
                firstSeenAt = now,
                lastSeenAt = now
            )
        }

        if (isPortOpen(ip, 31339)) {
            return DeviceEntry(
                id = "tivo:$ip",
                ip = ip,
                name = "[TiVo] $ip",
                deviceType = "tivo",
                firstSeenAt = now,
                lastSeenAt = now
            )
        }

        return null
    }

    private suspend fun samsungPowerOff(device: DeviceEntry): Int {
        val attempts = listOf<suspend () -> Int>(
            { samsungSendHoldKey(device, 8002, "KEY_POWER", 1200) },
            { samsungSendKey(device, "KEY_POWEROFF", 8002) },
            { samsungSendKey(device, "KEY_POWEROFF", 8001) }
        )
        var lastError: Throwable? = null
        for (attempt in attempts) {
            try {
                return attempt()
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException(lastError?.message ?: "Samsung Power-Off fehlgeschlagen.")
    }

    private suspend fun samsungSetSource(device: DeviceEntry, name: String) {
        val source = resolveSource(device.deviceType, name)
        samsungSendSequence(device, source.keys, source.delayMs, source.initialDelayMs)
    }

    private suspend fun samsungLaunchApp(device: DeviceEntry, app: AppEntry) {
        val candidates = listOfNotNull(app.appId, app.tizenAppId).distinct()
        var lastError: Throwable? = null
        for (appId in candidates) {
            for (secure in listOf(false, true)) {
                try {
                    val protocol = if (secure) "https" else "http"
                    val port = if (secure) 8002 else 8001
                    val client = if (secure) insecureHttpClient else standardHttpClient
                    val request = Request.Builder()
                        .url("$protocol://${device.ip}:$port/api/v2/applications/${URLEncoder.encode(appId, "UTF-8")}")
                        .post(ByteArray(0).toRequestBody(null))
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IllegalStateException("HTTP ${response.code} beim Samsung-App-Start.")
                        }
                    }
                    return
                } catch (error: Throwable) {
                    lastError = error
                }
            }
        }
        throw IllegalStateException(lastError?.message ?: "Samsung-App konnte nicht gestartet werden.")
    }

    private suspend fun samsungSendKey(device: DeviceEntry, key: String, preferredPort: Int? = null): Int {
        val ports = listOfNotNull(preferredPort, 8002, 8001).distinct()
        var lastError: Throwable? = null
        for (port in ports) {
            try {
                withSamsungRemote(device, port) { socket ->
                    socket.send(samsungRemotePayload(key, "Click"))
                    delay(120)
                }
                return port
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException(lastError?.message ?: "Samsung-Taste konnte nicht gesendet werden.")
    }

    private suspend fun samsungSendHoldKey(device: DeviceEntry, port: Int, key: String, holdMs: Int): Int {
        withSamsungRemote(device, port) { socket ->
            socket.send(samsungRemotePayload(key, "Press"))
            delay(holdMs.toLong())
            socket.send(samsungRemotePayload(key, "Release"))
            delay(120)
        }
        return port
    }

    private suspend fun samsungSendSequence(device: DeviceEntry, keys: List<String>, delayMs: Int, initialDelayMs: Int = 0) {
        withSamsungRemote(device, 8002, fallback = true) { socket ->
            if (initialDelayMs > 0) delay(initialDelayMs.toLong())
            keys.forEachIndexed { index, key ->
                socket.send(samsungRemotePayload(key, "Click"))
                delay(120)
                if (index < keys.lastIndex) delay(delayMs.toLong())
            }
        }
    }

    private suspend fun withSamsungRemote(
        device: DeviceEntry,
        preferredPort: Int,
        fallback: Boolean = false,
        block: suspend (WebSocket) -> Unit
    ) {
        val ports = if (fallback) listOf(preferredPort, 8001).distinct() else listOf(preferredPort)
        var lastError: Throwable? = null
        for (port in ports) {
            try {
                val connection = connectSamsung(device, port)
                try {
                    block(connection.first)
                    return
                } finally {
                    connection.first.close(1000, null)
                }
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException(lastError?.message ?: "Samsung-WebSocket fehlgeschlagen.")
    }

    private suspend fun connectSamsung(device: DeviceEntry, port: Int): Pair<WebSocket, Int> =
        suspendCancellableCoroutine { continuation ->
            val protocol = if (port == 8002) "wss" else "ws"
            val client = if (port == 8002) insecureHttpClient else standardHttpClient
            val token = store.getToken(device.id)
            val encodedName = Base64.getEncoder().encodeToString("UniversalFernbedienung".toByteArray())
            val url = buildString {
                append("$protocol://${device.ip}:$port/api/v2/channels/samsung.remote.control?name=")
                append(URLEncoder.encode(encodedName, "UTF-8"))
                if (!token.isNullOrBlank()) {
                    append("&token=").append(URLEncoder.encode(token, "UTF-8"))
                }
            }
            var resumed = false
            val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!resumed) {
                        resumed = true
                        continuation.resume(webSocket to port)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    extractSamsungToken(text)?.let { store.saveToken(device.id, it) }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    extractSamsungToken(bytes.utf8())?.let { store.saveToken(device.id, it) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!resumed) {
                        resumed = true
                        continuation.resumeWithException(t)
                    }
                }
            })
            continuation.invokeOnCancellation { socket.cancel() }
        }

    private fun extractSamsungToken(message: String): String? {
        return runCatching {
            val json = JSONObject(message)
            val data = json.optJSONObject("data") ?: return@runCatching null
            data.optString("token").takeIf { it.matches(Regex("^\\d+$")) }
        }.getOrNull()
    }

    private fun samsungRemotePayload(key: String, cmd: String): String =
        JSONObject()
            .put("method", "ms.remote.control")
            .put(
                "params",
                JSONObject()
                    .put("Cmd", cmd)
                    .put("DataOfCmd", key)
                    .put("Option", "false")
                    .put("TypeOfRemote", "SendRemoteKey")
            )
            .toString()

    private suspend fun lgTurnOff(device: DeviceEntry): Int {
        val session = lgRequest(device, "ssap://system/turnOff")
        return session.port
    }

    private suspend fun lgLaunchApp(device: DeviceEntry, app: AppEntry) {
        val appId = app.appId ?: throw IllegalStateException("LG-App-ID fehlt.")
        lgRequest(
            device,
            "ssap://system.launcher/launch",
            JSONObject().put("id", appId)
        )
    }

    private suspend fun lgSendKey(device: DeviceEntry, key: String): Int {
        return when (key) {
            "KEY_VOLUP" -> lgRequest(device, "ssap://audio/volumeUp").port
            "KEY_VOLDOWN" -> lgRequest(device, "ssap://audio/volumeDown").port
            "KEY_MUTE" -> lgRequest(device, "ssap://audio/setMute", JSONObject().put("mute", true)).port
            "KEY_CHUP" -> lgRequest(device, "ssap://tv/channelUp").port
            "KEY_CHDOWN" -> lgRequest(device, "ssap://tv/channelDown").port
            else -> lgPointerKey(device, key)
        }
    }

    private suspend fun lgPointerKey(device: DeviceEntry, key: String): Int {
        val pointerName = when (key) {
            "KEY_HOME" -> "HOME"
            "KEY_RETURN" -> "BACK"
            "KEY_MENU" -> "MENU"
            "KEY_UP" -> "UP"
            "KEY_DOWN" -> "DOWN"
            "KEY_LEFT" -> "LEFT"
            "KEY_RIGHT" -> "RIGHT"
            "KEY_ENTER" -> "ENTER"
            "KEY_INFO" -> "INFO"
            "KEY_TV" -> "LIVE_TV"
            else -> throw IllegalStateException("LG-Key derzeit nicht unterstützt: $key")
        }
        val session = lgRequest(device, "ssap://com.webos.service.networkinput/getPointerInputSocket")
        val socketPath = session.payload?.optString("socketPath")
            ?: throw IllegalStateException("LG Pointer-Socket konnte nicht ermittelt werden.")
        val client = if (socketPath.startsWith("wss://")) insecureHttpClient else standardHttpClient
        val command = "type:button\nname:$pointerName\n\n"
        suspendCancellableCoroutine<Unit> { continuation ->
            var resumed = false
            val socket = client.newWebSocket(Request.Builder().url(socketPath).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(command)
                    webSocket.close(1000, null)
                    if (!resumed) {
                        resumed = true
                        continuation.resume(Unit)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!resumed) {
                        resumed = true
                        continuation.resumeWithException(t)
                    }
                }
            })
            continuation.invokeOnCancellation { socket.cancel() }
        }
        return session.port
    }

    private suspend fun lgRequest(device: DeviceEntry, uri: String, payload: JSONObject = JSONObject()): LgResult {
        var lastError: Throwable? = null
        for (port in listOf(3000, 3001)) {
            try {
                return connectLgAndRequest(device, port, uri, payload)
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException(lastError?.message ?: "LG-Verbindung fehlgeschlagen.")
    }

    private suspend fun connectLgAndRequest(device: DeviceEntry, port: Int, uri: String, payload: JSONObject): LgResult =
        suspendCancellableCoroutine { continuation ->
            val protocol = if (port == 3001) "wss" else "ws"
            val client = if (port == 3001) insecureHttpClient else standardHttpClient
            val request = Request.Builder().url("$protocol://${device.ip}:$port").build()
            val requestId = UUID.randomUUID().toString()
            val storedKey = store.getToken(device.id)
            var resumed = false

            val socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val registerPayload = JSONObject()
                        .put("type", "register")
                        .put("id", "register_${UUID.randomUUID()}")
                        .put("payload", JSONObject().put("pairingType", "PROMPT").put("client-key", storedKey ?: ""))
                    webSocket.send(registerPayload.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val json = JSONObject(text)
                        when (json.optString("type")) {
                            "registered" -> {
                                json.optJSONObject("payload")?.optString("client-key")?.takeIf { it.isNotBlank() }?.let {
                                    store.saveToken(device.id, it)
                                }
                                val call = JSONObject()
                                    .put("id", requestId)
                                    .put("type", "request")
                                    .put("uri", uri)
                                    .put("payload", payload)
                                webSocket.send(call.toString())
                            }

                            "response" -> {
                                if (json.optString("id") == requestId && !resumed) {
                                    resumed = true
                                    val responsePayload = json.optJSONObject("payload")
                                    webSocket.close(1000, null)
                                    continuation.resume(LgResult(port, responsePayload))
                                }
                                Unit
                            }

                            "error" -> {
                                if (!resumed) {
                                    resumed = true
                                    continuation.resumeWithException(IllegalStateException(json.toString()))
                                }
                                Unit
                            }
                            else -> Unit
                        }
                    }.onFailure {
                        if (!resumed) {
                            resumed = true
                            continuation.resumeWithException(it)
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!resumed) {
                        resumed = true
                        continuation.resumeWithException(t)
                    }
                }
            })

            continuation.invokeOnCancellation { socket.cancel() }
        }

    private suspend fun sendTivoCode(device: DeviceEntry, code: String) = withContext(Dispatchers.IO) {
        Socket(device.ip, 31339).use { socket ->
            socket.soTimeout = 2000
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
            writer.write("IRCODE $code\r")
            writer.flush()
            delay(80)
        }
    }

    private fun mapTivoKey(key: String): String = when (key) {
        "KEY_HOME" -> "TIVO"
        "KEY_RETURN" -> "BACK"
        "KEY_MENU" -> "GUIDE"
        "KEY_UP" -> "UP"
        "KEY_DOWN" -> "DOWN"
        "KEY_LEFT" -> "LEFT"
        "KEY_RIGHT" -> "RIGHT"
        "KEY_ENTER" -> "SELECT"
        "KEY_INFO" -> "INFO"
        "KEY_VOLUP" -> "VOLUMEUP"
        "KEY_VOLDOWN" -> "VOLUMEDOWN"
        "KEY_MUTE" -> "MUTE"
        "KEY_CHUP" -> "CHANNELUP"
        "KEY_CHDOWN" -> "CHANNELDOWN"
        "KEY_GUIDE" -> "GUIDE"
        "KEY_SOURCE" -> "TVINPUT"
        "KEY_EXIT" -> "EXIT"
        "KEY_0" -> "NUM0"
        "KEY_1" -> "NUM1"
        "KEY_2" -> "NUM2"
        "KEY_3" -> "NUM3"
        "KEY_4" -> "NUM4"
        "KEY_5" -> "NUM5"
        "KEY_6" -> "NUM6"
        "KEY_7" -> "NUM7"
        "KEY_8" -> "NUM8"
        "KEY_9" -> "NUM9"
        else -> throw IllegalStateException("TiVo-Key derzeit nicht unterstützt: $key")
    }

    private suspend fun probeSamsungStatus(device: DeviceEntry): ProbeStatus = withContext(Dispatchers.IO) {
        val identity = probeSamsungIdentity(device.ip)
        if (identity != null) ProbeStatus(true, identity.port ?: 8001) else ProbeStatus(false, null)
    }

    private suspend fun probeLgStatus(device: DeviceEntry): ProbeStatus = withContext(Dispatchers.IO) {
        when {
            isPortOpen(device.ip, 3001) -> ProbeStatus(true, 3001)
            isPortOpen(device.ip, 3000) -> ProbeStatus(true, 3000)
            else -> ProbeStatus(false, null)
        }
    }

    private suspend fun probeTivoStatus(device: DeviceEntry): ProbeStatus = withContext(Dispatchers.IO) {
        if (isPortOpen(device.ip, 31339)) ProbeStatus(true, 31339) else ProbeStatus(false, null)
    }

    private suspend fun probeSamsungIdentity(ip: String): SamsungIdentity? = withContext(Dispatchers.IO) {
        val checks = listOf(
            Triple(false, 8001, standardHttpClient),
            Triple(true, 8002, insecureHttpClient)
        )
        for ((secure, port, client) in checks) {
            val protocol = if (secure) "https" else "http"
            val request = Request.Builder().url("$protocol://$ip:$port/api/v2/").get().build()
            val responseText = runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.string()
                }
            }.getOrNull() ?: continue

            val json = runCatching { JSONObject(responseText) }.getOrNull() ?: continue
            val deviceJson = json.optJSONObject("device")
            return@withContext SamsungIdentity(
                name = json.optString("name").takeIf { it.isNotBlank() } ?: deviceJson?.optString("name"),
                modelName = deviceJson?.optString("modelName"),
                mac = deviceJson?.optString("wifiMac"),
                duid = deviceJson?.optString("duid"),
                networkType = deviceJson?.optString("networkType"),
                port = port
            )
        }
        null
    }

    private fun isPortOpen(ip: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(ip, port), 220)
            }
            true
        } catch (_: SocketTimeoutException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun sendWakeOnLan(mac: String) = withContext(Dispatchers.IO) {
        val normalized = mac.replace("-", ":").uppercase()
        val bytes = normalized.split(":").map { it.toInt(16).toByte() }.toByteArray()
        require(bytes.size == 6) { "Ungültige MAC-Adresse." }

        val packet = ByteArray(6 + 16 * bytes.size)
        repeat(6) { packet[it] = 0xFF.toByte() }
        for (offset in 6 until packet.size step bytes.size) {
            bytes.copyInto(packet, offset)
        }

        val broadcastTargets = buildSet {
            add("255.255.255.255")
            inferSubnetPrefix()?.let { add("$it.255") }
        }

        DatagramSocket().use { socket ->
            socket.broadcast = true
            repeat(3) {
                broadcastTargets.forEach { target ->
                    val address = InetAddress.getByName(target)
                    val datagram = DatagramPacket(packet, packet.size, address, 9)
                    socket.send(datagram)
                }
                delay(150)
            }
        }
    }

    private fun inferLocalIpv4(): String? {
        return NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }

    private fun inferSubnetPrefix(): String? {
        val address = inferLocalIpv4() ?: return null
        return address.substringBeforeLast('.', "")
            .takeIf { it.count { char -> char == '.' } == 2 }
    }

    private fun resolveSource(deviceType: String, name: String): SourceEntry {
        val normalized = normalizeName(name)
        return store.listSources(deviceType).firstOrNull { entry ->
            normalizeName(entry.name) == normalized || entry.aliases.any { normalizeName(it) == normalized }
        } ?: throw IllegalStateException("Quelle nicht gefunden: $name")
    }

    private fun resolveApp(deviceType: String, name: String): AppEntry {
        val normalized = normalizeName(name)
        return store.listApps(deviceType).firstOrNull { entry ->
            normalizeName(entry.name) == normalized || entry.aliases.any { normalizeName(it) == normalized }
        } ?: throw IllegalStateException("App nicht gefunden: $name")
    }

    private fun normalizeName(value: String): String =
        value.lowercase()
            .replace("ä", "ae")
            .replace("ö", "oe")
            .replace("ü", "ue")
            .replace("ß", "ss")
            .replace(" ", "")

    private suspend fun <T> withSelectedDevice(block: suspend (DeviceEntry) -> T): T {
        val device = store.getActiveDevice()
            ?: throw IllegalStateException("Bitte zuerst ein TV-Gerät auswählen.")
        return block(device)
    }

    private data class ProbeStatus(val online: Boolean, val port: Int?)
    private data class SamsungIdentity(
        val name: String?,
        val modelName: String?,
        val mac: String?,
        val duid: String?,
        val networkType: String?,
        val port: Int?
    )

    private data class LgResult(val port: Int, val payload: JSONObject?)

    companion object {
        private val insecureTrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        private val insecureSslSocketFactory: SSLSocketFactory by lazy {
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<TrustManager>(insecureTrustManager), SecureRandom())
            context.socketFactory
        }

        private val allHostsValid = HostnameVerifier { _, _ -> true }
    }
}
