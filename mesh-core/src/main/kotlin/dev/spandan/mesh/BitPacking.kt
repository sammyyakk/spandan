package dev.spandan.mesh

/**
 * MSB-first bit packer over a fixed-size byte array. Values are written as
 * unsigned bit patterns; callers mask signed values to two's-complement
 * before writing and sign-extend after reading.
 */
internal class BitWriter(sizeBytes: Int) {
    val bytes = ByteArray(sizeBytes)
    private var bitPos = 0

    fun writeBits(value: Long, bitCount: Int) {
        require(bitCount in 1..64)
        val masked = value and maskFor(bitCount)
        for (i in bitCount - 1 downTo 0) {
            val bit = (masked ushr i) and 1L
            val byteIndex = bitPos / 8
            val bitInByte = 7 - (bitPos % 8)
            if (bit == 1L) {
                bytes[byteIndex] = (bytes[byteIndex].toInt() or (1 shl bitInByte)).toByte()
            }
            bitPos++
        }
    }

    companion object {
        fun maskFor(bitCount: Int): Long =
            if (bitCount == 64) -1L else (1L shl bitCount) - 1L
    }
}

internal class BitReader(private val bytes: ByteArray) {
    private var bitPos = 0

    fun readBits(bitCount: Int): Long {
        require(bitCount in 1..64)
        var result = 0L
        repeat(bitCount) {
            val byteIndex = bitPos / 8
            val bitInByte = 7 - (bitPos % 8)
            val bit = (bytes[byteIndex].toInt() ushr bitInByte) and 1
            result = (result shl 1) or bit.toLong()
            bitPos++
        }
        return result
    }

    /** Reads [bitCount] bits and sign-extends the result as a two's-complement value. */
    fun readSignedBits(bitCount: Int): Long {
        val raw = readBits(bitCount)
        val signBit = 1L shl (bitCount - 1)
        return if (raw and signBit != 0L) raw - (1L shl bitCount) else raw
    }
}

/** Masks a signed value into its [bitCount]-bit two's-complement unsigned representation. */
internal fun signedToBits(value: Long, bitCount: Int): Long =
    value and BitWriter.maskFor(bitCount)
