package dev.spandan.mesh

/**
 * Optional victim-supplied profile, stored locally and never required. Never
 * crammed into the 16-byte BLE beacon — attached, when present, as a bulk
 * payload over a separate opportunistic transport (Wi-Fi Direct tier, single
 * hop only; see CLAUDE.md for why multi-hop bulk transfer isn't attempted).
 */
data class PersonalCard(
    val bloodGroup: String = "",
    val allergiesOrMedicalNeeds: String = "",
    val emergencyContactName: String = "",
    val emergencyContactNumber: String = "",
    val peopleWithThem: Int = 0,
    val note: String = "",
) {
    val isEmpty: Boolean
        get() = bloodGroup.isBlank() && allergiesOrMedicalNeeds.isBlank() &&
            emergencyContactName.isBlank() && emergencyContactNumber.isBlank() &&
            peopleWithThem == 0 && note.isBlank()

    /** Simple length-prefixed UTF-8 encoding — no external serialization dependency needed. */
    fun encode(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val fields = listOf(bloodGroup, allergiesOrMedicalNeeds, emergencyContactName, emergencyContactNumber, note)
        for (field in fields) {
            val bytes = field.toByteArray(Charsets.UTF_8)
            out.write((bytes.size shr 8) and 0xFF)
            out.write(bytes.size and 0xFF)
            out.write(bytes)
        }
        out.write((peopleWithThem shr 8) and 0xFF)
        out.write(peopleWithThem and 0xFF)
        return out.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): PersonalCard {
            val input = java.io.ByteArrayInputStream(bytes)
            fun readField(): String {
                val hi = input.read(); val lo = input.read()
                require(hi >= 0 && lo >= 0) { "truncated PersonalCard payload" }
                val len = (hi shl 8) or lo
                val buf = ByteArray(len)
                var off = 0
                while (off < len) {
                    val n = input.read(buf, off, len - off)
                    require(n >= 0) { "truncated PersonalCard payload" }
                    off += n
                }
                return String(buf, Charsets.UTF_8)
            }
            val bloodGroup = readField()
            val allergies = readField()
            val contactName = readField()
            val contactNumber = readField()
            val note = readField()
            val hi = input.read(); val lo = input.read()
            require(hi >= 0 && lo >= 0) { "truncated PersonalCard payload" }
            val people = (hi shl 8) or lo
            return PersonalCard(bloodGroup, allergies, contactName, contactNumber, people, note)
        }

        fun empty() = PersonalCard()
    }
}
