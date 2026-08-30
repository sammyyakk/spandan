package dev.spandan.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.spandan.app.ui.AppSettings
import dev.spandan.app.ui.theme.SpandanColors
import dev.spandan.app.ui.theme.SpandanSpacing

/**
 * Settings, including the developer panel's real home: seven taps on the
 * version number reveals it, exactly per brief -- this screen is for the
 * user, the dev panel behind it is for the developer.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    lowPowerSuggested: Boolean,
    dashboardUrl: String?,
    onChange: (AppSettings) -> Unit,
    onToggleDashboard: (Boolean) -> Unit,
    onOpenDevPanel: () -> Unit,
    onBack: () -> Unit,
) {
    var versionTapCount by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize().background(SpandanColors.Surface).padding(SpandanSpacing.md)) {
        Text(
            "Settings",
            color = SpandanColors.OnSurface,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = SpandanSpacing.md),
        )

        if (lowPowerSuggested && !settings.lowPowerMode) {
            Text(
                "Battery is low — consider turning on low-power mode.",
                color = SpandanColors.Hazard,
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = SpandanSpacing.md),
            )
        }

        SettingRow("Low-power mode", "Relaying for others uses more battery — this drops you to beacon-only (your own signal, no relaying) and dims the screen.", settings.lowPowerMode) {
            onChange(settings.copy(lowPowerMode = it))
        }
        SettingRow("Haptics", "Feel a distinct vibration when your signal is sent, relayed, or acknowledged.", settings.hapticsEnabled) {
            onChange(settings.copy(hapticsEnabled = it))
        }
        SettingRow("Sound", "Play a distinct tone for the same events.", settings.audioEnabled) {
            onChange(settings.copy(audioEnabled = it))
        }
        SettingRow("Responder mode", "For rescue-command devices only: shows received signals instead of the victim screen, and lets this device acknowledge them.", settings.responderMode) {
            onChange(settings.copy(responderMode = it))
        }

        if (settings.responderMode) {
            SettingRow("Laptop dashboard", "Shows a live view of received signals on a nearby laptop — open the address below in its browser.", dashboardUrl != null) {
                onToggleDashboard(it)
            }
            if (dashboardUrl != null) {
                Text(dashboardUrl, color = SpandanColors.Hazard, fontSize = 18.sp, modifier = Modifier.padding(bottom = SpandanSpacing.md))
            }
        }

        Text(
            "Back",
            color = SpandanColors.OnSurface,
            fontSize = 18.sp,
            modifier = Modifier.padding(top = SpandanSpacing.lg).clickable { onBack() },
        )

        Text(
            "Spandan v0.1",
            color = SpandanColors.OnSurface,
            fontSize = 18.sp,
            modifier = Modifier
                .padding(top = SpandanSpacing.xl)
                .clickable {
                    versionTapCount++
                    if (versionTapCount >= 7) onOpenDevPanel()
                },
        )
    }
}

@Composable
private fun SettingRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = SpandanSpacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.fillMaxWidth(0.8f)) {
            Text(title, color = SpandanColors.OnSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(description, color = SpandanColors.OnSurface, fontSize = 18.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
