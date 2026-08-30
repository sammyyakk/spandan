package dev.spandan.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens for Spandan's victim-facing UI. Neubrutalist hazard palette:
 * yellow/black is the primary register (high-visibility, dim-screen readable,
 * OLED battery-friendly); pink is reserved for state transitions and the
 * acknowledgement moment specifically, so it carries weight instead of
 * decorating everything. Never used for the developer panel, which keeps the
 * existing dense Material3 layout untouched.
 */
object SpandanColors {
    val Surface = Color(0xFF141414)      // near-black background
    val SurfaceRaised = Color(0xFF1F1F1F) // cards/rows sitting on Surface -- one step up, still flat
    val Hazard = Color(0xFFF5F04A)        // primary action, warnings
    val Accent = Color(0xFFFF1E6F)       // acknowledgement, active state transitions
    val SecondaryAccent = Color(0xFFF2635F) // sparing, tertiary distinctions only
    val OnHazard = Color(0xFF141414)     // near-black text on yellow
    val OnSurface = Color(0xFFFFFFFF)    // white text on near-black
    val OnSurfaceMuted = Color(0xFFA0A0A0) // secondary/tertiary text and inactive links only

    // Responder mode is a different job (triage, not panic) and must never
    // read as the same screen as the victim UI. Cool register instead of the
    // victim's hazard yellow/pink -- same hard-edged neubrutalist form, a
    // completely different color story.
    val ResponderSurface = Color(0xFF0B1B1E)
    val ResponderAccent = Color(0xFF35D4C7)   // cyan-teal, "command console" not "hazard"
    val ResponderPending = Color(0xFFF2A63A)  // amber: signal not yet acknowledged
    val ResponderAcked = Color(0xFF35D4C7)
    val OnResponderSurface = Color(0xFFE8FBFA)
}

object SpandanType {
    // Heavy condensed display face for the SOS action + state labels; system
    // sans-serif-condensed as a placeholder until a real font asset is chosen.
    const val DisplayFontFamily = "sans-serif-condensed"
    const val BodyFontFamily = "sans-serif"

    val DisplaySize = 40.sp   // SOS button label, state headline
    val TitleSize = 24.sp
    val BodySize = 18.sp      // minimum body size per brief — never go below this
    val LabelSize = 16.sp     // secondary/tertiary text only, never primary content
}

object SpandanSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 40.dp            // generous whitespace on victim-facing screens
}

object SpandanShape {
    // Hard edges everywhere on victim-facing screens: no rounded corners, no
    // soft shadows, no gradients. Thick borders carry the neubrutalist look.
    val borderWidth = 4.dp
    val thinBorderWidth = 2.dp // list rows / secondary containers -- still hard-edged, less shouty than a primary action
    val cornerRadius = 0.dp
}

object SpandanMotion {
    // Minimal, short, purposeful — every animation costs battery. The
    // acknowledgement transition is the one deliberate exception (see
    // AckTransition spec, built alongside the Status screen).
    const val shortMs = 120
    const val ackTransitionMs = 900
}
