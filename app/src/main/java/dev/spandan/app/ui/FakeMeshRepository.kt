package dev.spandan.app.ui

import dev.spandan.app.ui.state.SosStatus
import dev.spandan.app.ui.state.SosUiState
import dev.spandan.mesh.CannedPhrase
import dev.spandan.mesh.CommandMessage
import dev.spandan.mesh.HazardCategory
import dev.spandan.mesh.NeighbourInfo
import dev.spandan.mesh.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [MeshRepository] with no service, no BLE, no hardware — for
 * Compose previews and for rehearsing the acknowledgement transition on
 * demand before a demo. [simulateRelayed]/[simulateAcknowledged]/
 * [simulateStale] are extra, not part of the interface: a debug trigger panel
 * casts to [FakeMeshRepository] to call them.
 */
class FakeMeshRepository(initial: SosUiState = SosUiState()) : MeshRepository {
    private val _uiState = MutableStateFlow(initial)
    override val uiState: StateFlow<SosUiState> = _uiState.asStateFlow()

    private val _nearbyDevices = MutableStateFlow<List<NeighbourInfo>>(emptyList())
    override val nearbyDevices: StateFlow<List<NeighbourInfo>> = _nearbyDevices.asStateFlow()

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    override val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    private val _messages = MutableStateFlow<List<IncomingMessage>>(emptyList())
    override val messages: StateFlow<List<IncomingMessage>> = _messages.asStateFlow()

    override fun fireSos(category: HazardCategory) {
        _uiState.value = _uiState.value.copy(
            status = SosStatus.SENDING,
            category = category,
            phrase = CannedPhrase.NONE,
            msgId = (0..0xFFFF).random(),
            sentAtMillis = System.currentTimeMillis(),
        )
    }

    override fun attachPhrase(phrase: CannedPhrase) {
        _uiState.value = _uiState.value.copy(phrase = phrase)
    }

    override fun cancelSos() {
        _uiState.value = _uiState.value.copy(status = SosStatus.IDLE, category = null, msgId = null, sentAtMillis = null)
    }

    override fun setGateway(enabled: Boolean) = Unit
    override fun setWeightedPropagation(enabled: Boolean) = Unit

    override fun sendCommandMessage(message: CommandMessage) = Unit

    override fun markMessageRead(msgId: Int) {
        _messages.value = _messages.value.map { if (it.msgId == msgId) it.copy(read = true) else it }
    }

    /** Rehearsal hook: simulate an incoming command message arriving, no hardware needed. */
    fun simulateIncomingMessage(message: CommandMessage) {
        val incoming = IncomingMessage(
            msgId = (0..0xFFFF).random(),
            message = message,
            receivedAtMillis = System.currentTimeMillis(),
            read = false,
        )
        _messages.value = listOf(incoming) + _messages.value
    }

    fun simulateRelayed() {
        _uiState.value = _uiState.value.copy(status = SosStatus.RELAYED, neighbourCount = 2, role = Role.RELAY)
    }

    fun simulateAcknowledged() {
        _uiState.value = _uiState.value.copy(status = SosStatus.ACKNOWLEDGED)
    }

    fun simulateStale() {
        _uiState.value = _uiState.value.copy(status = SosStatus.STALE)
    }

    fun setNearbyDevicesForPreview(devices: List<NeighbourInfo>) {
        _nearbyDevices.value = devices
    }
}
