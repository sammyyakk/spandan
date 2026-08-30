package dev.spandan.app

import android.os.Bundle
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.spandan.app.ble.SpandanAdvertiser
import dev.spandan.app.ble.SpandanScanner
import dev.spandan.mesh.HazardCategory
import dev.spandan.mesh.MsgType
import dev.spandan.mesh.QuantizedLocation
import dev.spandan.mesh.SpandanPacket
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.random.Random

class MainActivity : ComponentActivity() {

    private val originId = Random.nextInt(0, 0x10000) // per-session pseudonymous ID

    private lateinit var advertiser: SpandanAdvertiser
    private lateinit var scanner: SpandanScanner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        advertiser = SpandanAdvertiser(this)
        scanner = SpandanScanner(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SpandanScreen(
                        originId = originId,
                        advertiser = advertiser,
                        scanner = scanner,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpandanScreen(originId: Int, advertiser: SpandanAdvertiser, scanner: SpandanScanner) {
    var running by remember { mutableStateOf(false) }
    var statusLine by remember { mutableStateOf("stopped") }
    val log = remember { mutableStateListOf<String>() }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            startMesh(originId, advertiser, scanner, log, timeFmt) { statusLine = it }
            running = true
        } else {
            statusLine = "permissions denied: ${grants.filterValues { !it }.keys}"
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Spandan — Stage 1: advertise + scan", style = MaterialTheme.typography.titleMedium)
        Text("origin_id: 0x${originId.toString(16).uppercase()}")
        Text("status: $statusLine")

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = {
                if (!running) {
                    permissionLauncher.launch(dev.spandan.app.ble.Permissions.required())
                }
            }) { Text("Start") }

            Button(onClick = {
                advertiser.stop()
                scanner.stop()
                running = false
                statusLine = "stopped"
            }) { Text("Stop") }
        }

        Text("log:", style = MaterialTheme.typography.titleSmall)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(log.asReversed()) { line -> Text(line) }
        }
    }
}

private fun startMesh(
    originId: Int,
    advertiser: SpandanAdvertiser,
    scanner: SpandanScanner,
    log: MutableList<String>,
    timeFmt: SimpleDateFormat,
    setStatus: (String) -> Unit,
) {
    val fixedPacket = SpandanPacket(
        msgType = MsgType.SOS,
        protocolVersion = 0,
        hazardCategory = HazardCategory.TRAPPED,
        severity = 5,
        originId = originId,
        msgId = Random.nextInt(0, 0x10000),
        location = QuantizedLocation.noFix(),
        baroValid = false,
        baroDeltaDeciHpa = 0,
        batteryBucket = 7,
        livenessBucket = 0,
        originTs = 0,
        hopCount = 0,
    )
    val payload = fixedPacket.encode()

    advertiser.start(payload) { success, message ->
        log.add(0, "${timeFmt.format(System.currentTimeMillis())} ADV ${if (success) "OK" else "FAIL"}: $message")
        setStatus(if (success) "advertising + scanning" else message)
    }

    scanner.start(
        onEvent = { event ->
            val hex = event.rawBytes.joinToString(" ") { "%02X".format(it) }
            var line = "${timeFmt.format(event.timestampMillis)} RX rssi=${event.rssi} bytes=[$hex]"
            if (event.rawBytes.size == dev.spandan.mesh.PACKET_SIZE_BYTES) {
                runCatching { SpandanPacket.decode(event.rawBytes) }.onSuccess { p ->
                    line += " origin=0x${p.originId.toString(16)} severity=${p.severity} hop=${p.hopCount}"
                }
            }
            log.add(0, line)
        },
        onResult = { success, message ->
            log.add(0, "${timeFmt.format(System.currentTimeMillis())} SCAN ${if (success) "OK" else "FAIL"}: $message")
        },
    )
}
