package com.ararmedia.editor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.ararmedia.editor.model.WaveformSelection
import com.ararmedia.editor.ui.theme.StudioCyanAccent
import com.ararmedia.editor.ui.theme.StudioPurpleAccent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
fun WaveformCanvas(
    audioSamples: FloatArray,
    sampleRate: Int = 44100,
    currentPlayheadMs: Long,
    selection: WaveformSelection,
    onSelectionChange: (Long, Long) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val totalDurationMs = if (audioSamples.isNotEmpty()) {
        (audioSamples.size.toLong() * 1000L) / sampleRate
    } else 1000L

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(totalDurationMs) {
                detectTapGestures { offset ->
                    val clickedMs = ((offset.x / size.width) * totalDurationMs).toLong()
                    onSeek(clickedMs.coerceIn(0L, totalDurationMs))
                }
            }
            .pointerInput(totalDurationMs) {
                var startDragMs = 0L
                detectDragGestures(
                    onDragStart = { offset ->
                        startDragMs = ((offset.x / size.width) * totalDurationMs).toLong().coerceIn(0L, totalDurationMs)
                    },
                    onDrag = { change, _ ->
                        val currentDragMs = ((change.position.x / size.width) * totalDurationMs).toLong().coerceIn(0L, totalDurationMs)
                        val start = min(startDragMs, currentDragMs)
                        val end = max(startDragMs, currentDragMs)
                        onSelectionChange(start, end)
                    }
                )
            }
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f

        // Draw background grid lines
        drawLine(
            color = Color.White.copy(alpha = 0.1f),
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = 1f
        )

        if (audioSamples.isEmpty()) {
            return@Canvas
        }

        // Draw Waveform bars (downsample to number of horizontal pixels)
        val numBars = width.toInt().coerceAtLeast(10)
        val step = max(1, audioSamples.size / numBars)
        val barWidth = width / numBars

        val waveBrush = Brush.verticalGradient(
            colors = listOf(
                StudioCyanAccent,
                StudioPurpleAccent,
                StudioCyanAccent
            )
        )

        for (i in 0 until numBars) {
            val sampleIdx = (i * step).coerceIn(0, audioSamples.size - 1)
            var peak = 0f
            val endIdx = min(sampleIdx + step, audioSamples.size)
            for (s in sampleIdx until endIdx) {
                val v = abs(audioSamples[s])
                if (v > peak) peak = v
            }

            val barHeight = peak * (height * 0.45f)
            val x = i * barWidth

            drawRect(
                brush = waveBrush,
                topLeft = Offset(x, centerY - barHeight),
                size = Size(max(1f, barWidth - 1f), barHeight * 2f)
            )
        }

        // Draw Region Selection
        if (selection.isActive && selection.endMs > selection.startMs) {
            val selStartX = (selection.startMs.toFloat() / totalDurationMs) * width
            val selEndX = (selection.endMs.toFloat() / totalDurationMs) * width
            val selWidth = selEndX - selStartX

            // Selected area tint
            drawRect(
                color = StudioCyanAccent.copy(alpha = 0.22f),
                topLeft = Offset(selStartX, 0f),
                size = Size(selWidth, height)
            )

            // Start handle
            drawLine(
                color = StudioCyanAccent,
                start = Offset(selStartX, 0f),
                end = Offset(selStartX, height),
                strokeWidth = 3f
            )
            drawCircle(
                color = StudioCyanAccent,
                radius = 8f,
                center = Offset(selStartX, 16f)
            )

            // End handle
            drawLine(
                color = StudioCyanAccent,
                start = Offset(selEndX, 0f),
                end = Offset(selEndX, height),
                strokeWidth = 3f
            )
            drawCircle(
                color = StudioCyanAccent,
                radius = 8f,
                center = Offset(selEndX, height - 16f)
            )
        }

        // Draw Current Playhead Line
        val playheadX = (currentPlayheadMs.toFloat() / totalDurationMs) * width
        if (playheadX in 0f..width) {
            drawLine(
                color = Color.White,
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, height),
                strokeWidth = 3f
            )
            drawCircle(
                color = StudioCyanAccent,
                radius = 6f,
                center = Offset(playheadX, 8f)
            )
        }
    }
}
