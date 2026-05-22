package de.craftplay.samsungtvbridge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.craftplay.samsungtvbridge.model.AppEntry
import de.craftplay.samsungtvbridge.model.DeviceEntry
import de.craftplay.samsungtvbridge.model.SourceEntry
import de.craftplay.samsungtvbridge.ui.MainUiState
import de.craftplay.samsungtvbridge.ui.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF77A8FF),
                    secondary = Color(0xFF89D89F),
                    surface = Color(0xFF161A20),
                    background = Color(0xFF090B10)
                )
            ) {
                val viewModel: MainViewModel = viewModel()
                val state = viewModel.uiState.value
                UniversalRemoteApp(
                    state = state,
                    onConnect = viewModel::connectSelectedTv,
                    onRefreshStatus = viewModel::refreshStatus,
                    onScanDevices = viewModel::scanDevices,
                    onLoadDevices = viewModel::loadDevices,
                    onSelectDevice = viewModel::selectDevice,
                    onRemoveDevice = viewModel::removeDevice,
                    onPowerOn = viewModel::powerOn,
                    onPowerOff = viewModel::powerOff,
                    onSendKey = viewModel::sendKey,
                    onSource = viewModel::setSource,
                    onApp = viewModel::launchApp,
                    onDiagnostics = viewModel::loadDiagnostics,
                    onReloadLog = viewModel::reloadPersistentLog,
                    onClearLog = viewModel::clearPersistentLog
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UniversalRemoteApp(
    state: MainUiState,
    onConnect: () -> Unit,
    onRefreshStatus: () -> Unit,
    onScanDevices: () -> Unit,
    onLoadDevices: () -> Unit,
    onSelectDevice: (String, String) -> Unit,
    onRemoveDevice: (String, String) -> Unit,
    onPowerOn: () -> Unit,
    onPowerOff: () -> Unit,
    onSendKey: (String) -> Unit,
    onSource: (String) -> Unit,
    onApp: (String) -> Unit,
    onDiagnostics: () -> Unit,
    onReloadLog: () -> Unit,
    onClearLog: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Universal Fernbedienung") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = {
                    TextButton(onClick = onScanDevices) { Text("Scannen") }
                    TextButton(onClick = onConnect) { Text("Verbinden") }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { StatusCard(state, onRefreshStatus) }
                item {
                    DevicesCard(
                        devices = state.devices.devices,
                        activeDeviceId = state.devices.activeDeviceId,
                        onScanDevices = onScanDevices,
                        onLoadDevices = onLoadDevices,
                        onSelectDevice = onSelectDevice,
                        onRemoveDevice = onRemoveDevice
                    )
                }
                item {
                    RemoteShell(
                        onPowerOn = onPowerOn,
                        onPowerOff = onPowerOff,
                        onSendKey = onSendKey
                    )
                }
                item {
                    SectionCard(title = "Eingänge") {
                        TwoColumnButtonGrid(labels = state.sources.map(SourceEntry::name), onClick = onSource)
                    }
                }
                item {
                    SectionCard(title = "Apps") {
                        TwoColumnButtonGrid(labels = state.apps.map(AppEntry::name), onClick = onApp)
                    }
                }
                item { DiagnosticsCard(state.diagnosticsOutput, onDiagnostics) }
                item {
                    LogCard(
                        messages = state.messageLog,
                        logFileInfo = state.logFileInfo,
                        logLineCount = state.logLineCount,
                        onReloadLog = onReloadLog,
                        onClearLog = onClearLog
                    )
                }
                item { Spacer(modifier = Modifier.height(12.dp)) }
            }

            if (state.isBusy) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x55000000)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: MainUiState, onRefreshStatus: () -> Unit) {
    val status = state.status
    val title = status?.name ?: "Noch keine Daten"
    val connectionText = when {
        status == null -> "Bitte zuerst ein TV auswählen und verbinden."
        status.selectionRequired -> "Bitte zuerst unten ein TV-Gerät auswählen."
        status.online -> "TV ist online."
        else -> "TV ist aktuell nicht erreichbar."
    }

    SectionCard(title = "Status") {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text("IP: ${status?.tvIp ?: "-"}")
        Text("Port: ${status?.port?.toString() ?: "-"}")
        Text(connectionText, color = if (status?.online == true) Color(0xFF86D6A3) else Color(0xFFFF9A9A))
        OutlinedButton(onClick = onRefreshStatus, modifier = Modifier.fillMaxWidth()) {
            Text("Status aktualisieren")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicesCard(
    devices: List<DeviceEntry>,
    activeDeviceId: String?,
    onScanDevices: () -> Unit,
    onLoadDevices: () -> Unit,
    onSelectDevice: (String, String) -> Unit,
    onRemoveDevice: (String, String) -> Unit
) {
    var expanded by remember(devices, activeDeviceId) { mutableStateOf(false) }
    var pendingDeviceId by remember(devices, activeDeviceId) { mutableStateOf(activeDeviceId ?: devices.firstOrNull()?.id) }
    val selectedDevice = devices.firstOrNull { it.id == pendingDeviceId }

    SectionCard(title = "TV-Geräte") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onScanDevices, modifier = Modifier.weight(1f)) { Text("Scannen") }
            OutlinedButton(onClick = onLoadDevices, modifier = Modifier.weight(1f)) { Text("Laden") }
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (devices.isEmpty()) {
            Text("Noch keine TVs gespeichert.")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = selectedDevice?.let { device ->
                            buildString {
                                append(device.name)
                                if (device.id == activeDeviceId) {
                                    append(" • Aktiv")
                                }
                            }
                        } ?: "Bitte Gerät wählen",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Erkannte Geräte") },
                        supportingText = {
                            selectedDevice?.let { device ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    DeviceTypeBadge(device.deviceType)
                                    DevicePresenceBadge(device.missing)
                                }
                            }
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        devices.forEach { device ->
                            DropdownMenuItem(
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                buildString {
                                                    append(device.name)
                                                    if (device.id == activeDeviceId) {
                                                        append(" • Aktiv")
                                                    }
                                                },
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            DevicePresenceBadge(device.missing)
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            DeviceTypeBadge(device.deviceType)
                                            Text(
                                                "${device.modelName ?: "Modell unbekannt"} | ${device.ip}",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    pendingDeviceId = device.id
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            selectedDevice?.let { onSelectDevice(it.id, it.name) }
                        },
                        enabled = selectedDevice != null && selectedDevice.id != activeDeviceId,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (selectedDevice?.id == activeDeviceId) "Aktiv" else "Auswahl bestätigen")
                    }
                    OutlinedButton(
                        onClick = {
                            selectedDevice?.let { onRemoveDevice(it.id, it.name) }
                        },
                        enabled = selectedDevice != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Entfernen")
                    }
                }

                selectedDevice?.let { device ->
                    Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(device.name, fontWeight = FontWeight.Bold)
                                DevicePresenceBadge(device.missing)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                DeviceTypeBadge(device.deviceType)
                                Text(device.modelName ?: "Modell unbekannt")
                            }
                            Text("IP: ${device.ip}")
                            device.firmwareVersion?.let { Text("Firmware: $it") }
                            device.sdkVersion?.let { Text("webOS SDK: $it") }
                            device.networkType?.let { Text("Netzwerk: $it") }
                            device.wakeOnWirelessLan?.let { enabled ->
                                Text("Wake-on-WiFi: ${if (enabled) "aktiv" else "nicht aktiv"}")
                            }
                            device.controlMethod?.let { Text("Steuerung: $it") }
                            device.controlUrl?.let { Text("Steueradresse: $it") }
                            device.platform?.let { Text("Plattform: $it") }
                            device.pairingStatus?.let { Text("Pairing: $it") }
                            device.lastRequestUri?.let { Text("Letzter Request: $it") }
                            device.lastSuccessfulCommand?.let { Text("Letzter Erfolg: $it") }
                            device.lastErrorCode?.let { Text("Letzter Fehler: $it", color = Color(0xFFFF9A9A)) }
                            Text(device.mac ?: "MAC unbekannt")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteShell(
    onPowerOn: () -> Unit,
    onPowerOff: () -> Unit,
    onSendKey: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                CircleRemoteButton("On", onPowerOn, filled = true)
                CircleRemoteButton("Off", onPowerOff, filled = false)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniRemoteButton("TXT") { onSendKey("KEY_TTX_MIX") }
                MiniRemoteButton("Home") { onSendKey("KEY_HOME") }
                MiniRemoteButton("Menü") { onSendKey("KEY_MENU") }
            }

            DPad(onSendKey)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniRemoteButton("Vol+") { onSendKey("KEY_VOLUP") }
                MiniRemoteButton("Vol-") { onSendKey("KEY_VOLDOWN") }
                MiniRemoteButton("Mute") { onSendKey("KEY_MUTE") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniRemoteButton("CH+") { onSendKey("KEY_CHUP") }
                MiniRemoteButton("CH-") { onSendKey("KEY_CHDOWN") }
                MiniRemoteButton("Guide") { onSendKey("KEY_GUIDE") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniRemoteButton("Zurück") { onSendKey("KEY_RETURN") }
                MiniRemoteButton("Source") { onSendKey("KEY_SOURCE") }
                MiniRemoteButton("Info") { onSendKey("KEY_INFO") }
            }

            NumericPad(onSendKey)
        }
    }
}

@Composable
private fun DPad(onSendKey: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CircleRemoteButton("▲", { onSendKey("KEY_UP") }, filled = false, small = true)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleRemoteButton("◀", { onSendKey("KEY_LEFT") }, filled = false, small = true)
            CircleRemoteButton("OK", { onSendKey("KEY_ENTER") }, filled = true, small = true)
            CircleRemoteButton("▶", { onSendKey("KEY_RIGHT") }, filled = false, small = true)
        }
        CircleRemoteButton("▼", { onSendKey("KEY_DOWN") }, filled = false, small = true)
    }
}

