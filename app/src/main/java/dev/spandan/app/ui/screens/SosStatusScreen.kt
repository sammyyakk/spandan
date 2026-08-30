package dev.spandan.app.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spandan.app.R
import dev.spandan.app.ui.Feedback
import dev.spandan.app.ui.state.SosStatus
import dev.spandan.app.ui.state.SosUiState
import dev.spandan.app.ui.theme.SpandanColors
import dev.spandan.app.ui.theme.SpandanShape
import dev.spandan.app.ui.theme.SpandanSpacing
import dev.spandan.mesh.CannedPhrase
import dev.spandan.mesh.HazardCategory
import kotlinx.coroutines.delay

/**
 * The only screen that matters: SOS before firing, Status after. No
 * navigation between the two -- firing morphs this screen in place, per brief.
 */
@Composable
fun SosStatusScreen(
    state: SosUiState,
    onFire: (HazardCategory) -> Unit,
    onCancel: () -> Unit,
    onAttachPhrase: (CannedPhrase) -> Unit,
    hapticsEnabled: Boolean = true,
    audioEnabled: Boolean = true,
) {
    var showingCategoryPicker by remember { mutableStateOf(false) }
    var showingCancelConfirm by remember { mutableStateOf(false) }
    var showingPhrasePicker by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val feedback = remember { Feedback(context) }
    feedback.hapticsEnabled = hapticsEnabled
    feedback.audioEnabled = audioEnabled
    LaunchedEffect(state.status) {
        if (state.status != SosStatus.IDLE) feedback.onStatusChanged(state.status)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SpandanColors.Surface)
    ) {
        when {
            state.status == SosStatus.IDLE && !showingCategoryPicker ->
                BigSosButton(onClick = { showingCategoryPicker = true })
            state.status == SosStatus.IDLE && showingCategoryPicker ->
                CategoryPicker(
                    onSelect = { category ->
                        onFire(category)
                        showingCategoryPicker = false
                    },
                    onBack = { showingCategoryPicker = false },
                )
            else ->
                StatusView(
                    state = state,
                    onRequestCancel = { showingCancelConfirm = true },
                    onRequestAddDetail = { showingPhrasePicker = true },
                )
        }

        if (showingCancelConfirm) {
            CancelConfirmOverlay(
                onConfirm = { showingCancelConfirm = false; onCancel() },
                onDismiss = { showingCancelConfirm = false },
            )
        }
        if (showingPhrasePicker) {
            PhrasePickerOverlay(
                onSelect = { phrase -> onAttachPhrase(phrase); showingPhrasePicker = false },
                onDismiss = { showingPhrasePicker = false },
            )
        }
    }
}

@Composable
private fun BigSosButton(onClick: () -> Unit) {
    val description = stringResource(R.string.sos_button_description)
    Box(modifier = Modifier.fillMaxSize().padding(SpandanSpacing.xl), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SpandanColors.Hazard)
                .border(SpandanShape.borderWidth, SpandanColors.OnHazard)
                .clickable(onClickLabel = description) { onClick() }
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.sos_button),
                color = SpandanColors.OnHazard,
                fontWeight = FontWeight.Black,
                fontSize = 72.sp,
            )
        }
    }
}

