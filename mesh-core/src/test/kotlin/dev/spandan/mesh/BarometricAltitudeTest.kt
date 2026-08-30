package dev.spandan.mesh

import kotlin.test.Test
import kotlin.test.assertEquals

class BarometricAltitudeTest {
    @Test
    fun `zero delta is zero elevation`() {
        val e = BarometricAltitude.estimate(0)
        assertEquals(0.0, e.meters, 0.001)
    }

    @Test
    fun `lower pressure than baseline reads as higher elevation`() {
        val e = BarometricAltitude.estimate(-10) // -1.0 hPa
        assertEquals(8.3, e.meters, 0.001)
    }

    @Test
    fun `higher pressure than baseline reads as lower elevation`() {
        val e = BarometricAltitude.estimate(10) // +1.0 hPa
        assertEquals(-8.3, e.meters, 0.001)
    }

    @Test
    fun `uncertainty band is constant and conservative`() {
        val e1 = BarometricAltitude.estimate(0)
        val e2 = BarometricAltitude.estimate(50)
        assertEquals(e1.uncertaintyMeters, e2.uncertaintyMeters, 0.001)
        assertEquals(8.3, e1.uncertaintyMeters, 0.001)
    }
}
