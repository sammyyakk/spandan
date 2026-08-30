package dev.spandan.mesh

/** Total wire size of a Spandan packet: 128 bits, fits in the 24-byte legacy BLE adv budget. */
const val PACKET_SIZE_BYTES = 16

enum class MsgType(val bits: Int) {
    SOS(0), RELAY_META(1), ACK(2), RESERVED(3);

    companion object {
        fun fromBits(bits: Long): MsgType = entries.first { it.bits.toLong() == bits }
    }
}

enum class HazardCategory(val bits: Int) {
    TRAPPED(0), STRANDED(1), MEDICAL(2), OTHER(3);

    companion object {
        fun fromBits(bits: Long): HazardCategory = entries.first { it.bits.toLong() == bits }
    }
}

/** Quantized, privacy-conscious GPS fix. [validFix] false means lat/lon are meaningless. */
data class QuantizedLocation(val validFix: Boolean, val latQ: Int, val lonQ: Int) {
    companion object {
        // Latitude spans +/-90 degrees; scaling by 2^23/90 uses the full 24-bit
        // signed range for ~1.2 m resolution at the equator.
        private const val LAT_SCALE = (1 shl 23) / 90.0
        // Longitude spans +/-180 degrees; scaling by 2^23/180 gives ~2.4 m
        // resolution at the equator (worse near the poles, which is fine —
        // distress location only needs to be actionable, not survey-grade).
        private const val LON_SCALE = (1 shl 23) / 180.0
        private const val MAX_24 = (1 shl 23) - 1
        private const val MIN_24 = -(1 shl 23)

        fun fromDegrees(lat: Double, lon: Double): QuantizedLocation {
            val latQ = Math.round(lat * LAT_SCALE).toInt().coerceIn(MIN_24, MAX_24)
            val lonQ = Math.round(lon * LON_SCALE).toInt().coerceIn(MIN_24, MAX_24)
            return QuantizedLocation(validFix = true, latQ = latQ, lonQ = lonQ)
        }

        fun noFix(): QuantizedLocation = QuantizedLocation(validFix = false, latQ = 0, lonQ = 0)

        internal fun latToDegrees(latQ: Int): Double = latQ / LAT_SCALE
        internal fun lonToDegrees(lonQ: Int): Double = lonQ / LON_SCALE
    }

    fun latDegrees(): Double = latToDegrees(latQ)
    fun lonDegrees(): Double = lonToDegrees(lonQ)
}

/**
 * The 16-byte Spandan distress/ack packet. Bit layout and field rationale are
 * documented in full in CLAUDE.md — this is the single source of truth for the
 * wire format; keep both in sync when changing field widths.
 */
data class SpandanPacket(
    val msgType: MsgType,
    val protocolVersion: Int,
    val hazardCategory: HazardCategory,
    val severity: Int,
    val originId: Int,
    val msgId: Int,
    val location: QuantizedLocation,
    val baroValid: Boolean,
    val baroDeltaDeciHpa: Int,
    val batteryBucket: Int,
    val livenessBucket: Int,
    val originTs: Int,
    val hopCount: Int,
) {
    init {
        require(protocolVersion in 0..7) { "protocolVersion out of 3-bit range: $protocolVersion" }
        require(severity in 0..7) { "severity out of 3-bit range: $severity" }
        require(originId in 0..0xFFFF) { "originId out of 16-bit range: $originId" }
        require(msgId in 0..0xFFFF) { "msgId out of 16-bit range: $msgId" }
        require(baroDeltaDeciHpa in -127..127) { "baroDeltaDeciHpa out of 8-bit signed range: $baroDeltaDeciHpa" }
        require(batteryBucket in 0..7) { "batteryBucket out of 3-bit range: $batteryBucket" }
        require(livenessBucket in 0..7) { "livenessBucket out of 3-bit range: $livenessBucket" }
        require(originTs in 0..255) { "originTs out of 8-bit range: $originTs" }
        require(hopCount in 0..15) { "hopCount out of 4-bit range: $hopCount" }
    }

    /** Dedup identity: the pair actually needs to be collision-resistant, not msgId alone. */
    fun dedupKey(): Long = (originId.toLong() shl 16) or msgId.toLong()

    fun encode(): ByteArray {
        val w = BitWriter(PACKET_SIZE_BYTES)
        w.writeBits(msgType.bits.toLong(), 2)
        w.writeBits(protocolVersion.toLong(), 3)
        w.writeBits(hazardCategory.bits.toLong(), 2)
        w.writeBits(severity.toLong(), 3)
        w.writeBits(originId.toLong(), 16)
        w.writeBits(msgId.toLong(), 16)
        w.writeBits(if (location.validFix) 1L else 0L, 1)
        w.writeBits(signedToBits(location.latQ.toLong(), 24), 24)
        w.writeBits(signedToBits(location.lonQ.toLong(), 24), 24)
        w.writeBits(if (baroValid) 1L else 0L, 1)
        w.writeBits(signedToBits(baroDeltaDeciHpa.toLong(), 8), 8)
        w.writeBits(batteryBucket.toLong(), 3)
        w.writeBits(livenessBucket.toLong(), 3)
        w.writeBits(originTs.toLong(), 8)
        w.writeBits(hopCount.toLong(), 4)
        w.writeBits(0L, 6) // reserved
        return w.bytes
    }

    companion object {
        fun decode(bytes: ByteArray): SpandanPacket {
            require(bytes.size == PACKET_SIZE_BYTES) {
                "expected $PACKET_SIZE_BYTES bytes, got ${bytes.size}"
            }
            val r = BitReader(bytes)
            val msgType = MsgType.fromBits(r.readBits(2))
            val protocolVersion = r.readBits(3).toInt()
            val hazardCategory = HazardCategory.fromBits(r.readBits(2))
            val severity = r.readBits(3).toInt()
            val originId = r.readBits(16).toInt()
            val msgId = r.readBits(16).toInt()
            val gpsValid = r.readBits(1) == 1L
            val latQ = r.readSignedBits(24).toInt()
            val lonQ = r.readSignedBits(24).toInt()
            val baroValid = r.readBits(1) == 1L
            val baroDelta = r.readSignedBits(8).toInt()
            val batteryBucket = r.readBits(3).toInt()
            val livenessBucket = r.readBits(3).toInt()
            val originTs = r.readBits(8).toInt()
            val hopCount = r.readBits(4).toInt()
            r.readBits(6) // reserved, discarded

            return SpandanPacket(
                msgType = msgType,
                protocolVersion = protocolVersion,
                hazardCategory = hazardCategory,
                severity = severity,
                originId = originId,
                msgId = msgId,
                location = QuantizedLocation(gpsValid, latQ, lonQ),
                baroValid = baroValid,
                baroDeltaDeciHpa = baroDelta,
                batteryBucket = batteryBucket,
                livenessBucket = livenessBucket,
                originTs = originTs,
                hopCount = hopCount,
            )
        }
    }
}
