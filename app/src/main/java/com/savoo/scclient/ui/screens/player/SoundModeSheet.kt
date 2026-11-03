package com.savoo.scclient.ui.screens.player

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.savoo.scclient.R
import com.savoo.scclient.player.EqBands
import com.savoo.scclient.player.PlayerController
import com.savoo.scclient.player.SoundMode
import com.savoo.scclient.player.SoundPreset
import com.savoo.scclient.ui.haptics.rememberHapticTick
import kotlin.math.roundToInt

@StringRes
private fun SoundPreset.labelRes(): Int = when (this) {
    SoundPreset.OFF -> R.string.sound_preset_off
    SoundPreset.SLOWED_REVERB -> R.string.sound_preset_slowed_reverb
    SoundPreset.SLOWED -> R.string.sound_preset_slowed
    SoundPreset.SPED_UP -> R.string.sound_preset_sped_up
    SoundPreset.NIGHTCORE -> R.string.sound_preset_nightcore
    SoundPreset.CUSTOM -> R.string.sound_preset_custom
}

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundModeSheet(
    controller: PlayerController,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SoundModeContent(controller = controller)
    }
}

@UnstableApi
@Composable
fun SoundModeContent(
    controller: PlayerController,
    modifier: Modifier = Modifier,
) {
    val haptic = rememberHapticTick()
    val state by controller.state.collectAsState()
    val mode = state.soundMode

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.player_sound),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.player_speed_format, formatSpeed(mode.rate)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.sound_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        SoundPreset.entries.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { preset ->
                    PresetTile(
                        preset = preset,
                        selected = mode.preset == preset,
                        rate = if (preset == SoundPreset.CUSTOM) mode.customRate else preset.rate,
                        onClick = {
                            haptic()
                            controller.setSoundMode(mode.copy(preset = preset))
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = mode.preset == SoundPreset.CUSTOM,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                SoundSlider(
                    label = stringResource(R.string.sound_rate),
                    valueText = stringResource(R.string.player_speed_format, formatSpeed(mode.customRate)),
                    value = mode.customRate,
                    range = SoundMode.MIN_RATE..SoundMode.MAX_RATE,
                    steps = ((SoundMode.MAX_RATE - SoundMode.MIN_RATE) / RATE_STEP).roundToInt() - 1,
                    onChange = { controller.setSoundMode(mode.copy(customRate = snap(it, RATE_STEP))) },
                    onChangeFinished = haptic,
                )
                SoundSlider(
                    label = stringResource(R.string.sound_reverb),
                    valueText = stringResource(R.string.sound_percent_format, (mode.customReverb * 100).roundToInt()),
                    value = mode.customReverb,
                    range = 0f..1f,
                    steps = 19,
                    onChange = { controller.setSoundMode(mode.copy(customReverb = snap(it, LEVEL_STEP))) },
                    onChangeFinished = haptic,
                )
                SoundSlider(
                    label = stringResource(R.string.sound_bass),
                    valueText = stringResource(R.string.sound_percent_format, (mode.customBass * 100).roundToInt()),
                    value = mode.customBass,
                    range = 0f..1f,
                    steps = 19,
                    onChange = { controller.setSoundMode(mode.copy(customBass = snap(it, LEVEL_STEP))) },
                    onChangeFinished = haptic,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        SoundSlider(
            label = stringResource(R.string.sound_noise),
            valueText = if (mode.noise <= 0f) {
                stringResource(R.string.sound_noise_off)
            } else {
                stringResource(R.string.sound_percent_format, (mode.noise * 100).roundToInt())
            },
            value = mode.noise,
            range = 0f..1f,
            steps = 19,
            onChange = { controller.setSoundMode(mode.copy(noise = snap(it, LEVEL_STEP))) },
            onChangeFinished = haptic,
        )
        Spacer(Modifier.height(4.dp))
        EqualizerSection(
            gains = mode.eq,
            onChange = { controller.setSoundMode(mode.copy(eq = it)) },
            onChangeFinished = haptic,
        )
        Spacer(Modifier.height(20.dp))
    }
}

private data class EqPresetItem(@StringRes val label: Int, val gains: List<Float>)

private val EQ_PRESETS = listOf(
    EqPresetItem(R.string.sound_eq_flat, listOf(0f, 0f, 0f, 0f, 0f)),
    EqPresetItem(R.string.sound_eq_warm, listOf(3f, 2f, 0f, -1.5f, -3f)),
    EqPresetItem(R.string.sound_eq_bass, listOf(6f, 4f, 0f, 0f, 0f)),
    EqPresetItem(R.string.sound_eq_vocal, listOf(-2f, 0f, 3f, 3f, 1f)),
    EqPresetItem(R.string.sound_eq_bright, listOf(0f, 0f, 0f, 3f, 5f)),
)

private val EQ_LABELS = listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")

@Composable
private fun EqualizerSection(
    gains: List<Float>,
    onChange: (List<Float>) -> Unit,
    onChangeFinished: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val isFlat = gains.all { it == 0f }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.sound_eq),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            if (isFlat) stringResource(R.string.sound_eq_flat) else stringResource(R.string.sound_eq_on),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
    AnimatedVisibility(
        visible = open,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EQ_PRESETS.forEach { preset ->
                    FilterChip(
                        selected = gains == preset.gains,
                        onClick = {
                            onChange(preset.gains)
                            onChangeFinished()
                        },
                        label = { Text(stringResource(preset.label)) },
                    )
                }
            }
            Column(
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                for (band in 0 until EqBands.COUNT) {
                    val value = gains.getOrElse(band) { 0f }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            EQ_LABELS[band],
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(56.dp),
                        )
                        Slider(
                            value = value,
                            onValueChange = { raw ->
                                val snapped = raw.roundToInt().toFloat()
                                if (snapped != value) {
                                    onChange(gains.toMutableList().also { it[band] = snapped })
                                }
                            },
                            onValueChangeFinished = onChangeFinished,
                            valueRange = -EqBands.MAX_DB..EqBands.MAX_DB,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            (if (value > 0f) "+" else "") + value.roundToInt() + " dB",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(52.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetTile(
    preset: SoundPreset,
    selected: Boolean,
    rate: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "soundPresetContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "soundPresetContent",
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(if (selected) 24.dp else 16.dp),
        color = container,
        contentColor = content,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            Text(
                stringResource(preset.labelRes()),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.player_speed_format, formatSpeed(rate)),
                style = MaterialTheme.typography.bodySmall,
                color = content.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun SoundSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                valueText,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val RATE_STEP = 0.05f
private const val LEVEL_STEP = 0.05f

private fun snap(value: Float, step: Float): Float = (value / step).roundToInt() * step
