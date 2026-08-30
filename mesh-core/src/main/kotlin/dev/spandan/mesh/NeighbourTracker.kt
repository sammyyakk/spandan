package dev.spandan.mesh

/**
 * Tracks distinct origin IDs heard recently, regardless of dedup outcome —
 * even a duplicate packet proves a neighbour is in range. Feeds role election
 * and density-adaptive duty cycling.
 */
class NeighbourTracker(private val clock: Clock, private val windowMillis: Long = 60_000L) {
    private val lastSeenAt = HashMap<Int, Long>()

    fun record(originId: Int) {
        lastSeenAt[originId] = clock.nowMillis()
    }

    fun count(): Int {
        val cutoff = clock.nowMillis() - windowMillis
        lastSeenAt.entries.removeIf { it.value < cutoff }
        return lastSeenAt.size
    }
}
