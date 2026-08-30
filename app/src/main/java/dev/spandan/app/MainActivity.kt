package dev.spandan.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.collectAsState
import dev.spandan.app.ble.Permissions
import dev.spandan.app.mesh.MeshService
import dev.spandan.app.mesh.MeshSnapshot
import dev.spandan.app.ui.RealMeshRepository
import dev.spandan.app.ui.screens.MessagesScreen
import dev.spandan.app.ui.screens.SosStatusScreen
import dev.spandan.mesh.DropReason
import dev.spandan.mesh.HazardCategory
import dev.spandan.mesh.MeshEvent
import dev.spandan.mesh.NeighbourInfo
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var service: MeshService? by mutableStateOfHolder()
    private var bound = false
    private val repository = RealMeshRepository()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as MeshService.LocalBinder).service()
            service = s
            repository.attach(s)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            repository.detach()
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        service = service,
                        repository = repository,
                        onStart = { startMesh() },
                        onStop = { stopMesh() },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        repository.detach()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    private fun startMesh() {
        val intent = Intent(this, MeshService::class.java)
        startForegroundService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        bound = true
    }

    private fun stopMesh() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        service = null
        stopService(Intent(this, MeshService::class.java))
    }

    // Small helper so `service` can be read/observed from Compose without pulling in a ViewModel.
    private fun mutableStateOfHolder() = androidx.compose.runtime.mutableStateOf<MeshService?>(null)
}

/**
 * Root: the SOS/Status screen is the app. The dev panel (old dense UI, kept
 * verbatim) is reachable through a temporary text link here -- it moves
 * behind a proper 7-tap-on-version-number gate in Settings in a follow-up
 * commit; this is a transitional wire-up so the app is runnable/demoable at
 * every commit in the meantime.
 */
@Composable
private fun AppRoot(service: MeshService?, repository: RealMeshRepository, onStart: () -> Unit, onStop: () -> Unit) {
    var showDevPanel by remember { mutableStateOf(false) }
    var showMessages by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it } && !started) {
            onStart()
            started = true
        }
    }

    // The app opens straight to the SOS screen, nothing gates it -- so the
    // mesh starts itself on first launch rather than waiting for a manual
    // "Start" tap. Onboarding (which explains *why* these permissions are
    // needed before asking) lands in a follow-up commit; for now this is a
    // direct request, same as before.
    LaunchedEffect(Unit) {
        if (!started) permissionLauncher.launch(Permissions.required())
    }

    val uiState by repository.uiState.collectAsState()
    val messages by repository.messages.collectAsState()
    val unreadCount = messages.count { !it.read }

    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
        when {
            showDevPanel -> {
                SpandanScreen(service = service, onStart = onStart, onStop = onStop)
                Text(
                    "< back",
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.TopStart)
                        .padding(8.dp)
                        .background(androidx.compose.ui.graphics.Color(0x99000000))
                        .padding(4.dp)
                        .clickable { showDevPanel = false },
                    color = androidx.compose.ui.graphics.Color.White,
                )
            }
            showMessages -> {
                MessagesScreen(messages = messages, onOpen = { msgId -> repository.markMessageRead(msgId) })
                Text(
                    "< back",
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.TopStart)
                        .padding(8.dp)
                        .clickable { showMessages = false },
                    color = androidx.compose.ui.graphics.Color.White,
                )
            }
            else -> {
                SosStatusScreen(
                    state = uiState,
                    onFire = { category -> repository.fireSos(category) },
                    onCancel = { repository.cancelSos() },
                    onAttachPhrase = { phrase -> repository.attachPhrase(phrase) },
                )
                Text(
                    if (unreadCount > 0) "Messages ($unreadCount)" else "Messages",
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.TopStart)
                        .padding(8.dp)
                        .clickable { showMessages = true },
                    color = if (unreadCount > 0) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Gray,
                )
                Text(
                    "dev",
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.TopEnd)
                        .padding(8.dp)
                        .clickable { showDevPanel = true },
                    color = androidx.compose.ui.graphics.Color.Gray,
                )
            }
        }
    }
}

