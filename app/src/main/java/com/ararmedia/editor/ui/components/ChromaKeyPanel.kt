package com.ararmedia.editor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ararmedia.editor.model.ChromaKeySettings
import com.ararmedia.editor.ui.theme.StudioCyanAccent
import com.ararmedia.editor.ui.theme.StudioGreenAccent
import com.ararmedia.editor.ui.theme.StudioSurfaceVariant

@Composable
fun ChromaKeyPanel(
    chromaKey: ChromaKeySettings,
    onChromaChange: (ChromaKeySettings) -> Unit,
    onAutoSampleColor: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var modeTab by remember { mutableIntStateOf(if (chromaKey.smartCutoutEnabled) 0 else 1) }

    var smartEnabled by remember(chromaKey) { mutableStateOf(chromaKey.smartCutoutEnabled) }
    var smartSens by remember(chromaKey) { mutableFloatStateOf(chromaKey.smartSensitivity) }
    var smartFeather by remember(chromaKey) { mutableFloatStateOf(chromaKey.smartFeather) }

    var chromaEnabled by remember(chromaKey) { mutableStateOf(chromaKey.enabled) }
    var selectedColor by remember(chromaKey) { mutableIntStateOf(chromaKey.keyColor) }
    var similarity by remember(chromaKey) { mutableFloatStateOf(chromaKey.similarity) }
    var smoothness by remember(chromaKey) { mutableFloatStateOf(chromaKey.smoothness) }
    var spill by remember(chromaKey) { mutableFloatStateOf(chromaKey.spillSuppression) }

    fun update() {
        onChromaChange(
            ChromaKeySettings(
                enabled = chromaEnabled,
                keyColor = selectedColor,
                similarity = similarity,
                smoothness = smoothness,
                spillSuppression = spill,
                smartCutoutEnabled = smartEnabled,
                smartSensitivity = smartSens,
                smartFeather = smartFeather
            )
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(StudioSurfaceVariant, RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        // Mode Switcher: AI Auto Cutout vs Chroma Key
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = modeTab == 0,
                onClick = { modeTab = 0 },
                label = { Text("AI Auto Cutout", fontSize = 11.sp) },
                leadingIcon = {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(14.dp))
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = StudioCyanAccent,
                    selectedLabelColor = Color.Black,
                    selectedLeadingIconColor = Color.Black
                )
            )
            FilterChip(
                selected = modeTab == 1,
                onClick = { modeTab = 1 },
                label = { Text("Chroma Key", fontSize = 11.sp) },
                leadingIcon = {
                    Icon(Icons.Default.Colorize, contentDescription = null, modifier = Modifier.size(14.dp))
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = StudioGreenAccent,
                    selectedLabelColor = Color.Black,
                    selectedLeadingIconColor = Color.Black
                )
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (modeTab == 0) {
            // AI Smart Subject Cutout
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Remove Background Automatically", fontSize = 12.sp, color = Color.White)
                Switch(
                    checked = smartEnabled,
                    onCheckedChange = {
                        smartEnabled = it
                        update()
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = StudioCyanAccent)
                )
            }

            if (smartEnabled) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Intensity: ${(smartSens * 100).toInt()}%", fontSize = 10.sp, color = Color.LightGray)
                        Slider(
                            value = smartSens,
                            onValueChange = { smartSens = it; update() },
                            valueRange = 0.15f..0.75f,
                            colors = SliderDefaults.colors(thumbColor = StudioCyanAccent, activeTrackColor = StudioCyanAccent)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Edge Feather: ${(smartFeather * 100).toInt()}%", fontSize = 10.sp, color = Color.LightGray)
                        Slider(
                            value = smartFeather,
                            onValueChange = { smartFeather = it; update() },
                            valueRange = 0.05f..0.60f,
                            colors = SliderDefaults.colors(thumbColor = StudioCyanAccent, activeTrackColor = StudioCyanAccent)
                        )
                    }
                }
            }
        } else {
            // Chroma Key
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val colorOptions = listOf(
                        0x00FF00 to Color.Green,
                        0x0000FF to Color.Blue,
                        0xFF0000 to Color.Red,
                        0x00FFFF to Color.Cyan,
                        0xFF00FF to Color.Magenta,
                        0xFFFF00 to Color.Yellow,
                        0xFFFFFF to Color.White
                    )
                    for ((rgb, composeColor) in colorOptions) {
                        val isPicked = selectedColor == rgb
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(composeColor, CircleShape)
                                .border(
                                    width = if (isPicked) 2.dp else 1.dp,
                                    color = if (isPicked) Color.White else Color.DarkGray,
                                    shape = CircleShape
                                )
                                .clickable {
                                    selectedColor = rgb
                                    chromaEnabled = true
                                    update()
                                }
                        )
                    }

                    FilledTonalButton(
                        onClick = onAutoSampleColor,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Icon(Icons.Default.Colorize, contentDescription = "Pick", modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Auto Pick", fontSize = 10.sp)
                    }
                }

                Switch(
                    checked = chromaEnabled,
                    onCheckedChange = {
                        chromaEnabled = it
                        update()
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = StudioGreenAccent)
                )
            }

            if (chromaEnabled) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Intensity: ${(similarity * 100).toInt()}%", fontSize = 10.sp, color = Color.LightGray)
                        Slider(
                            value = similarity,
                            onValueChange = { similarity = it; update() },
                            valueRange = 0.1f..0.8f,
                            colors = SliderDefaults.colors(thumbColor = StudioGreenAccent, activeTrackColor = StudioGreenAccent)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Shadow/Edge: ${(smoothness * 100).toInt()}%", fontSize = 10.sp, color = Color.LightGray)
                        Slider(
                            value = smoothness,
                            onValueChange = { smoothness = it; update() },
                            valueRange = 0.02f..0.4f,
                            colors = SliderDefaults.colors(thumbColor = StudioGreenAccent, activeTrackColor = StudioGreenAccent)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Spill: ${(spill * 100).toInt()}%", fontSize = 10.sp, color = Color.LightGray)
                        Slider(
                            value = spill,
                            onValueChange = { spill = it; update() },
                            valueRange = 0.0f..1.0f,
                            colors = SliderDefaults.colors(thumbColor = StudioGreenAccent, activeTrackColor = StudioGreenAccent)
                        )
                    }
                }
            }
        }
    }
}
