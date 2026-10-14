package com.ararmedia.editor.engine

import android.content.Context
import android.graphics.*
import androidx.compose.ui.graphics.toArgb
import com.ararmedia.editor.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.*
import kotlin.random.Random

class VideoTimelineEngine(val context: Context) {

    // Canvas configuration
    var currentAspectRatio: AspectRatio = AspectRatio.TIKTOK_9_16
    var isBlurredBackgroundEnabled: Boolean = true

    /**
     * Finds the interpolated Keyframe transformation for a clip at a given timestamp.
     */
    fun interpolateKeyframe(clip: TimelineClip, currentMs: Long): Keyframe {
        if (clip.keyframes.isEmpty()) {
            return Keyframe(timestampMs = currentMs)
        }
        if (clip.keyframes.size == 1) {
            return clip.keyframes.first()
        }

        val sorted = clip.keyframes.sortedBy { it.timestampMs }
        if (currentMs <= sorted.first().timestampMs) return sorted.first()
        if (currentMs >= sorted.last().timestampMs) return sorted.last()

        for (i in 0 until sorted.size - 1) {
            val k0 = sorted[i]
            val k1 = sorted[i + 1]
            if (currentMs in k0.timestampMs..k1.timestampMs) {
                val span = (k1.timestampMs - k0.timestampMs).toFloat()
                if (span <= 0f) return k0
                val progress = ((currentMs - k0.timestampMs) / span).coerceIn(0f, 1f)
                // Smooth cosine interpolation
                val t = (1.0 - cos(progress * PI)) / 2.0

                return Keyframe(
                    timestampMs = currentMs,
                    scale = (k0.scale + (k1.scale - k0.scale) * t).toFloat(),
                    translationX = (k0.translationX + (k1.translationX - k0.translationX) * t).toFloat(),
                    translationY = (k0.translationY + (k1.translationY - k0.translationY) * t).toFloat(),
                    rotationDegrees = (k0.rotationDegrees + (k1.rotationDegrees - k0.rotationDegrees) * t).toFloat(),
                    opacity = (k0.opacity + (k1.opacity - k0.opacity) * t).toFloat()
                )
            }
        }
        return sorted.last()
    }

    /**
     * Composites a full frame for the given timeline playhead timestamp.
     * Renders Main Video -> PIP Overlay (with Chroma Key & Keyframe) -> Subtitles/Text.
     */
    suspend fun renderFrameAt(
        playheadMs: Long,
        clips: List<TimelineClip>,
        outputWidth: Int = currentAspectRatio.width,
        outputHeight: Int = currentAspectRatio.height
    ): Bitmap = withContext(Dispatchers.Default) {
        val compositeBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(compositeBitmap)

        // 1. Draw Canvas Background (Solid or Studio Dark)
        canvas.drawColor(0xFF0A0B10.toInt())

        // 2. Find active Main Video Clip
        val mainClip = clips.firstOrNull {
            it.trackType == TrackType.MAIN_VIDEO &&
                    playheadMs >= it.startOnTimelineMs &&
                    playheadMs < (it.startOnTimelineMs + it.durationMs)
        }

        var mainFrameBmp: Bitmap? = null
        if (mainClip != null) {
            mainFrameBmp = loadOrGenerateClipFrame(mainClip, playheadMs)
        }

        // Render blurred background if enabled
        if (isBlurredBackgroundEnabled && mainFrameBmp != null) {
            val bgPaint = Paint().apply {
                alpha = 110
                isFilterBitmap = true
            }
            val srcRect = Rect(0, 0, mainFrameBmp.width, mainFrameBmp.height)
            val dstRect = Rect(0, 0, outputWidth, outputHeight)
            canvas.drawBitmap(mainFrameBmp, srcRect, dstRect, bgPaint)
        }

        // 3. Render Main Video with Aspect-Fit, Color Grading & Keyframe
        if (mainClip != null && mainFrameBmp != null) {
            val kf = interpolateKeyframe(mainClip, playheadMs)
            val gradedBmp = applyColorGrading(mainFrameBmp, mainClip.colorGrading)

            // Transition effect at clip start
            val timeIntoClip = playheadMs - mainClip.startOnTimelineMs
            val transitionAlpha = if (timeIntoClip < mainClip.transitionDurationMs && mainClip.transitionIn != TransitionType.NONE) {
                applyTransitionProgress(canvas, mainClip.transitionIn, timeIntoClip, mainClip.transitionDurationMs, outputWidth, outputHeight)
            } else {
                1.0f
            }

            renderTransformedBitmap(
                canvas = canvas,
                bitmap = gradedBmp,
                keyframe = kf,
                alpha = kf.opacity * transitionAlpha,
                canvasWidth = outputWidth,
                canvasHeight = outputHeight
            )
        }

        // 4. Render PIP Overlay Track (with Chroma Key cutout)
        val pipClip = clips.firstOrNull {
            it.trackType == TrackType.PIP_OVERLAY &&
                    playheadMs >= it.startOnTimelineMs &&
                    playheadMs < (it.startOnTimelineMs + it.durationMs)
        }
        if (pipClip != null) {
            val pipBmp = loadOrGenerateClipFrame(pipClip, playheadMs)
            if (pipBmp != null) {
                val processedPip = if (pipClip.chromaKey.enabled && MediaNativeBridge.isLoaded()) {
                    val chromaOut = Bitmap.createBitmap(pipBmp.width, pipBmp.height, Bitmap.Config.ARGB_8888)
                    MediaNativeBridge.processChromaKey(
                        srcBitmap = pipBmp,
                        dstBitmap = chromaOut,
                        keyColorRgb = pipClip.chromaKey.keyColor,
                        similarity = pipClip.chromaKey.similarity,
                        smoothness = pipClip.chromaKey.smoothness,
                        spillSuppression = pipClip.chromaKey.spillSuppression
                    )
                    chromaOut
                } else {
                    pipBmp
                }

                val kf = interpolateKeyframe(pipClip, playheadMs)
                renderTransformedBitmap(
                    canvas = canvas,
                    bitmap = processedPip,
                    keyframe = kf,
                    alpha = kf.opacity,
                    canvasWidth = outputWidth,
                    canvasHeight = outputHeight
                )
            }
        }

        // 5. Render Text & Captions Track
        val textClips = clips.filter {
            it.trackType == TrackType.TEXT_STICKER &&
                    playheadMs >= it.startOnTimelineMs &&
                    playheadMs < (it.startOnTimelineMs + it.durationMs)
        }
        for (tc in textClips) {
            renderTextCaption(canvas, tc, playheadMs, outputWidth, outputHeight)
        }

        compositeBitmap
    }

