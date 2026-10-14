package com.ararmedia.editor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.ararmedia.editor.model.BeatMarker
import com.ararmedia.editor.ui.theme.StudioCyanAccent
import com.ararmedia.editor.ui.theme.StudioGoldAccent
import java.util.Locale

@Composable
fun TimeRuler(
    totalDurationMs: Long,
    currentPlayheadMs: Long,
    zoomScale: Float, // Pixels per second
    scrollOffsetPx: Float,
    beatMarkers: List<BeatMarker> = emptyList(),
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .pointerInput(zoomScale, scrollOffsetPx) {
                detectDragGestures { change, _ ->
                    val clickedPx = change.position.x + scrollOffsetPx
                    val timeMs = ((clickedPx / zoomScale) * 1000L).toLong()
                    onSeek(timeMs.coerceAtLeast(0L))
                }
            }
    ) {
        val width = size.width
        val height = size.height

        // Background
        drawRect(Color(0xFF141622))

        val startVisibleMs = ((scrollOffsetPx / zoomScale) * 1000L).toLong().coerceAtLeast(0L)
        val endVisibleMs = (((scrollOffsetPx + width) / zoomScale) * 1000L).toLong()

        // 1. Draw yellow beat markers
        for (beat in beatMarkers) {
            if (beat.timestampMs in startVisibleMs..endVisibleMs) {
                val beatX = (beat.timestampMs / 1000f) * zoomScale - scrollOffsetPx
                drawLine(
                    color = StudioGoldAccent,
                    start = Offset(beatX, 0f),
                    end = Offset(beatX, 8f),
                    strokeWidth = 3f
                )
            }
        }

        // 2. Draw second and frame ticks
        val stepMs = when {
            zoomScale > 150f -> 100L  // Show every 100ms
            zoomScale > 60f -> 500L   // Show every half second
            else -> 1000L             // Show every 1s
        }

        val firstTick = (startVisibleMs / stepMs) * stepMs
        var tickMs = firstTick
        while (tickMs <= endVisibleMs) {
            val tickX = (tickMs / 1000f) * zoomScale - scrollOffsetPx
            val isMajorSecond = (tickMs % 1000L == 0L)

            val tickHeight = if (isMajorSecond) height * 0.45f else height * 0.22f
            val tickColor = if (isMajorSecond) Color.White.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.35f)

            drawLine(
                color = tickColor,
                start = Offset(tickX, height - tickHeight),
                end = Offset(tickX, height),
                strokeWidth = if (isMajorSecond) 2f else 1f
            )

            // Draw timecode label on major seconds
            if (isMajorSecond && tickX in 0f..width) {
                val sec = (tickMs / 1000L) % 60
                val min = (tickMs / 60000L)
                val label = String.format(Locale.US, "%02d:%02d", min, sec)

                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.LTGRAY
                        textSize = 24f
                        isAntiAlias = true
                    }
                    canvas.nativeCanvas.drawText(label, tickX + 4f, 26f, paint)
                }
            }

            tickMs += stepMs
        }

        // 3. Draw red playhead line
        val playheadX = (currentPlayheadMs / 1000f) * zoomScale - scrollOffsetPx
        if (playheadX in 0f..width) {
            drawLine(
                color = StudioCyanAccent,
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, height),
                strokeWidth = 3f
            )
        }
    }
}

fun formatTimecode(ms: Long): String {
    val totalSec = ms / 1000L
    val frames = ((ms % 1000L) / 33.33f).toInt()
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d:%02d:%02d", hours, minutes, seconds, frames)
}
