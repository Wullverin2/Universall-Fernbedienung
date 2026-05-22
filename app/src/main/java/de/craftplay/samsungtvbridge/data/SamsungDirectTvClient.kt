package de.craftplay.samsungtvbridge.data

import android.content.Context
import android.net.wifi.WifiManager
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
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
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

class SamsungDirectTvClient(
    private val appContext: Context,
    private val store: DirectTvStore
) {
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
    private val lgStandardHttpClient = OkHttpClient.Builder()
        .connectTimeout(2000, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val lgInsecureHttpClient = OkHttpClient.Builder()
        .connectTimeout(2000, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .sslSocketFactory(insecureSslSocketFactory, insecureTrustManager)
        .hostnameVerifier(allHostsValid)
        .build()
    private val lgLaunchPointCache = mutableMapOf<String, List<LgLaunchPoint>>()

    suspend fun getStatus(): StatusResponse {
        val device = store.getActiveDevice()
            ?: return StatusResponse(name = "Kein TV ausgewählt", selectionRequired = true)

        val status = when (device.deviceType.lowercase()) {
            "lg" -> probeLgStatus(device)
            "tivo", "vestel" -> probeVestelStatus(device)
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
        val prefixes = inferSubnetPrefixes()
        if (prefixes.isEmpty()) {
            throw IllegalStateException("Kein lokales IPv4-Heimnetz erkannt.")
        }
        val localIps = inferLocalIpv4Addresses().toSet()
        val semaphore = Semaphore(32)
        val ssdpHints = discoverSsdpDevices()
        val addresses = prefixes
            .flatMap { prefix -> (1..254).map { "$prefix.$it" } }
            .plus(ssdpHints.keys)
            .distinct()
            .filterNot { it in localIps }
        val now = Instant.now().toString()

        val found = addresses.map { ip ->
            async(Dispatchers.IO) {
                semaphore.withPermit { discoverDevice(ip, now, ssdpHints[ip]) }
            }
        }.awaitAll().filterNotNull()

        val registry = store.upsertDevices(found)
        registry to DeviceScanSummary(scannedCandidateCount = addresses.size, foundCount = found.size)
    }

    fun getRegistry(): DeviceRegistryResponse = store.getRegistry()

    fun selectDevice(deviceId: String): DeviceEntry = store.selectDevice(deviceId)

    fun removeDevice(deviceId: String): DeviceRegistryResponse = store.removeDevice(deviceId)

    suspend fun refreshActiveDeviceProfile(): DeviceEntry? {
        val device = store.getActiveDevice() ?: return null
        return when (device.deviceType.lowercase()) {
            "lg" -> refreshLgDeviceProfile(device)
            else -> device
        }
    }

    suspend fun powerOn(): ActionResponse = withSelectedDevice { device ->
        val mac = device.mac?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Für dieses Gerät ist keine MAC-Adresse bekannt. Bitte zuerst scannen.")
        sendWakeOnLan(mac)
        ActionResponse(message = "Wake-on-LAN wurde gesendet.")
    }

    suspend fun powerOff(): ActionResponse = withSelectedDevice { device ->
        val port = when (device.deviceType.lowercase()) {
            "lg" -> lgTurnOff(device)
            "tivo", "vestel" -> sendVestelRemoteKey(device, "POWER")
            else -> samsungPowerOff(device)
        }
        ActionResponse(message = "Ausschalten gesendet.", port = port)
    }

    suspend fun sendRemoteKey(key: String): ActionResponse = withSelectedDevice { device ->
        val port = when (device.deviceType.lowercase()) {
            "lg" -> lgSendKey(device, key)
            "tivo", "vestel" -> sendVestelRemoteKey(device, key)
            else -> samsungSendKey(device, key)
        }
        ActionResponse(message = "Taste gesendet.", key = key, port = port)
    }

    suspend fun setSource(name: String): ActionResponse = withSelectedDevice { device ->
        when (device.deviceType.lowercase()) {
            "lg" -> {
                lgSetSource(device, name)
            }
            "tivo", "vestel" -> {
                val source = resolveSource(device.deviceType, name)
                sendVestelRemoteKey(device, source.keys.first())
            }
            else -> samsungSetSource(device, name)
        }
        ActionResponse(message = "Quelle gewechselt: $name", source = name)
    }

    suspend fun launchApp(name: String): ActionResponse = withSelectedDevice { device ->
        val app = resolveApp(device.deviceType, name)
        when (device.deviceType.lowercase()) {
            "lg" -> lgLaunchApp(device, app)
            "tivo", "vestel" -> vestelLaunchApp(device, app)
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
            "tivo", "vestel" -> probeVestelStatus(device)
            else -> probeSamsungStatus(device)
        }

        return JSONObject()
            .put("name", device.name)
            .put("deviceType", device.deviceType)
            .put("tvIp", device.ip)
            .put("online", diagnostics.online)
            .put("port", diagnostics.port ?: JSONObject.NULL)
            .put("modelName", device.modelName ?: JSONObject.NULL)
            .put("firmwareVersion", device.firmwareVersion ?: JSONObject.NULL)
            .put("sdkVersion", device.sdkVersion ?: JSONObject.NULL)
            .put("mac", device.mac ?: JSONObject.NULL)
            .put("networkType", device.networkType ?: JSONObject.NULL)
            .put("wakeOnWirelessLan", device.wakeOnWirelessLan ?: JSONObject.NULL)
            .put("controlUrl", device.controlUrl ?: JSONObject.NULL)
            .put("controlMethod", device.controlMethod ?: JSONObject.NULL)
            .put("platform", device.platform ?: JSONObject.NULL)
            .put(
                "capabilities",
                JSONObject()
                    .put("supportsDial", device.supportsDial)
                    .put("supportsNetworkRemote", device.supportsNetworkRemote)
                    .put("supportsWakeOnLan", device.supportsWakeOnLan)
                    .put("supportsSmartCenter", device.supportsSmartCenter)
                    .put("supportsTiVoProfile", device.supportsTiVoProfile)
            )
            .put("lastErrorCode", device.lastErrorCode ?: JSONObject.NULL)
            .put("lastSuccessfulCommand", device.lastSuccessfulCommand ?: JSONObject.NULL)
            .put("lastRequestUri", device.lastRequestUri ?: JSONObject.NULL)
            .put("pairingStatus", device.pairingStatus ?: JSONObject.NULL)
            .put("lgClientKeyStored", if (device.deviceType.lowercase() == "lg") !store.getToken(device.id).isNullOrBlank() else JSONObject.NULL)
            .put("localIpv4", JSONArray(inferLocalIpv4Addresses()))
            .put("localSubnetPrefixes", JSONArray(inferSubnetPrefixes()))
            .put("vestelPorts", if (device.deviceType.lowercase() in setOf("tivo", "vestel")) vestelPortDiagnostics(device) else JSONObject.NULL)
            .put("hint", networkTroubleshootingHint(device))
            .toString(2)
    }

    private suspend fun discoverDevice(ip: String, now: String, ssdpHint: SsdpIdentity?): DeviceEntry? {
        if (ssdpHint?.deviceType == "vestel" || ssdpHint?.deviceType == "tivo") {
            val controlUrl = validatedVestelControlUrl(ip, ssdpHint)
            return DeviceEntry(
                id = ssdpHint.udn ?: "vestel:$ip",
                ip = ip,
                name = ssdpHint.name ?: "[Nabo/Vestel] $ip",
                deviceType = "vestel",
                modelName = ssdpHint.modelName,
                mac = ssdpHint.mac ?: lookupMacAddress(ip),
                duid = ssdpHint.udn,
                controlUrl = controlUrl,
                controlMethod = "Vestel SmartCenter",
                platform = if (ssdpHint.deviceType == "tivo") "VESTEL_TIVO" else "VESTEL_LEGACY",
                supportsDial = !ssdpHint.applicationUrl.isNullOrBlank(),
                supportsNetworkRemote = true,
                supportsWakeOnLan = !ssdpHint.mac.isNullOrBlank(),
                supportsSmartCenter = !controlUrl.isNullOrBlank(),
                supportsTiVoProfile = ssdpHint.deviceType == "tivo",
                pairingStatus = "nicht erforderlich",
                firstSeenAt = now,
                lastSeenAt = now
            )
        }

        if (ssdpHint?.deviceType == "lg") {
            return DeviceEntry(
                id = ssdpHint.udn ?: "lg:$ip",
                ip = ip,
                name = ssdpHint.name ?: "[LG] $ip",
                deviceType = "lg",
                modelName = ssdpHint.modelName,
                duid = ssdpHint.udn,
                platform = "LG_WEBOS",
                supportsDial = false,
                supportsNetworkRemote = true,
                supportsWakeOnLan = false,
                pairingStatus = if (store.getToken(ssdpHint.udn ?: "lg:$ip").isNullOrBlank()) "Pairing erforderlich" else "gekoppelt",
                firstSeenAt = now,
                lastSeenAt = now
            )
        }

        if (isPortOpen(ip, 8001) || isPortOpen(ip, 8002)) {
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
                    platform = "SAMSUNG_TIZEN",
                    supportsNetworkRemote = true,
                    supportsWakeOnLan = !samsung.mac.isNullOrBlank(),
                    firstSeenAt = now,
                    lastSeenAt = now
                )
            }
        }

        if (isPortOpen(ip, 3000) || isPortOpen(ip, 3001)) {
            return DeviceEntry(
                id = ssdpHint?.udn ?: "lg:$ip",
                ip = ip,
                name = ssdpHint?.name ?: "[LG] $ip",
                deviceType = "lg",
                modelName = ssdpHint?.modelName,
                duid = ssdpHint?.udn,
                platform = "LG_WEBOS",
                supportsNetworkRemote = true,
                pairingStatus = "Pairing erforderlich",
                firstSeenAt = now,
                lastSeenAt = now
            )
        }

        val controlUrl = if (ssdpHint?.deviceType == "dial") validatedVestelControlUrl(ip, ssdpHint) else null
        if (controlUrl != null) {
            return DeviceEntry(
                id = ssdpHint?.udn ?: "vestel:$ip",
                ip = ip,
                name = ssdpHint?.name ?: "[Nabo/Vestel] $ip",
                deviceType = "vestel",
                modelName = ssdpHint?.modelName,
                mac = ssdpHint?.mac ?: lookupMacAddress(ip),
                duid = ssdpHint?.udn,
                controlUrl = controlUrl,
                controlMethod = "Vestel SmartCenter",
                platform = "VESTEL_LEGACY",
                supportsDial = !ssdpHint?.applicationUrl.isNullOrBlank(),
                supportsNetworkRemote = true,
                supportsWakeOnLan = !ssdpHint?.mac.isNullOrBlank(),
                supportsSmartCenter = true,
                supportsTiVoProfile = isPortOpen(ip, 31339),
                pairingStatus = "nicht erforderlich",
                firstSeenAt = now,
                lastSeenAt = now
            )
        }

        return null
    }

    private suspend fun discoverSsdpDevices(): Map<String, SsdpIdentity> = withContext(Dispatchers.IO) {
        val searchTargets = listOf(
            "urn:lge-com:service:webos-second-screen:1",
            "urn:dial-multiscreen-org:service:dial:1",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "ssdp:all"
        )
        val found = linkedMapOf<String, SsdpIdentity>()
        val multicastAddress = InetAddress.getByName("239.255.255.250")

        val wifiManager = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("universal-remote-ssdp")?.apply {
            setReferenceCounted(false)
            acquire()
        }

        try {
            searchTargets.forEach { target ->
                runCatching {
                    DatagramSocket().use { socket ->
                        socket.broadcast = true
                        socket.soTimeout = 500
                        val payload = buildString {
                            append("M-SEARCH * HTTP/1.1\r\n")
                            append("HOST: 239.255.255.250:1900\r\n")
                            append("MAN: \"ssdp:discover\"\r\n")
                            append("MX: 1\r\n")
                            append("ST: $target\r\n")
                            append("\r\n")
                        }.toByteArray()

                        repeat(2) {
                            socket.send(DatagramPacket(payload, payload.size, multicastAddress, 1900))
                        }

                        val deadline = System.currentTimeMillis() + 1400
                        while (System.currentTimeMillis() < deadline) {
                            val buffer = ByteArray(4096)
                            val packet = DatagramPacket(buffer, buffer.size)
                            try {
                                socket.receive(packet)
                                val response = String(packet.data, 0, packet.length)
                                val headers = parseSsdpHeaders(response)
                                val location = headers["location"]
                                val ip = extractIpFromLocation(location) ?: packet.address?.hostAddress ?: continue
                                classifySsdp(headers, ip)?.let { identity ->
                                    val enriched = enrichSsdpIdentity(identity)
                                    found[ip] = mergeSsdpIdentity(found[ip], enriched)
                                }
                            } catch (_: SocketTimeoutException) {
                                break
                            }
                        }
                    }
                }
            }
        } finally {
            if (multicastLock?.isHeld == true) {
                multicastLock.release()
            }
        }
        discoverVestelUdpDevices().forEach { (ip, identity) ->
            found[ip] = mergeSsdpIdentity(found[ip], identity)
        }
        found
    }

    private fun discoverVestelUdpDevices(): Map<String, SsdpIdentity> {
        val found = linkedMapOf<String, SsdpIdentity>()
        val payload = "vr_query_tv_version_782".toByteArray()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 450

            broadcastTargets().forEach { target ->
                runCatching {
                    val address = InetAddress.getByName(target)
                    repeat(2) {
                        socket.send(DatagramPacket(payload, payload.size, address, 4950))
                    }
                }
            }

            val deadline = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < deadline) {
                val buffer = ByteArray(2048)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val response = String(packet.data, 0, packet.length).trim()
                    if (response.contains("WAKEUP", ignoreCase = true)) continue
                    val normalized = response.lowercase()
                    if (isVestelUdpResponse(normalized)) {
                        val ip = packet.address?.hostAddress ?: continue
                        val name = response
                            .split(';', '|', ',', ' ')
                            .firstOrNull { it.contains("tv", ignoreCase = true) || it.contains("vestel", ignoreCase = true) }
                            ?.takeIf { it.length in 3..60 }
                        val smartCenterPort = extractVestelSmartCenterPort(response)
                        found[ip] = SsdpIdentity(
                            ip = ip,
                            deviceType = "vestel",
                            applicationUrl = smartCenterPort?.let { "http://$ip:$it/apps/" },
                            mac = findFirstMacAddressInText(response),
                            name = name ?: "[Nabo/Vestel] $ip"
                        )
                    }
                } catch (_: SocketTimeoutException) {
                    break
                }
            }
        }
        return found
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
        val candidates = (app.samsungAppIds + listOfNotNull(app.tizenAppId, app.appId))
            .filter { it.isNotBlank() }
            .distinct()
        if (candidates.isEmpty()) {
            throw IllegalStateException("Samsung-App-ID fehlt.")
        }

        var lastError: Throwable? = null
        for (appId in candidates) {
            for (port in listOf(8002, 8001)) {
                try {
                    samsungLaunchAppWebSocket(device, appId, port, app.actionType)
                    recordDeviceSuccess(device, "Samsung App ${app.name} via WebSocket $appId")
                    return
                } catch (error: Throwable) {
                    lastError = error
                }
            }
        }

        for (appId in candidates) {
            for (secure in listOf(false, true)) {
                try {
                    val protocol = if (secure) "https" else "http"
                    val port = if (secure) 8002 else 8001
                    val client = if (secure) insecureHttpClient else standardHttpClient
                    recordDeviceRequest(device, "samsung:http-applications/$appId", port)
                    val request = Request.Builder()
                        .url("$protocol://${device.ip}:$port/api/v2/applications/${URLEncoder.encode(appId, "UTF-8")}")
                        .post(ByteArray(0).toRequestBody(null))
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IllegalStateException("HTTP ${response.code} beim Samsung-App-Start.")
                        }
                    }
                    recordDeviceSuccess(device, "Samsung App ${app.name} via HTTP $appId")
                    return
                } catch (error: Throwable) {
                    lastError = error
                }
            }
        }

        if (app.macroKeys.isNotEmpty()) {
            samsungSendSequence(device, app.macroKeys, delayMs = 450, initialDelayMs = 0)
            recordDeviceSuccess(device, "Samsung App ${app.name} via Makro")
            return
        }

        recordDeviceError(device, "SAMSUNG_APP_LAUNCH_FAILED")
        throw IllegalStateException(lastError?.message ?: "Samsung-App konnte nicht gestartet werden.")
    }

    private suspend fun samsungLaunchAppWebSocket(device: DeviceEntry, appId: String, port: Int, actionType: String) {
        recordDeviceRequest(device, "samsung:ed.apps.launch/$appId", port)
        withSamsungRemote(device, port) { socket ->
            socket.send(samsungAppLaunchPayload(appId, actionType))
            delay(350)
        }
    }

    private suspend fun samsungSendKey(device: DeviceEntry, key: String, preferredPort: Int? = null): Int {
        val samsungKey = TvKeyMapping.samsung(key)
        val ports = listOfNotNull(preferredPort, 8002, 8001).distinct()
        var lastError: Throwable? = null
        for (port in ports) {
            try {
                withSamsungRemote(device, port) { socket ->
                    socket.send(samsungRemotePayload(samsungKey, "Click"))
                    delay(120)
                }
                recordDeviceSuccess(device, "Samsung Key $samsungKey")
                return port
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException(lastError?.message ?: "Samsung-Taste konnte nicht gesendet werden.")
    }

    private suspend fun samsungSendHoldKey(device: DeviceEntry, port: Int, key: String, holdMs: Int): Int {
        val samsungKey = TvKeyMapping.samsung(key)
        withSamsungRemote(device, port) { socket ->
            socket.send(samsungRemotePayload(samsungKey, "Press"))
            delay(holdMs.toLong())
            socket.send(samsungRemotePayload(samsungKey, "Release"))
            delay(120)
        }
        recordDeviceSuccess(device, "Samsung Hold $samsungKey")
        return port
    }

    private suspend fun samsungSendSequence(device: DeviceEntry, keys: List<String>, delayMs: Int, initialDelayMs: Int = 0) {
        withSamsungRemote(device, 8002, fallback = true) { socket ->
            if (initialDelayMs > 0) delay(initialDelayMs.toLong())
            keys.forEachIndexed { index, key ->
                socket.send(samsungRemotePayload(TvKeyMapping.samsung(key), "Click"))
                delay(120)
                if (index < keys.lastIndex) delay(delayMs.toLong())
            }
        }
        recordDeviceSuccess(device, "Samsung Sequence ${keys.joinToString(",")}")
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

    private fun samsungAppLaunchPayload(appId: String, actionType: String): String {
        val data = JSONObject()
            .put("appId", appId)
            .put("action_type", actionType.ifBlank { "NATIVE_LAUNCH" })
            .toString()
        return JSONObject()
            .put("method", "ms.channel.emit")
            .put(
                "params",
                JSONObject()
                    .put("event", "ed.apps.launch")
                    .put("to", "host")
                    .put("data", data)
            )
            .toString()
    }

    private suspend fun lgTurnOff(device: DeviceEntry): Int {
        val session = lgRequest(device, "ssap://system/turnOff")
        return session.port
    }

    private suspend fun refreshLgDeviceProfile(device: DeviceEntry): DeviceEntry {
        val systemInfo = runCatching {
            lgRequest(
                device,
                "ssap://com.webos.service.tv.systemproperty/getSystemInfo",
                JSONObject().put(
                    "keys",
                    JSONArray(listOf("modelName", "firmwareVersion", "sdkVersion", "boardType", "UHD"))
                )
            ).payload
        }.getOrNull()

        val connectionInfo = runCatching {
            lgRequest(
                device,
                "ssap://com.webos.service.connectionmanager/getStatus",
                JSONObject().put("subscribe", false)
            ).payload
        }.recoverCatching {
            lgRequest(
                device,
                "ssap://com.palm.connectionmanager/getStatus",
                JSONObject().put("subscribe", false)
            ).payload
        }.getOrNull()

        val modelName = systemInfo?.optNonBlankString("modelName") ?: device.modelName
        val firmwareVersion = systemInfo?.optNonBlankString("firmwareVersion") ?: device.firmwareVersion
        val sdkVersion = systemInfo?.optNonBlankString("sdkVersion") ?: device.sdkVersion
        val boardType = systemInfo?.optNonBlankString("boardType")
        val networkType = detectLgNetworkType(connectionInfo) ?: device.networkType
        val mac = detectLgMacAddress(connectionInfo) ?: device.mac
        val wakeOnWirelessLan = detectLgWakeOnWirelessLan(connectionInfo) ?: device.wakeOnWirelessLan
        val name = if (device.name == "[LG] ${device.ip}" && !modelName.isNullOrBlank()) {
            "LG $modelName"
        } else {
            device.name
        }

        val updated = device.copy(
            name = name,
            modelName = modelName,
            firmwareVersion = firmwareVersion,
            sdkVersion = sdkVersion,
            mac = mac,
            networkType = networkType ?: boardType,
            wakeOnWirelessLan = wakeOnWirelessLan,
            platform = "LG_WEBOS",
            supportsNetworkRemote = true,
            supportsWakeOnLan = !mac.isNullOrBlank(),
            pairingStatus = "gekoppelt",
            missing = false
        )
        store.updateDevice(updated)
        return updated
    }

    private suspend fun lgLaunchApp(device: DeviceEntry, app: AppEntry) {
        val launchPoints = lgListLaunchPoints(device)
        val resolved = resolveLgLaunchPoint(launchPoints, app)
        val candidates = listOfNotNull(resolved?.id) + (app.lgAppIds + listOfNotNull(app.appId)).distinct()
        if (candidates.isEmpty()) throw IllegalStateException("LG-App-ID fehlt.")
        var lastError: Throwable? = null
        for (appId in candidates.distinct()) {
            try {
                lgRequest(
                    device,
                    "ssap://system.launcher/launch",
                    JSONObject().put("id", appId)
                )
                return
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException(lastError?.message ?: "LG-App konnte nicht gestartet werden.")
    }

    private suspend fun lgSetSource(device: DeviceEntry, name: String) {
        val normalized = normalizeName(name)
        when (normalized) {
            normalizeName("Home Dashboard") -> {
                lgSendKey(device, "HOME")
                return
            }
            normalizeName("Live TV") -> {
                lgSendKey(device, "TV")
                return
            }
        }

        val payload = lgRequest(device, "ssap://tv/getExternalInputList").payload
        val devices = payload?.optJSONArray("devices") ?: payload?.optJSONArray("inputs") ?: JSONArray()
        val match = (0 until devices.length())
            .mapNotNull { devices.optJSONObject(it) }
            .firstOrNull { input ->
                val searchable = listOfNotNull(
                    input.optNonBlankString("id"),
                    input.optNonBlankString("label"),
                    input.optNonBlankString("name"),
                    input.optNonBlankString("port"),
                    input.optNonBlankString("appId")
                ).joinToString(" ")
                normalizeName(searchable).contains(normalized)
            }
            ?: throw IllegalStateException("LG-Eingang nicht gefunden: $name")

        val inputId = match.optNonBlankString("id") ?: match.optNonBlankString("appId")
            ?: throw IllegalStateException("LG-Eingang hat keine ID: $name")
        lgRequest(device, "ssap://tv/switchInput", JSONObject().put("inputId", inputId))
    }

    private suspend fun lgListLaunchPoints(device: DeviceEntry): List<LgLaunchPoint> {
        lgLaunchPointCache[device.id]?.let { return it }
        val result = lgRequest(device, "ssap://com.webos.applicationManager/listLaunchPoints").payload
        val launchPoints = result?.optJSONArray("launchPoints") ?: JSONArray()
        val parsed = (0 until launchPoints.length()).mapNotNull { index ->
            val item = launchPoints.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optNonBlankString("id")
                ?: item.optNonBlankString("launchPointId")
                ?: item.optNonBlankString("appId")
                ?: return@mapNotNull null
            LgLaunchPoint(
                id = id,
                title = item.optNonBlankString("title")
                    ?: item.optNonBlankString("name")
                    ?: item.optNonBlankString("appDescription")
            )
        }
        lgLaunchPointCache[device.id] = parsed
        return parsed
    }

    private fun resolveLgLaunchPoint(launchPoints: List<LgLaunchPoint>, app: AppEntry): LgLaunchPoint? {
        val needles = (listOf(app.name) + app.aliases + app.lgAppIds + listOfNotNull(app.appId))
            .map(::normalizeName)
            .filter { it.isNotBlank() }
        return launchPoints.firstOrNull { launchPoint ->
            val title = normalizeName(launchPoint.title.orEmpty())
            val id = normalizeName(launchPoint.id)
            needles.any { needle -> title.contains(needle) || id.contains(needle) || needle.contains(title) }
        }
    }

    private suspend fun lgSendKey(device: DeviceEntry, key: String): Int {
        return when (TvKeyMapping.generic(key)) {
            "VOLUME_UP" -> lgRequest(device, "ssap://audio/volumeUp").port
            "VOLUME_DOWN" -> lgRequest(device, "ssap://audio/volumeDown").port
            "MUTE" -> lgRequest(device, "ssap://audio/setMute", JSONObject().put("mute", true)).port
            "CHANNEL_UP" -> lgRequest(device, "ssap://tv/channelUp").port
            "CHANNEL_DOWN" -> lgRequest(device, "ssap://tv/channelDown").port
            else -> lgPointerKey(device, key)
        }
    }

    private suspend fun lgPointerKey(device: DeviceEntry, key: String): Int {
        val pointerName = TvKeyMapping.lgPointerName(key)
        val session = lgRequest(device, "ssap://com.webos.service.networkinput/getPointerInputSocket")
        val socketPath = session.payload?.optString("socketPath")
            ?: throw IllegalStateException("LG Pointer-Socket konnte nicht ermittelt werden.")
        val client = if (socketPath.startsWith("wss://")) lgInsecureHttpClient else lgStandardHttpClient
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
        val hadToken = !store.getToken(device.id).isNullOrBlank()

        repeat(if (hadToken) 2 else 1) {
            for (port in lgPorts) {
                try {
                    recordDeviceRequest(device, uri, port)
                    val result = withTimeout(if (store.getToken(device.id).isNullOrBlank()) 30000 else 15000) {
                        connectLgAndRequest(device, port, uri, payload)
                    }
                    recordDeviceSuccess(device, "LG $uri", pairingStatus = "gekoppelt")
                    return result
                } catch (error: LgAuthorizationException) {
                    lastError = error
                    recordDeviceError(device, error.code, "LG Pairing gespeichert, aber Anfrage abgelehnt")
                } catch (error: Throwable) {
                    lastError = error
                }
            }
        }

        if (lastError is LgAuthorizationException) {
            throw IllegalStateException("LG meldet 401/keine Berechtigung. Bitte am TV die Pairing-Abfrage bestätigen. Falls keine Abfrage erscheint, LG-Gerät in der App entfernen, neu scannen und erneut verbinden.")
        }
        throw IllegalStateException(lastError?.message ?: "LG-Verbindung fehlgeschlagen.")
    }

    private suspend fun connectLgAndRequest(device: DeviceEntry, port: Int, uri: String, payload: JSONObject): LgResult =
        suspendCancellableCoroutine { continuation ->
            val protocol = if (port == 3001) "wss" else "ws"
            val client = if (port == 3001) lgInsecureHttpClient else lgStandardHttpClient
            val request = Request.Builder().url("$protocol://${device.ip}:$port").build()
            val requestId = UUID.randomUUID().toString()
            val storedKey = store.getToken(device.id)
            var resumed = false

            val socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(lgRegisterMessage(storedKey).toString())
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
                                    val responsePayload = json.optJSONObject("payload")
                                    lgResponseError(responsePayload)?.let { throw it }
                                    resumed = true
                                    webSocket.close(1000, null)
                                    continuation.resume(LgResult(port, responsePayload))
                                }
                                Unit
                            }

                            "error" -> {
                                if (!resumed) {
                                    resumed = true
                                    continuation.resumeWithException(lgErrorFromJson(json))
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

    private fun lgRegisterMessage(storedKey: String?): JSONObject {
        val permissions = JSONArray(
            listOf(
                "LAUNCH",
                "LAUNCH_WEBAPP",
                "APP_TO_APP",
                "CONTROL_AUDIO",
                "CONTROL_DISPLAY",
                "CONTROL_INPUT_JOYSTICK",
                "CONTROL_INPUT_MEDIA_PLAYBACK",
                "CONTROL_INPUT_MEDIA_RECORDING",
                "CONTROL_INPUT_TV",
                "CONTROL_POWER",
                "READ_APP_STATUS",
                "READ_CURRENT_CHANNEL",
                "READ_INPUT_DEVICE_LIST",
                "READ_INSTALLED_APPS",
                "READ_LGE_SDX",
                "READ_NETWORK_STATE",
                "READ_TV_CHANNEL_LIST",
                "WRITE_NOTIFICATION_TOAST"
            )
        )
        val payload = JSONObject()
            .put("pairingType", "PROMPT")
            .put(
                "manifest",
                JSONObject()
                    .put("manifestVersion", 1)
                    .put("appVersion", "1.0")
                    .put("signed", JSONObject().put("permissions", permissions))
                    .put("permissions", permissions)
            )

        if (!storedKey.isNullOrBlank()) {
            payload.put("client-key", storedKey)
        }

        return JSONObject()
            .put("type", "register")
            .put("id", "register_${UUID.randomUUID()}")
            .put("payload", payload)
    }

    private fun lgResponseError(payload: JSONObject?): Throwable? {
        if (payload == null) return null
        if (payload.optBoolean("returnValue", true)) return null
        val code = payload.optString("errorCode")
            .takeIf { it.isNotBlank() }
            ?: payload.optString("code").takeIf { it.isNotBlank() }
            ?: "LG_ERROR"
        val message = payload.optString("errorText")
            .takeIf { it.isNotBlank() }
            ?: payload.optString("error").takeIf { it.isNotBlank() }
            ?: payload.toString()
        return if (code.contains("401") || message.contains("401")) {
            LgAuthorizationException(code, message)
        } else {
            IllegalStateException("LG $code: $message")
        }
    }

    private fun lgErrorFromJson(json: JSONObject): Throwable {
        val payload = json.optJSONObject("payload")
        val code = payload?.optString("errorCode")?.takeIf { it.isNotBlank() }
            ?: payload?.optString("code")?.takeIf { it.isNotBlank() }
            ?: json.optString("errorCode").takeIf { it.isNotBlank() }
            ?: "LG_ERROR"
        val text = payload?.optString("errorText")?.takeIf { it.isNotBlank() }
            ?: payload?.optString("error")?.takeIf { it.isNotBlank() }
            ?: json.optString("error").takeIf { it.isNotBlank() }
            ?: json.toString()
        return if (code.contains("401") || text.contains("401") || text.contains("unauthorized", ignoreCase = true)) {
            LgAuthorizationException(code, text)
        } else {
            IllegalStateException("LG $code: $text")
        }
    }

    private suspend fun sendVestelRemoteKey(device: DeviceEntry, key: String): Int {
        val button = TvKeyMapping.vestelButton(key)
        val codes = listOfNotNull(
            vestelLegacyKeyCodes[button],
            vestelAndroidKeyCodes[button],
            button
        ).distinct()
        var lastError: Throwable? = null

        for (code in codes) {
            val payload = vestelRemotePayload(code)
            try {
                vestelPostSmartCenter(device, payload)?.let { return it }
            } catch (_: Throwable) {
                Unit
            }

            try {
                val port = vestelSendWebSocket(device, payload)
                recordDeviceSuccess(device, "Vestel WebSocket $key")
                return port
            } catch (error: Throwable) {
                lastError = error
            }
        }

        if (isPortOpen(device.ip, 31339)) {
            try {
                sendTivoCode(device, TvKeyMapping.tivoCode(key))
                recordDeviceSuccess(device, "TiVo IRCODE $key")
                return 31339
            } catch (error: Throwable) {
                lastError = error
            }
        }

        recordDeviceError(device, "VESTEL_SEND_FAILED")
        throw IllegalStateException(lastError?.message ?: "Nabo/Vestel-Taste konnte nicht gesendet werden.")
    }

    private suspend fun vestelLaunchApp(device: DeviceEntry, app: AppEntry) {
        if (vestelLaunchDialApp(device, app)) {
            recordDeviceSuccess(device, "Vestel DIAL App ${app.name}")
            return
        }

        val packageName = app.appId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Für diese Vestel/Nabo-App ist keine App-ID hinterlegt.")
        val page = app.tizenAppId.orEmpty()
        val payload = "<?xml version='1.0' ?><openapplication><application packagename='${escapeXml(packageName)}' page='${escapeXml(page)}'/></openapplication>"
        if (vestelPostSmartCenter(device, payload) != null) return

        if (app.macroKeys.isNotEmpty()) {
            app.macroKeys.forEach { key ->
                sendVestelRemoteKey(device, key)
                delay(350)
            }
            return
        }

        throw IllegalStateException("Vestel/Nabo-App-Start wurde vom TV nicht angenommen.")
    }

    private fun vestelLaunchDialApp(device: DeviceEntry, app: AppEntry): Boolean {
        val appNames = (app.dialNames + listOfNotNull(app.appId, app.name))
            .filter { it.isNotBlank() }
            .distinct()
        val appBaseUrls = discoverVestelAppsUrls(device.ip)
            .plus(device.controlUrl?.substringBeforeLast("/SmartCenter")?.let { ensureTrailingSlash(it.removeSuffix("/apps")) + "apps/" })
            .filterNotNull()
            .distinct()

        for (baseUrl in appBaseUrls) {
            for (appName in appNames) {
                val url = "${ensureTrailingSlash(baseUrl)}${URLEncoder.encode(appName, "UTF-8")}"
                val ok = runCatching {
                    val request = Request.Builder()
                        .url(url)
                        .post(ByteArray(0).toRequestBody(null))
                        .build()
                    standardHttpClient.newCall(request).execute().use { response ->
                        response.isSuccessful
                    }
                }.getOrDefault(false)
                if (ok) return true
            }
        }
        return false
    }

    private suspend fun vestelPostSmartCenter(device: DeviceEntry, payload: String): Int? = withContext(Dispatchers.IO) {
        for (url in vestelSmartCenterUrls(device)) {
            for (contentType in vestelContentTypes) {
                try {
                    val body = payload.toRequestBody(contentType.toMediaType())
                    val request = Request.Builder()
                        .url(url)
                        .post(body)
                        .header("application_name", "tv smart centre")
                        .header("Connection", "keep-alive")
                        .header("Accept", "text/xml, */*")
                        .build()
                    standardHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            recordDeviceSuccess(device, "Vestel SmartCenter $url")
                            return@withContext defaultPortForUrl(url)
                        }
                    }
                } catch (_: Throwable) {
                    Unit
                }
            }
        }
        null
    }

    private suspend fun vestelSendWebSocket(device: DeviceEntry, payload: String): Int =
        suspendCancellableCoroutine { continuation ->
            var resumed = false
            val request = Request.Builder().url("ws://${device.ip}:7681/").build()
            val socket = standardHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!webSocket.send(payload)) {
                        if (!resumed) {
                            resumed = true
                            continuation.resumeWithException(IllegalStateException("Vestel-WebSocket hat das Kommando nicht angenommen."))
                        }
                        webSocket.close(1000, null)
                        return
                    }
                    webSocket.close(1000, null)
                    if (!resumed) {
                        resumed = true
                        continuation.resume(7681)
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

    private fun vestelSmartCenterUrls(ip: String): List<String> {
        val targeted = listOfNotNull(queryVestelTargetedControlUrl(ip))
        val discovered = discoverVestelAppsUrls(ip)
            .map { "${ensureTrailingSlash(it)}SmartCenter" }
        val fallback = listOf(
            "http://$ip:56789/apps/SmartCenter",
            "http://$ip/apps/SmartCenter"
        )
        return (targeted + discovered + fallback).distinct()
    }

    private fun vestelSmartCenterUrls(device: DeviceEntry): List<String> {
        val stored = listOfNotNull(device.controlUrl?.takeIf { it.isNotBlank() })
        return (stored + vestelSmartCenterUrls(device.ip)).distinct()
    }

    private fun validatedVestelControlUrl(ip: String, identity: SsdpIdentity?): String? {
        val candidates = listOfNotNull(
            vestelControlUrlFromHint(identity),
            queryVestelTargetedControlUrl(ip),
            "http://$ip:56789/apps/SmartCenter"
        ).distinct()
        return candidates.firstOrNull { validateVestelSmartCenterUrl(it) }
    }

    private fun validateVestelSmartCenterUrl(url: String): Boolean {
        val payloads = listOf(
            "<?xml version='1.0' ?><command>getapplicationlist</command>",
            "<?xml version='1.0' ?><command>getlauncherapplist</command>"
        )
        for (payload in payloads) {
            for (contentType in vestelContentTypes) {
                val result = runCatching {
                    val request = Request.Builder()
                        .url(url)
                        .post(payload.toRequestBody(contentType.toMediaType()))
                        .header("application_name", "tv smart centre")
                        .header("Accept", "text/xml, */*")
                        .build()
                    standardHttpClient.newCall(request).execute().use { response ->
                        response.isSuccessful
                    }
                }.getOrDefault(false)
                if (result) return true
            }
        }
        return false
    }

    private fun discoverVestelAppsUrls(ip: String): List<String> {
        val found = linkedSetOf<String>()
        val multicastAddress = InetAddress.getByName("239.255.255.250")
        val payload = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: 239.255.255.250:1900\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 1\r\n")
            append("ST: urn:dial-multiscreen-org:service:dial:1\r\n")
            append("\r\n")
        }.toByteArray()

        DatagramSocket().use { socket ->
            socket.soTimeout = 450
            repeat(2) {
                socket.send(DatagramPacket(payload, payload.size, multicastAddress, 1900))
            }

            val deadline = System.currentTimeMillis() + 1200
            while (System.currentTimeMillis() < deadline) {
                val buffer = ByteArray(4096)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    if (packet.address?.hostAddress != ip) continue
                    val response = String(packet.data, 0, packet.length)
                    val headers = parseSsdpHeaders(response)
                    headers["application-url"]?.let { found.add(ensureTrailingSlash(it)) }
                    headers["location"]?.let { location ->
                        deriveAppsUrlFromLocation(location)?.let { found.add(it) }
                    }
                } catch (_: SocketTimeoutException) {
                    break
                }
            }
        }
        return found.toList()
    }

    private fun deriveAppsUrlFromLocation(location: String): String? {
        return runCatching {
            val url = URL(location)
            val port = if (url.port > 0) ":${url.port}" else ""
            "${url.protocol}://${url.host}$port/apps/"
        }.getOrNull()
    }

    private fun vestelControlUrlFromHint(identity: SsdpIdentity?): String? =
        identity?.applicationUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { "${ensureTrailingSlash(it)}SmartCenter" }

    private fun queryVestelTargetedControlUrl(ip: String): String? {
        val payload = "vr_query_tv".toByteArray()
        return runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val address = InetAddress.getByName(ip)
                repeat(2) {
                    socket.send(DatagramPacket(payload, payload.size, address, 4950))
                }

                val deadline = System.currentTimeMillis() + 1000
                while (System.currentTimeMillis() < deadline) {
                    val buffer = ByteArray(2048)
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        if (packet.address?.hostAddress != ip) continue
                        val response = String(packet.data, 0, packet.length).trim()
                        if (response.contains("WAKEUP", ignoreCase = true)) continue
                        if (!isVestelUdpResponse(response.lowercase())) continue
                        extractVestelSmartCenterPort(response)?.let { port ->
                            return@runCatching "http://$ip:$port/apps/SmartCenter"
                        }
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun extractVestelSmartCenterPort(response: String): Int? {
        val firstPart = response.take(24)
        return Regex("\\b\\d{2,5}\\b")
            .findAll(firstPart)
            .mapNotNull { it.value.toIntOrNull() }
            .firstOrNull { it in 1024..65535 && it !in setOf(1900, 4950, 7681, 31339) }
    }

    private fun isVestelUdpResponse(response: String): Boolean =
        response.contains("vr_tv_query_rsp") || response.startsWith("vr_tv") || response.contains(";vr_tv")

    private fun ensureTrailingSlash(value: String): String =
        if (value.endsWith('/')) value else "$value/"

    private fun defaultPortForUrl(url: String): Int {
        val parsed = URL(url)
        if (parsed.port > 0) return parsed.port
        return if (parsed.protocol.equals("https", ignoreCase = true)) 443 else 80
    }

    private fun escapeXml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun vestelRemotePayload(code: String): String =
        "<?xml version='1.0' ?><remote><key code='${escapeXml(code)}'/></remote>"

    private fun mapVestelButton(key: String): String = when (key) {
        "KEY_HOME" -> "BUTTON_HOME"
        "KEY_POWER", "KEY_POWERON", "KEY_POWEROFF" -> "BUTTON_POWER"
        "KEY_RETURN", "KEY_PRECH" -> "BUTTON_BACK"
        "KEY_MENU" -> "BUTTON_MENU"
        "KEY_UP" -> "BUTTON_UP"
        "KEY_DOWN" -> "BUTTON_DOWN"
        "KEY_LEFT" -> "BUTTON_LEFT"
        "KEY_RIGHT" -> "BUTTON_RIGHT"
        "KEY_ENTER" -> "BUTTON_OK"
        "KEY_INFO" -> "BUTTON_INFO"
        "KEY_VOLUP" -> "BUTTON_VOL_UP"
        "KEY_VOLDOWN" -> "BUTTON_VOL_DOWN"
        "KEY_MUTE" -> "BUTTON_MUTE"
        "KEY_CHUP" -> "BUTTON_PROG_UP"
        "KEY_CHDOWN" -> "BUTTON_PROG_DOWN"
        "KEY_GUIDE" -> "BUTTON_EPG"
        "KEY_SOURCE" -> "BUTTON_SOURCE"
        "KEY_EXIT" -> "BUTTON_EXIT"
        "KEY_TV" -> "BUTTON_TV"
        "KEY_TTX_MIX", "KEY_TEXT" -> "BUTTON_TEXT"
        "KEY_PLAY" -> "BUTTON_PLAY"
        "KEY_PAUSE" -> "BUTTON_PAUSE"
        "KEY_STOP" -> "BUTTON_STOP"
        "KEY_REWIND" -> "BUTTON_REWIND"
        "KEY_FF" -> "BUTTON_FORWARD"
        "KEY_REC" -> "BUTTON_RECORD"
        "KEY_RED" -> "BUTTON_RED"
        "KEY_GREEN" -> "BUTTON_GREEN"
        "KEY_YELLOW" -> "BUTTON_YELLOW"
        "KEY_BLUE" -> "BUTTON_BLUE"
        "KEY_0" -> "BUTTON_0"
        "KEY_1" -> "BUTTON_1"
        "KEY_2" -> "BUTTON_2"
        "KEY_3" -> "BUTTON_3"
        "KEY_4" -> "BUTTON_4"
        "KEY_5" -> "BUTTON_5"
        "KEY_6" -> "BUTTON_6"
        "KEY_7" -> "BUTTON_7"
        "KEY_8" -> "BUTTON_8"
        "KEY_9" -> "BUTTON_9"
        else -> throw IllegalStateException("Nabo/Vestel-Key derzeit nicht unterstützt: $key")
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

    private suspend fun probeVestelStatus(device: DeviceEntry): ProbeStatus = withContext(Dispatchers.IO) {
        when {
            device.controlUrl?.let { isUrlHostPortOpen(it) } == true -> ProbeStatus(true, device.controlUrl.let { defaultPortForUrl(it) })
            isPortOpen(device.ip, 7681) -> ProbeStatus(true, 7681)
            isPortOpen(device.ip, 56789) -> ProbeStatus(true, 56789)
            isPortOpen(device.ip, 31339) -> ProbeStatus(true, 31339)
            else -> ProbeStatus(false, null)
        }
    }

    private fun vestelPortDiagnostics(device: DeviceEntry): JSONObject =
        JSONObject()
            .put("controlUrl", device.controlUrl ?: JSONObject.NULL)
            .put("controlUrlPortOpen", device.controlUrl?.let { isUrlHostPortOpen(it) } ?: JSONObject.NULL)
            .put("webSocket7681", isPortOpen(device.ip, 7681))
            .put("smartCenter56789", isPortOpen(device.ip, 56789))
            .put("tivo31339", isPortOpen(device.ip, 31339))

    private fun networkTroubleshootingHint(device: DeviceEntry): String {
        if (device.deviceType.lowercase() !in setOf("tivo", "vestel")) return ""
        return "Wenn Steuerung im Handy-Hotspot funktioniert, im vorhandenen WLAN aber nicht, blockiert der Router wahrscheinlich Client-zu-Client-Verbindungen, AP-Isolation, Gast-WLAN, VLAN oder Multicast/UPnP. TV und Handy müssen im gleichen normalen WLAN/LAN miteinander direkt sprechen dürfen."
    }

    private fun isUrlHostPortOpen(url: String): Boolean {
        return runCatching {
            val parsed = URL(url)
            val port = defaultPortForUrl(url)
            isPortOpen(parsed.host, port)
        }.getOrDefault(false)
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

    private fun parseSsdpHeaders(response: String): Map<String, String> =
        response.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) {
                    null
                } else {
                    line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
                }
            }
            .toMap()

    private fun classifySsdp(headers: Map<String, String>, ip: String): SsdpIdentity? {
        val signature = buildString {
            append(headers["st"].orEmpty()).append(' ')
            append(headers["server"].orEmpty()).append(' ')
            append(headers["usn"].orEmpty()).append(' ')
            append(headers["location"].orEmpty()).append(' ')
            append(headers["application-url"].orEmpty())
        }.lowercase()

        return when {
            signature.contains("lge") || signature.contains("webos") || signature.contains("udap") -> {
                SsdpIdentity(
                    ip = ip,
                    deviceType = "lg",
                    location = headers["location"],
                    applicationUrl = headers["application-url"],
                    mac = findFirstMacAddressInText(signature),
                    udn = extractUdn(headers["usn"]),
                    name = headers["friendlyname"]
                )
            }

            isVestelSignature(signature) -> {
                SsdpIdentity(
                    ip = ip,
                    deviceType = "vestel",
                    location = headers["location"],
                    applicationUrl = headers["application-url"],
                    mac = findFirstMacAddressInText(signature),
                    udn = extractUdn(headers["usn"]),
                    name = headers["friendlyname"]
                )
            }

            signature.contains("urn:dial-multiscreen-org:service:dial:1") -> {
                SsdpIdentity(
                    ip = ip,
                    deviceType = "dial",
                    location = headers["location"],
                    applicationUrl = headers["application-url"],
                    mac = findFirstMacAddressInText(signature),
                    udn = extractUdn(headers["usn"]),
                    name = headers["friendlyname"]
                )
            }

            else -> null
        }
    }

    private fun enrichSsdpIdentity(identity: SsdpIdentity): SsdpIdentity {
        val location = identity.location ?: return identity
        val description = fetchDeviceDescription(location) ?: return identity
        val friendlyName = readXmlTag(description, "friendlyName")
        val manufacturer = readXmlTag(description, "manufacturer")
        val modelName = readXmlTag(description, "modelName")
            ?: readXmlTag(description, "modelNumber")
            ?: readXmlTag(description, "modelDescription")
        val udn = readXmlTag(description, "UDN") ?: identity.udn
        val normalizedManufacturer = manufacturer.orEmpty().lowercase()
        val normalizedText = listOf(friendlyName, manufacturer, modelName, description).joinToString(" ").lowercase()
        val detectedType = when {
            normalizedManufacturer.contains("lg") || normalizedText.contains("webos") -> "lg"
            isVestelSignature(normalizedText) -> "vestel"
            else -> identity.deviceType
        }

        return identity.copy(
            deviceType = detectedType,
            name = friendlyName ?: identity.name,
            modelName = modelName ?: identity.modelName,
            udn = udn,
            mac = identity.mac ?: findFirstMacAddressInText(description),
            applicationUrl = identity.applicationUrl
        )
    }

    private fun fetchDeviceDescription(location: String): String? {
        return runCatching {
            val connection = URL(location).openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1500
            connection.requestMethod = "GET"
            connection.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    private fun readXmlTag(xml: String, tag: String): String? {
        val regex = Regex("<$tag>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return regex.find(xml)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun extractIpFromLocation(location: String?): String? {
        if (location.isNullOrBlank()) return null
        return runCatching { URL(location).host }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun extractUdn(usn: String?): String? =
        usn?.substringBefore("::")?.takeIf { it.isNotBlank() }

    private fun isVestelSignature(signature: String): Boolean {
        val normalized = signature.lowercase()
        return listOf(
            "vestel",
            "smartcenter",
            "smart centre",
            "nabo",
            "finlux",
            "telefunken",
            "toshiba",
            "jvc",
            "hitachi",
            "techwood",
            "regal",
            "mb100",
            "mb97",
            "mb90"
        ).any { normalized.contains(it) }
    }

    private fun mergeSsdpIdentity(existing: SsdpIdentity?, incoming: SsdpIdentity): SsdpIdentity {
        if (existing == null) return incoming
        return existing.copy(
            deviceType = if (existing.deviceType == "samsung" || existing.deviceType == "dial") incoming.deviceType else existing.deviceType,
            location = existing.location ?: incoming.location,
            applicationUrl = existing.applicationUrl ?: incoming.applicationUrl,
            mac = existing.mac ?: incoming.mac,
            udn = existing.udn ?: incoming.udn,
            name = existing.name ?: incoming.name,
            modelName = existing.modelName ?: incoming.modelName
        )
    }

    private fun JSONObject.optNonBlankString(key: String): String? =
        optString(key).trim().takeIf { it.isNotBlank() && it != "null" }

    private fun detectLgNetworkType(connectionInfo: JSONObject?): String? {
        if (connectionInfo == null) return null
        val wifi = connectionInfo.optJSONObject("wifi")
        val wired = connectionInfo.optJSONObject("wired") ?: connectionInfo.optJSONObject("ethernet")
        return when {
            wifi?.toString()?.lowercase()?.contains("connected") == true -> "wireless"
            wifi?.optNonBlankString("onInternet") == "yes" -> "wireless"
            wired?.toString()?.lowercase()?.contains("connected") == true -> "wired"
            wired?.optNonBlankString("onInternet") == "yes" -> "wired"
            else -> null
        }
    }

    private fun detectLgWakeOnWirelessLan(connectionInfo: JSONObject?): Boolean? {
        if (connectionInfo == null) return null
        return findBooleanValue(
            connectionInfo,
            setOf("isWakeOnWiFiEnabled", "wakeOnWifi", "wakeOnWiFi", "wakeOnWirelessLan")
        )
    }

    private fun detectLgMacAddress(connectionInfo: JSONObject?): String? {
        if (connectionInfo == null) return null
        val explicit = findStringValue(
            connectionInfo,
            setOf("mac", "macAddress", "wifiMac", "wiredMac", "wiredMacAddress", "wifiMacAddress")
        )
        return explicit?.takeIf { looksLikeMacAddress(it) } ?: findFirstMacAddress(connectionInfo)
    }

    private fun findStringValue(json: JSONObject, keys: Set<String>): String? {
        val names = json.keys()
        while (names.hasNext()) {
            val key = names.next()
            val value = json.opt(key)
            if (keys.any { it.equals(key, ignoreCase = true) }) {
                val text = value?.toString()?.trim()
                if (!text.isNullOrBlank() && text != "null") return text
            }
            when (value) {
                is JSONObject -> findStringValue(value, keys)?.let { return it }
                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        (value.opt(index) as? JSONObject)?.let { nested ->
                            findStringValue(nested, keys)?.let { return it }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun findBooleanValue(json: JSONObject, keys: Set<String>): Boolean? {
        val names = json.keys()
        while (names.hasNext()) {
            val key = names.next()
            val value = json.opt(key)
            if (keys.any { it.equals(key, ignoreCase = true) }) {
                when (value) {
                    is Boolean -> return value
                    is String -> value.trim().lowercase().let {
                        if (it == "true" || it == "yes" || it == "1") return true
                        if (it == "false" || it == "no" || it == "0") return false
                    }
                }
            }
            when (value) {
                is JSONObject -> findBooleanValue(value, keys)?.let { return it }
                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        (value.opt(index) as? JSONObject)?.let { nested ->
                            findBooleanValue(nested, keys)?.let { return it }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun findFirstMacAddress(json: JSONObject): String? {
        return findFirstMacAddressInText(json.toString())
    }

    private fun findFirstMacAddressInText(value: String): String? {
        val macRegex = Regex("(?i)\\b[0-9a-f]{2}([:-][0-9a-f]{2}){5}\\b")
        return macRegex.find(value)?.value?.uppercase()?.replace("-", ":")
    }

    private fun looksLikeMacAddress(value: String): Boolean =
        value.matches(Regex("(?i)^[0-9a-f]{2}([:-][0-9a-f]{2}){5}$"))

    private fun lookupMacAddress(ip: String): String? {
        primeNeighborCache(ip)
        return lookupMacFromArpTable(ip)
    }

    private fun primeNeighborCache(ip: String) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, 80), 120)
            }
        }
        listOf(7681, 56789, 31339, 4950).forEach { port ->
            runCatching {
                DatagramSocket().use { socket ->
                    val payload = ByteArray(1)
                    socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(ip), port))
                }
            }
        }
    }

    private fun lookupMacFromArpTable(ip: String): String? {
        return runCatching {
            java.io.File("/proc/net/arp")
                .readLines()
                .drop(1)
                .map { it.trim().split(Regex("\\s+")) }
                .firstOrNull { columns ->
                    columns.size >= 4 && columns[0] == ip && looksLikeMacAddress(columns[3]) && columns[3] != "00:00:00:00:00:00"
                }
                ?.get(3)
                ?.uppercase()
        }.getOrNull()
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

        DatagramSocket().use { socket ->
            socket.broadcast = true
            repeat(3) {
                broadcastTargets().forEach { target ->
                    val address = InetAddress.getByName(target)
                    val datagram = DatagramPacket(packet, packet.size, address, 9)
                    socket.send(datagram)
                }
                delay(150)
            }
        }
    }

    private fun broadcastTargets(): Set<String> = buildSet {
        add("255.255.255.255")
        inferSubnetPrefixes().forEach { prefix -> add("$prefix.255") }
    }

    private fun inferLocalIpv4(): String? = inferLocalIpv4Addresses().firstOrNull()

    private fun inferLocalIpv4Addresses(): List<String> {
        val addresses = linkedSetOf<String>()
        inferWifiIpv4Address()?.let { addresses.add(it) }
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .forEach { addresses.add(it) }
        return addresses.toList()
    }

    private fun inferWifiIpv4Address(): String? {
        val wifiManager = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val dhcpInfo = wifiManager.dhcpInfo ?: return null
        if (dhcpInfo.ipAddress == 0) return null
        return intToIpv4(dhcpInfo.ipAddress)
    }

    private fun inferSubnetPrefix(): String? = inferSubnetPrefixes().firstOrNull()

    private fun inferSubnetPrefixes(): List<String> {
        val prefixes = linkedSetOf<String>()
        inferLocalIpv4Addresses().forEach { address ->
            address.substringBeforeLast('.', "")
                .takeIf { it.count { char -> char == '.' } == 2 }
                ?.let { prefixes.add(it) }
        }
        return prefixes.toList()
    }

    private fun intToIpv4(value: Int): String {
        val octets = listOf(
            value and 0xFF,
            value shr 8 and 0xFF,
            value shr 16 and 0xFF,
            value shr 24 and 0xFF
        )
        return octets.joinToString(".")
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

    private fun recordDeviceSuccess(device: DeviceEntry, command: String, pairingStatus: String? = null) {
        val current = store.getRegistry().devices.firstOrNull { it.id == device.id } ?: device
        store.updateDevice(
            current.copy(
                lastSuccessfulCommand = command,
                lastErrorCode = null,
                pairingStatus = pairingStatus ?: current.pairingStatus,
                missing = false
            )
        )
    }

    private fun recordDeviceRequest(device: DeviceEntry, uri: String, port: Int? = null) {
        val current = store.getRegistry().devices.firstOrNull { it.id == device.id } ?: device
        store.updateDevice(
            current.copy(
                lastRequestUri = if (port == null) uri else "$uri via $port"
            )
        )
    }

    private fun recordDeviceError(device: DeviceEntry, code: String, pairingStatus: String? = null) {
        val current = store.getRegistry().devices.firstOrNull { it.id == device.id } ?: device
        store.updateDevice(
            current.copy(
                lastErrorCode = code,
                pairingStatus = pairingStatus ?: current.pairingStatus
            )
        )
    }

    private data class ProbeStatus(val online: Boolean, val port: Int?)
    private class LgAuthorizationException(val code: String, message: String) : IllegalStateException(message)
    private data class SamsungIdentity(
        val name: String?,
        val modelName: String?,
        val mac: String?,
        val duid: String?,
        val networkType: String?,
        val port: Int?
    )

    private data class LgLaunchPoint(val id: String, val title: String?)
    private data class LgResult(val port: Int, val payload: JSONObject?)
    private data class SsdpIdentity(
        val ip: String,
        val deviceType: String,
        val location: String? = null,
        val applicationUrl: String? = null,
        val mac: String? = null,
        val udn: String? = null,
        val name: String? = null,
        val modelName: String? = null
    )

    companion object {
        private val lgPorts = listOf(3001, 3000)

        private val vestelContentTypes = listOf(
            "text/plain; charset=ISO-8859-1",
            "text/xml; charset=UTF-8"
        )

        private val vestelLegacyKeyCodes = mapOf(
            "BUTTON_0" to "1000",
            "BUTTON_1" to "1001",
            "BUTTON_2" to "1002",
            "BUTTON_3" to "1003",
            "BUTTON_4" to "1004",
            "BUTTON_5" to "1005",
            "BUTTON_6" to "1006",
            "BUTTON_7" to "1007",
            "BUTTON_8" to "1008",
            "BUTTON_9" to "1009",
            "BUTTON_BACK" to "1010",
            "BUTTON_SCREEN" to "1011",
            "BUTTON_POWER" to "1012",
            "BUTTON_MUTE" to "1013",
            "BUTTON_PRESETS" to "1014",
            "BUTTON_LANG" to "1015",
            "BUTTON_VOL_UP" to "1016",
            "BUTTON_VOL_DOWN" to "1017",
            "BUTTON_INFO" to "1018",
            "BUTTON_DOWN" to "1019",
            "BUTTON_UP" to "1020",
            "BUTTON_LEFT" to "1021",
            "BUTTON_RIGHT" to "1022",
            "BUTTON_STOP" to "1024",
            "BUTTON_PLAY" to "1025",
            "BUTTON_REWIND" to "1027",
            "BUTTON_FORWARD" to "1028",
            "BUTTON_TV" to "1030",
            "BUTTON_SUBTITLE" to "1031",
            "BUTTON_PROG_UP" to "1032",
            "BUTTON_PROG_DOWN" to "1033",
            "BUTTON_PREVIOUS" to "1034",
            "BUTTON_SWAP" to "1034",
            "BUTTON_EXIT" to "1037",
            "BUTTON_FAV" to "1040",
            "BUTTON_3D" to "1040",
            "BUTTON_SLEEP" to "1042",
            "BUTTON_QMENU" to "1043",
            "BUTTON_CHAN" to "1045",
            "BUTTON_HOME" to "1046",
            "BUTTON_EPG" to "1047",
            "BUTTON_MENU" to "1048",
            "BUTTON_PAUSE" to "1049",
            "BUTTON_YELLOW" to "1050",
            "BUTTON_RECORD" to "1051",
            "BUTTON_BLUE" to "1052",
            "BUTTON_OK" to "1053",
            "BUTTON_GREEN" to "1054",
            "BUTTON_RED" to "1055",
            "BUTTON_SOURCE" to "1056",
            "BUTTON_MMEDIA" to "1057",
            "BUTTON_MY_BUTTON" to "1062",
            "BUTTON_MY_BUTTON_2" to "1063",
            "BUTTON_TEXT" to "1255"
        )

        private val vestelAndroidKeyCodes = mapOf(
            "BUTTON_0" to "7",
            "BUTTON_1" to "8",
            "BUTTON_2" to "9",
            "BUTTON_3" to "10",
            "BUTTON_4" to "11",
            "BUTTON_5" to "12",
            "BUTTON_6" to "13",
            "BUTTON_7" to "14",
            "BUTTON_8" to "15",
            "BUTTON_9" to "16",
            "BUTTON_HOME" to "3",
            "BUTTON_BACK" to "4",
            "BUTTON_POWER" to "26",
            "BUTTON_MENU" to "82",
            "BUTTON_UP" to "19",
            "BUTTON_DOWN" to "20",
            "BUTTON_LEFT" to "21",
            "BUTTON_RIGHT" to "22",
            "BUTTON_OK" to "23",
            "BUTTON_VOL_UP" to "24",
            "BUTTON_VOL_DOWN" to "25",
            "BUTTON_MUTE" to "164",
            "BUTTON_PROG_UP" to "166",
            "BUTTON_PROG_DOWN" to "167",
            "BUTTON_INFO" to "165",
            "BUTTON_EXIT" to "170",
            "BUTTON_EPG" to "227",
            "BUTTON_TEXT" to "233",
            "BUTTON_SOURCE" to "178",
            "BUTTON_RECORD" to "130",
            "BUTTON_PLAY" to "85",
            "BUTTON_PAUSE" to "85",
            "BUTTON_STOP" to "86",
            "BUTTON_PREVIOUS" to "88",
            "BUTTON_REWIND" to "89",
            "BUTTON_FORWARD" to "90",
            "BUTTON_NEXT" to "87",
            "BUTTON_RED" to "183",
            "BUTTON_GREEN" to "184",
            "BUTTON_YELLOW" to "185",
            "BUTTON_BLUE" to "186",
            "BUTTON_LANG" to "204",
            "BUTTON_3D" to "206",
            "BUTTON_SLEEP" to "223",
            "BUTTON_CHAN" to "229"
        )

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