    private fun loadOrGenerateClipFrame(clip: TimelineClip, playheadMs: Long): Bitmap? {
        if (clip.thumbnailBitmap != null) {
            return clip.thumbnailBitmap
        }
        if (clip.localUri.isNotEmpty()) {
            val file = File(clip.localUri)
            if (file.exists()) {
                try {
                    return BitmapFactory.decodeFile(file.absolutePath)
                } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun renderTransformedBitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        keyframe: Keyframe,
        alpha: Float,
        canvasWidth: Int,
        canvasHeight: Int
    ) {
        val matrix = Matrix()
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()

        // Center fit
        val scaleFit = min(canvasWidth / bw, canvasHeight / bh)
        val finalScale = scaleFit * keyframe.scale

        matrix.postTranslate(-bw / 2f, -bh / 2f)
        matrix.postRotate(keyframe.rotationDegrees)
        matrix.postScale(finalScale, finalScale)
        matrix.postTranslate(canvasWidth / 2f + keyframe.translationX, canvasHeight / 2f + keyframe.translationY)

        val paint = Paint().apply {
            this.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
            isFilterBitmap = true
        }

        canvas.drawBitmap(bitmap, matrix, paint)
    }

    private fun applyColorGrading(src: Bitmap, grading: ColorGrading): Bitmap {
        if (grading == ColorGrading()) return src

        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint()

        val cm = ColorMatrix()

        // Contrast and Brightness
        val scale = grading.contrast
        val translate = grading.brightness * 1.5f + (1f - scale) * 128f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(contrastMatrix)

        // Saturation
        val satMatrix = ColorMatrix().apply { setSaturation(grading.saturation) }
        cm.postConcat(satMatrix)

        // LUT Presets Color Offsets
        when (grading.lut) {
            LutPreset.TEAL_AND_ORANGE -> {
                val lutMat = ColorMatrix(
                    floatArrayOf(
                        1.2f, 0f, 0f, 0f, 15f,     // Boost Orange in Reds
                        0f, 0.95f, 0f, 0f, -5f,
                        0f, 0f, 1.25f, 0f, 25f,    // Boost Teal in Blues
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                cm.postConcat(lutMat)
            }
            LutPreset.CYBERPUNK -> {
                val lutMat = ColorMatrix(
                    floatArrayOf(
                        1.3f, 0f, 0.2f, 0f, 30f,   // Vibrant Neon Magenta
                        0f, 0.8f, 0f, 0f, -20f,
                        0.1f, 0.2f, 1.4f, 0f, 40f, // Deep Cyan/Blue
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                cm.postConcat(lutMat)
            }
            LutPreset.VINTAGE_FILM -> {
                val lutMat = ColorMatrix(
                    floatArrayOf(
                        1.05f, 0.1f, 0f, 0f, 20f,
                        0f, 1.0f, 0f, 0f, 10f,
                        0f, 0f, 0.85f, 0f, -15f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                cm.postConcat(lutMat)
            }
            LutPreset.NOIR -> {
                val noir = ColorMatrix().apply { setSaturation(0.0f) }
                cm.postConcat(noir)
            }
            LutPreset.ANIME -> {
                val anime = ColorMatrix().apply { setSaturation(1.4f) }
                cm.postConcat(anime)
            }
            LutPreset.NONE -> {}
        }

        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)

        // Vignette Overlay
        if (grading.vignette > 1f) {
            val vigPaint = Paint().apply {
                val radius = max(src.width, src.height) * 0.7f
                shader = RadialGradient(
                    src.width / 2f, src.height / 2f, radius,
                    intArrayOf(android.graphics.Color.TRANSPARENT, android.graphics.Color.BLACK),
                    floatArrayOf(0.5f, 1.0f),
                    Shader.TileMode.CLAMP
                )
                alpha = (grading.vignette * 2.55f).toInt().coerceIn(0, 255)
            }
            canvas.drawRect(0f, 0f, src.width.toFloat(), src.height.toFloat(), vigPaint)
        }

        return out
    }

    private fun applyTransitionProgress(
        canvas: Canvas,
        type: TransitionType,
        timeIntoTransition: Long,
        duration: Long,
        width: Int,
        height: Int
    ): Float {
        val progress = (timeIntoTransition.toFloat() / duration).coerceIn(0f, 1f)
        when (type) {
            TransitionType.FLASH_WHITE -> {
                if (progress < 0.5f) {
                    val flashAlpha = ((1f - (progress / 0.5f)) * 255).toInt()
                    val p = Paint().apply {
                        color = android.graphics.Color.WHITE
                        alpha = flashAlpha
                    }
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
                }
                return progress
            }
            TransitionType.CROSS_DISSOLVE -> {
                return progress
            }
            TransitionType.WHIP_PAN -> {
                val offset = (1f - progress) * width
                canvas.translate(-offset * 0.5f, 0f)
                return progress
            }
            TransitionType.CAMERA_SHAKE -> {
                if (progress < 0.8f) {
                    val shake = sin(progress * 25.0).toFloat() * 18f * (1f - progress)
                    canvas.translate(shake, shake * 0.5f)
                }
                return 1.0f
            }
            TransitionType.GLITCH -> {
                if (progress < 0.6f && (progress * 100).toInt() % 2 == 0) {
                    canvas.translate(Random.nextInt(-15, 15).toFloat(), 0f)
                }
                return progress
            }
            else -> return 1.0f
        }
    }

    private fun renderTextCaption(
        canvas: Canvas,
        clip: TimelineClip,
        playheadMs: Long,
        width: Int,
        height: Int
    ) {
        val text = clip.textContent
        if (text.isEmpty()) return

        val style = clip.textStyle
        val density = width / 360f // Approximate scale factor
        val textSizePx = style.fontSizeSp * density

        val fillPaint = Paint().apply {
            color = style.textColor.toArgb()
            textSize = textSizePx
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            setShadowLayer(style.shadowRadius * density, 0f, 3f, style.shadowColor.toArgb())
        }

        val strokePaint = Paint().apply {
            color = style.strokeColor.toArgb()
            textSize = textSizePx
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            this.style = Paint.Style.STROKE
            strokeWidth = style.strokeWidth * density
        }

        // Draw at 75% height of screen (standard subtitle position)
        val textY = height * 0.78f
        val textX = width / 2f

        canvas.drawText(text, textX, textY, strokePaint)
        canvas.drawText(text, textX, textY, fillPaint)
    }

    // --- Timeline Editing Actions ---

    fun splitClipAtPlayhead(clips: List<TimelineClip>, targetClipId: String, playheadMs: Long): List<TimelineClip> {
        val index = clips.indexOfFirst { it.id == targetClipId }
        if (index == -1) return clips
        val clip = clips[index]

        if (playheadMs <= clip.startOnTimelineMs || playheadMs >= (clip.startOnTimelineMs + clip.durationMs)) {
            return clips // Playhead is outside clip bounds
        }

        val firstDuration = playheadMs - clip.startOnTimelineMs
        val secondDuration = clip.durationMs - firstDuration

        val firstClip = clip.copy(
            id = java.util.UUID.randomUUID().toString(),
            durationMs = firstDuration,
            sourceDurationMs = (firstDuration * clip.speed).toLong()
        )

        val secondClip = clip.copy(
            id = java.util.UUID.randomUUID().toString(),
            startOnTimelineMs = playheadMs,
            durationMs = secondDuration,
            sourceOffsetMs = clip.sourceOffsetMs + (firstDuration * clip.speed).toLong(),
            sourceDurationMs = (secondDuration * clip.speed).toLong(),
            transitionIn = TransitionType.NONE
        )

        val updated = clips.toMutableList()
        updated.removeAt(index)
        updated.add(index, firstClip)
        updated.add(index + 1, secondClip)
        return updated
    }
}
