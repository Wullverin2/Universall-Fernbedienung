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

    fun listSources(deviceType: String): List<SourceEntry> = when (deviceType.lowercase()) {
        "samsung" -> listOf(
            SourceEntry("HDMI1", listOf("hdmi 1", "konsole", "receiver"), listOf("KEY_SOURCE", "KEY_DOWN", "KEY_ENTER"), delayMs = 600, initialDelayMs = 900),
            SourceEntry("HDMI2", listOf("hdmi 2", "fire tv", "firetv"), listOf("KEY_SOURCE", "KEY_DOWN", "KEY_DOWN", "KEY_ENTER"), delayMs = 600, initialDelayMs = 900),
            SourceEntry("TV", listOf("fernsehen", "live tv", "tv"), listOf("KEY_SOURCE", "KEY_UP", "KEY_ENTER"), delayMs = 600, initialDelayMs = 900)
        )

        "lg" -> listOf(
            SourceEntry("Home Dashboard", listOf("home", "dashboard"), listOf("KEY_HOME")),
            SourceEntry("Live TV", listOf("tv", "fernsehen"), listOf("KEY_TV"))
        )

        else -> emptyList()
    }

    fun listApps(deviceType: String): List<AppEntry> = when (deviceType.lowercase()) {
        "samsung" -> listOf(
            AppEntry("YouTube", "9Ur5IzDKqV.TizenYouTube", "9Ur5IzDKqV.TizenYouTube", listOf("youtube", "yt")),
            AppEntry("Netflix", "11101200001", "RN1MCdNq8t.Netflix", listOf("netflix")),
            AppEntry("Prime Video", "3201512006785", "evKhCgZelL.AmazonIgnitionLauncher2", listOf("prime", "amazon prime", "prime video")),
            AppEntry("Crunchyroll", "3202302030097", "OGLLvqej7u.CrunchyrollWebApp", listOf("crunchyroll", "anime"), "NATIVE_LAUNCH"),
            AppEntry("Sky X", "3201812017464", "J0zX4W0EmB.SkyX", listOf("sky x", "skyx"), "NATIVE_LAUNCH"),
            AppEntry("Joyn", "3202106024013", "2200MKoe7n.ZAPPNVOLLTVFREIGESTREAMT", listOf("joyn"), "NATIVE_LAUNCH"),
            AppEntry("Plex", "3201512006963", "kIciSQlYEM.plex", listOf("plex"), "NATIVE_LAUNCH"),
            AppEntry("simpliTV", "LibFXRqQAD.simplitv", "LibFXRqQAD.simplitv", listOf("simpli", "simplitv"), "NATIVE_LAUNCH"),
            AppEntry("Internet", "org.tizen.browser", "org.tizen.browser", listOf("internet", "browser"), "NATIVE_LAUNCH")
        )

        "lg" -> listOf(
            AppEntry("YouTube", "youtube.leanback.v4", aliases = listOf("youtube", "yt")),
            AppEntry("Netflix", "netflix", aliases = listOf("netflix")),
            AppEntry("Prime Video", "amazon", aliases = listOf("prime", "amazon prime", "prime video")),
            AppEntry("Disney+", "disneyplus", aliases = listOf("disney", "disney+")),
            AppEntry("Browser", "com.webos.app.browser", aliases = listOf("browser", "internet"))
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
