package dev.spandan.app.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spandan.mesh.HazardCategory
import dev.spandan.mesh.SpandanPacket

/**
 * Minimal responder-side view: received signals, category, estimated
 * position (when available), status. The real responder dashboard is a
 * separate product per brief -- this just proves the gateway role and ack
 * flow on the same device during a demo.
 */
data class ReceivedSignal(
    val packet: SpandanPacket,
    val receivedAtMillis: Long,
    val acknowledged: Boolean,
)

@Composable
fun ResponderScreen(signals: List<ReceivedSignal>) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Responder view", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
        if (signals.isEmpty()) {
            Text("No signals received yet", fontSize = 18.sp)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(signals, key = { "${it.packet.originId}-${it.packet.msgId}" }) { signal -> SignalRow(signal) }
            }
        }
    }
}

@Composable
private fun SignalRow(signal: ReceivedSignal) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, Color.Black)
            .padding(12.dp),
    ) {
        Text("origin 0x${signal.packet.originId.toString(16).uppercase()}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("hazard: ${describeHazard(signal.packet.hazardCategory)}  severity: ${signal.packet.severity}", fontSize = 18.sp)
        Text(
            if (signal.packet.location.validFix) {
                "position: ${"%.5f".format(signal.packet.location.latDegrees())}, ${"%.5f".format(signal.packet.location.lonDegrees())}"
            } else {
                "position: no GPS fix"
            },
            fontSize = 18.sp,
        )
        Text(if (signal.acknowledged) "status: acknowledged" else "status: pending", fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

private fun describeHazard(hazard: HazardCategory): String = when (hazard) {
    HazardCategory.TRAPPED -> "Trapped"
    HazardCategory.STRANDED -> "Water rising / stranded"
    HazardCategory.MEDICAL -> "Injured"
    HazardCategory.OTHER -> "Other"
}
