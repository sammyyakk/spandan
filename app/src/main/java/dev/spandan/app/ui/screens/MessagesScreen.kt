package dev.spandan.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.spandan.app.ui.IncomingMessage
import dev.spandan.app.ui.theme.SpandanColors
import dev.spandan.app.ui.theme.SpandanShape
import dev.spandan.app.ui.theme.SpandanSpacing
import dev.spandan.mesh.CommandMessage
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Incoming reverse-channel broadcasts from rescue command. Newest first,
 * timestamped, large type, unread visually distinct. Nothing else — per
 * brief, this screen does one job.
 */
@Composable
fun MessagesScreen(messages: List<IncomingMessage>, onOpen: (Int) -> Unit) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize().background(SpandanColors.Surface).padding(SpandanSpacing.md)) {
        Text(
            "Messages from command",
            color = SpandanColors.OnSurface,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = SpandanSpacing.md),
        )
        if (messages.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No messages yet", color = SpandanColors.OnSurface, fontSize = 18.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(SpandanSpacing.sm)) {
                items(messages, key = { it.msgId }) { entry ->
                    MessageRow(entry, timeFmt) { onOpen(entry.msgId) }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(entry: IncomingMessage, timeFmt: SimpleDateFormat, onClick: () -> Unit) {
    val background = if (entry.read) SpandanColors.Surface else SpandanColors.Hazard
    val textColor = if (entry.read) SpandanColors.OnSurface else SpandanColors.OnHazard
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .border(SpandanShape.borderWidth, SpandanColors.OnSurface)
            .clickable(onClick = onClick)
            .padding(SpandanSpacing.md),
    ) {
        Column {
            Text(describeCommandMessage(entry.message), color = textColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(timeFmt.format(entry.receivedAtMillis), color = textColor, fontSize = 18.sp)
        }
    }
}

private fun describeCommandMessage(message: CommandMessage): String = when (message) {
    CommandMessage.NONE -> "—"
    CommandMessage.HELP_EN_ROUTE -> "Help is on the way"
    CommandMessage.STAY_PUT -> "Stay where you are"
    CommandMessage.MOVE_TO_HIGHER_GROUND -> "Move to higher ground"
    CommandMessage.EVACUATE_NOW -> "Evacuate now"
    CommandMessage.RESPONDER_NEARBY -> "A responder is nearby"
    CommandMessage.AREA_UNSAFE -> "This area is unsafe"
    CommandMessage.WAIT_FOR_RESCUE -> "Wait for rescue"
    CommandMessage.SIGNAL_RECEIVED_HELP_COMING -> "Your signal was received, help is coming"
    CommandMessage.FOLLOW_NEAREST_EXIT -> "Follow the nearest exit"
    CommandMessage.DO_NOT_MOVE -> "Do not move"
    CommandMessage.RESCUE_DELAYED -> "Rescue is delayed"
    CommandMessage.HELP_ARRIVING_SOON -> "Help arriving soon"
    CommandMessage.OTHER_INSTRUCTION -> "Instruction from command"
    CommandMessage.RESERVED_14, CommandMessage.RESERVED_15 -> "Instruction from command"
}
