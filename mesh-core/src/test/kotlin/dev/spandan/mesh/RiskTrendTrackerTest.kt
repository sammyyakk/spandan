package dev.spandan.mesh

import dev.spandan.mesh.sim.VirtualClock
import kotlin.test.Test
import kotlin.test.assertEquals

class RiskTrendTrackerTest {
    @Test
    fun `single reading is stable, not enough history for a trend`() {
        val clock = VirtualClock()
        val tracker = RiskTrendTracker(clock)
        tracker.record(originId = 1, baroValid = true, baroDeltaDeciHpa = 0)
        assertEquals(RiskTrend.STABLE, tracker.trendFor(1))
    }

    @Test
    fun `rapidly rising pressure (falling elevation) reads as worsening`() {
        val clock = VirtualClock()
        val tracker = RiskTrendTracker(clock)
        tracker.record(originId = 1, baroValid = true, baroDeltaDeciHpa = 0)
        clock.now = 10_000
        // +3.0 hPa vs baseline now -> ~-24.9m from the first sample, well past the 2m threshold.
        tracker.record(originId = 1, baroValid = true, baroDeltaDeciHpa = 30)
        assertEquals(RiskTrend.WORSENING, tracker.trendFor(1))
    }

    @Test
    fun `rapidly falling pressure (rising elevation) reads as improving`() {
        val clock = VirtualClock()
        val tracker = RiskTrendTracker(clock)
        tracker.record(originId = 1, baroValid = true, baroDeltaDeciHpa = 0)
        clock.now = 10_000
        tracker.record(originId = 1, baroValid = true, baroDeltaDeciHpa = -30)
        assertEquals(RiskTrend.IMPROVING, tracker.trendFor(1))
    }

    @Test
    fun `small drift under the threshold stays stable`() {
        val clock = VirtualClock()
        val tracker = RiskTrendTracker(clock)
        tracker.record(originId = 1, baroValid = true, baroDeltaDeciHpa = 0)
        clock.now = 10_000
        tracker.record(originId = 1, baroValid = true, baroDeltaDeciHpa = 1) // ~0.83m, under threshold
        assertEquals(RiskTrend.STABLE, tracker.trendFor(1))
    }

    @Test
    fun `samples outside the window age out`() {
        val clock = VirtualClock()
        val tracker = RiskTrendTracker(clock)
        tracker.record(originId = 1, baroValid = true, baroDeltaDeciHpa = 0)
        clock.now = 60_000 // past the 30s window
        tracker.record(originId = 1, baroValid = true, baroDeltaDeciHpa = 30)
        // Only the second sample remains -- single sample, not enough for a trend.
        assertEquals(RiskTrend.STABLE, tracker.trendFor(1))
    }

    @Test
    fun `no fix (baroValid false) is never recorded, stays stable`() {
        val clock = VirtualClock()
        val tracker = RiskTrendTracker(clock)
        tracker.record(originId = 1, baroValid = false, baroDeltaDeciHpa = 30)
        clock.now = 10_000
        tracker.record(originId = 1, baroValid = false, baroDeltaDeciHpa = -30)
        assertEquals(RiskTrend.STABLE, tracker.trendFor(1))
    }

    @Test
    fun `unknown origin defaults to stable`() {
        val tracker = RiskTrendTracker(VirtualClock())
        assertEquals(RiskTrend.STABLE, tracker.trendFor(0xDEAD))
    }
}
