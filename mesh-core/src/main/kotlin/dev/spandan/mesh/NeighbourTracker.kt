package dev.spandan.mesh

/** Everything a "nearby devices" UI would want to show about one neighbour. */
data class NeighbourInfo(
    val originId: Int,
    val lastSeenMillis: Long,
    val lastRssi: Int?,
    val lastMsgType: MsgType,
    val lastHazardCategory: HazardCategory,
    val lastSeverity: Int,
)

/**
 * Tracks every distinct origin ID heard recently, regardless of dedup outcome —
 * even a duplicate packet proves a neighbour is in range. This is what makes
 * "who's nearby" automatic: any two nodes that are both running hear each
 * other's broadcasts with no pairing or discovery handshake, since advertising
 * is connectionless. Feeds role election, density-adaptive duty cycling, and
 * the nearby-devices UI list.
 */
class NeighbourTracker(private val clock: Clock, private val windowMillis: Long = 60_000L) {
    private val lastSeen = HashMap<Int, NeighbourInfo>()

    fun record(packet: SpandanPacket, rssi: Int?) {
        lastSeen[packet.originId] = NeighbourInfo(
            originId = packet.originId,
            lastSeenMillis = clock.nowMillis(),
            lastRssi = rssi,
            lastMsgType = packet.msgType,
            lastHazardCategory = packet.hazardCategory,
            lastSeverity = packet.severity,
        )
    }

    fun count(): Int = snapshot().size

    /** Non-stale neighbours, most recently seen first. */
    fun snapshot(): List<NeighbourInfo> {
        val cutoff = clock.nowMillis() - windowMillis
        lastSeen.entries.removeIf { it.value.lastSeenMillis < cutoff }
        return lastSeen.values.sortedByDescending { it.lastSeenMillis }
    }
}
