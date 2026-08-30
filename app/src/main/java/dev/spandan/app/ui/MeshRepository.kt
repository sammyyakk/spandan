package dev.spandan.app.ui

import android.os.Handler
import android.os.Looper
import dev.spandan.app.mesh.MeshService
import dev.spandan.app.ui.state.SosStatus
import dev.spandan.app.ui.state.SosUiState
import dev.spandan.mesh.CannedPhrase
import dev.spandan.mesh.HazardCategory
import dev.spandan.mesh.MeshEvent
import dev.spandan.mesh.MsgType
import dev.spandan.mesh.NeighbourInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LogEntry(val timestampMillis: Long, val text: String)

/**
 * Everything a screen needs from the mesh, abstracted so screens are
 * previewable/rehearsable without a live [MeshService] or real hardware.
 * Unidirectional: screens read [uiState]/[nearbyDevices]/[log], never reach
 * into MeshService or MeshNode directly.
 */
interface MeshRepository {
    val uiState: StateFlow<SosUiState>
    val nearbyDevices: StateFlow<List<NeighbourInfo>>
    val log: StateFlow<List<LogEntry>>

    fun fireSos(category: HazardCategory)
    fun attachPhrase(phrase: CannedPhrase)
    fun cancelSos()
    fun setGateway(enabled: Boolean)
    fun setWeightedPropagation(enabled: Boolean)
}

/** Category -> default severity, since a panicking one-handed user is never asked to rate severity 0-7 themselves. */
private fun defaultSeverityFor(category: HazardCategory): Int = when (category) {
    HazardCategory.TRAPPED -> 7
    HazardCategory.MEDICAL -> 6
    HazardCategory.STRANDED -> 6
    HazardCategory.OTHER -> 5
}

/** Wraps a bound [MeshService], polling for role/battery/neighbour state and reacting instantly to protocol events. */
class RealMeshRepository : MeshRepository {
    private var service: MeshService? = null
    private val handler = Handler(Looper.getMainLooper())

    private val _uiState = MutableStateFlow(SosUiState())
    override val uiState: StateFlow<SosUiState> = _uiState.asStateFlow()

    private val _nearbyDevices = MutableStateFlow<List<NeighbourInfo>>(emptyList())
    override val nearbyDevices: StateFlow<List<NeighbourInfo>> = _nearbyDevices.asStateFlow()

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    override val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    // The service (not this repository) is the source of truth for whether an
    // SOS is active -- this repository is recreated whenever MainActivity is
    // (rotation, process death, a fresh screenshot/debug cycle), but the
    // service and its MeshNode are not. Every poll reconciles from
    // MeshService.snapshot() rather than only doing it once in attach(), so
    // "service killed and restarted, don't lose an active SOS" holds even if
    // the *activity* is what got recreated instead. RELAYED is a locally
    // observed upgrade (from a duplicate-drop event, see onMeshEvent) that the
    // service has no record of, so a poll is never allowed to downgrade it
    // back to SENDING.
    private val pollTick = object : Runnable {
        override fun run() {
            val s = service
            if (s != null) {
                val snap = s.snapshot()
                _nearbyDevices.value = s.nearbyDevices()
                val current = _uiState.value
                val reconciledStatus = when {
                    snap.pendingSosMsgId == null -> SosStatus.IDLE
                    snap.acknowledged -> SosStatus.ACKNOWLEDGED
                    current.msgId == snap.pendingSosMsgId && current.status == SosStatus.RELAYED -> SosStatus.RELAYED
                    else -> SosStatus.SENDING
                }
                _uiState.value = current.copy(
                    status = reconciledStatus,
                    category = snap.activeCategory,
                    phrase = snap.activePhrase,
                    msgId = snap.pendingSosMsgId,
                    sentAtMillis = snap.sentAtMillis,
                    neighbourCount = snap.neighbourCount,
                    role = snap.role,
                    batteryBucket = snap.batteryBucket,
                ).let { withStaleCheck(it) }
            }
            handler.postDelayed(this, 1_000)
        }
    }

