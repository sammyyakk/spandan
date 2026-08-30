package dev.spandan.mesh

enum class RiskTrend { IMPROVING, STABLE, WORSENING }

/**
 * Severity already answers "how dangerous is it right now" (victim-declared,
 * static per SOS). This answers "how fast is it getting worse" -- tracked
 * from successive barometric readings for the same origin. A rapidly
 * falling reading (elevation dropping fast -- e.g. a floor giving way) reads
 * as worsening; a rapidly rising one (being moved/climbing to safety) reads
 * as improving. Small drift is not a trend: only a change past a threshold
 * within a short window counts, so ordinary sensor noise (see
 * [BarometricAltitude]'s own uncertainty band) never registers as one.
 */
class RiskTrendTracker(private val clock: Clock) {
    private data class Sample(val meters: Double, val atMillis: Long)

    private val history = HashMap<Int, MutableList<Sample>>()

    // 2m of vertical change in 30s is a real, fast move for a stationary
    // distress scenario -- comfortably outside the +-8.3m uncertainty band
    // for a single reading because it's a *change across samples*, not a
    // single estimate's absolute error.
    private val worseningThresholdMeters = 2.0
    private val windowMillis = 30_000L

    fun record(originId: Int, baroValid: Boolean, baroDeltaDeciHpa: Int) {
        if (!baroValid) return
        val estimate = BarometricAltitude.estimate(baroDeltaDeciHpa)
        val list = history.getOrPut(originId) { mutableListOf() }
        list.add(Sample(estimate.meters, clock.nowMillis()))
        val cutoff = clock.nowMillis() - windowMillis
        list.removeAll { it.atMillis < cutoff }
    }

    fun trendFor(originId: Int): RiskTrend {
        val list = history[originId] ?: return RiskTrend.STABLE
        if (list.size < 2) return RiskTrend.STABLE
        val delta = list.last().meters - list.first().meters
        return when {
            delta <= -worseningThresholdMeters -> RiskTrend.WORSENING
            delta >= worseningThresholdMeters -> RiskTrend.IMPROVING
            else -> RiskTrend.STABLE
        }
    }
}
