package de.craftplay.samsungtvbridge.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.craftplay.samsungtvbridge.data.DirectTvStore
import de.craftplay.samsungtvbridge.data.PersistentAppLogger
import de.craftplay.samsungtvbridge.data.SamsungDirectTvClient
import de.craftplay.samsungtvbridge.data.TvKeyMapping
import de.craftplay.samsungtvbridge.model.ActionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = DirectTvStore(application.getSharedPreferences("universal_remote", 0))
    private val client = SamsungDirectTvClient(application.applicationContext, store)
    private val persistentLogger = PersistentAppLogger(application.applicationContext)
    private val clockFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    var uiState = androidx.compose.runtime.mutableStateOf(
        MainUiState(
            sources = listSourcesForActiveDevice(),
            apps = listAppsForActiveDevice(),
            devices = store.getRegistry(),
            messageLog = persistentLogger.readRecentLines(),
            logFileInfo = persistentLogger.fileDescription(),
            logLineCount = persistentLogger.lineCount()
        )
    )
        private set

    init {
        appendLog("App gestartet. Persistente Logdatei aktiv.")
    }

    fun connectSelectedTv() {
        execute("Verbindung zum TV wird aufgebaut...") {
            val status = client.getStatus()
            val profile = runCatching { client.refreshActiveDeviceProfile() }
                .onSuccess { device ->
                    if (device?.deviceType == "lg") {
                        val details = listOfNotNull(device.modelName, device.sdkVersion?.let { "webOS SDK $it" })
                            .joinToString(" | ")
                            .ifBlank { device.name }
                        appendLog("LG-Profil aktualisiert: $details")
                    }
                }
                .onFailure { error ->
                    appendLog("Profil konnte nicht aktualisiert werden: ${error.message}", true)
                }
                .getOrNull()
            val nextStatus = if (profile != null && profile.ip == status.tvIp) {
                status.copy(name = profile.name)
            } else {
                status
            }
            uiState.value = uiState.value.copy(
                status = nextStatus,
                devices = client.getRegistry(),
                sources = listSourcesForActiveDevice(),
                apps = listAppsForActiveDevice()
            )
            appendLog("Verbunden mit ${nextStatus.name}.")
        }
    }

    fun refreshStatus() {
        execute("Status wird geladen...") {
            val status = client.getStatus()
            uiState.value = uiState.value.copy(status = status, devices = client.getRegistry())
            appendLog("Status aktualisiert.")
        }
    }

    fun scanDevices() {
        execute("TVs werden gesucht...") {
            val (registry, summary) = client.scanDevices()
            uiState.value = uiState.value.copy(devices = registry)
            appendLog("Scan abgeschlossen: ${summary.foundCount} Gerät(e) gefunden.")
            refreshStatusSilently()
        }
    }

    fun loadDevices() {
        uiState.value = uiState.value.copy(devices = client.getRegistry())
        appendLog("Geräteliste geladen.")
    }

    fun selectDevice(deviceId: String, name: String) {
        runCatching {
            client.selectDevice(deviceId)
            uiState.value = uiState.value.copy(
                devices = client.getRegistry(),
                status = null,
                sources = listSourcesForActiveDevice(),
                apps = listAppsForActiveDevice()
            )
            appendLog("TV ausgewählt: $name")
        }.onFailure {
            appendLog(it.message ?: "TV konnte nicht ausgewählt werden.", true)
        }
    }

    fun removeDevice(deviceId: String, name: String) {
        runCatching {
            uiState.value = uiState.value.copy(
                devices = client.removeDevice(deviceId),
                sources = listSourcesForActiveDevice(),
                apps = listAppsForActiveDevice()
            )
            appendLog("TV entfernt: $name")
        }.onFailure {
            appendLog(it.message ?: "TV konnte nicht entfernt werden.", true)
        }
    }

    fun powerOn() = action("Power On", "Wake-on-LAN senden", "Wake-on-LAN gesendet.") { client.powerOn() }

    fun powerOff() = action("Power Off", "TV ausschalten", "Ausschalten gesendet.") { client.powerOff() }

    fun sendKey(key: String) = action("Button $key", describeRemoteKey(key), "Taste gesendet: $key") { client.sendRemoteKey(key) }

    fun setSource(name: String) = action("Quelle $name", "Eingang wechseln", "Quelle gewechselt: $name") { client.setSource(name) }

    fun launchApp(name: String) = action("App $name", "App starten", "App gestartet: $name") { client.launchApp(name) }

    fun loadDiagnostics() {
        execute("Diagnose wird geladen...") {
            uiState.value = uiState.value.copy(diagnosticsOutput = client.getDiagnostics())
            appendLog("Diagnose aktualisiert.")
        }
    }

    fun reloadPersistentLog() {
        uiState.value = uiState.value.copy(
            messageLog = persistentLogger.readRecentLines(),
            logFileInfo = persistentLogger.fileDescription(),
            logLineCount = persistentLogger.lineCount()
        )
    }

    fun clearPersistentLog() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                persistentLogger.clear()
            }
            uiState.value = uiState.value.copy(messageLog = emptyList(), logLineCount = 0)
            appendLog("Persistente Logdatei wurde geleert.")
        }
    }

    private fun action(actionName: String, plannedFunction: String, successFallback: String, block: suspend () -> ActionResponse) {
        execute("$actionName -> geplant: $plannedFunction") {
            val response = block()
            refreshStatusSilently()
            val message = response.message
                ?: response.app?.let { "App gestartet: $it" }
                ?: response.source?.let { "Quelle gewechselt: $it" }
                ?: response.key?.let { "Taste gesendet: $it" }
                ?: response.name?.let { "Aktion ausgeführt: $it" }
                ?: successFallback
            appendLog(message)
        }
    }

    private fun describeRemoteKey(key: String): String = when (TvKeyMapping.generic(key)) {
        "HOME" -> "Home öffnen"
        "BACK" -> "Zurück"
        "MENU" -> "Menü öffnen"
        "SOURCE" -> "Eingangsquelle öffnen"
        "GUIDE" -> "TV-Guide öffnen"
        "INFO" -> "Info anzeigen"
        "TELETEXT" -> "Teletext"
        "OK" -> "OK/Enter"
        "UP" -> "Navigation hoch"
        "DOWN" -> "Navigation runter"
        "LEFT" -> "Navigation links"
        "RIGHT" -> "Navigation rechts"
        "VOLUME_UP" -> "Lauter"
        "VOLUME_DOWN" -> "Leiser"
        "MUTE" -> "Stumm schalten"
        "CHANNEL_UP" -> "Sender hoch"
        "CHANNEL_DOWN" -> "Sender runter"
        "EXIT" -> "Beenden"
        else -> "Remote-Taste senden"
    }

    private fun refreshStatusSilently() {
        viewModelScope.launch {
            runCatching {
                val status = client.getStatus()
                uiState.value = uiState.value.copy(
                    status = status,
                    devices = client.getRegistry(),
                    sources = listSourcesForActiveDevice(),
                    apps = listAppsForActiveDevice()
                )
            }
        }
    }

    private fun execute(progressMessage: String, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        viewModelScope.launch {
            try {
                uiState.value = uiState.value.copy(isBusy = true)
                appendLog(progressMessage)
                block()
            } catch (error: Exception) {
                appendLog(error.message ?: "Unbekannter Fehler", true)
            } finally {
                uiState.value = uiState.value.copy(isBusy = false)
            }
        }
    }

    private fun appendLog(message: String, isError: Boolean = false) {
        val prefix = if (isError) "FEHLER" else "INFO"
        val timestamp = LocalDateTime.now().format(clockFormatter)
        val deviceContext = store.getActiveDevice()?.let { device ->
            " | tv=${device.name} | ip=${device.ip} | typ=${device.deviceType}"
        }.orEmpty()
        val line = "[$timestamp] [$prefix] $message$deviceContext"
        if (isError) {
            Log.e("UniversalRemote", line)
        } else {
            Log.i("UniversalRemote", line)
        }
        val nextMessages = listOf(line) + uiState.value.messageLog
        uiState.value = uiState.value.copy(
            messageLog = nextMessages.take(120),
            logLineCount = uiState.value.logLineCount + 1,
            logFileInfo = persistentLogger.fileDescription()
        )
        viewModelScope.launch(Dispatchers.IO) {
            persistentLogger.append(line)
        }
    }

    private fun listSourcesForActiveDevice() =
        store.listSources(store.getActiveDevice()?.deviceType ?: "samsung")

    private fun listAppsForActiveDevice() =
        store.listApps(store.getActiveDevice()?.deviceType ?: "samsung")
}
