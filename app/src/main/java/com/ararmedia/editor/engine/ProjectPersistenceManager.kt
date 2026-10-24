package com.ararmedia.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.ararmedia.editor.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedProjectSummary(
    val id: String,
    val title: String,
    val clipCount: Int,
    val durationMs: Long,
    val aspectRatioLabel: String,
    val updatedAtMs: Long
)

data class LoadedProjectState(
    val id: String,
    val title: String,
    val aspectRatio: AspectRatio,
    val backgroundMode: CanvasBackgroundMode,
    val clips: List<TimelineClip>
)

object ProjectPersistenceManager {

    private fun getProjectsDir(context: Context): File {
        val dir = File(context.filesDir, "projects")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun saveProject(
        context: Context,
        projectId: String,
        title: String,
        aspectRatio: AspectRatio,
        backgroundMode: CanvasBackgroundMode,
        clips: List<TimelineClip>,
        totalDurationMs: Long
    ) = withContext(Dispatchers.IO) {
        if (clips.isEmpty()) return@withContext
        try {
            val root = JSONObject()
            root.put("id", projectId)
            root.put("title", title)
            root.put("aspectRatio", aspectRatio.name)
            root.put("backgroundMode", backgroundMode.name)
            root.put("durationMs", totalDurationMs)
            root.put("updatedAtMs", System.currentTimeMillis())

            val clipsArr = JSONArray()
            for (c in clips) {
                val obj = JSONObject()
                obj.put("id", c.id)
                obj.put("name", c.name)
                obj.put("trackType", c.trackType.name)
                obj.put("startOnTimelineMs", c.startOnTimelineMs)
                obj.put("durationMs", c.durationMs)
                obj.put("sourceOffsetMs", c.sourceOffsetMs)
                obj.put("sourceDurationMs", c.sourceDurationMs)
                obj.put("speed", c.speed.toDouble())
                obj.put("speedCurve", c.speedCurve.name)
                obj.put("isReversed", c.isReversed)
                obj.put("isMirrored", c.isMirrored)
                obj.put("isFreezeFrame", c.isFreezeFrame)
                obj.put("isVideoSource", c.isVideoSource)
                obj.put("isStabilized", c.isStabilized)
                obj.put("isAiDenoised", c.isAiDenoised)
                obj.put("isVoiceEnhanced", c.isVoiceEnhanced)
                obj.put("isAutoReframed", c.isAutoReframed)
                obj.put("voicePresetId", c.voicePresetId)
                obj.put("volume", c.volume.toDouble())
                obj.put("isMuted", c.isMuted)
                obj.put("localUri", c.localUri)
                obj.put("pcmCachePath", c.pcmCachePath)
                obj.put("textContent", c.textContent)
                obj.put("transitionIn", c.transitionIn.name)
                obj.put("transitionDurationMs", c.transitionDurationMs)
                obj.put("animationType", c.animationType.name)
                obj.put("animationDurationMs", c.animationDurationMs)

                // Color Grading
                val cg = c.colorGrading
                val cgObj = JSONObject().apply {
                    put("brightness", cg.brightness.toDouble())
                    put("contrast", cg.contrast.toDouble())
                    put("saturation", cg.saturation.toDouble())
                    put("exposure", cg.exposure.toDouble())
                    put("highlights", cg.highlights.toDouble())
                    put("shadows", cg.shadows.toDouble())
                    put("temperature", cg.temperature.toDouble())
                    put("sharpness", cg.sharpness.toDouble())
                    put("vignette", cg.vignette.toDouble())
                    put("lut", cg.lut.name)
                    put("skinSmooth", cg.skinSmooth.toDouble())
                    put("faceRelight", cg.faceRelight.toDouble())
                }
                obj.put("colorGrading", cgObj)

                // Chroma Key & Smart Cutout
                val ck = c.chromaKey
                val ckObj = JSONObject().apply {
                    put("enabled", ck.enabled)
                    put("keyColor", ck.keyColor)
                    put("similarity", ck.similarity.toDouble())
                    put("smoothness", ck.smoothness.toDouble())
                    put("spillSuppression", ck.spillSuppression.toDouble())
                    put("smartCutoutEnabled", ck.smartCutoutEnabled)
                    put("smartSensitivity", ck.smartSensitivity.toDouble())
                    put("smartFeather", ck.smartFeather.toDouble())
                }
                obj.put("chromaKey", ckObj)

                // Text Style
                val ts = c.textStyle
                val tsObj = JSONObject().apply {
                    put("fontSizeSp", ts.fontSizeSp.toDouble())
                    put("textColor", ts.textColor.toArgb())
                    put("positionXRatio", ts.positionXRatio.toDouble())
                    put("positionYRatio", ts.positionYRatio.toDouble())
                    put("isKaraokeHighlighted", ts.isKaraokeHighlighted)
                    put("showBackgroundPill", ts.showBackgroundPill)
                }
                obj.put("textStyle", tsObj)

                // Keyframes
                val kfArr = JSONArray()
                for (kf in c.keyframes) {
                    kfArr.put(
                        JSONObject().apply {
                            put("timestampMs", kf.timestampMs)
                            put("scale", kf.scale.toDouble())
                            put("translationX", kf.translationX.toDouble())
                            put("translationY", kf.translationY.toDouble())
                            put("rotationDegrees", kf.rotationDegrees.toDouble())
                            put("opacity", kf.opacity.toDouble())
                        }
                    )
                }
                obj.put("keyframes", kfArr)

                clipsArr.put(obj)
            }
            root.put("clips", clipsArr)

            val outFile = File(getProjectsDir(context), "$projectId.json")
            outFile.writeText(root.toString())
        } catch (_: Exception) {}
    }

    suspend fun listSavedProjects(context: Context): List<SavedProjectSummary> = withContext(Dispatchers.IO) {
        val dir = getProjectsDir(context)
        val files = dir.listFiles { f -> f.extension == "json" } ?: return@withContext emptyList()
        val list = mutableListOf<SavedProjectSummary>()

        for (file in files) {
            try {
                val root = JSONObject(file.readText())
                val id = root.optString("id", file.nameWithoutExtension)
                val title = root.optString("title", "Proyek Video")
                val ratioName = root.optString("aspectRatio", AspectRatio.TIKTOK_9_16.name)
                val ratio = runCatching { AspectRatio.valueOf(ratioName) }.getOrDefault(AspectRatio.TIKTOK_9_16)
                val dur = root.optLong("durationMs", 0L)
                val updated = root.optLong("updatedAtMs", file.lastModified())
                val clipsCount = root.optJSONArray("clips")?.length() ?: 0
                if (clipsCount > 0) {
                    list.add(
                        SavedProjectSummary(
                            id = id,
                            title = title,
                            clipCount = clipsCount,
                            durationMs = dur,
                            aspectRatioLabel = ratio.label,
                            updatedAtMs = updated
                        )
                    )
                }
            } catch (_: Exception) {}
        }

        list.sortedByDescending { it.updatedAtMs }
    }

    suspend fun loadProject(context: Context, projectId: String): LoadedProjectState? = withContext(Dispatchers.IO) {
        val file = File(getProjectsDir(context), "$projectId.json")
        if (!file.exists()) return@withContext null
        try {
            val root = JSONObject(file.readText())
            val title = root.optString("title", "Proyek Video")
            val ratio = runCatching {
                AspectRatio.valueOf(root.optString("aspectRatio", AspectRatio.TIKTOK_9_16.name))
            }.getOrDefault(AspectRatio.TIKTOK_9_16)
            val bgMode = runCatching {
                CanvasBackgroundMode.valueOf(root.optString("backgroundMode", CanvasBackgroundMode.BLUR.name))
            }.getOrDefault(CanvasBackgroundMode.BLUR)

            val clipsArr = root.optJSONArray("clips") ?: JSONArray()
            val loadedClips = mutableListOf<TimelineClip>()

            for (i in 0 until clipsArr.length()) {
                val obj = clipsArr.getJSONObject(i)
                val localUri = obj.optString("localUri", "")
                val pcmCachePath = obj.optString("pcmCachePath", "")
                val isVideoSource = obj.optBoolean("isVideoSource", false)

                var thumbBmp: Bitmap? = null
                var audioSamples: FloatArray? = null
                var waveformPeaks = FloatArray(0)

                if (localUri.isNotEmpty() && File(localUri).exists()) {
                    if (isVideoSource) {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(localUri)
                            thumbBmp = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (_: Exception) {
                        } finally {
                            try { retriever.release() } catch (_: Exception) {}
                        }
                    } else {
                        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                        thumbBmp = BitmapFactory.decodeFile(localUri, opts)
                    }
                }

                if (pcmCachePath.isNotEmpty() && File(pcmCachePath).exists()) {
                    val pcmFile = File(pcmCachePath)
                    val numSamples = (pcmFile.length() / 2L).toInt()
                    if (numSamples > 0) {
                        val buf = FloatArray(numSamples)
                        AudioDecoder.readPcmWindowFromDisk(pcmCachePath, 0, numSamples, buf)
                        audioSamples = buf
                        waveformPeaks = AudioDecoder.computeWaveformPeaks(buf, 512)
                    }
                }

                val cgObj = obj.optJSONObject("colorGrading")
                val colorGrading = if (cgObj != null) {
                    ColorGrading(
                        brightness = cgObj.optDouble("brightness", 0.0).toFloat(),
                        contrast = cgObj.optDouble("contrast", 1.0).toFloat(),
                        saturation = cgObj.optDouble("saturation", 1.0).toFloat(),
                        exposure = cgObj.optDouble("exposure", 0.0).toFloat(),
                        highlights = cgObj.optDouble("highlights", 0.0).toFloat(),
                        shadows = cgObj.optDouble("shadows", 0.0).toFloat(),
                        temperature = cgObj.optDouble("temperature", 0.0).toFloat(),
                        sharpness = cgObj.optDouble("sharpness", 0.0).toFloat(),
                        vignette = cgObj.optDouble("vignette", 0.0).toFloat(),
                        lut = runCatching { LutPreset.valueOf(cgObj.optString("lut", "NONE")) }.getOrDefault(LutPreset.NONE),
                        skinSmooth = cgObj.optDouble("skinSmooth", 0.0).toFloat(),
                        faceRelight = cgObj.optDouble("faceRelight", 0.0).toFloat()
                    )
                } else ColorGrading()

                val ckObj = obj.optJSONObject("chromaKey")
                val chromaKey = if (ckObj != null) {
                    ChromaKeySettings(
                        enabled = ckObj.optBoolean("enabled", false),
                        keyColor = ckObj.optInt("keyColor", 0x00FF00),
                        similarity = ckObj.optDouble("similarity", 0.35).toFloat(),
                        smoothness = ckObj.optDouble("smoothness", 0.15).toFloat(),
                        spillSuppression = ckObj.optDouble("spillSuppression", 0.60).toFloat(),
                        smartCutoutEnabled = ckObj.optBoolean("smartCutoutEnabled", false),
                        smartSensitivity = ckObj.optDouble("smartSensitivity", 0.38).toFloat(),
                        smartFeather = ckObj.optDouble("smartFeather", 0.20).toFloat()
                    )
                } else ChromaKeySettings()

                val tsObj = obj.optJSONObject("textStyle")
                val textStyle = if (tsObj != null) {
                    TextStyleSettings(
                        fontSizeSp = tsObj.optDouble("fontSizeSp", 26.0).toFloat(),
                        textColor = Color(tsObj.optInt("textColor", 0xFFFFFFFF.toInt())),
                        positionXRatio = tsObj.optDouble("positionXRatio", 0.50).toFloat(),
                        positionYRatio = tsObj.optDouble("positionYRatio", 0.78).toFloat(),
                        isKaraokeHighlighted = tsObj.optBoolean("isKaraokeHighlighted", false),
                        showBackgroundPill = tsObj.optBoolean("showBackgroundPill", true)
                    )
                } else TextStyleSettings()

                val kfList = mutableListOf<Keyframe>()
                val kfArr = obj.optJSONArray("keyframes")
                if (kfArr != null) {
                    for (k in 0 until kfArr.length()) {
                        val ko = kfArr.getJSONObject(k)
                        kfList.add(
                            Keyframe(
                                timestampMs = ko.optLong("timestampMs", 0L),
                                scale = ko.optDouble("scale", 1.0).toFloat(),
                                translationX = ko.optDouble("translationX", 0.0).toFloat(),
                                translationY = ko.optDouble("translationY", 0.0).toFloat(),
                                rotationDegrees = ko.optDouble("rotationDegrees", 0.0).toFloat(),
                                opacity = ko.optDouble("opacity", 1.0).toFloat()
                            )
                        )
                    }
                }

                loadedClips.add(
                    TimelineClip(
                        id = obj.optString("id"),
                        name = obj.optString("name", "Clip"),
                        trackType = runCatching { TrackType.valueOf(obj.optString("trackType")) }.getOrDefault(TrackType.MAIN_VIDEO),
                        startOnTimelineMs = obj.optLong("startOnTimelineMs", 0L),
                        durationMs = obj.optLong("durationMs", 3000L),
                        sourceOffsetMs = obj.optLong("sourceOffsetMs", 0L),
                        sourceDurationMs = obj.optLong("sourceDurationMs", 3000L),
                        speed = obj.optDouble("speed", 1.0).toFloat(),
                        speedCurve = runCatching { SpeedCurvePreset.valueOf(obj.optString("speedCurve")) }.getOrDefault(SpeedCurvePreset.NORMAL),
                        isReversed = obj.optBoolean("isReversed", false),
                        isMirrored = obj.optBoolean("isMirrored", false),
                        isFreezeFrame = obj.optBoolean("isFreezeFrame", false),
                        isVideoSource = isVideoSource,
                        isStabilized = obj.optBoolean("isStabilized", false),
                        isAiDenoised = obj.optBoolean("isAiDenoised", false),
                        isVoiceEnhanced = obj.optBoolean("isVoiceEnhanced", false),
                        isAutoReframed = obj.optBoolean("isAutoReframed", false),
                        voicePresetId = obj.optInt("voicePresetId", 0),
                        volume = obj.optDouble("volume", 1.0).toFloat(),
                        isMuted = obj.optBoolean("isMuted", false),
                        colorGrading = colorGrading,
                        chromaKey = chromaKey,
                        transitionIn = runCatching { TransitionType.valueOf(obj.optString("transitionIn")) }.getOrDefault(TransitionType.NONE),
                        transitionDurationMs = obj.optLong("transitionDurationMs", 600L),
                        animationType = runCatching { ClipAnimationType.valueOf(obj.optString("animationType")) }.getOrDefault(ClipAnimationType.NONE),
                        animationDurationMs = obj.optLong("animationDurationMs", 650L),
                        keyframes = kfList,
                        textContent = obj.optString("textContent", ""),
                        textStyle = textStyle,
                        localUri = localUri,
                        pcmCachePath = pcmCachePath,
                        waveformPeaks = waveformPeaks,
                        thumbnailBitmap = thumbBmp,
                        audioSamples = audioSamples
                    )
                )
            }

            LoadedProjectState(
                id = projectId,
                title = title,
                aspectRatio = ratio,
                backgroundMode = bgMode,
                clips = loadedClips
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun deleteProject(context: Context, projectId: String) = withContext(Dispatchers.IO) {
        try {
            File(getProjectsDir(context), "$projectId.json").delete()
        } catch (_: Exception) {}
    }
}
