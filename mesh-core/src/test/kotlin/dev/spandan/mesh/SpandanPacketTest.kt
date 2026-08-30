package dev.spandan.mesh

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpandanPacketTest {

    private fun samplePacket(
        msgType: MsgType = MsgType.SOS,
        protocolVersion: Int = 0,
        hazardCategory: HazardCategory = HazardCategory.TRAPPED,
        severity: Int = 7,
        originId: Int = 0x1234,
        msgId: Int = 0xABCD,
        location: QuantizedLocation = QuantizedLocation.fromDegrees(28.6139, 77.2090),
        baroValid: Boolean = true,
        baroDeltaDeciHpa: Int = -42,
        batteryBucket: Int = 3,
        livenessBucket: Int = 2,
        originTs: Int = 200,
        hopCount: Int = 5,
    ) = SpandanPacket(
        msgType, protocolVersion, hazardCategory, severity, originId, msgId,
        location, baroValid, baroDeltaDeciHpa, batteryBucket, livenessBucket,
        originTs, hopCount,
    )

    @Test
    fun `encodes to exactly 16 bytes`() {
        assertEquals(PACKET_SIZE_BYTES, samplePacket().encode().size)
    }

    @Test
    fun `round trips a typical packet`() {
        val original = samplePacket()
        val decoded = SpandanPacket.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun `round trips every msg type and hazard category`() {
        for (msgType in MsgType.entries) {
            for (hazard in HazardCategory.entries) {
                val p = samplePacket(msgType = msgType, hazardCategory = hazard)
                assertEquals(p, SpandanPacket.decode(p.encode()), "failed for $msgType/$hazard")
            }
        }
    }

    @Test
    fun `round trips boundary values for every field`() {
        val boundaryPackets = listOf(
            samplePacket(protocolVersion = 0, severity = 0, originId = 0, msgId = 0, originTs = 0, hopCount = 0),
            samplePacket(protocolVersion = 7, severity = 7, originId = 0xFFFF, msgId = 0xFFFF, originTs = 255, hopCount = 15),
            samplePacket(baroDeltaDeciHpa = -127),
            samplePacket(baroDeltaDeciHpa = 127),
            samplePacket(baroDeltaDeciHpa = 0),
            samplePacket(batteryBucket = 0, livenessBucket = 0),
            samplePacket(batteryBucket = 7, livenessBucket = 7),
            samplePacket(location = QuantizedLocation.noFix()),
            samplePacket(location = QuantizedLocation.fromDegrees(90.0, 180.0)),
            samplePacket(location = QuantizedLocation.fromDegrees(-90.0, -180.0)),
            samplePacket(location = QuantizedLocation.fromDegrees(0.0, 0.0)),
            samplePacket(baroValid = false),
        )
        for (p in boundaryPackets) {
            assertEquals(p, SpandanPacket.decode(p.encode()), "failed for $p")
        }
    }

    @Test
    fun `round trips a large random sample`() {
        val random = Random(42)
        repeat(2000) {
            val p = samplePacket(
                msgType = MsgType.entries.random(random),
                protocolVersion = random.nextInt(0, 8),
                hazardCategory = HazardCategory.entries.random(random),
                severity = random.nextInt(0, 8),
                originId = random.nextInt(0, 0x10000),
                msgId = random.nextInt(0, 0x10000),
                location = if (random.nextBoolean()) {
                    QuantizedLocation.fromDegrees(
                        random.nextDouble(-90.0, 90.0),
                        random.nextDouble(-180.0, 180.0),
                    )
                } else {
                    QuantizedLocation.noFix()
                },
                baroValid = random.nextBoolean(),
                baroDeltaDeciHpa = random.nextInt(-127, 128),
                batteryBucket = random.nextInt(0, 8),
                livenessBucket = random.nextInt(0, 8),
                originTs = random.nextInt(0, 256),
                hopCount = random.nextInt(0, 16),
            )
            assertEquals(p, SpandanPacket.decode(p.encode()), "failed for $p")
        }
    }

    @Test
    fun `dedup key combines originId and msgId to avoid msgId-only collisions`() {
        val a = samplePacket(originId = 1, msgId = 42)
        val b = samplePacket(originId = 2, msgId = 42)
        assertTrue(a.dedupKey() != b.dedupKey())
    }

    @Test
    fun `rejects out of range fields`() {
        assertFailsWith<IllegalArgumentException> { samplePacket(severity = 8) }
        assertFailsWith<IllegalArgumentException> { samplePacket(protocolVersion = 8) }
        assertFailsWith<IllegalArgumentException> { samplePacket(originId = 0x10000) }
        assertFailsWith<IllegalArgumentException> { samplePacket(hopCount = 16) }
        assertFailsWith<IllegalArgumentException> { samplePacket(baroDeltaDeciHpa = 128) }
        assertFailsWith<IllegalArgumentException> { samplePacket(baroDeltaDeciHpa = -128) }
    }

    @Test
    fun `rejects wrong-size buffers on decode`() {
        assertFailsWith<IllegalArgumentException> { SpandanPacket.decode(ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { SpandanPacket.decode(ByteArray(17)) }
    }

    @Test
    fun `quantized location round trips within resolution bounds`() {
        val loc = QuantizedLocation.fromDegrees(28.6139, 77.2090)
        assertTrue(Math.abs(loc.latDegrees() - 28.6139) < 0.001)
        assertTrue(Math.abs(loc.lonDegrees() - 77.2090) < 0.001)
    }
}
