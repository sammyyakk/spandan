package dev.spandan.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.spandan.app.ui.theme.SpandanBody
import dev.spandan.app.ui.theme.SpandanColors
import dev.spandan.app.ui.theme.SpandanHeadline
import dev.spandan.app.ui.theme.SpandanShape
import dev.spandan.app.ui.theme.SpandanSpacing
import dev.spandan.mesh.HazardCategory
import dev.spandan.mesh.SpandanPacket

/**
 * Command-console register, deliberately not the victim UI: cool teal-on-
 * near-black instead of hazard yellow/pink, dense list instead of one giant
 * action, triage framing ("pending"/"acknowledged") instead of reassurance
 * copy. A responder is doing a different job than a victim and the screen
 * must never look interchangeable with SosStatusScreen.
 */
data class ReceivedSignal(
    val packet: SpandanPacket,
    val receivedAtMillis: Long,
    val acknowledged: Boolean,
)

@Composable
fun ResponderScreen(signals: List<ReceivedSignal>) {
    val pending = signals.count { !it.acknowledged }
    Column(modifier = Modifier.fillMaxSize().background(SpandanColors.ResponderSurface).padding(SpandanSpacing.md)) {
        SpandanHeadline("Responder", color = SpandanColors.OnResponderSurface)
        SpandanBody(
            if (signals.isEmpty()) "No signals received" else "$pending pending · ${signals.size - pending} acknowledged",
            color = SpandanColors.ResponderAccent,
            modifier = Modifier.padding(top = SpandanSpacing.xs, bottom = SpandanSpacing.md),
        )
        if (signals.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SpandanBody("Waiting for distress signals…", color = SpandanColors.OnResponderSurface)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(SpandanSpacing.sm)) {
                items(
                    signals.sortedWith(compareBy({ it.acknowledged }, { -it.packet.severity })),
                    key = { "${it.packet.originId}-${it.packet.msgId}" },
                ) { signal -> SignalRow(signal) }
            }
        }
    }
}

@Composable
private fun SignalRow(signal: ReceivedSignal) {
    val statusColor = if (signal.acknowledged) SpandanColors.ResponderAcked else SpandanColors.ResponderPending
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SpandanColors.ResponderSurface)
            .border(SpandanShape.thinBorderWidth, statusColor)
            .padding(SpandanSpacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                "0x${signal.packet.originId.toString(16).uppercase()} · ${describeHazard(signal.packet.hazardCategory)}",
                color = SpandanColors.OnResponderSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text("severity ${signal.packet.severity}", color = SpandanColors.OnResponderSurface, fontSize = 18.sp)
            Text(
                if (signal.packet.location.validFix) {
                    "%.5f, %.5f".format(signal.packet.location.latDegrees(), signal.packet.location.lonDegrees())
                } else {
                    "no GPS fix"
                },
                color = SpandanColors.OnResponderSurface,
                fontSize = 18.sp,
            )
        }
        Text(
            if (signal.acknowledged) "ACKED" else "PENDING",
            color = statusColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

private fun describeHazard(hazard: HazardCategory): String = when (hazard) {
    HazardCategory.TRAPPED -> "Trapped"
    HazardCategory.STRANDED -> "Water rising / stranded"
    HazardCategory.MEDICAL -> "Injured"
    HazardCategory.OTHER -> "Other"
}
