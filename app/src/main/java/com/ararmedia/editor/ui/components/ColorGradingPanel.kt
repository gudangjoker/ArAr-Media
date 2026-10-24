package com.ararmedia.editor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ararmedia.editor.model.ColorGrading
import com.ararmedia.editor.model.LutPreset
import com.ararmedia.editor.ui.theme.StudioCyanAccent
import com.ararmedia.editor.ui.theme.StudioGoldAccent
import com.ararmedia.editor.ui.theme.StudioSurfaceVariant
import java.util.Locale

@Composable
fun ColorGradingPanel(
    colorGrading: ColorGrading,
    onColorGradingChange: (ColorGrading) -> Unit,
    modifier: Modifier = Modifier
) {
    var brightness by remember(colorGrading) { mutableFloatStateOf(colorGrading.brightness) }
    var contrast by remember(colorGrading) { mutableFloatStateOf(colorGrading.contrast) }
    var saturation by remember(colorGrading) { mutableFloatStateOf(colorGrading.saturation) }
    var exposure by remember(colorGrading) { mutableFloatStateOf(colorGrading.exposure) }
    var highlights by remember(colorGrading) { mutableFloatStateOf(colorGrading.highlights) }
    var shadows by remember(colorGrading) { mutableFloatStateOf(colorGrading.shadows) }
    var temperature by remember(colorGrading) { mutableFloatStateOf(colorGrading.temperature) }
    var sharpness by remember(colorGrading) { mutableFloatStateOf(colorGrading.sharpness) }
    var vignette by remember(colorGrading) { mutableFloatStateOf(colorGrading.vignette) }
    var skinSmooth by remember(colorGrading) { mutableFloatStateOf(colorGrading.skinSmooth) }
    var faceRelight by remember(colorGrading) { mutableFloatStateOf(colorGrading.faceRelight) }

    var adjustPage by remember { mutableIntStateOf(0) } // 0 = Light & Color, 1 = Detail & Tone, 2 = Face Retouch

    fun update() {
        onColorGradingChange(
            colorGrading.copy(
                brightness = brightness,
                contrast = contrast,
                saturation = saturation,
                exposure = exposure,
                highlights = highlights,
                shadows = shadows,
                temperature = temperature,
                sharpness = sharpness,
                vignette = vignette,
                skinSmooth = skinSmooth,
                faceRelight = faceRelight
            )
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(StudioSurfaceVariant, RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = adjustPage == 0,
                    onClick = { adjustPage = 0 },
                    label = { Text("Filters & Light", fontSize = 10.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioCyanAccent,
                        selectedLabelColor = Color.Black
                    )
                )
                FilterChip(
                    selected = adjustPage == 1,
                    onClick = { adjustPage = 1 },
                    label = { Text("Tone & Detail", fontSize = 10.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioCyanAccent,
                        selectedLabelColor = Color.Black
                    )
                )
                FilterChip(
                    selected = adjustPage == 2,
                    onClick = { adjustPage = 2 },
                    label = { Text("Face Retouch", fontSize = 10.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioGoldAccent,
                        selectedLabelColor = Color.Black
                    )
                )
            }
            TextButton(onClick = { onColorGradingChange(ColorGrading()) }) {
                Text("Reset", fontSize = 10.sp, color = Color.LightGray)
            }
        }

        if (adjustPage != 2) {
            // LUT Preset Pills
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LutPreset.values().forEach { lut ->
                    val isSelected = colorGrading.lut == lut
                    FilterChip(
                        selected = isSelected,
                        onClick = { onColorGradingChange(colorGrading.copy(lut = lut)) },
                        label = { Text(lut.title, fontSize = 10.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StudioCyanAccent,
                            selectedLabelColor = Color.Black
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        when (adjustPage) {
            0 -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Brightness: ${brightness.toInt()}", fontSize = 10.sp, color = Color.White)
                        Slider(
                            value = brightness,
                            onValueChange = { brightness = it; update() },
                            valueRange = -50f..50f,
                            colors = SliderDefaults.colors(thumbColor = StudioCyanAccent, activeTrackColor = StudioCyanAccent)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Contrast: ${String.format(Locale.US, "%.2f", contrast)}", fontSize = 10.sp, color = Color.White)
                        Slider(
                            value = contrast,
                            onValueChange = { contrast = it; update() },
                            valueRange = 0.5f..2.0f,
                            colors = SliderDefaults.colors(thumbColor = StudioCyanAccent, activeTrackColor = StudioCyanAccent)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Saturation: ${String.format(Locale.US, "%.2f", saturation)}", fontSize = 10.sp, color = Color.White)
                        Slider(
                            value = saturation,
                            onValueChange = { saturation = it; update() },
                            valueRange = 0.0f..2.0f,
                            colors = SliderDefaults.colors(thumbColor = StudioCyanAccent, activeTrackColor = StudioCyanAccent)
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Exposure: ${String.format(Locale.US, "%+.1f", exposure)}", fontSize = 10.sp, color = Color.White)
                        Slider(
                            value = exposure,
                            onValueChange = { exposure = it; update() },
                            valueRange = -1.5f..1.5f,
                            colors = SliderDefaults.colors(thumbColor = StudioCyanAccent, activeTrackColor = StudioCyanAccent)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Temp: ${temperature.toInt()}", fontSize = 10.sp, color = Color.White)
                        Slider(
                            value = temperature,
                            onValueChange = { temperature = it; update() },
                            valueRange = -50f..50f,
                            colors = SliderDefaults.colors(thumbColor = StudioCyanAccent, activeTrackColor = StudioCyanAccent)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Vignette: ${vignette.toInt()}%", fontSize = 10.sp, color = Color.White)
                        Slider(
                            value = vignette,
                            onValueChange = { vignette = it; update() },
                            valueRange = 0f..100f,
                            colors = SliderDefaults.colors(thumbColor = StudioCyanAccent, activeTrackColor = StudioCyanAccent)
                        )
                    }
                }
            }
            1 -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Highlights: ${highlights.toInt()}", fontSize = 10.sp, color = Color.White)
                        Slider(
                            value = highlights,
                            onValueChange = { highlights = it; update() },
                            valueRange = -100f..100f,
                            colors = SliderDefaults.colors(thumbColor = StudioCyanAccent, activeTrackColor = StudioCyanAccent)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Shadows: ${shadows.toInt()}", fontSize = 10.sp, color = Color.White)
                        Slider(
                            value = shadows,
                            onValueChange = { shadows = it; update() },
                            valueRange = -100f..100f,
                            colors = SliderDefaults.colors(thumbColor = StudioCyanAccent, activeTrackColor = StudioCyanAccent)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Sharpen: ${sharpness.toInt()}%", fontSize = 10.sp, color = Color.White)
                        Slider(
                            value = sharpness,
                            onValueChange = { sharpness = it; update() },
                            valueRange = 0f..100f,
                            colors = SliderDefaults.colors(thumbColor = StudioCyanAccent, activeTrackColor = StudioCyanAccent)
                        )
                    }
                }
            }
            2 -> {
                // Neural Face Retouch Controls (Skin Smooth & Portrait Relight)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = skinSmooth == 0f && faceRelight == 0f,
                        onClick = { skinSmooth = 0f; faceRelight = 0f; update() },
                        label = { Text("Original", fontSize = 10.sp) }
                    )
                    FilterChip(
                        selected = skinSmooth == 45f && faceRelight == 20f,
                        onClick = { skinSmooth = 45f; faceRelight = 20f; update() },
                        label = { Text("Natural Smooth", fontSize = 10.sp) }
                    )
                    FilterChip(
                        selected = skinSmooth == 70f && faceRelight == 45f,
                        onClick = { skinSmooth = 70f; faceRelight = 45f; update() },
                        label = { Text("Studio Portrait", fontSize = 10.sp) }
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Skin Smooth: ${skinSmooth.toInt()}%", fontSize = 10.sp, color = Color.White)
                        Slider(
                            value = skinSmooth,
                            onValueChange = { skinSmooth = it; update() },
                            valueRange = 0f..100f,
                            colors = SliderDefaults.colors(thumbColor = StudioGoldAccent, activeTrackColor = StudioGoldAccent)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Face Relight: ${faceRelight.toInt()}%", fontSize = 10.sp, color = Color.White)
                        Slider(
                            value = faceRelight,
                            onValueChange = { faceRelight = it; update() },
                            valueRange = 0f..100f,
                            colors = SliderDefaults.colors(thumbColor = StudioGoldAccent, activeTrackColor = StudioGoldAccent)
                        )
                    }
                }
            }
        }
    }
}