@Composable
private fun CategoryPicker(onSelect: (HazardCategory) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(SpandanSpacing.md)) {
        Text(
            text = stringResource(R.string.sos_pick_category),
            color = SpandanColors.OnSurface,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = SpandanSpacing.md),
        )
        val tiles = listOf(
            Triple(HazardCategory.TRAPPED, "🪨", R.string.category_trapped),
            Triple(HazardCategory.STRANDED, "🌊", R.string.category_water_rising),
            Triple(HazardCategory.MEDICAL, "🩹", R.string.category_injured),
            Triple(HazardCategory.OTHER, "❗", R.string.category_other),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SpandanSpacing.md)) {
            for (row in tiles.chunked(2)) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SpandanSpacing.md)) {
                    for ((category, emoji, labelRes) in row) {
                        val label = stringResource(labelRes)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .background(SpandanColors.Hazard)
                                .border(SpandanShape.borderWidth, SpandanColors.OnHazard)
                                .clickable(onClickLabel = label) { onSelect(category) }
                                .semantics { contentDescription = label },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(emoji, fontSize = 40.sp)
                                Text(label, color = SpandanColors.OnHazard, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
        Text(
            text = stringResource(R.string.sos_cancel_prompt),
            color = SpandanColors.OnSurface,
            fontSize = 18.sp,
            modifier = Modifier.padding(top = SpandanSpacing.md).clickable(onClickLabel = stringResource(R.string.sos_cancel_prompt)) { onBack() },
        )
    }
}

private fun statusColor(status: SosStatus): androidx.compose.ui.graphics.Color = when (status) {
    SosStatus.SENDING -> SpandanColors.Surface
    SosStatus.RELAYED -> SpandanColors.SecondaryAccent
    SosStatus.ACKNOWLEDGED -> SpandanColors.Accent
    SosStatus.STALE -> SpandanColors.Surface
    SosStatus.IDLE -> SpandanColors.Surface
}

@Composable
private fun StatusView(state: SosUiState, onRequestCancel: () -> Unit, onRequestAddDetail: () -> Unit) {
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Crossfade(targetState = state.status, animationSpec = tween(900), label = "status") { status ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(statusColor(status))
                .padding(SpandanSpacing.lg),
            verticalArrangement = Arrangement.Center,
        ) {
            val onColor = if (status == SosStatus.ACKNOWLEDGED) SpandanColors.OnHazard else SpandanColors.OnSurface
            val (titleRes, bodyRes) = when (status) {
                SosStatus.SENDING -> R.string.status_sending_title to R.string.status_sending_body
                SosStatus.RELAYED -> R.string.status_relayed_title to R.string.status_relayed_body
                SosStatus.ACKNOWLEDGED -> R.string.status_acknowledged_title to R.string.status_acknowledged_body
                SosStatus.STALE -> R.string.status_stale_title to R.string.status_stale_body
                SosStatus.IDLE -> R.string.status_sending_title to R.string.status_sending_body
            }
            Text(stringResource(titleRes), color = onColor, fontSize = 34.sp, fontWeight = FontWeight.Black)
            Text(stringResource(bodyRes), color = onColor, fontSize = 18.sp, modifier = Modifier.padding(top = SpandanSpacing.sm))

            Text(
                text = if (state.neighbourCount > 0) {
                    stringResource(R.string.status_neighbour_count, state.neighbourCount)
                } else {
                    stringResource(R.string.status_neighbour_count_zero)
                },
                color = onColor,
                fontSize = 18.sp,
                modifier = Modifier.padding(top = SpandanSpacing.md),
            )

            state.sentAtMillis?.let { sentAt ->
                val elapsedMin = ((nowMillis - sentAt) / 60_000).toInt()
                Text(
                    text = if (elapsedMin <= 0) stringResource(R.string.status_time_just_now) else stringResource(R.string.status_time_minutes_ago, elapsedMin),
                    color = onColor,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(top = SpandanSpacing.xs),
                )
            }

            if (state.phrase == CannedPhrase.NONE) {
                Text(
                    text = stringResource(R.string.status_add_detail),
                    color = onColor,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .padding(top = SpandanSpacing.lg)
                        .clickable(onClickLabel = stringResource(R.string.status_add_detail)) { onRequestAddDetail() },
                )
            } else {
                Text(state.phrase.name.replace('_', ' '), color = onColor, fontSize = 18.sp, modifier = Modifier.padding(top = SpandanSpacing.lg))
            }

            Text(
                text = stringResource(R.string.status_im_safe),
                color = onColor,
                fontSize = 18.sp,
                modifier = Modifier
                    .padding(top = SpandanSpacing.md)
                    .clickable(onClickLabel = stringResource(R.string.status_im_safe)) { onRequestCancel() },
            )
        }
    }
}

@Composable
private fun CancelConfirmOverlay(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .padding(SpandanSpacing.lg)
                .background(SpandanColors.Surface)
                .border(SpandanShape.borderWidth, SpandanColors.OnSurface)
                .padding(SpandanSpacing.lg),
        ) {
            Text(stringResource(R.string.status_im_safe_confirm_title), color = SpandanColors.OnSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.status_im_safe_confirm_body), color = SpandanColors.OnSurface, fontSize = 18.sp, modifier = Modifier.padding(top = SpandanSpacing.sm))
            Row(modifier = Modifier.padding(top = SpandanSpacing.lg), horizontalArrangement = Arrangement.spacedBy(SpandanSpacing.md)) {
                Text(
                    stringResource(R.string.status_im_safe_confirm_no),
                    color = SpandanColors.OnSurface,
                    fontSize = 18.sp,
                    modifier = Modifier.clickable { onDismiss() },
                )
                Text(
                    stringResource(R.string.status_im_safe_confirm_yes),
                    color = SpandanColors.Accent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onConfirm() },
                )
            }
        }
    }
}

@Composable
private fun PhrasePickerOverlay(onSelect: (CannedPhrase) -> Unit, onDismiss: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xCC000000))) {
        Column(modifier = Modifier.fillMaxSize().padding(SpandanSpacing.lg), verticalArrangement = Arrangement.Bottom) {
            Box(modifier = Modifier.fillMaxWidth().background(SpandanColors.Surface).border(SpandanShape.borderWidth, SpandanColors.OnSurface)) {
                Column(modifier = Modifier.padding(SpandanSpacing.md)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(SpandanSpacing.sm), contentPadding = PaddingValues(vertical = SpandanSpacing.sm)) {
                        items(CannedPhrase.entries.filter { it != CannedPhrase.NONE }) { phrase ->
                            Text(
                                text = phrase.name.replace('_', ' '),
                                color = SpandanColors.OnSurface,
                                fontSize = 18.sp,
                                modifier = Modifier
                                    .border(SpandanShape.borderWidth, SpandanColors.OnSurface)
                                    .clickable { onSelect(phrase) }
                                    .padding(SpandanSpacing.sm),
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.sos_cancel_prompt),
                        color = SpandanColors.OnSurface,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(top = SpandanSpacing.sm).clickable { onDismiss() },
                    )
                }
            }
        }
    }
}
