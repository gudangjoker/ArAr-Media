package com.ararmedia.editor.engine

import android.graphics.Bitmap
import android.util.LruCache
import com.ararmedia.editor.model.Keyframe
import com.ararmedia.editor.model.TimelineClip
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Bundled On-Device Neural Vision AI Engine powered by Google ML Kit Bundled Models
 * (Selfie Segmentation & Face Detection) combined with C++ JNI pixel acceleration.
 */
object NeuralVisionProcessor {

    private val segmenter by lazy {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
        Segmentation.getClient(options)
    }

    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.10f)
            .build()
        FaceDetection.getClient(options)
    }

    private data class CachedMask(
        val mask: FloatArray,
        val width: Int,
        val height: Int,
        val maxConfidence: Float
    )

    private val maskCache = object : LruCache<Int, CachedMask>(24) {}
    private val faceCache = object : LruCache<Int, FloatArray>(36) {}

    /**
     * Runs the bundled Google ML Kit Selfie Segmentation neural network on [srcBitmap]
     * and applies C++ bilinear edge feathering + premultiplied alpha cutout.
     */
    fun applyNeuralCutout(
        srcBitmap: Bitmap,
        sensitivity: Float,
        feather: Float
    ): Bitmap {
        if (!MediaNativeBridge.isLoaded()) return srcBitmap
        val outBitmap = Bitmap.createBitmap(srcBitmap.width, srcBitmap.height, Bitmap.Config.ARGB_8888)

        try {
            val bmpKey = bitmapFingerprint(srcBitmap)
            var cached = maskCache.get(bmpKey)

            if (cached == null) {
                val inputImage = InputImage.fromBitmap(srcBitmap, 0)
                val segMask = Tasks.await(segmenter.process(inputImage), 850, TimeUnit.MILLISECONDS)
                val mWidth = segMask.width
                val mHeight = segMask.height
                val buffer = segMask.buffer
                buffer.rewind()

                val floatMask = FloatArray(mWidth * mHeight)
                var maxConf = 0f
                for (i in floatMask.indices) {
                    val c = buffer.float
                    floatMask[i] = c
                    if (c > maxConf) maxConf = c
                }
                cached = CachedMask(floatMask, mWidth, mHeight, maxConf)
                maskCache.put(bmpKey, cached)
            }

            // If neural model found a person/subject with confidence >= 0.20, apply neural mask
            if (cached.maxConfidence >= 0.20f) {
                val threshold = (1.0f - sensitivity.coerceIn(0.15f, 0.85f)).coerceIn(0.20f, 0.80f)
                MediaNativeBridge.applyNeuralMaskCutout(
                    srcBitmap = srcBitmap,
                    dstBitmap = outBitmap,
                    confidenceMask = cached.mask,
                    maskWidth = cached.width,
                    maskHeight = cached.height,
                    threshold = threshold,
                    feather = feather.coerceIn(0.05f, 0.65f)
                )
                return outBitmap
            }
        } catch (_: Exception) {
            // Fall through to C++ border-cluster saliency cutout
        }

        // Fallback for non-human objects or synthetic scenes
        MediaNativeBridge.processSmartCutout(
            srcBitmap = srcBitmap,
            dstBitmap = outBitmap,
            sensitivity = sensitivity,
            feather = feather
        )
        return outBitmap
    }

    /**
     * Detects faces using the bundled Google ML Kit Face Detection neural network.
     * Returns normalized [left, top, right, bottom] coordinates in 0..1 range for each face.
     */
    fun detectFacesNormalized(srcBitmap: Bitmap): FloatArray {
        val w = srcBitmap.width.toFloat()
        val h = srcBitmap.height.toFloat()
        if (w <= 8f || h <= 8f) return FloatArray(0)

        val key = bitmapFingerprint(srcBitmap)
        faceCache.get(key)?.let { return it }

        return try {
            val inputImage = InputImage.fromBitmap(srcBitmap, 0)
            val faces = Tasks.await(faceDetector.process(inputImage), 700, TimeUnit.MILLISECONDS)
            if (faces.isNullOrEmpty()) {
                val empty = FloatArray(0)
                faceCache.put(key, empty)
                empty
            } else {
                val sorted = faces.sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }
                val count = min(4, sorted.size)
                val rects = FloatArray(count * 4)
                for (i in 0 until count) {
                    val box = sorted[i].boundingBox
                    rects[i * 4 + 0] = (box.left / w).coerceIn(0f, 1f)
                    rects[i * 4 + 1] = (box.top / h).coerceIn(0f, 1f)
                    rects[i * 4 + 2] = (box.right / w).coerceIn(0f, 1f)
                    rects[i * 4 + 3] = (box.bottom / h).coerceIn(0f, 1f)
                }
                faceCache.put(key, rects)
                rects
            }
        } catch (_: Exception) {
            FloatArray(0)
        }
    }

    /**
     * Applies Neural Face-Guided Skin Smoothing & 3D Portrait Relighting via C++ JNI.
     */
    fun applyFaceRetouch(
        srcBitmap: Bitmap,
        skinSmooth: Float,
        faceRelight: Float
    ): Bitmap {
        if (!MediaNativeBridge.isLoaded() || (skinSmooth <= 0.5f && faceRelight <= 0.5f)) {
            return srcBitmap
        }
        val rects = detectFacesNormalized(srcBitmap)
        val numFaces = rects.size / 4
        val outBitmap = Bitmap.createBitmap(srcBitmap.width, srcBitmap.height, Bitmap.Config.ARGB_8888)
        MediaNativeBridge.processFaceRetouch(
            srcBitmap = srcBitmap,
            dstBitmap = outBitmap,
            faceRectsNormalized = if (rects.isNotEmpty()) rects else FloatArray(4),
            numFaces = numFaces,
            skinSmooth = skinSmooth,
            faceRelight = faceRelight
        )
        return outBitmap
    }

    /**
     * Scans frames across a video clip to track the primary speaker's face and generates
     * smooth pan/zoom keyframes to keep the subject centered in vertical (9:16) framing.
     */
    fun generateAutoReframeKeyframes(
        clip: TimelineClip,
        sampleFrameAtTimelineMs: (Long) -> Bitmap?
    ): List<Keyframe> {
        val stepMs = max(350L, clip.durationMs / 10L)
        val rawOffsets = mutableListOf<Triple<Long, Float, Float>>()

        var t = clip.startOnTimelineMs
        val endMs = clip.startOnTimelineMs + clip.durationMs
        while (t <= endMs) {
            val frame = sampleFrameAtTimelineMs(min(t, endMs - 50L))
            if (frame != null) {
                val rects = detectFacesNormalized(frame)
                if (rects.size >= 4) {
                    val faceCenterX = (rects[0] + rects[2]) * 0.5f
                    val faceCenterY = (rects[1] + rects[3]) * 0.5f
                    // Shift camera opposite to face offset from center (0.5, 0.44)
                    val panX = ((0.50f - faceCenterX) * 620f).coerceIn(-340f, 340f)
                    val panY = ((0.44f - faceCenterY) * 520f).coerceIn(-280f, 280f)
                    rawOffsets.add(Triple(t, panX, panY))
                } else {
                    // Fallback saliency centroid if no human face in shot
                    val (salX, salY) = computeVisualSaliencyCenter(frame)
                    val panX = ((0.50f - salX) * 480f).coerceIn(-260f, 260f)
                    val panY = ((0.48f - salY) * 420f).coerceIn(-220f, 220f)
                    rawOffsets.add(Triple(t, panX, panY))
                }
            }
            t += stepMs
        }

        if (rawOffsets.isEmpty()) {
            return listOf(
                Keyframe(timestampMs = clip.startOnTimelineMs, scale = 1.32f, translationX = 0f, translationY = 0f)
            )
        }

        // Temporal 3-point Gaussian smoothing so camera pans like a professional gimbal operator
        return rawOffsets.mapIndexed { idx, (timeMs, px, py) ->
            val prev = rawOffsets[max(0, idx - 1)]
            val next = rawOffsets[min(rawOffsets.lastIndex, idx + 1)]
            val smoothX = prev.second * 0.25f + px * 0.50f + next.second * 0.25f
            val smoothY = prev.third * 0.25f + py * 0.50f + next.third * 0.25f
            Keyframe(
                timestampMs = timeMs,
                scale = 1.34f,
                translationX = smoothX,
                translationY = smoothY,
                rotationDegrees = 0f,
                opacity = 1.0f
            )
        }
    }

    private fun computeVisualSaliencyCenter(bmp: Bitmap): Pair<Float, Float> {
        val w = bmp.width
        val h = bmp.height
        val stepX = max(1, w / 24)
        val stepY = max(1, h / 24)
        var sumWx = 0.0
        var sumWy = 0.0
        var sumW = 0.0

        for (y in stepY until h - stepY step stepY) {
            for (x in stepX until w - stepX step stepX) {
                val c = bmp.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                val sat = (maxC - minC).toDouble() / 255.0
                val luma = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
                val weight = (sat * 0.65 + luma * 0.35).coerceAtLeast(0.01)
                sumWx += (x.toDouble() / w) * weight
                sumWy += (y.toDouble() / h) * weight
                sumW += weight
            }
        }
        if (sumW <= 1e-5) return 0.5f to 0.5f
        return (sumWx / sumW).toFloat() to (sumWy / sumW).toFloat()
    }

    private fun bitmapFingerprint(bmp: Bitmap): Int {
        val w = bmp.width
        val h = bmp.height
        if (w <= 4 || h <= 4) return System.identityHashCode(bmp)
        var hash = w * 31 + h
        hash = hash * 31 + bmp.getPixel(w / 2, h / 2)
        hash = hash * 31 + bmp.getPixel(w / 3, h / 3)
        hash = hash * 31 + bmp.getPixel((w * 2) / 3, (h * 2) / 3)
        return hash
    }
}
