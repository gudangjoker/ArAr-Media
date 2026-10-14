package com.ararmedia.editor.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ararmedia.editor.ui.theme.StudioGoldAccent
import com.ararmedia.editor.ui.theme.StudioRedAccent
import com.ararmedia.editor.ui.theme.StudioSurfaceVariant
import java.util.Locale

@Composable
fun CompressorPanel(
    gainReductionDb: Float,
    onCompressorChange: (threshold: Float, ratio: Float, attack: Float, release: Float, makeup: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var threshold by remember { mutableFloatStateOf(-18f) }
    var ratio by remember { mutableFloatStateOf(4f) }
    var attack by remember { mutableFloatStateOf(20f) }
    var release by remember { mutableFloatStateOf(200f) }
    var makeup by remember { mutableFloatStateOf(2.5f) }

    fun update() {
        onCompressorChange(threshold, ratio, attack, release, makeup)
    }

    val animatedGr by animateFloatAsState(targetValue = gainReductionDb, label = "GRMeter")

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
            Text(
                text = "Dynamics Processor (Compressor & Limiter)",
                style = MaterialTheme.typography.titleSmall,
                color = StudioGoldAccent
            )
            Text(
                text = String.format(Locale.US, "GR: -%.1f dB", animatedGr),
                color = if (animatedGr > 0.5f) StudioRedAccent else Color.LightGray,
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Live Gain Reduction (GR) Meter Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(Color(0xFF0F111A), RoundedCornerShape(5.dp))
        ) {
            val fillFraction = (animatedGr / 20f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fillFraction)
                    .background(
                        if (animatedGr > 10f) StudioRedAccent else StudioGoldAccent,
                        RoundedCornerShape(5.dp)
                    )
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Sliders
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Threshold
            Column(modifier = Modifier.weight(1f)) {
                Text("Thresh: ${threshold.toInt()} dB", fontSize = 11.sp, color = Color.White)
                Slider(
                    value = threshold,
                    onValueChange = { threshold = it; update() },
                    valueRange = -40f..0f,
                    colors = SliderDefaults.colors(thumbColor = StudioGoldAccent, activeTrackColor = StudioGoldAccent)
                )
            }
            // Ratio
            Column(modifier = Modifier.weight(1f)) {
                Text("Ratio: ${String.format(Locale.US, "%.1f:1", ratio)}", fontSize = 11.sp, color = Color.White)
                Slider(
                    value = ratio,
                    onValueChange = { ratio = it; update() },
                    valueRange = 1f..16f,
                    colors = SliderDefaults.colors(thumbColor = StudioGoldAccent, activeTrackColor = StudioGoldAccent)
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Attack
            Column(modifier = Modifier.weight(1f)) {
                Text("Att: ${attack.toInt()} ms", fontSize = 11.sp, color = Color.White)
                Slider(
                    value = attack,
                    onValueChange = { attack = it; update() },
                    valueRange = 1f..100f,
                    colors = SliderDefaults.colors(thumbColor = StudioGoldAccent, activeTrackColor = StudioGoldAccent)
                )
            }
            // Release
            Column(modifier = Modifier.weight(1f)) {
                Text("Rel: ${release.toInt()} ms", fontSize = 11.sp, color = Color.White)
                Slider(
                    value = release,
                    onValueChange = { release = it; update() },
                    valueRange = 20f..800f,
                    colors = SliderDefaults.colors(thumbColor = StudioGoldAccent, activeTrackColor = StudioGoldAccent)
                )
            }
            // Makeup Gain
            Column(modifier = Modifier.weight(1f)) {
                Text("Gain: ${String.format(Locale.US, "+%.1f dB", makeup)}", fontSize = 11.sp, color = Color.White)
                Slider(
                    value = makeup,
                    onValueChange = { makeup = it; update() },
                    valueRange = 0f..12f,
                    colors = SliderDefaults.colors(thumbColor = StudioGoldAccent, activeTrackColor = StudioGoldAccent)
                )
            }
        }
    }
}
