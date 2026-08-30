package dev.spandan.mesh

/** Total wire size of a Spandan packet: 128 bits, fits in the 24-byte legacy BLE adv budget. */
const val PACKET_SIZE_BYTES = 16

enum class MsgType(val bits: Int) {
    SOS(0), RELAY_META(1), ACK(2), COMMAND_MESSAGE(3);

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

/**
 * Canned distress phrases — audio doesn't fit in a 24-byte legacy BLE
 * advertisement (nowhere close to the throughput even compressed speech
 * needs), so this is the practical substitute: a fixed vocabulary the victim
 * picks from, costing 4 bits instead of a live audio stream. 16 slots, one
 * reserved as "none selected".
 */
enum class CannedPhrase(val bits: Int) {
    NONE(0),
    NEED_WATER(1),
    NEED_MEDICAL_EVAC(2),
    BLEEDING(3),
    CANT_MOVE(4),
    TRAPPED_LIMB(5),
    STRUCTURE_UNSTABLE(6),
    FIRE_NEARBY(7),
    SMOKE_PRESENT(8),
    WATER_RISING(9),
    LOW_OXYGEN(10),
    WITH_CHILD(11),
    WITH_ELDERLY(12),
    HEAR_RESCUERS(13),
    PLEASE_HURRY(14),
    OTHER_HAZARD(15);

    companion object {
        fun fromBits(bits: Long): CannedPhrase = entries.first { it.bits.toLong() == bits }
    }
}

/**
 * Reverse-channel canned messages from rescue command back to victims —
 * same reasoning as [CannedPhrase] (no room for free text in a 16-byte
 * packet), and reuses the exact same 4-bit wire slot: a packet's `phrase`
 * bits mean [CannedPhrase] when `msg_type = SOS` and [CommandMessage] when
 * `msg_type = COMMAND_MESSAGE`. No new bits needed — the field's meaning is
 * just msg_type-dependent, the same way `origin_id`/`msg_id` already mean
 * different things on an SOS vs. an ACK.
 */
enum class CommandMessage(val bits: Int) {
    NONE(0),
    HELP_EN_ROUTE(1),
    STAY_PUT(2),
    MOVE_TO_HIGHER_GROUND(3),
    EVACUATE_NOW(4),
    RESPONDER_NEARBY(5),
    AREA_UNSAFE(6),
    WAIT_FOR_RESCUE(7),
    SIGNAL_RECEIVED_HELP_COMING(8),
    FOLLOW_NEAREST_EXIT(9),
    DO_NOT_MOVE(10),
    RESCUE_DELAYED(11),
    HELP_ARRIVING_SOON(12),
    OTHER_INSTRUCTION(13),
    RESERVED_14(14),
    RESERVED_15(15);

    companion object {
        fun fromBits(bits: Long): CommandMessage = entries.first { it.bits.toLong() == bits }
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
    val phrase: CannedPhrase = CannedPhrase.NONE,
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

    /**
     * Dedup identity: (msgType, originId, msgId). msgType must be part of the key —
     * an ACK reuses the SOS's originId/msgId on purpose (it's "for that SOS"), so
     * without msgType every node that already cached the SOS would see the ACK's
     * key as a duplicate and drop it before it could ever propagate.
     */
    fun dedupKey(): Long =
        (msgType.bits.toLong() shl 32) or (originId.toLong() shl 16) or msgId.toLong()

    /** Reinterprets the [phrase] wire bits as a [CommandMessage] — only meaningful when [msgType] is [MsgType.COMMAND_MESSAGE]. */
    val commandMessage: CommandMessage get() = CommandMessage.fromBits(phrase.bits.toLong())

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
        w.writeBits(phrase.bits.toLong(), 4)
        w.writeBits(0L, 2) // reserved
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
            val phrase = CannedPhrase.fromBits(r.readBits(4))
            r.readBits(2) // reserved, discarded

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
                phrase = phrase,
            )
        }
    }
}
