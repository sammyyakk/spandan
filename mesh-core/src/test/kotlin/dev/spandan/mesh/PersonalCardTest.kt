package dev.spandan.mesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonalCardTest {
    @Test
    fun `empty card round trips and reports empty`() {
        val card = PersonalCard.empty()
        assertTrue(card.isEmpty)
        assertEquals(card, PersonalCard.decode(card.encode()))
    }

    @Test
    fun `filled card round trips`() {
        val card = PersonalCard(
            bloodGroup = "O+",
            allergiesOrMedicalNeeds = "Penicillin allergy, type 1 diabetic",
            emergencyContactName = "Asha Verma",
            emergencyContactNumber = "+91 98765 43210",
            peopleWithThem = 3,
            note = "With two children, one is 4 years old",
        )
        assertTrue(!card.isEmpty)
        assertEquals(card, PersonalCard.decode(card.encode()))
    }

    @Test
    fun `partially filled card is not empty`() {
        val card = PersonalCard(bloodGroup = "AB-")
        assertTrue(!card.isEmpty)
    }

    @Test
    fun `non-ascii text round trips`() {
        val card = PersonalCard(note = "बचाओ मदद चाहिए")
        assertEquals(card, PersonalCard.decode(card.encode()))
    }
}
