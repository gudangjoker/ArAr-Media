package com.ararmedia.editor.engine

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.toArgb
import com.ararmedia.editor.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.*

class VideoTimelineEngine(val context: Context) {

    var currentAspectRatio: AspectRatio = AspectRatio.TIKTOK_9_16
    var canvasBackgroundMode: CanvasBackgroundMode = CanvasBackgroundMode.BLUR

    // LRU Frame Cache at 30fps (33ms) granularity
    private val frameCache = object : LruCache<String, Bitmap>(48) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    // Persistent MediaMetadataRetriever Pool keyed by video file path to eliminate per-frame setDataSource() latency
    private val retrieverPool = java.util.concurrent.ConcurrentHashMap<String, MediaMetadataRetriever>()

    fun clearCache() {
        frameCache.evictAll()
        for ((_, retriever) in retrieverPool) {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
        retrieverPool.clear()
    }

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
     * Maps linear clip elapsed time through non-linear Speed Curve presets.
     */
    fun mapCurveElapsedMs(elapsedInClipMs: Long, clipDurationMs: Long, baseSpeed: Float, curve: SpeedCurvePreset): Long {
        val linearScaled = elapsedInClipMs * baseSpeed
        if (curve == SpeedCurvePreset.NORMAL || clipDurationMs <= 100L) {
            return linearScaled.toLong()
        }
        val u = (elapsedInClipMs.toDouble() / clipDurationMs.toDouble()).coerceIn(0.0, 1.0)
        val warpedU = when (curve) {
            SpeedCurvePreset.NORMAL -> u
            SpeedCurvePreset.MONTAGE -> {
                // Fast entry, slow middle highlight, fast exit
                0.5 + 0.5 * sin((u - 0.5) * PI)
            }
            SpeedCurvePreset.HERO -> {
                // Normal up to 40%, dramatic slow-mo peak, smooth recovery
                u - 0.18 * sin(u * 2.0 * PI)
            }
            SpeedCurvePreset.BULLET -> {
                // Ultra slow-motion center impact
                0.5 + 0.5 * (2.0 * u - 1.0).pow(3.0)
            }
            SpeedCurvePreset.JUMP_CUT -> {
                // Rhythmic burst acceleration
                (u + 0.08 * sin(u * 6.0 * PI)).coerceIn(0.0, 1.0)
            }
            SpeedCurvePreset.FLASH_IN -> {
                // Rapid start, smooth ease-out
                1.0 - (1.0 - u).pow(2.4)
            }
        }
        return (warpedU * clipDurationMs * baseSpeed).toLong()
    }

    /**
     * Composites a full frame for the given timeline playhead timestamp.
     * Renders Canvas Background -> Main Video -> PIP Overlays -> Subtitles/Text/Stickers.
     */
    suspend fun renderFrameAt(
        playheadMs: Long,
        clips: List<TimelineClip>,
        outputWidth: Int = currentAspectRatio.width,
        outputHeight: Int = currentAspectRatio.height
    ): Bitmap = withContext(Dispatchers.Default) {
        val compositeBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(compositeBitmap)

        // 1. Find active Main Video Clip
        val mainClip = clips.firstOrNull {
            it.trackType == TrackType.MAIN_VIDEO &&
                    playheadMs >= it.startOnTimelineMs &&
                    playheadMs < (it.startOnTimelineMs + it.durationMs)
        }

        var mainFrameBmp: Bitmap? = null
        if (mainClip != null) {
            mainFrameBmp = loadOrGenerateClipFrame(mainClip, playheadMs, outputWidth, outputHeight)
        }

        // 2. Render Canvas Background (Blur / Black / Studio Dark / Gradient)
        renderCanvasBackground(canvas, mainFrameBmp, outputWidth, outputHeight)

        // 3. Render Main Video with Cutout/ChromaKey, Stabilization, Color Grading, Animations, Transitions & Keyframe
        if (mainClip != null && mainFrameBmp != null) {
            var kf = interpolateKeyframe(mainClip, playheadMs)

            // C++ Motion Stabilization across consecutive 33ms frames
            if (mainClip.isStabilized && MediaNativeBridge.isLoaded() && playheadMs > mainClip.startOnTimelineMs + 33L) {
                val prevFrameBmp = loadOrGenerateClipFrame(mainClip, playheadMs - 33L, outputWidth, outputHeight)
                if (prevFrameBmp != null && prevFrameBmp !== mainFrameBmp &&
                    prevFrameBmp.width == mainFrameBmp.width && prevFrameBmp.height == mainFrameBmp.height
                ) {
                    val delta1 = FloatArray(2)
                    MediaNativeBridge.processStabilization(prevFrameBmp, mainFrameBmp, delta1)
                    var smoothDx = delta1[0]
                    var smoothDy = delta1[1]

                    if (playheadMs > mainClip.startOnTimelineMs + 66L) {
                        val prev2Bmp = loadOrGenerateClipFrame(mainClip, playheadMs - 66L, outputWidth, outputHeight)
                        if (prev2Bmp != null && prev2Bmp !== prevFrameBmp &&
                            prev2Bmp.width == prevFrameBmp.width && prev2Bmp.height == prevFrameBmp.height
                        ) {
                            val delta2 = FloatArray(2)
                            MediaNativeBridge.processStabilization(prev2Bmp, prevFrameBmp, delta2)
                            smoothDx = delta1[0] * 0.65f + delta2[0] * 0.35f
                            smoothDy = delta1[1] * 0.65f + delta2[1] * 0.35f
                        }
                    }

                    kf = kf.copy(
                        translationX = kf.translationX + smoothDx * 1.4f,
                        translationY = kf.translationY + smoothDy * 1.4f,
                        scale = max(kf.scale, 1.08f) // Overscan margin to hide stabilized frame edges
                    )
                }
            }

            // Apply AI Smart Cutout / Chroma Key if enabled on Main Video
            val cutoutBmp = applyCutoutAndChroma(mainFrameBmp, mainClip.chromaKey)
            val gradedBmp = applyColorGrading(cutoutBmp, mainClip.colorGrading)

            canvas.save()
            val timeIntoClip = playheadMs - mainClip.startOnTimelineMs

            // Apply Clip Animation (In / Combo)
            val animAlpha = applyClipAnimation(
                canvas = canvas,
                type = mainClip.animationType,
                timeIntoClip = timeIntoClip,
                animDuration = mainClip.animationDurationMs,
                clipDuration = mainClip.durationMs,
                width = outputWidth,
                height = outputHeight
            )

            // Apply Transition Junction
            val isTransitioning = timeIntoClip < mainClip.transitionDurationMs && mainClip.transitionIn != TransitionType.NONE
            val transitionAlpha = if (isTransitioning) {
                applyPreTransitionTransform(
                    canvas = canvas,
                    type = mainClip.transitionIn,
                    timeIntoTransition = timeIntoClip,
                    duration = mainClip.transitionDurationMs,
                    width = outputWidth,
                    height = outputHeight
                )
            } else {
                1.0f
            }

            renderTransformedBitmap(
                canvas = canvas,
                bitmap = gradedBmp,
                keyframe = kf,
                isMirrored = mainClip.isMirrored,
                alpha = kf.opacity * transitionAlpha * animAlpha,
                canvasWidth = outputWidth,
                canvasHeight = outputHeight
            )
            canvas.restore()

            if (isTransitioning) {
                applyPostTransitionOverlay(
                    canvas = canvas,
                    type = mainClip.transitionIn,
                    timeIntoTransition = timeIntoClip,
                    duration = mainClip.transitionDurationMs,
                    width = outputWidth,
                    height = outputHeight
                )
            }

            if (gradedBmp !== cutoutBmp) gradedBmp.recycle()
            if (cutoutBmp !== mainFrameBmp) cutoutBmp.recycle()
        }

        // 4. Render ALL active PIP Overlay Clips (with AI Smart Cutout, Chroma Key, Animations & Keyframes)
        val pipClips = clips.filter {
            it.trackType == TrackType.PIP_OVERLAY &&
                    playheadMs >= it.startOnTimelineMs &&
                    playheadMs < (it.startOnTimelineMs + it.durationMs)
        }
        for (pipClip in pipClips) {
            val pipBmp = loadOrGenerateClipFrame(pipClip, playheadMs, outputWidth / 2, outputHeight / 2)
            if (pipBmp != null) {
                val processedPip = applyCutoutAndChroma(pipBmp, pipClip.chromaKey)
                val gradedPip = applyColorGrading(processedPip, pipClip.colorGrading)
                val kf = interpolateKeyframe(pipClip, playheadMs)
                val timeIntoPip = playheadMs - pipClip.startOnTimelineMs

                canvas.save()
                val animAlpha = applyClipAnimation(
                    canvas = canvas,
                    type = pipClip.animationType,
                    timeIntoClip = timeIntoPip,
                    animDuration = pipClip.animationDurationMs,
                    clipDuration = pipClip.durationMs,
                    width = outputWidth,
                    height = outputHeight
                )

                renderTransformedBitmap(
                    canvas = canvas,
                    bitmap = gradedPip,
                    keyframe = kf,
                    isMirrored = pipClip.isMirrored,
                    alpha = kf.opacity * animAlpha,
                    canvasWidth = outputWidth,
                    canvasHeight = outputHeight
                )
                canvas.restore()

                if (gradedPip !== processedPip) gradedPip.recycle()
                if (processedPip !== pipBmp) processedPip.recycle()
            }
        }

        // 5. Render Text, Auto-Captions & Stickers Track
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

    private fun renderCanvasBackground(canvas: Canvas, mainFrameBmp: Bitmap?, width: Int, height: Int) {
        when (canvasBackgroundMode) {
            CanvasBackgroundMode.BLACK -> {
                canvas.drawColor(android.graphics.Color.BLACK)
            }
            CanvasBackgroundMode.STUDIO_DARK -> {
                canvas.drawColor(0xFF0A0B10.toInt())
            }
            CanvasBackgroundMode.GRADIENT -> {
                val grad = LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    intArrayOf(0xFF090D16.toInt(), 0xFF141E30.toInt(), 0xFF243B55.toInt()),
                    null, Shader.TileMode.CLAMP
                )
                val p = Paint().apply { shader = grad }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            }
            CanvasBackgroundMode.BLUR -> {
                canvas.drawColor(0xFF0A0B10.toInt())
                if (mainFrameBmp != null && !mainFrameBmp.isRecycled) {
                    val tinyW = (width / 14).coerceAtLeast(16)
                    val tinyH = (height / 14).coerceAtLeast(16)
                    val tinyBmp = Bitmap.createScaledBitmap(mainFrameBmp, tinyW, tinyH, true)
                    val blurredTiny = fastBoxBlur(tinyBmp, radius = 3)
                    val bgPaint = Paint().apply {
                        alpha = 155
                        isFilterBitmap = true
                    }
                    canvas.drawBitmap(
                        blurredTiny,
                        Rect(0, 0, blurredTiny.width, blurredTiny.height),
                        Rect(0, 0, width, height),
                        bgPaint
                    )
                    // Subtle dark scrim so foreground video pops
                    canvas.drawColor(0x55000000)
                    if (blurredTiny !== tinyBmp) blurredTiny.recycle()
                    if (tinyBmp !== mainFrameBmp) tinyBmp.recycle()
                }
            }
        }
    }

    private fun fastBoxBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        val outPixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                var count = 0
                for (dy in -radius..radius) {
                    val ny = (y + dy).coerceIn(0, h - 1)
                    for (dx in -radius..radius) {
                        val nx = (x + dx).coerceIn(0, w - 1)
                        val c = pixels[ny * w + nx]
                        rSum += (c shr 16) and 0xFF
                        gSum += (c shr 8) and 0xFF
                        bSum += c and 0xFF
                        count++
                    }
                }
                outPixels[y * w + x] = (0xFF shl 24) or ((rSum / count) shl 16) or ((gSum / count) shl 8) or (bSum / count)
            }
        }
        val blurred = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        blurred.setPixels(outPixels, 0, w, 0, 0, w, h)
        return blurred
    }

    private fun applyCutoutAndChroma(srcBmp: Bitmap, settings: ChromaKeySettings): Bitmap {
        if (!MediaNativeBridge.isLoaded()) return srcBmp
        if (!settings.smartCutoutEnabled && !settings.enabled) return srcBmp

        var current = if (srcBmp.config == Bitmap.Config.ARGB_8888) srcBmp else srcBmp.copy(Bitmap.Config.ARGB_8888, false)

        if (settings.smartCutoutEnabled) {
            val smartOut = NeuralVisionProcessor.applyNeuralCutout(
                srcBitmap = current,
                sensitivity = settings.smartSensitivity,
                feather = settings.smartFeather
            )
            if (current !== srcBmp && current !== smartOut) current.recycle()
            current = smartOut
        }

        if (settings.enabled) {
            val chromaOut = Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888)
            MediaNativeBridge.processChromaKey(
                srcBitmap = current,
                dstBitmap = chromaOut,
                keyColorRgb = settings.keyColor,
                similarity = settings.similarity,
                smoothness = settings.smoothness,
                spillSuppression = settings.spillSuppression
            )
            if (current !== srcBmp) current.recycle()
            current = chromaOut
        }

        return current
    }

    /**
     * Auto-samples the dominant border color of a clip's frame for 1-Tap Chroma Key color picking.
     */
    fun sampleDominantBorderColor(clip: TimelineClip, playheadMs: Long): Int {
        val bmp = loadOrGenerateClipFrame(clip, playheadMs, 320, 320) ?: return 0x00FF00
        val w = bmp.width
        val h = bmp.height
        if (w < 4 || h < 4) return 0x00FF00

        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0L
        val step = max(1, w / 16)
        for (x in 0 until w step step) {
            val cTop = bmp.getPixel(x, 2)
            rSum += (cTop shr 16) and 0xFF
            gSum += (cTop shr 8) and 0xFF
            bSum += cTop and 0xFF
            count++
        }
        for (y in 0 until h / 2 step step) {
            val cLeft = bmp.getPixel(2, y)
            val cRight = bmp.getPixel(w - 3, y)
            rSum += ((cLeft shr 16) and 0xFF) + ((cRight shr 16) and 0xFF)
            gSum += ((cLeft shr 8) and 0xFF) + ((cRight shr 8) and 0xFF)
            bSum += (cLeft and 0xFF) + (cRight and 0xFF)
            count += 2
        }
        if (count == 0L) return 0x00FF00
        val r = (rSum / count).toInt().coerceIn(0, 255)
        val g = (gSum / count).toInt().coerceIn(0, 255)
        val b = (bSum / count).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    private fun loadOrGenerateClipFrame(
        clip: TimelineClip,
        playheadMs: Long,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        if (clip.isFreezeFrame && clip.thumbnailBitmap != null) {
            return clip.thumbnailBitmap
        }

        val elapsedInClipMs = (playheadMs - clip.startOnTimelineMs).coerceAtLeast(0L)
        val scaledElapsedMs = mapCurveElapsedMs(elapsedInClipMs, clip.durationMs, clip.speed, clip.speedCurve)
        val effectiveLocalMs = if (clip.isReversed) {
            (clip.sourceDurationMs - scaledElapsedMs).coerceAtLeast(0L)
        } else {
            clip.sourceOffsetMs + scaledElapsedMs
        }

        // 1. Real Video File Extraction via Persistent Pooled MediaMetadataRetriever (33ms / 30fps cache granularity)
        if (clip.isVideoSource && clip.localUri.isNotEmpty()) {
            val bucketMs = (effectiveLocalMs / 33L) * 33L
            val cacheKey = "${clip.id}_vid_${bucketMs}_${targetWidth}x${targetHeight}"
            frameCache.get(cacheKey)?.let { if (!it.isRecycled) return it }

            val file = File(clip.localUri)
            if (file.exists()) {
                val pathKey = file.absolutePath
                val retriever = retrieverPool.getOrPut(pathKey) {
                    MediaMetadataRetriever().apply {
                        setDataSource(pathKey)
                    }
                }
                synchronized(retriever) {
                    try {
                        val timeUs = effectiveLocalMs * 1000L
                        val rawFrame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            retriever.getScaledFrameAtTime(
                                timeUs,
                                MediaMetadataRetriever.OPTION_CLOSEST,
                                targetWidth.coerceAtLeast(320),
                                targetHeight.coerceAtLeast(320)
                            )
                        } else {
                            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        }
                        if (rawFrame != null) {
                            val argb = if (rawFrame.config == Bitmap.Config.ARGB_8888) rawFrame else rawFrame.copy(Bitmap.Config.ARGB_8888, false)
                            frameCache.put(cacheKey, argb)
                            return argb
                        }
                    } catch (_: Exception) {
                        retrieverPool.remove(pathKey)
                        try { retriever.release() } catch (_: Exception) {}
                    }
                }
            }
        }

        // 2. Dynamic Procedural Animation for Demo Scene 2
        if (clip.name.contains("Cyber Waves", ignoreCase = true) || clip.name.contains("Neon Wave", ignoreCase = true)) {
            return renderAnimatedCyberWaves(targetWidth.coerceAtLeast(480), targetHeight.coerceAtLeast(480), effectiveLocalMs)
        }

        // 3. Static Image / Thumbnail fallback
        if (clip.thumbnailBitmap != null && !clip.thumbnailBitmap.isRecycled) {
            return clip.thumbnailBitmap
        }

        if (clip.localUri.isNotEmpty()) {
            val cacheKey = "${clip.localUri}_img"
            frameCache.get(cacheKey)?.let { if (!it.isRecycled) return it }
            val file = File(clip.localUri)
            if (file.exists()) {
                try {
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val decoded = BitmapFactory.decodeFile(file.absolutePath, opts)
                    if (decoded != null) {
                        frameCache.put(cacheKey, decoded)
                        return decoded
                    }
                } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun renderAnimatedCyberWaves(width: Int, height: Int, timeMs: Long): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF0B0D18.toInt())

        val phase = (timeMs / 1000.0) * 4.5
        val path = Path()
        val numPoints = 64
        val centerY = height / 2f

        for (w in 0..2) {
            path.reset()
            path.moveTo(0f, centerY)
            for (i in 0..numPoints) {
                val x = (i.toFloat() / numPoints) * width
                val amp = (height * 0.08f) + w * (height * 0.03f)
                val y = centerY + sin(i * 0.28 + phase + w * 1.4).toFloat() * amp * sin(i * PI / numPoints).toFloat()
                path.lineTo(x, y)
            }

            val strokePaint = Paint().apply {
                color = when (w) {
                    0 -> 0xFF00E5FF.toInt()
                    1 -> 0xFF7C4DFF.toInt()
                    else -> 0xFFFFD600.toInt()
                }
                style = Paint.Style.STROKE
                strokeWidth = max(3f, width * 0.006f)
                isAntiAlias = true
            }
            canvas.drawPath(path, strokePaint)
        }
        return bitmap
    }

    private fun renderTransformedBitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        keyframe: Keyframe,
        isMirrored: Boolean,
        alpha: Float,
        canvasWidth: Int,
        canvasHeight: Int
    ) {
        val matrix = Matrix()
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()

        val coordScaleX = canvasWidth / 1080f
        val coordScaleY = canvasHeight / 1920f

        val scaleFit = min(canvasWidth / bw, canvasHeight / bh)
        val finalScale = scaleFit * keyframe.scale
        val scaleX = if (isMirrored) -finalScale else finalScale

        matrix.postTranslate(-bw / 2f, -bh / 2f)
        matrix.postScale(scaleX, finalScale)
        matrix.postRotate(keyframe.rotationDegrees)
        matrix.postTranslate(
            canvasWidth / 2f + keyframe.translationX * coordScaleX,
            canvasHeight / 2f + keyframe.translationY * coordScaleY
        )

        val paint = Paint().apply {
            this.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
            isFilterBitmap = true
        }

        canvas.drawBitmap(bitmap, matrix, paint)
    }

    private fun applyClipAnimation(
        canvas: Canvas,
        type: ClipAnimationType,
        timeIntoClip: Long,
        animDuration: Long,
        clipDuration: Long,
        width: Int,
        height: Int
    ): Float {
        if (type == ClipAnimationType.NONE) return 1.0f
        val dur = max(150L, animDuration)
        val p = (timeIntoClip.toFloat() / dur).coerceIn(0f, 1f)
        val easeOut = 1f - (1f - p).pow(3)

        return when (type) {
            ClipAnimationType.NONE -> 1.0f
            ClipAnimationType.FADE_IN -> easeOut
            ClipAnimationType.ZOOM_IN -> {
                if (p < 1f) {
                    val s = 0.35f + 0.65f * easeOut
                    canvas.scale(s, s, width / 2f, height / 2f)
                }
                easeOut
            }
            ClipAnimationType.SLIDE_UP -> {
                if (p < 1f) {
                    val dy = (1f - easeOut) * (height * 0.35f)
                    canvas.translate(0f, dy)
                }
                easeOut
            }
            ClipAnimationType.SLIDE_LEFT -> {
                if (p < 1f) {
                    val dx = (1f - easeOut) * (width * 0.45f)
                    canvas.translate(dx, 0f)
                }
                easeOut
            }
            ClipAnimationType.SPIN_IN -> {
                if (p < 1f) {
                    val deg = (1f - easeOut) * -180f
                    val s = 0.4f + 0.6f * easeOut
                    canvas.rotate(deg, width / 2f, height / 2f)
                    canvas.scale(s, s, width / 2f, height / 2f)
                }
                easeOut
            }
            ClipAnimationType.BOUNCE -> {
                if (p < 1f) {
                    val bounce = 1f - cos(p * 2.5 * PI).toFloat() * (1f - p) * 0.35f
                    canvas.scale(bounce, bounce, width / 2f, height / 2f)
                }
                1.0f
            }
            ClipAnimationType.PENDULUM -> {
                val tSec = timeIntoClip / 1000.0
                val swing = sin(tSec * 2.2 * PI).toFloat() * 7.5f
                canvas.rotate(swing, width / 2f, height / 2f)
                1.0f
            }
            ClipAnimationType.PULSE -> {
                val tSec = timeIntoClip / 1000.0
                val pulse = 1.0f + 0.06f * abs(sin(tSec * 2.0 * PI)).toFloat()
                canvas.scale(pulse, pulse, width / 2f, height / 2f)
                1.0f
            }
        }
    }

    private fun applyColorGrading(src: Bitmap, grading: ColorGrading): Bitmap {
        if (grading == ColorGrading()) return src

        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint()

        val cm = ColorMatrix()

        // Exposure + Brightness + Contrast + Highlights + Shadows + Sharpness micro-contrast
        val expFactor = 2.0f.pow(grading.exposure)
        val shadowLift = grading.shadows * 0.55f
        val highlightGain = 1.0f + (grading.highlights / 250f)
        val sharpContrast = 1.0f + (grading.sharpness / 320f)

        val scale = grading.contrast * expFactor * highlightGain * sharpContrast
        val translate = grading.brightness * 1.8f + shadowLift + (1f - grading.contrast * sharpContrast) * 128f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(contrastMatrix)

        // Color Temperature (Warm / Cool shift on R and B channels)
        if (abs(grading.temperature) > 0.5f) {
            val temp = grading.temperature
            val tempMatrix = ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, temp * 1.2f,
                    0f, 1f, 0f, 0f, temp * 0.2f,
                    0f, 0f, 1f, 0f, -temp * 1.2f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(tempMatrix)
        }

        // Saturation
        val satMatrix = ColorMatrix().apply { setSaturation(grading.saturation) }
        cm.postConcat(satMatrix)

        // LUT Presets Color Offsets
        when (grading.lut) {
            LutPreset.TEAL_AND_ORANGE -> {
                val lutMat = ColorMatrix(
                    floatArrayOf(
                        1.2f, 0f, 0f, 0f, 15f,
                        0f, 0.95f, 0f, 0f, -5f,
                        0f, 0f, 1.25f, 0f, 25f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                cm.postConcat(lutMat)
            }
            LutPreset.CYBERPUNK -> {
                val lutMat = ColorMatrix(
                    floatArrayOf(
                        1.3f, 0f, 0.2f, 0f, 30f,
                        0f, 0.8f, 0f, 0f, -20f,
                        0.1f, 0.2f, 1.4f, 0f, 40f,
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
                val anime = ColorMatrix().apply { setSaturation(1.45f) }
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
                    floatArrayOf(0.45f, 1.0f),
                    Shader.TileMode.CLAMP
                )
                alpha = (grading.vignette * 2.55f).toInt().coerceIn(0, 255)
            }
            canvas.drawRect(0f, 0f, src.width.toFloat(), src.height.toFloat(), vigPaint)
        }

        // AI Neural Face Retouch (Bilateral Skin Smoothing & 3D Portrait Relight)
        if (grading.skinSmooth > 0.5f || grading.faceRelight > 0.5f) {
            val retouched = NeuralVisionProcessor.applyFaceRetouch(
                srcBitmap = out,
                skinSmooth = grading.skinSmooth,
                faceRelight = grading.faceRelight
            )
            if (retouched !== out) {
                out.recycle()
                return retouched
            }
        }

        return out
    }

    private fun applyPreTransitionTransform(
        canvas: Canvas,
        type: TransitionType,
        timeIntoTransition: Long,
        duration: Long,
        width: Int,
        height: Int
    ): Float {
        val progress = (timeIntoTransition.toFloat() / max(1L, duration)).coerceIn(0f, 1f)
        when (type) {
            TransitionType.FLASH_WHITE -> return progress.coerceAtLeast(0.35f)
            TransitionType.CROSS_DISSOLVE -> return progress
            TransitionType.WHIP_PAN -> {
                val ease = 1f - (1f - progress).pow(3)
                val offset = (1f - ease) * width
                canvas.translate(-offset, 0f)
                return progress.coerceAtLeast(0.4f)
            }
            TransitionType.ZOOM_BLUR -> {
                val ease = 1f - (1f - progress).pow(2)
                val zoomScale = 1.8f - 0.8f * ease
                canvas.scale(zoomScale, zoomScale, width / 2f, height / 2f)
                return progress
            }
            TransitionType.CAMERA_SHAKE -> {
                if (progress < 0.85f) {
                    val damp = 1f - progress
                    val shakeX = sin(progress * 35.0).toFloat() * (width * 0.04f) * damp
                    val shakeY = cos(progress * 28.0).toFloat() * (height * 0.025f) * damp
                    canvas.translate(shakeX, shakeY)
                }
                return 1.0f
            }
            TransitionType.GLITCH -> {
                if (progress < 0.75f) {
                    val jitter = sin(progress * 50.0).toFloat() * (width * 0.03f)
                    canvas.translate(jitter, 0f)
                }
                return progress.coerceAtLeast(0.5f)
            }
            TransitionType.VINTAGE_VHS -> {
                val trackingShift = sin(progress * 40.0).toFloat() * 8f * (1f - progress)
                canvas.translate(trackingShift, 0f)
                return progress.coerceAtLeast(0.6f)
            }
            TransitionType.NONE -> return 1.0f
        }
    }

    private fun applyPostTransitionOverlay(
        canvas: Canvas,
        type: TransitionType,
        timeIntoTransition: Long,
        duration: Long,
        width: Int,
        height: Int
    ) {
        val progress = (timeIntoTransition.toFloat() / max(1L, duration)).coerceIn(0f, 1f)
        when (type) {
            TransitionType.FLASH_WHITE -> {
                val flashAlpha = ((1f - progress).pow(2) * 240).toInt().coerceIn(0, 255)
                if (flashAlpha > 0) {
                    val p = Paint().apply {
                        color = android.graphics.Color.WHITE
                        alpha = flashAlpha
                    }
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
                }
            }
            TransitionType.GLITCH -> {
                if (progress < 0.75f) {
                    val pCyan = Paint().apply { color = 0x5500E5FF }
                    val pMag = Paint().apply { color = 0x55FF1744 }
                    val sliceH = height * 0.06f
                    val y1 = (height * ((progress * 3.7f) % 1.0f))
                    val y2 = (height * (((1f - progress) * 2.3f) % 1.0f))
                    canvas.drawRect(0f, y1, width.toFloat(), y1 + sliceH, pCyan)
                    canvas.drawRect(0f, y2, width.toFloat(), y2 + sliceH, pMag)
                }
            }
            TransitionType.VINTAGE_VHS -> {
                val linePaint = Paint().apply {
                    color = 0x44FFFFFF
                    strokeWidth = 2f
                }
                var y = (progress * 40f) % 16f
                while (y < height) {
                    canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
                    y += 16f
                }
            }
            else -> {}
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
        val kf = interpolateKeyframe(clip, playheadMs)
        val density = width / 360f
        val textSizePx = style.fontSizeSp * density * kf.scale

        canvas.save()
        val textX = width * style.positionXRatio + kf.translationX * (width / 1080f)
        val textY = height * style.positionYRatio + kf.translationY * (height / 1920f)
        canvas.rotate(kf.rotationDegrees, textX, textY)

        val alphaInt = (kf.opacity.coerceIn(0f, 1f) * 255).toInt()

        val strokePaint = Paint().apply {
            color = style.strokeColor.toArgb()
            this.alpha = alphaInt
            textSize = textSizePx
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            this.style = Paint.Style.STROKE
            strokeWidth = style.strokeWidth * density
        }

        val fillPaint = Paint().apply {
            color = style.textColor.toArgb()
            this.alpha = alphaInt
            textSize = textSizePx
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            setShadowLayer(style.shadowRadius * density, 0f, 3f, style.shadowColor.toArgb())
        }

        val textBounds = Rect()
        fillPaint.getTextBounds(text, 0, text.length, textBounds)
        val pillPaddingH = 14f * density
        val pillPaddingV = 8f * density
        val pillRect = RectF(
            textX - textBounds.width() / 2f - pillPaddingH,
            textY + textBounds.top - pillPaddingV,
            textX + textBounds.width() / 2f + pillPaddingH,
            textY + textBounds.bottom + pillPaddingV
        )

        if (style.showBackgroundPill) {
            val pillPaint = Paint().apply {
                color = 0x880A0B10.toInt()
                this.alpha = (alphaInt * 0.55f).toInt()
                isAntiAlias = true
            }
            canvas.drawRoundRect(pillRect, 8f * density, 8f * density, pillPaint)
        }

        canvas.drawText(text, textX, textY, strokePaint)

        if (style.isKaraokeHighlighted && clip.durationMs > 0) {
            val progress = ((playheadMs - clip.startOnTimelineMs).toFloat() / clip.durationMs).coerceIn(0f, 1f)
            val basePaint = Paint(fillPaint).apply {
                color = android.graphics.Color.WHITE
                this.alpha = alphaInt
            }
            canvas.drawText(text, textX, textY, basePaint)

            canvas.save()
            val highlightRight = pillRect.left + pillRect.width() * progress
            canvas.clipRect(pillRect.left, pillRect.top, highlightRight, pillRect.bottom)
            canvas.drawText(text, textX, textY, fillPaint)
            canvas.restore()
        } else {
            canvas.drawText(text, textX, textY, fillPaint)
        }

        canvas.restore()
    }

    fun splitClipAtPlayhead(clips: List<TimelineClip>, targetClipId: String, playheadMs: Long): List<TimelineClip> {
        val index = clips.indexOfFirst { it.id == targetClipId }
        if (index == -1) return clips
        val clip = clips[index]

        if (playheadMs <= clip.startOnTimelineMs + 100L || playheadMs >= (clip.startOnTimelineMs + clip.durationMs - 100L)) {
            return clips
        }

        val firstDuration = playheadMs - clip.startOnTimelineMs
        val secondDuration = clip.durationMs - firstDuration

        val firstClip = clip.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = "${clip.name} (1)",
            durationMs = firstDuration,
            sourceDurationMs = (firstDuration * clip.speed).toLong()
        )

        val secondClip = clip.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = "${clip.name} (2)",
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

    /**
     * AI Auto-Reframe: Uses bundled Google ML Kit Face Detection to track the subject's face
     * across the clip and generate smooth pan/zoom keyframes for 9:16 vertical framing.
     */
    suspend fun autoReframeClipWithFaceTracking(clip: TimelineClip): List<Keyframe> = withContext(Dispatchers.Default) {
        NeuralVisionProcessor.generateAutoReframeKeyframes(clip) { timeMs ->
            loadOrGenerateClipFrame(clip, timeMs, 360, 640)
        }
    }

    /**
     * AI Smart Scene Cut Detection: Scans consecutive frames across the target clip using
     * C++ 64-bin Histogram Chi-Square + Luma Flux and automatically splits at shot boundaries.
     */
    suspend fun detectSceneCutsAndSplit(
        clips: List<TimelineClip>,
        targetClipId: String
    ): Pair<List<TimelineClip>, Int> = withContext(Dispatchers.Default) {
        val targetClip = clips.firstOrNull { it.id == targetClipId } ?: return@withContext (clips to 0)
        if (targetClip.durationMs < 1600L || !MediaNativeBridge.isLoaded()) {
            return@withContext (clips to 0)
        }

        val cutTimestamps = mutableListOf<Long>()
        val stepMs = 250L
        val minShotDurationMs = 900L
        var lastCutMs = targetClip.startOnTimelineMs

        var prevFrame = loadOrGenerateClipFrame(targetClip, targetClip.startOnTimelineMs + 50L, 240, 240)
        var t = targetClip.startOnTimelineMs + stepMs
        val endMs = targetClip.startOnTimelineMs + targetClip.durationMs - 400L

        while (t <= endMs) {
            val currFrame = loadOrGenerateClipFrame(targetClip, t, 240, 240)
            if (prevFrame != null && currFrame != null && prevFrame !== currFrame) {
                val diffScore = MediaNativeBridge.computeSceneDifference(prevFrame, currFrame)
                if (diffScore >= 0.32f && (t - lastCutMs) >= minShotDurationMs) {
                    cutTimestamps.add(t)
                    lastCutMs = t
                }
            }
            if (currFrame != null) {
                prevFrame = currFrame
            }
            t += stepMs
        }

        // If clip is a single continuous take with no hard cuts, split into balanced highlight segments
        if (cutTimestamps.isEmpty() && targetClip.durationMs >= 2600L) {
            val mid = targetClip.startOnTimelineMs + targetClip.durationMs / 2L
            cutTimestamps.add(mid)
        }

        var workingClips = clips
        var currentClipId = targetClipId
        var splitsDone = 0

        for (cutMs in cutTimestamps) {
            val beforeSize = workingClips.size
            workingClips = splitClipAtPlayhead(workingClips, currentClipId, cutMs)
            if (workingClips.size > beforeSize) {
                splitsDone++
                // The second half is the newly added clip starting at cutMs
                val nextHalf = workingClips.firstOrNull {
                    it.trackType == targetClip.trackType && it.startOnTimelineMs == cutMs
                }
                if (nextHalf != null) {
                    currentClipId = nextHalf.id
                } else {
                    break
                }
            }
        }

        workingClips to splitsDone
    }
}
