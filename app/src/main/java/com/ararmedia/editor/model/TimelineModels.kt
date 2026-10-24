package com.ararmedia.editor.model

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import java.util.UUID

enum class TrackType {
    MAIN_VIDEO,
    PIP_OVERLAY,
    TEXT_STICKER,
    MUSIC_BGM,
    VOICEOVER
}

enum class AspectRatio(val ratio: Float, val label: String, val width: Int, val height: Int) {
    TIKTOK_9_16(9f / 16f, "9:16", 1080, 1920),
    YOUTUBE_16_9(16f / 9f, "16:9", 1920, 1080),
    SQUARE_1_1(1f, "1:1", 1080, 1080),
    PORTRAIT_4_5(4f / 5f, "4:5", 1080, 1350),
    CINEMA_21_9(21f / 9f, "21:9", 2560, 1080)
}

enum class CanvasBackgroundMode(val title: String) {
    BLUR("Blur"),
    BLACK("Black"),
    STUDIO_DARK("Studio"),
    GRADIENT("Gradient")
}

enum class TransitionType(val title: String) {
    NONE("None"),
    CROSS_DISSOLVE("Dissolve"),
    GLITCH("Glitch"),
    WHIP_PAN("Whip Pan"),
    ZOOM_BLUR("Zoom Blur"),
    FLASH_WHITE("Flash White"),
    CAMERA_SHAKE("Shake"),
    VINTAGE_VHS("VHS")
}

enum class ClipAnimationType(val title: String, val category: String) {
    NONE("None", "None"),
    FADE_IN("Fade In", "In"),
    ZOOM_IN("Zoom In", "In"),
    SLIDE_UP("Slide Up", "In"),
    SLIDE_LEFT("Slide Left", "In"),
    SPIN_IN("Spin In", "In"),
    BOUNCE("Bounce", "Combo"),
    PENDULUM("Pendulum", "Combo"),
    PULSE("Beat Pulse", "Combo")
}

enum class SpeedCurvePreset(val title: String, val description: String) {
    NORMAL("Normal", "Constant Speed"),
    MONTAGE("Montage", "Fast-Slow-Fast"),
    HERO("Hero", "Slow-Mo Peak"),
    BULLET("Bullet", "Ultra Slow Impact"),
    JUMP_CUT("Jump Cut", "Rhythmic Burst"),
    FLASH_IN("Flash In", "Rapid Entry")
}

enum class LutPreset(val title: String) {
    NONE("Original"),
    TEAL_AND_ORANGE("Teal & Orange"),
    CYBERPUNK("Cyberpunk"),
    VINTAGE_FILM("Vintage Film"),
    NOIR("Noir B&W"),
    ANIME("Vivid Cinema")
}

data class ColorGrading(
    val brightness: Float = 0f,     // -50 to 50
    val contrast: Float = 1f,        // 0.5 to 2.0
    val saturation: Float = 1f,      // 0.0 to 2.0
    val exposure: Float = 0f,        // -2.0 to 2.0
    val highlights: Float = 0f,      // -100 to 100
    val shadows: Float = 0f,         // -100 to 100
    val temperature: Float = 0f,     // -50 (cool) to 50 (warm)
    val sharpness: Float = 0f,       // 0 to 100
    val vignette: Float = 0f,        // 0 to 100
    val lut: LutPreset = LutPreset.NONE
)

data class Keyframe(
    val timestampMs: Long,
    val scale: Float = 1.0f,
    val translationX: Float = 0.0f,
    val translationY: Float = 0.0f,
    val rotationDegrees: Float = 0.0f,
    val opacity: Float = 1.0f
)

data class ChromaKeySettings(
    val enabled: Boolean = false,
    val keyColor: Int = 0x00FF00, // Green
    val similarity: Float = 0.35f,
    val smoothness: Float = 0.15f,
    val spillSuppression: Float = 0.60f,
    val smartCutoutEnabled: Boolean = false,
    val smartSensitivity: Float = 0.38f,
    val smartFeather: Float = 0.20f
)

data class TimelineClip(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val trackType: TrackType,
    val startOnTimelineMs: Long,
    val durationMs: Long,
    val sourceOffsetMs: Long = 0L,
    val sourceDurationMs: Long = durationMs,
    val speed: Float = 1.0f,
    val speedCurve: SpeedCurvePreset = SpeedCurvePreset.NORMAL,
    val isReversed: Boolean = false,
    val isMirrored: Boolean = false,
    val isFreezeFrame: Boolean = false,
    val isVideoSource: Boolean = false,
    val isStabilized: Boolean = false,
    val isAiDenoised: Boolean = false,
    val voicePresetId: Int = 0,
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val colorGrading: ColorGrading = ColorGrading(),
    val chromaKey: ChromaKeySettings = ChromaKeySettings(),
    val transitionIn: TransitionType = TransitionType.NONE,
    val transitionDurationMs: Long = 600L,
    val animationType: ClipAnimationType = ClipAnimationType.NONE,
    val animationDurationMs: Long = 650L,
    val keyframes: List<Keyframe> = emptyList(),
    // For text & sticker clips:
    val textContent: String = "",
    val textStyle: TextStyleSettings = TextStyleSettings(),
    // Content source & cached audio PCM
    val localUri: String = "",
    val thumbnailBitmap: Bitmap? = null,
    val audioSamples: FloatArray? = null
)

data class TextStyleSettings(
    val fontSizeSp: Float = 26f,
    val textColor: Color = Color.White,
    val strokeColor: Color = Color.Black,
    val strokeWidth: Float = 2.5f,
    val shadowColor: Color = Color(0xAA000000),
    val shadowRadius: Float = 4f,
    val positionXRatio: Float = 0.50f,
    val positionYRatio: Float = 0.78f,
    val isKaraokeHighlighted: Boolean = false,
    val showBackgroundPill: Boolean = true
)

data class BeatMarker(
    val timestampMs: Long,
    val intensity: Float = 1.0f
)

data class SubtitleEntry(
    val id: String = UUID.randomUUID().toString(),
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class StemSplitResult(
    val vocalBuffer: FloatArray,
    val backingBuffer: FloatArray,
    val numFrames: Int,
    val sampleRate: Int = 44100
)

data class WaveformSelection(
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val isActive: Boolean = false
)

data class AudioBufferData(
    val samples: FloatArray,
    val sampleRate: Int = 44100,
    val channels: Int = 1,
    val durationMs: Long = (samples.size * 1000L) / sampleRate
)
