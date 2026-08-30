package dev.spandan.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import dev.spandan.app.ui.theme.SpandanColors
import dev.spandan.app.ui.theme.SpandanShape
import dev.spandan.app.ui.theme.SpandanSpacing
import dev.spandan.mesh.PersonalCard

/**
 * Optional personal profile, filled in whenever the user wants, never pushed
 * on them. Empty card, full function -- nothing here is required, and
 * leaving every field blank is a completely valid, supported state.
 */
@Composable
fun PersonalCardScreen(card: PersonalCard, onSave: (PersonalCard) -> Unit, onBack: () -> Unit) {
    var bloodGroup by remember { mutableStateOf(card.bloodGroup) }
    var allergies by remember { mutableStateOf(card.allergiesOrMedicalNeeds) }
    var contactName by remember { mutableStateOf(card.emergencyContactName) }
    var contactNumber by remember { mutableStateOf(card.emergencyContactNumber) }
    var peopleWithThem by remember { mutableStateOf(card.peopleWithThem.toString()) }
    var note by remember { mutableStateOf(card.note) }

    fun save() {
        onSave(
            PersonalCard(
                bloodGroup = bloodGroup,
                allergiesOrMedicalNeeds = allergies,
                emergencyContactName = contactName,
                emergencyContactNumber = contactNumber,
                peopleWithThem = peopleWithThem.toIntOrNull() ?: 0,
                note = note,
            )
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize().background(SpandanColors.Surface).padding(SpandanSpacing.md)) {
        item {
            Text(
                "My card",
                color = SpandanColors.OnSurface,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = SpandanSpacing.xs),
            )
            Text(
                "Optional. Nothing here is required — an empty card works fine. If filled, it's stored on this device only.",
                color = SpandanColors.OnSurface,
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = SpandanSpacing.md),
            )
        }
        item { CardField("Blood group", bloodGroup, { bloodGroup = it; save() }) }
        item { CardField("Allergies / critical medical needs", allergies, { allergies = it; save() }) }
        item { CardField("Emergency contact name", contactName, { contactName = it; save() }) }
        item { CardField("Emergency contact number", contactNumber, { contactNumber = it; save() }, KeyboardType.Phone) }
        item { CardField("People currently with you", peopleWithThem, { peopleWithThem = it.filter { c -> c.isDigit() }; save() }, KeyboardType.Number) }
        item { CardField("Note", note, { note = it; save() }) }
        item {
            Text(
                "Back",
                color = SpandanColors.OnSurface,
                fontSize = 18.sp,
                modifier = Modifier.padding(top = SpandanSpacing.md).clickable { onBack() },
            )
        }
    }
}

@Composable
private fun CardField(label: String, value: String, onChange: (String) -> Unit, keyboardType: KeyboardType = KeyboardType.Text) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = SpandanSpacing.md)) {
        Text(label, color = SpandanColors.OnSurface, fontSize = 18.sp, modifier = Modifier.padding(bottom = SpandanSpacing.xs))
        TextField(
            value = value,
            onValueChange = onChange,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier
                .fillMaxWidth()
                .border(SpandanShape.borderWidth, SpandanColors.OnSurface),
        )
    }
}