    fun attach(meshService: MeshService) {
        service = meshService
        meshService.listener = { event -> onMeshEvent(event) }
        handler.removeCallbacks(pollTick)
        handler.post(pollTick)
    }

    fun detach() {
        service?.listener = null
        service = null
        handler.removeCallbacks(pollTick)
    }

    override fun fireSos(category: HazardCategory) {
        val s = service ?: return
        val severity = defaultSeverityFor(category)
        val msgId = s.sendSos(category, severity)
        _uiState.value = _uiState.value.copy(
            status = SosStatus.SENDING,
            category = category,
            phrase = CannedPhrase.NONE,
            msgId = msgId,
            sentAtMillis = System.currentTimeMillis(),
        )
        appendLog("SOS fired: $category")
    }

    override fun attachPhrase(phrase: CannedPhrase) {
        val msgId = _uiState.value.msgId ?: return
        service?.attachPhrase(msgId, phrase)
        _uiState.value = _uiState.value.copy(phrase = phrase)
        appendLog("Phrase attached: $phrase")
    }

    override fun cancelSos() {
        // "I'm safe now": stops repeating locally. Does not (cannot) recall
        // copies already relayed elsewhere in the mesh -- that's inherent to
        // a flood broadcast, not a bug, and the confirm dialog says so.
        service?.cancelSos()
        _uiState.value = _uiState.value.copy(status = SosStatus.IDLE, category = null, msgId = null, sentAtMillis = null)
        appendLog("Marked safe, SOS cancelled")
    }

    override fun setGateway(enabled: Boolean) {
        service?.setGateway(enabled)
    }

    override fun setWeightedPropagation(enabled: Boolean) {
        service?.setWeightedPropagation(enabled)
    }

    private fun onMeshEvent(event: MeshEvent) {
        val current = _uiState.value
        when {
            event is MeshEvent.Acknowledged && event.msgId == current.msgId -> {
                _uiState.value = current.copy(status = SosStatus.ACKNOWLEDGED)
                appendLog("Acknowledged by rescue command")
            }
            // A neighbour that already relayed our own SOS drops our later
            // resend as a duplicate -- that Dropped event is itself proof the
            // packet reached and was relayed by someone. See CLAUDE.md.
            event is MeshEvent.Dropped && event.reason == dev.spandan.mesh.DropReason.DUPLICATE &&
                event.packet.let { it != null && it.msgType == MsgType.SOS && it.msgId == current.msgId && it.hopCount > 0 } &&
                current.status == SosStatus.SENDING -> {
                _uiState.value = current.copy(status = SosStatus.RELAYED)
                appendLog("Relayed by a nearby device")
            }
            event is MeshEvent.Sent && event.packet.msgType != MsgType.RELAY_META ->
                appendLog("Sent: ${event.packet.msgType} sev=${event.packet.severity}")
            event is MeshEvent.Received && event.packet.msgType != MsgType.RELAY_META ->
                appendLog("Received: ${event.packet.msgType} from 0x${event.packet.originId.toString(16)}")
            event is MeshEvent.Relayed ->
                appendLog("Relayed onward: origin=0x${event.packet.originId.toString(16)} hop=${event.packet.hopCount}")
            event is MeshEvent.Dropped ->
                appendLog("Dropped: ${event.reason}")
            event is MeshEvent.RoleChanged ->
                appendLog("Role changed: ${event.role}")
            else -> Unit
        }
    }

    private fun withStaleCheck(state: SosUiState): SosUiState {
        val sentAt = state.sentAtMillis ?: return state
        val elapsed = System.currentTimeMillis() - sentAt
        return if (state.status == SosStatus.SENDING && elapsed > STALE_THRESHOLD_MS) {
            state.copy(status = SosStatus.STALE)
        } else {
            state
        }
    }

    private fun appendLog(text: String) {
        _log.value = (listOf(LogEntry(System.currentTimeMillis(), text)) + _log.value).take(500)
    }

    companion object {
        private const val STALE_THRESHOLD_MS = 45_000L
    }
}
