package dev.spandan.mesh

/**
 * Severity (0-7) modulates four things: how often a node re-sends a packet it
 * originated or is relaying, how long the packet survives cache pressure, how
 * many hops it's allowed to travel, and how eviction-worthy it is. All four are
 * driven off one table so a demo can flip [NAIVE_FLOODING] vs [WEIGHTED] with a
 * single config swap and show the behavioural difference live.
 */
class SeverityConfig(
    private val rebroadcastIntervalMs: (severity: Int) -> Long,
    private val cacheTtlMs: (severity: Int) -> Long,
    private val hopTtl: (severity: Int) -> Int,
) {
    fun rebroadcastIntervalMs(severity: Int): Long = rebroadcastIntervalMs.invoke(severity)
    fun cacheTtlMs(severity: Int): Long = cacheTtlMs.invoke(severity)
    fun hopTtl(severity: Int): Int = hopTtl.invoke(severity)

    /** Eviction weight: lower is evicted first. Directly severity — no separate curve needed. */
    fun evictionWeight(severity: Int): Int = severity

    companion object {
        /** Every severity behaves identically — the flooding baseline for comparison. */
        val NAIVE_FLOODING = SeverityConfig(
            rebroadcastIntervalMs = { 4_000L },
            cacheTtlMs = { 60_000L },
            hopTtl = { 6 },
        )

        /**
         * Severity 0 (info) barely propagates; severity 7 (critical) retransmits
         * fast, survives cache pressure longest, and travels the most hops.
         * Linear in severity — simple enough to reason about live in a demo.
         */
        val WEIGHTED = SeverityConfig(
            rebroadcastIntervalMs = { severity -> 8_000L - severity * 800L }, // 8s down to 2.4s
            cacheTtlMs = { severity -> 30_000L + severity * 30_000L },        // 30s up to 4.5min
            hopTtl = { severity -> 3 + severity },                            // 3 hops up to 10
        )
    }
}
