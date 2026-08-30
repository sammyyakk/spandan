package dev.spandan.app.ui.state

import dev.spandan.mesh.CannedPhrase
import dev.spandan.mesh.HazardCategory
import dev.spandan.mesh.Role

enum class SosStatus { IDLE, SENDING, RELAYED, ACKNOWLEDGED, STALE }

data class SosUiState(
    val status: SosStatus = SosStatus.IDLE,
    val category: HazardCategory? = null,
    val phrase: CannedPhrase = CannedPhrase.NONE,
    val msgId: Int? = null,
    val sentAtMillis: Long? = null,
    val neighbourCount: Int = 0,
    val role: Role = Role.RELAY,
    val batteryBucket: Int = 7,
    val bluetoothEnabled: Boolean = true,
    val permissionsGranted: Boolean = true,
)
