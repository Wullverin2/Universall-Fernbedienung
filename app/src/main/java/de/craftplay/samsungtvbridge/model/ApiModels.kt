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
    val actionType: String = "DEEP_LINK",
    val metaTag: String? = null,
    val samsungAppIds: List<String> = emptyList(),
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

@Serializable
data class LearningEntry(
    val id: String,
    val timestamp: String,
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val platform: String? = null,
    val modelName: String? = null,
    val firmwareVersion: String? = null,
    val sdkVersion: String? = null,
    val ip: String,
    val actionName: String,
    val plannedFunction: String,
    val input: String,
    val normalizedInput: String,
    val success: Boolean,
    val method: String? = null,
    val port: Int? = null,
    val appId: String? = null,
    val message: String? = null,
    val error: String? = null
)

@Serializable
data class LearningSummary(
    val total: Int = 0,
    val successes: Int = 0,
    val failures: Int = 0,
    val byDeviceType: List<LearningBucket> = emptyList(),
    val byModel: List<LearningBucket> = emptyList(),
    val byInput: List<LearningBucket> = emptyList(),
    val recent: List<LearningEntry> = emptyList()
)

@Serializable
data class LearningBucket(
    val name: String,
    val total: Int,
    val successes: Int,
    val failures: Int
)