@Composable
private fun SpandanScreen(service: MeshService?, onStart: () -> Unit, onStop: () -> Unit) {
    var running by remember { mutableStateOf(false) }
    var permissionMessage by remember { mutableStateOf("") }
    var snapshot by remember { mutableStateOf<MeshSnapshot?>(null) }
    var nearby by remember { mutableStateOf<List<NeighbourInfo>>(emptyList()) }
    var severity by remember { mutableStateOf(5) }
    var hazard by remember { mutableStateOf(HazardCategory.TRAPPED) }
    var phrase by remember { mutableStateOf(dev.spandan.mesh.CannedPhrase.NONE) }
    val log = remember { mutableStateListOf<String>() }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            onStart()
            running = true
        } else {
            permissionMessage = "permissions denied: ${grants.filterValues { !it }.keys}"
        }
    }

    // Bind service's event stream into the on-screen log once it's available.
    // Heartbeats (presence-only) are omitted here — the nearby-devices row
    // above already shows that, and logging every ~1-15s per neighbour would
    // drown out the SOS/relay/ack events that actually matter for a demo.
    LaunchedEffect(service) {
        service?.listener = { event ->
            val isHeartbeat = when (event) {
                is MeshEvent.Sent -> event.packet.msgType == dev.spandan.mesh.MsgType.RELAY_META
                is MeshEvent.Received -> event.packet.msgType == dev.spandan.mesh.MsgType.RELAY_META
                else -> false
            }
            if (!isHeartbeat) log.add(0, "${timeFmt.format(System.currentTimeMillis())} ${describe(event)}")
        }
    }

    // Poll role/battery/neighbour/ack snapshot at a light cadence — no extra
    // reactive-state library needed for a Stage-6/7 demo.
    LaunchedEffect(service) {
        while (true) {
            snapshot = service?.snapshot()
            nearby = service?.nearbyDevices() ?: emptyList()
            delay(1_000)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Spandan mesh", style = MaterialTheme.typography.titleMedium)
        val s = snapshot
        if (s != null) {
            Text("origin_id: 0x${s.originId.toString(16).uppercase()}  role: ${s.role}")
            Text("battery bucket: ${s.batteryBucket}/7  neighbours: ${s.neighbourCount}")
            Text(
                "status: " + when {
                    s.pendingSosMsgId == null -> "idle"
                    s.acknowledged -> "acknowledged"
                    else -> "broadcasting"
                }
            )
        } else {
            Text("status: stopped")
        }
        if (permissionMessage.isNotEmpty()) Text(permissionMessage)

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (!running) permissionLauncher.launch(Permissions.required())
            }) { Text("Start") }
            Button(onClick = { onStop(); running = false }) { Text("Stop") }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Gateway")
            Switch(checked = s?.isGateway ?: false, onCheckedChange = { service?.setGateway(it) })
            Text("Weighted")
            Switch(checked = s?.weightedPropagation ?: true, onCheckedChange = { service?.setWeightedPropagation(it) })
        }

        Text("nearby devices (${nearby.size}):", style = MaterialTheme.typography.titleSmall)
        LazyRow(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            items(nearby) { n -> Text(describeNeighbour(n) + "   ") }
        }

        Text("hazard: ${hazard.name}  severity: $severity")
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (h in HazardCategory.entries) {
                Button(onClick = { hazard = h }) { Text(h.name.take(4)) }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { severity = (severity - 1).coerceAtLeast(0) }) { Text("-") }
            Text("$severity")
            Button(onClick = { severity = (severity + 1).coerceAtMost(7) }) { Text("+") }
            Button(onClick = { service?.sendSos(hazard, severity, phrase) }) { Text("Send SOS") }
        }

        Text("phrase: ${phrase.name} (no audio fits in a 24-byte BLE packet — canned phrases instead)")
        LazyRow(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            items(dev.spandan.mesh.CannedPhrase.entries) { p ->
                Button(onClick = { phrase = p }) { Text(p.name.replace('_', ' ').take(14) + "  ") }
            }
        }

        Text("command message (gateway-only, stands in for the responder dashboard):")
        LazyRow(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            items(dev.spandan.mesh.CommandMessage.entries.filter { it != dev.spandan.mesh.CommandMessage.NONE }) { m ->
                Button(onClick = {
                    runCatching { service?.sendCommandMessage(m) }
                }) { Text(m.name.replace('_', ' ').take(16) + "  ") }
            }
        }

        Text("log:", style = MaterialTheme.typography.titleSmall)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(log) { line -> Text(line) }
        }
    }
}

private fun describeNeighbour(n: NeighbourInfo): String =
    "0x${n.originId.toString(16).uppercase()} ${n.lastRssi?.let { "${it}dBm" } ?: ""} sev=${n.lastSeverity}"

// The wire-format 4-bit payload means CannedPhrase on an SOS/ACK but
// CommandMessage on a COMMAND_MESSAGE packet -- see SpandanPacket.commandMessage.
private fun describePayload(packet: dev.spandan.mesh.SpandanPacket): String =
    if (packet.msgType == dev.spandan.mesh.MsgType.COMMAND_MESSAGE) "msg=${packet.commandMessage}" else "phrase=${packet.phrase}"

private fun describe(event: MeshEvent): String = when (event) {
    is MeshEvent.Sent -> "SENT origin=0x${event.packet.originId.toString(16)} type=${event.packet.msgType} sev=${event.packet.severity} hop=${event.packet.hopCount} ${describePayload(event.packet)}"
    is MeshEvent.Received -> "RX origin=0x${event.packet.originId.toString(16)} type=${event.packet.msgType} sev=${event.packet.severity} hop=${event.packet.hopCount} ${describePayload(event.packet)}"
    is MeshEvent.Relayed -> "RELAYED origin=0x${event.packet.originId.toString(16)} hop=${event.packet.hopCount}"
    is MeshEvent.Dropped -> "DROPPED reason=${event.reason}" + (event.packet?.let { " origin=0x${it.originId.toString(16)}" } ?: "")
    is MeshEvent.RoleChanged -> "ROLE -> ${event.role}"
    is MeshEvent.Acknowledged -> "ACKNOWLEDGED msgId=0x${event.msgId.toString(16)}"
}
