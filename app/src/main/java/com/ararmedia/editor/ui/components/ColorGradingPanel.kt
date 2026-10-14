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
    var vignette by remember(colorGrading) { mutableFloatStateOf(colorGrading.vignette) }

    fun update() {
        onColorGradingChange(
            colorGrading.copy(
                brightness = brightness,
                contrast = contrast,
                saturation = saturation,
                vignette = vignette
            )
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(StudioSurfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text("Pro Color Grading & HSL", style = MaterialTheme.typography.titleSmall, color = StudioCyanAccent)

        Spacer(modifier = Modifier.height(6.dp))

        // LUT Preset Pills
        Text("Cinematic 3D LUT Presets:", fontSize = 11.sp, color = Color.LightGray)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LutPreset.values().forEach { lut ->
                val isSelected = colorGrading.lut == lut
                FilterChip(
                    selected = isSelected,
                    onClick = { onColorGradingChange(colorGrading.copy(lut = lut)) },
                    label = { Text(lut.title, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioCyanAccent,
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Sliders
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Saturation: ${String.format(Locale.US, "%.2f", saturation)}", fontSize = 10.sp, color = Color.White)
                Slider(
                    value = saturation,
                    onValueChange = { saturation = it; update() },
                    valueRange = 0.0f..2.0f,
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
}
