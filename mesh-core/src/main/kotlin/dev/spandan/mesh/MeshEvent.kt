package dev.spandan.mesh

enum class DropReason { DUPLICATE, TTL_EXCEEDED, DECODE_FAILED, ROLE_NO_RELAY }

/** Everything a UI log or a simulation harness would want to observe from a [MeshNode]. */
sealed class MeshEvent {
    data class Sent(val packet: SpandanPacket) : MeshEvent()
    data class Received(val packet: SpandanPacket) : MeshEvent()
    data class Relayed(val packet: SpandanPacket) : MeshEvent()
    data class Dropped(val reason: DropReason, val packet: SpandanPacket?) : MeshEvent()
    data class RoleChanged(val role: Role) : MeshEvent()
    data class Acknowledged(val msgId: Int) : MeshEvent()
}
