package dev.spandan.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.spandan.app.ui.theme.SpandanColors
import dev.spandan.app.ui.theme.SpandanSpacing

/**
 * Max 3 screens, fully skippable, no permissions requested before they're
 * explained. Skip must land on the SOS screen, fully functional -- this
 * screen itself never blocks anything, it only decides when the permission
 * request (which AppRoot fires) is allowed to happen.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var page by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize().background(SpandanColors.Surface).padding(SpandanSpacing.lg)) {
        Text(
            "Skip",
            color = SpandanColors.OnSurface,
            fontSize = 18.sp,
            modifier = Modifier.clickable { onDone() },
        )
        Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
            when (page) {
                0 -> OnboardingPage(
                    title = "Spandan",
                    body = "When phones lose signal, they can still talk to each other. This app lets you send a distress signal that hops from phone to phone until it reaches help — no internet, no cell towers needed.",
                )
                1 -> OnboardingPage(
                    title = "Permissions",
                    body = "This app needs permission to talk to nearby phones so your signal can travel, and to check your location so rescuers know where you are. Your location is only ever used for that — never to track you.",
                )
                else -> OnboardingPage(
                    title = "Your card",
                    body = "You can optionally add your blood group, allergies, and an emergency contact any time from the SOS screen. It's stored on your phone only, and completely optional — you can skip this and use the app fully without it.",
                )
            }
        }
        PageIndicatorAndNext(page) { if (page < 2) page++ else onDone() }
    }
}

@Composable
private fun OnboardingPage(title: String, body: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = SpandanColors.Hazard, fontSize = 34.sp, fontWeight = FontWeight.Black)
        Text(
            body,
            color = SpandanColors.OnSurface,
            fontSize = 18.sp,
            modifier = Modifier.padding(top = SpandanSpacing.md),
        )
    }
}

@Composable
private fun PageIndicatorAndNext(page: Int, onNext: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(SpandanSpacing.sm)) {
            repeat(3) { i ->
                Text(if (i == page) "●" else "○", color = SpandanColors.OnSurface, fontSize = 18.sp)
            }
        }
    }
    Text(
        if (page < 2) "Next" else "Get started",
        color = SpandanColors.Hazard,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = SpandanSpacing.md)
            .clickable { onNext() },
    )
}
