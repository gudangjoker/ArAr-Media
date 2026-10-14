package com.ararmedia.editor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ararmedia.editor.ui.theme.StudioCyanAccent
import com.ararmedia.editor.ui.theme.StudioSurfaceVariant
import java.util.Locale

@Composable
fun EqSliderPanel(
    bandGains: FloatArray,
    onGainChange: (Int, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val bandLabels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

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
                text = "10-Band Parametric Equalizer",
                style = MaterialTheme.typography.titleSmall,
                color = StudioCyanAccent
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        for (i in 0..9) onGainChange(i, 0f)
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C3048))
                ) {
                    Text("Flat (0 dB)", fontSize = 11.sp, color = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Frequency Response Curve Canvas
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(Color(0xFF0F111A), RoundedCornerShape(6.dp))
        ) {
            val w = size.width
            val h = size.height
            val midY = h / 2f

            // Center 0dB line
            drawLine(
                color = Color.White.copy(alpha = 0.15f),
                start = Offset(0f, midY),
                end = Offset(w, midY),
                strokeWidth = 1f
            )

            val path = Path()
            val stepX = w / (bandLabels.size - 1)

            for (i in bandGains.indices) {
                val x = i * stepX
                val gain = bandGains[i].coerceIn(-15f, 15f)
                val y = midY - (gain / 15f) * (h * 0.42f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                drawCircle(color = StudioCyanAccent, radius = 3.5f, center = Offset(x, y))
            }

            drawPath(path = path, color = StudioCyanAccent, style = Stroke(width = 2.5f))
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 10 Vertical Sliders in Horizontal Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            for (i in 0 until 10) {
                val gain = bandGains[i]
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(36.dp)
                ) {
                    Text(
                        text = String.format(Locale.US, "%+.1f", gain),
                        fontSize = 9.sp,
                        color = if (gain != 0f) StudioCyanAccent else Color.Gray
                    )

                    Slider(
                        value = gain,
                        onValueChange = { onGainChange(i, it) },
                        valueRange = -15f..15f,
                        modifier = Modifier
                            .height(110.dp)
                            .padding(vertical = 0.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = StudioCyanAccent,
                            activeTrackColor = StudioCyanAccent,
                            inactiveTrackColor = Color(0xFF2C3048)
                        )
                    )

                    Text(
                        text = bandLabels[i],
                        fontSize = 10.sp,
                        color = Color.LightGray
                    )
                }
            }
        }
    }
}
