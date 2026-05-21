package de.craftplay.samsungtvbridge.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.craftplay.samsungtvbridge.data.DirectTvStore
import de.craftplay.samsungtvbridge.data.SamsungDirectTvClient
import de.craftplay.samsungtvbridge.model.ActionResponse
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = DirectTvStore(application.getSharedPreferences("universal_remote", 0))
    private val client = SamsungDirectTvClient(application.applicationContext, store)
    private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    var uiState = androidx.compose.runtime.mutableStateOf(
        MainUiState(
            sources = listSourcesForActiveDevice(),
            apps = listAppsForActiveDevice(),
            devices = store.getRegistry()
        )
    )
        private set

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

    private fun describeRemoteKey(key: String): String = when (key) {
        "KEY_HOME" -> "Home oeffnen"
        "KEY_RETURN" -> "Zurueck"
        "KEY_MENU" -> "Menue oeffnen"
        "KEY_SOURCE" -> "Eingangsquelle oeffnen"
        "KEY_GUIDE" -> "TV-Guide oeffnen"
        "KEY_INFO" -> "Info anzeigen"
        "KEY_TTX_MIX" -> "Teletext"
        "KEY_ENTER" -> "OK/Enter"
        "KEY_UP" -> "Navigation hoch"
        "KEY_DOWN" -> "Navigation runter"
        "KEY_LEFT" -> "Navigation links"
        "KEY_RIGHT" -> "Navigation rechts"
        "KEY_VOLUP" -> "Lauter"
        "KEY_VOLDOWN" -> "Leiser"
        "KEY_MUTE" -> "Stumm schalten"
        "KEY_CHUP" -> "Sender hoch"
        "KEY_CHDOWN" -> "Sender runter"
        "KEY_EXIT" -> "Beenden"
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
        val timestamp = LocalTime.now().format(clockFormatter)
        val nextMessages = listOf("[$timestamp] [$prefix] $message") + uiState.value.messageLog
        uiState.value = uiState.value.copy(messageLog = nextMessages.take(80))
    }

    private fun listSourcesForActiveDevice() =
        store.listSources(store.getActiveDevice()?.deviceType ?: "samsung")

    private fun listAppsForActiveDevice() =
        store.listApps(store.getActiveDevice()?.deviceType ?: "samsung")
}
