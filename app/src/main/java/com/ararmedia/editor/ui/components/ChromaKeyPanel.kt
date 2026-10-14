package com.ararmedia.editor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ararmedia.editor.model.ChromaKeySettings
import com.ararmedia.editor.ui.theme.StudioGreenAccent
import com.ararmedia.editor.ui.theme.StudioSurfaceVariant
import java.util.Locale

@Composable
fun ChromaKeyPanel(
    chromaKey: ChromaKeySettings,
    onChromaChange: (ChromaKeySettings) -> Unit,
    modifier: Modifier = Modifier
) {
    var enabled by remember(chromaKey) { mutableStateOf(chromaKey.enabled) }
    var selectedColor by remember(chromaKey) { mutableIntStateOf(chromaKey.keyColor) }
    var similarity by remember(chromaKey) { mutableFloatStateOf(chromaKey.similarity) }
    var smoothness by remember(chromaKey) { mutableFloatStateOf(chromaKey.smoothness) }
    var spill by remember(chromaKey) { mutableFloatStateOf(chromaKey.spillSuppression) }

    fun update() {
        onChromaChange(
            ChromaKeySettings(
                enabled = enabled,
                keyColor = selectedColor,
                similarity = similarity,
                smoothness = smoothness,
                spillSuppression = spill
            )
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(StudioSurfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Chroma Key (Green-Screen Removal)", style = MaterialTheme.typography.titleSmall, color = StudioGreenAccent)
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    update()
                },
                colors = SwitchDefaults.colors(checkedThumbColor = StudioGreenAccent)
            )
        }

        if (enabled) {
            Spacer(modifier = Modifier.height(8.dp))

            // Key Color Palette
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Key Color:", fontSize = 11.sp, color = Color.LightGray)
                val colorOptions = listOf(
                    0x00FF00 to Color.Green,
                    0x0000FF to Color.Blue,
                    0xFF00FF to Color.Magenta
                )
                for ((rgb, composeColor) in colorOptions) {
                    val isPicked = selectedColor == rgb
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(composeColor, CircleShape)
                            .border(
                                width = if (isPicked) 2.5.dp else 1.dp,
                                color = if (isPicked) Color.White else Color.Gray,
                                shape = CircleShape
                            )
                            .clickable {
                                selectedColor = rgb
                                update()
                            }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Similarity & Smoothness
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Similarity: ${(similarity * 100).toInt()}%", fontSize = 10.sp, color = Color.White)
                    Slider(
                        value = similarity,
                        onValueChange = { similarity = it; update() },
                        valueRange = 0.1f..0.8f,
                        colors = SliderDefaults.colors(thumbColor = StudioGreenAccent, activeTrackColor = StudioGreenAccent)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Smoothness: ${(smoothness * 100).toInt()}%", fontSize = 10.sp, color = Color.White)
                    Slider(
                        value = smoothness,
                        onValueChange = { smoothness = it; update() },
                        valueRange = 0.02f..0.4f,
                        colors = SliderDefaults.colors(thumbColor = StudioGreenAccent, activeTrackColor = StudioGreenAccent)
                    )
                }
            }

            // Spill Suppression
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Spill Suppression: ${(spill * 100).toInt()}%", fontSize = 10.sp, color = Color.White)
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
