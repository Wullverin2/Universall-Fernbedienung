package de.craftplay.samsungtvbridge.ui

import de.craftplay.samsungtvbridge.model.AppEntry
import de.craftplay.samsungtvbridge.model.DeviceRegistryResponse
import de.craftplay.samsungtvbridge.model.LearningSummary
import de.craftplay.samsungtvbridge.model.SourceEntry
import de.craftplay.samsungtvbridge.model.StatusResponse

data class MainUiState(
    val isBusy: Boolean = false,
    val status: StatusResponse? = null,
    val sources: List<SourceEntry> = emptyList(),
    val apps: List<AppEntry> = emptyList(),
    val devices: DeviceRegistryResponse = DeviceRegistryResponse(),
    val diagnosticsOutput: String = "",
    val messageLog: List<String> = emptyList(),
    val logFileInfo: String = "",
    val logLineCount: Int = 0,
    val learningSummary: LearningSummary = LearningSummary(),
    val learningExportJson: String = "[]"
)
