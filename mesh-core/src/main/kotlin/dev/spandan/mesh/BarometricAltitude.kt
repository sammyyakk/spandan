package dev.spandan.mesh

/**
 * Coarse relative-elevation estimate from a barometric pressure delta
 * against the device's own boot-time baseline (the packet's `baro_delta`
 * field). Consumer phone barometers are noisy and drift with weather, so
 * this is always presented with an explicit uncertainty band -- "here,
 * roughly this high, with this uncertainty", never a bare number claiming
 * floor-level precision.
 */
object BarometricAltitude {
    // Standard near-surface approximation: ~8.3 m of altitude per hPa of
    // pressure change (dP/dh ~= -0.12 hPa/m at sea level). Good enough to
    // distinguish "rooftop" from "basement", not survey-grade.
    private const val METERS_PER_HPA = 8.3

    // Consumer MEMS barometers: roughly 0.06-0.3 hPa of sensor noise, plus
    // weather drift over the time since the origin device's boot-time
    // baseline was set. +-1 hPa is a deliberately conservative band rather
    // than a tight one that would overstate confidence.
    private const val UNCERTAINTY_HPA = 1.0

    data class Estimate(val meters: Double, val uncertaintyMeters: Double)

    /** Negative delta (lower pressure than baseline) means higher elevation. */
    fun estimate(baroDeltaDeciHpa: Int): Estimate {
        val deltaHpa = baroDeltaDeciHpa / 10.0
        val meters = -deltaHpa * METERS_PER_HPA
        val uncertainty = UNCERTAINTY_HPA * METERS_PER_HPA
        return Estimate(meters, uncertainty)
    }
}
