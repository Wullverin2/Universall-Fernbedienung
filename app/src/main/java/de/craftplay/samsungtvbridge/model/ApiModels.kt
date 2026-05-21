package de.craftplay.samsungtvbridge.model

import kotlinx.serialization.Serializable

@Serializable
data class StatusResponse(
    val online: Boolean = false,
    val port: Int? = null,
    val tvIp: String? = null,
    val name: String = "-",
    val selectionRequired: Boolean = false
)

@Serializable
data class SourceEntry(
    val name: String,
    val aliases: List<String> = emptyList(),
    val keys: List<String> = emptyList(),
    val delayMs: Int = 600,
    val initialDelayMs: Int = 900
)

@Serializable
data class AppEntry(
    val name: String,
    val appId: String? = null,
    val tizenAppId: String? = null,
    val aliases: List<String> = emptyList(),
    val actionType: String = "NATIVE_LAUNCH",
    val dialNames: List<String> = emptyList(),
    val lgAppIds: List<String> = emptyList(),
    val vestelKeys: List<String> = emptyList(),
    val macroKeys: List<String> = emptyList()
)

@Serializable
data class DeviceEntry(
    val id: String,
    val ip: String,
    val name: String,
    val deviceType: String = "samsung",
    val modelName: String? = null,
    val firmwareVersion: String? = null,
    val sdkVersion: String? = null,
    val mac: String? = null,
    val duid: String? = null,
    val networkType: String? = null,
    val wakeOnWirelessLan: Boolean? = null,
    val controlUrl: String? = null,
    val controlMethod: String? = null,
    val platform: String? = null,
    val supportsDial: Boolean = false,
    val supportsNetworkRemote: Boolean = false,
    val supportsWakeOnLan: Boolean = false,
    val supportsSmartCenter: Boolean = false,
    val supportsTiVoProfile: Boolean = false,
    val lastErrorCode: String? = null,
    val lastSuccessfulCommand: String? = null,
    val lastRequestUri: String? = null,
    val pairingStatus: String? = null,
    val firstSeenAt: String,
    val lastSeenAt: String,
    val missing: Boolean = false
)

@Serializable
data class DeviceRegistryResponse(
    val activeDeviceId: String? = null,
    val devices: List<DeviceEntry> = emptyList()
)

@Serializable
data class ActionResponse(
    val success: Boolean = true,
    val message: String? = null,
    val key: String? = null,
    val port: Int? = null,
    val number: String? = null,
    val name: String? = null,
    val source: String? = null,
    val app: String? = null,
    val appId: String? = null,
    val method: String? = null
)

@Serializable
data class DeviceScanSummary(
    val scannedCandidateCount: Int,
    val foundCount: Int
)
