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
import dev.spandan.app.ble.Permissions
import dev.spandan.app.mesh.MeshService
import dev.spandan.app.mesh.MeshSnapshot
import dev.spandan.mesh.DropReason
import dev.spandan.mesh.HazardCategory
import dev.spandan.mesh.MeshEvent
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var service: MeshService? by mutableStateOfHolder()
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as MeshService.LocalBinder).service()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SpandanScreen(
                        service = service,
                        onStart = { startMesh() },
                        onStop = { stopMesh() },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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

@Composable
private fun SpandanScreen(service: MeshService?, onStart: () -> Unit, onStop: () -> Unit) {
    var running by remember { mutableStateOf(false) }
    var permissionMessage by remember { mutableStateOf("") }
    var snapshot by remember { mutableStateOf<MeshSnapshot?>(null) }
    var severity by remember { mutableStateOf(5) }
    var hazard by remember { mutableStateOf(HazardCategory.TRAPPED) }
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
    LaunchedEffect(service) {
        service?.listener = { event -> log.add(0, "${timeFmt.format(System.currentTimeMillis())} ${describe(event)}") }
    }

    // Poll role/battery/neighbour/ack snapshot at a light cadence — no extra
    // reactive-state library needed for a Stage-6/7 demo.
    LaunchedEffect(service) {
        while (true) {
            snapshot = service?.snapshot()
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
            Button(onClick = { service?.sendSos(hazard, severity) }) { Text("Send SOS") }
        }

        Text("log:", style = MaterialTheme.typography.titleSmall)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(log) { line -> Text(line) }
        }
    }
}

private fun describe(event: MeshEvent): String = when (event) {
    is MeshEvent.Sent -> "SENT origin=0x${event.packet.originId.toString(16)} type=${event.packet.msgType} sev=${event.packet.severity} hop=${event.packet.hopCount}"
    is MeshEvent.Received -> "RX origin=0x${event.packet.originId.toString(16)} type=${event.packet.msgType} sev=${event.packet.severity} hop=${event.packet.hopCount}"
    is MeshEvent.Relayed -> "RELAYED origin=0x${event.packet.originId.toString(16)} hop=${event.packet.hopCount}"
    is MeshEvent.Dropped -> "DROPPED reason=${event.reason}" + (event.packet?.let { " origin=0x${it.originId.toString(16)}" } ?: "")
    is MeshEvent.RoleChanged -> "ROLE -> ${event.role}"
    is MeshEvent.Acknowledged -> "ACKNOWLEDGED msgId=0x${event.msgId.toString(16)}"
}
