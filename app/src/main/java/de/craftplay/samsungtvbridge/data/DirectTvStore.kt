package de.craftplay.samsungtvbridge.data

import android.content.SharedPreferences
import de.craftplay.samsungtvbridge.model.AppEntry
import de.craftplay.samsungtvbridge.model.DeviceEntry
import de.craftplay.samsungtvbridge.model.DeviceRegistryResponse
import de.craftplay.samsungtvbridge.model.SourceEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

class DirectTvStore(private val prefs: SharedPreferences) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getRegistry(): DeviceRegistryResponse {
        val devices = runCatching {
            json.decodeFromString(ListSerializer(DeviceEntry.serializer()), prefs.getString(KEY_DEVICES, "[]").orEmpty())
        }.getOrDefault(emptyList())
        val activeDeviceId = prefs.getString(KEY_ACTIVE_DEVICE_ID, null)
        return DeviceRegistryResponse(activeDeviceId = activeDeviceId, devices = devices)
    }

    fun upsertDevices(discovered: List<DeviceEntry>): DeviceRegistryResponse {
        val registry = getRegistry()
        val existingById = registry.devices.associateBy { it.id }.toMutableMap()
        val now = Instant.now().toString()
        val seenIds = discovered.map { it.id }.toSet()

        discovered.forEach { device ->
            val existing = existingById[device.id]
            existingById[device.id] = device.copy(
                modelName = device.modelName ?: existing?.modelName,
                firmwareVersion = device.firmwareVersion ?: existing?.firmwareVersion,
                sdkVersion = device.sdkVersion ?: existing?.sdkVersion,
                mac = device.mac ?: existing?.mac,
                duid = device.duid ?: existing?.duid,
                networkType = device.networkType ?: existing?.networkType,
                wakeOnWirelessLan = device.wakeOnWirelessLan ?: existing?.wakeOnWirelessLan,
                controlUrl = device.controlUrl ?: existing?.controlUrl,
                controlMethod = device.controlMethod ?: existing?.controlMethod,
                platform = device.platform ?: existing?.platform,
                supportsDial = device.supportsDial || existing?.supportsDial == true,
                supportsNetworkRemote = device.supportsNetworkRemote || existing?.supportsNetworkRemote == true,
                supportsWakeOnLan = device.supportsWakeOnLan || existing?.supportsWakeOnLan == true,
                supportsSmartCenter = device.supportsSmartCenter || existing?.supportsSmartCenter == true,
                supportsTiVoProfile = device.supportsTiVoProfile || existing?.supportsTiVoProfile == true,
                lastErrorCode = device.lastErrorCode ?: existing?.lastErrorCode,
                lastSuccessfulCommand = device.lastSuccessfulCommand ?: existing?.lastSuccessfulCommand,
                lastRequestUri = device.lastRequestUri ?: existing?.lastRequestUri,
                pairingStatus = device.pairingStatus ?: existing?.pairingStatus,
                firstSeenAt = existing?.firstSeenAt ?: device.firstSeenAt.ifBlank { now },
                lastSeenAt = now,
                missing = false
            )
        }

        val merged = existingById.values.map { device ->
            if (device.id in seenIds) device else device.copy(missing = true)
        }.sortedBy { it.name.lowercase() }

        val activeStillExists = merged.any { it.id == registry.activeDeviceId }
        val nextRegistry = DeviceRegistryResponse(
            activeDeviceId = if (activeStillExists) registry.activeDeviceId else null,
            devices = merged
        )
        saveRegistry(nextRegistry)
        return nextRegistry
    }

    fun selectDevice(deviceId: String): DeviceEntry {
        val registry = getRegistry()
        val selected = registry.devices.firstOrNull { it.id == deviceId }
            ?: throw IllegalStateException("TV-Gerät nicht gefunden.")
        prefs.edit().putString(KEY_ACTIVE_DEVICE_ID, deviceId).apply()
        return selected
    }

    fun updateDevice(device: DeviceEntry): DeviceRegistryResponse {
        val registry = getRegistry()
        val nextDevices = registry.devices.map { existing ->
            if (existing.id == device.id) {
                device.copy(firstSeenAt = existing.firstSeenAt.ifBlank { device.firstSeenAt })
            } else {
                existing
            }
        }
        val nextRegistry = registry.copy(devices = nextDevices.sortedBy { it.name.lowercase() })
        saveRegistry(nextRegistry)
        return nextRegistry
    }

    fun removeDevice(deviceId: String): DeviceRegistryResponse {
        val registry = getRegistry()
        val nextDevices = registry.devices.filterNot { it.id == deviceId }
        val nextActive = if (registry.activeDeviceId == deviceId) null else registry.activeDeviceId
        val nextRegistry = DeviceRegistryResponse(activeDeviceId = nextActive, devices = nextDevices)
        saveRegistry(nextRegistry)
        return nextRegistry
    }

    fun getActiveDevice(): DeviceEntry? {
        val registry = getRegistry()
        return registry.devices.firstOrNull { it.id == registry.activeDeviceId }
    }

    fun getToken(deviceId: String): String? {
        return loadTokens()[deviceId]
    }

    fun saveToken(deviceId: String, token: String) {
        val tokens = loadTokens().toMutableMap()
        tokens[deviceId] = token
        prefs.edit()
            .putString(KEY_TOKENS, json.encodeToString(MapSerializer(String.serializer(), String.serializer()), tokens))
            .apply()
    }

    fun clearToken(deviceId: String) {
        val tokens = loadTokens().toMutableMap()
        if (tokens.remove(deviceId) != null) {
            prefs.edit()
                .putString(KEY_TOKENS, json.encodeToString(MapSerializer(String.serializer(), String.serializer()), tokens))
                .apply()
        }
    }

    fun listSources(deviceType: String): List<SourceEntry> = when (deviceType.lowercase()) {
        "samsung" -> listOf(
            SourceEntry("HDMI1", listOf("hdmi 1", "konsole", "receiver"), listOf("SOURCE", "DOWN", "OK"), delayMs = 600, initialDelayMs = 900),
            SourceEntry("HDMI2", listOf("hdmi 2", "fire tv", "firetv"), listOf("SOURCE", "DOWN", "DOWN", "OK"), delayMs = 600, initialDelayMs = 900),
            SourceEntry("TV", listOf("fernsehen", "live tv", "tv"), listOf("SOURCE", "UP", "OK"), delayMs = 600, initialDelayMs = 900)
        )

        "lg" -> listOf(
            SourceEntry("Home Dashboard", listOf("home", "dashboard"), listOf("HOME")),
            SourceEntry("Live TV", listOf("tv", "fernsehen"), listOf("TV")),
            SourceEntry("HDMI1", listOf("hdmi 1", "hdmi1"), emptyList()),
            SourceEntry("HDMI2", listOf("hdmi 2", "hdmi2"), emptyList()),
            SourceEntry("HDMI3", listOf("hdmi 3", "hdmi3"), emptyList()),
            SourceEntry("HDMI4", listOf("hdmi 4", "hdmi4"), emptyList())
        )

        "tivo", "vestel" -> listOf(
            SourceEntry("Quelle", listOf("source", "eingang"), listOf("SOURCE")),
            SourceEntry("TV", listOf("fernsehen", "live tv", "tv"), listOf("TV"))
        )

        else -> emptyList()
    }

    fun listApps(deviceType: String): List<AppEntry> = when (deviceType.lowercase()) {
        "samsung" -> listOf(
            AppEntry(
                name = "YouTube",
                appId = "111299001912",
                tizenAppId = "9Ur5IzDKqV.TizenYouTube",
                aliases = listOf("youtube", "yt"),
                samsungAppIds = listOf("9Ur5IzDKqV.TizenYouTube", "111299001912")
            ),
            AppEntry(
                name = "Netflix",
                appId = "11101200001",
                tizenAppId = "RN1MCdNq8t.Netflix",
                aliases = listOf("netflix"),
                samsungAppIds = listOf("RN1MCdNq8t.Netflix", "11101200001")
            ),
            AppEntry(
                name = "Prime Video",
                appId = "3201512006785",
                tizenAppId = "evKhCgZelL.AmazonIgnitionLauncher2",
                aliases = listOf("prime", "amazon prime", "prime video"),
                samsungAppIds = listOf("evKhCgZelL.AmazonIgnitionLauncher2", "3201512006785")
            ),
            AppEntry(
                name = "Crunchyroll",
                appId = "3202302030097",
                tizenAppId = "OGLLvqej7u.CrunchyrollWebApp",
                aliases = listOf("crunchyroll", "anime"),
                samsungAppIds = listOf("OGLLvqej7u.CrunchyrollWebApp", "3202302030097")
            ),
            AppEntry(
                name = "Sky X",
                appId = "3201812017464",
                tizenAppId = "J0zX4W0EmB.SkyX",
                aliases = listOf("sky x", "skyx"),
                samsungAppIds = listOf("J0zX4W0EmB.SkyX", "3201812017464")
            ),
            AppEntry(
                name = "Joyn",
                appId = "3202106024013",
                tizenAppId = "2200MKoe7n.ZAPPNVOLLTVFREIGESTREAMT",
                aliases = listOf("joyn"),
                samsungAppIds = listOf("2200MKoe7n.ZAPPNVOLLTVFREIGESTREAMT", "3202106024013")
            ),
            AppEntry(
                name = "Plex",
                appId = "3201512006963",
                tizenAppId = "kIciSQlYEM.plex",
                aliases = listOf("plex"),
                samsungAppIds = listOf("kIciSQlYEM.plex", "3201512006963")
            ),
            AppEntry(
                name = "simpliTV",
                appId = "LibFXRqQAD.simplitv",
                tizenAppId = "LibFXRqQAD.simplitv",
                aliases = listOf("simpli", "simplitv"),
                samsungAppIds = listOf("LibFXRqQAD.simplitv")
            ),
            AppEntry(
                name = "Internet",
                appId = "org.tizen.browser",
                tizenAppId = "org.tizen.browser",
                aliases = listOf("internet", "browser"),
                samsungAppIds = listOf("org.tizen.browser")
            )
        )

        "lg" -> listOf(
            AppEntry("YouTube", "youtube.leanback.v4", aliases = listOf("youtube", "yt"), lgAppIds = listOf("youtube.leanback.v4", "youtube")),
            AppEntry("Netflix", "netflix", aliases = listOf("netflix"), lgAppIds = listOf("netflix")),
            AppEntry("Prime Video", "amazon", aliases = listOf("prime", "amazon prime", "prime video"), lgAppIds = listOf("amazon", "amazon.leanback.v4")),
            AppEntry("Disney+", "disneyplus", aliases = listOf("disney", "disney+"), lgAppIds = listOf("disneyplus")),
            AppEntry("Browser", "com.webos.app.browser", aliases = listOf("browser", "internet"), lgAppIds = listOf("com.webos.app.browser"))
        )

        "tivo", "vestel" -> listOf(
            AppEntry("YouTube", "YouTube", aliases = listOf("youtube", "yt"), dialNames = listOf("YouTube", "youtube")),
            AppEntry("Netflix", "Netflix", aliases = listOf("netflix"), dialNames = listOf("Netflix", "netflix")),
            AppEntry("Prime Video", "Amazon Prime Video", aliases = listOf("prime", "amazon prime", "prime video"), dialNames = listOf("AmazonVideo", "PrimeVideo", "Amazon Prime Video")),
            AppEntry("Disney+", "Disney+", aliases = listOf("disney", "disney+"), dialNames = listOf("DisneyPlus", "Disney+")),
            AppEntry("Browser", "Browser", aliases = listOf("browser", "internet"), dialNames = listOf("Browser"), macroKeys = listOf("HOME"))
        )

        else -> emptyList()
    }

    private fun saveRegistry(registry: DeviceRegistryResponse) {
        prefs.edit()
            .putString(KEY_ACTIVE_DEVICE_ID, registry.activeDeviceId)
            .putString(KEY_DEVICES, json.encodeToString(ListSerializer(DeviceEntry.serializer()), registry.devices))
            .apply()
    }

    private fun loadTokens(): Map<String, String> {
        return runCatching {
            json.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                prefs.getString(KEY_TOKENS, "{}").orEmpty()
            )
        }.getOrDefault(emptyMap())
    }

    companion object {
        private const val KEY_DEVICES = "direct_devices"
        private const val KEY_ACTIVE_DEVICE_ID = "direct_active_device_id"
        private const val KEY_TOKENS = "direct_tokens"
    }
}
