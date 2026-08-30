package dev.spandan.mesh

/**
 * Bounded store of packets a node has already seen, keyed by [SpandanPacket.dedupKey].
 * Two independent aging mechanisms, per the brief:
 *  - active expiry: an entry older than its severity's cache TTL is pruned lazily
 *    on the next cache operation (no background thread needed for a hackathon).
 *  - pressure eviction: when a new key needs room and the cache is full, evict
 *    lowest severity first, then oldest [CacheEntry.receivedAtMillis] among ties.
 */
class SeenPacketCache(
    private val capacity: Int,
    private val clock: Clock,
    private val severityConfigProvider: () -> SeverityConfig,
) {
    class CacheEntry(val packet: SpandanPacket, val receivedAtMillis: Long) {
        var relayed: Boolean = false
            internal set
    }

    private val entries = LinkedHashMap<Long, CacheEntry>()

    fun contains(key: Long): Boolean {
        pruneExpired()
        return entries.containsKey(key)
    }

    fun get(key: Long): CacheEntry? {
        pruneExpired()
        return entries[key]
    }

    /** Inserts (or overwrites) an entry, evicting under pressure if needed. Returns false if it was already present. */
    fun insert(key: Long, packet: SpandanPacket): Boolean {
        pruneExpired()
        if (entries.containsKey(key)) return false
        if (entries.size >= capacity) evictOne()
        entries[key] = CacheEntry(packet, clock.nowMillis())
        return true
    }

    fun markRelayed(key: Long) {
        entries[key]?.relayed = true
    }

    fun wasRelayed(key: Long): Boolean = entries[key]?.relayed ?: false

    val size: Int get() = entries.size

    private fun pruneExpired() {
        val config = severityConfigProvider()
        val now = clock.nowMillis()
        val expiredKeys = entries.filterValues { entry ->
            now - entry.receivedAtMillis > config.cacheTtlMs(entry.packet.severity)
        }.keys
        expiredKeys.forEach { entries.remove(it) }
    }

    private fun evictOne() {
        val config = severityConfigProvider()
        val victimKey = entries.entries.minWithOrNull(
            compareBy(
                { config.evictionWeight(it.value.packet.severity) },
                { it.value.receivedAtMillis },
            )
        )?.key ?: return
        entries.remove(victimKey)
    }
}