@Composable
private fun NumericPad(onSendKey: (String) -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("-", "0", "Exit")
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { label ->
                    OutlinedButton(
                        onClick = {
                            when (label) {
                                "-" -> onSendKey("KEY_PRECH")
                                "Exit" -> onSendKey("KEY_EXIT")
                                else -> onSendKey("KEY_$label")
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1.35f)
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(output: String, onDiagnostics: () -> Unit) {
    SectionCard(title = "Diagnose") {
        Button(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) {
            Text("Diagnose laden")
        }
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF10141B), RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Text(
                text = if (output.isBlank()) "Noch keine Diagnose geladen." else output,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun LogCard(
    messages: List<String>,
    logFileInfo: String,
    logLineCount: Int,
    onReloadLog: () -> Unit,
    onClearLog: () -> Unit
) {
    val context = LocalContext.current
    SectionCard(title = "Log") {
        Text("Gespeicherte Zeilen: $logLineCount", style = MaterialTheme.typography.bodySmall)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF10141B), RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            Text(
                text = logFileInfo.ifBlank { "Logdatei wird beim ersten Eintrag angelegt." },
                style = MaterialTheme.typography.bodySmall
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onReloadLog, modifier = Modifier.weight(1f)) {
                Text("Neu laden")
            }
            OutlinedButton(onClick = onClearLog, modifier = Modifier.weight(1f)) {
                Text("Leeren")
            }
        }
        OutlinedButton(
            onClick = {
                val text = messages.joinToString("\n").ifBlank { "Keine Logeintraege vorhanden." }
                val intent = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Universal Fernbedienung Diagnose-Log")
                    .putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(intent, "Log teilen"))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Log teilen")
        }
        if (messages.isEmpty()) {
            Text("Noch keine Aktionen.")
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                messages.forEach { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF10141B), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun TwoColumnButtonGrid(labels: List<String>, onClick: (String) -> Unit) {
    if (labels.isEmpty()) {
        Text("Keine Einträge verfügbar.")
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.chunked(2).forEach { rowLabels ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowLabels.forEach { label ->
                    Button(onClick = { onClick(label) }, modifier = Modifier.weight(1f)) {
                        Text(label)
                    }
                }
                if (rowLabels.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MiniRemoteButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.height(44.dp)) {
        Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

@Composable
private fun DeviceTypeBadge(type: String) {
    val (label, backgroundColor, borderColor) = when (type.lowercase()) {
        "lg" -> Triple("LG webOS", Color(0xFF1E3A2B), Color(0xFF4CAF7B))
        "tivo", "vestel" -> Triple("Nabo / Vestel", Color(0xFF2C2145), Color(0xFF9B7BFF))
        else -> Triple("Samsung", Color(0xFF1D2D45), Color(0xFF77A8FF))
    }

    Box(
        modifier = Modifier
            .background(backgroundColor, RoundedCornerShape(999.dp))
            .border(1.dp, borderColor, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.White)
    }
}

@Composable
private fun DevicePresenceBadge(missing: Boolean) {
    val (label, backgroundColor, borderColor) = if (missing) {
        Triple("Zuletzt nicht gefunden", Color(0xFF402020), Color(0xFFE57373))
    } else {
        Triple("Beim letzten Scan gefunden", Color(0xFF17311D), Color(0xFF7AD79B))
    }

    Box(
        modifier = Modifier
            .background(backgroundColor, RoundedCornerShape(999.dp))
            .border(1.dp, borderColor, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.White)
    }
}

@Composable
private fun CircleRemoteButton(label: String, onClick: () -> Unit, filled: Boolean, small: Boolean = false) {
    val size = if (small) 72.dp else 74.dp
    if (filled) {
        Button(
            onClick = onClick,
            shape = CircleShape,
            modifier = Modifier.size(size)
        ) {
            Text(
                text = label,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = CircleShape,
            modifier = Modifier.size(size)
        ) {
            Text(
                text = label,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center
            )
        }
    }
}
