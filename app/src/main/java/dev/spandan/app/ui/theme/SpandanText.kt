package dev.spandan.app.ui.theme

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Consistent type hierarchy so every screen reads the same instead of each
 * one hand-picking fontSize/fontWeight. Three tiers only: Headline (screen
 * title / the one thing on the screen), Body (everything functional, never
 * below the brief's 18sp minimum), Label (secondary/muted, still 18sp --
 * the brief's minimum applies even to "small" text, there is no smaller).
 */
@Composable
fun SpandanHeadline(text: String, color: Color = SpandanColors.OnSurface, modifier: Modifier = Modifier) {
    Text(text, color = color, fontSize = 32.sp, fontWeight = FontWeight.Black, modifier = modifier)
}

@Composable
fun SpandanTitle(text: String, color: Color = SpandanColors.OnSurface, modifier: Modifier = Modifier) {
    Text(text, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = modifier)
}

@Composable
fun SpandanBody(text: String, color: Color = SpandanColors.OnSurface, modifier: Modifier = Modifier) {
    Text(text, color = color, fontSize = 18.sp, modifier = modifier)
}

@Composable
fun SpandanLabel(text: String, color: Color = SpandanColors.OnSurfaceMuted, modifier: Modifier = Modifier) {
    Text(text, color = color, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = modifier)
}
