package com.ararmedia.editor.engine

import android.content.Context
import android.graphics.*
import androidx.compose.ui.graphics.Color
import com.ararmedia.editor.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import kotlin.random.Random

object DemoMediaGenerator {

    /**
     * Synthesizes a rich multi-harmonic + speech-cadence + rhythmic beat PCM audio buffer (10 seconds, 44100Hz).
     */
    fun generateSyntheticAudioBuffer(sampleRate: Int = 44100, durationSec: Int = 10): FloatArray {
        val totalSamples = sampleRate * durationSec
        val buffer = FloatArray(totalSamples)

        val beatIntervalSamples = (sampleRate * 0.5).toInt() // 120 BPM = 500ms per beat
        val rng = Random(42)

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            var sample = 0.0

            val inSpeechPhrase = (t in 0.35..2.15) || (t in 2.75..4.55) || (t in 5.25..7.05) || (t in 7.65..9.45)
            if (inSpeechPhrase) {
                val syllableMod = (0.55 + 0.45 * sin(2.0 * PI * 4.2 * t)).coerceIn(0.0, 1.0)
                val f0 = 145.0 + 18.0 * sin(2.0 * PI * 1.5 * t)
                val vocalHarmonics = sin(2.0 * PI * f0 * t) * 0.32 +
                        sin(2.0 * PI * (f0 * 2.0) * t) * 0.24 +
                        sin(2.0 * PI * (f0 * 3.0) * t) * 0.16 +
                        sin(2.0 * PI * 1150.0 * t) * 0.14 +
                        sin(2.0 * PI * 2450.0 * t) * 0.09
                sample += vocalHarmonics * syllableMod * 0.68
            }

            val sampleInBeat = i % beatIntervalSamples
            val beatTime = sampleInBeat.toDouble() / sampleRate
            if (beatTime < 0.22) {
                val kickFreq = 48.0 + 92.0 * exp(-beatTime * 32.0)
                val kickEnv = exp(-beatTime * 15.0)
                sample += sin(2.0 * PI * kickFreq * beatTime) * kickEnv * 0.65
            }

            val eighthSample = i % (beatIntervalSamples / 2)
            val eighthTime = eighthSample.toDouble() / sampleRate
            if (eighthTime < 0.045) {
                val hatEnv = exp(-eighthTime * 85.0)
                val noise = (rng.nextDouble() * 2.0 - 1.0)
                sample += noise * hatEnv * 0.16
            }

            val backgroundHiss = (rng.nextDouble() * 2.0 - 1.0) * 0.025
            sample += backgroundHiss

            buffer[i] = sample.toFloat().coerceIn(-0.98f, 0.98f)
        }

        return buffer
    }

    /**
     * Generates a true stereo interleaved buffer where vocals are centered (L=R)
     * and stereo synth chords + hi-hats are panned wide across L/R for 2-Stem Separation.
     */
    fun generateSyntheticStereoBuffer(sampleRate: Int = 44100, durationSec: Int = 10): FloatArray {
        val totalFrames = sampleRate * durationSec
        val stereo = FloatArray(totalFrames * 2)
        val beatIntervalSamples = (sampleRate * 0.5).toInt()
        val rng = Random(42)

        for (i in 0 until totalFrames) {
            val t = i.toDouble() / sampleRate
            val inSpeechPhrase = (t in 0.35..2.15) || (t in 2.75..4.55) || (t in 5.25..7.05) || (t in 7.65..9.45)

            var centerVocal = 0.0
            if (inSpeechPhrase) {
                val syllableMod = (0.55 + 0.45 * sin(2.0 * PI * 4.2 * t)).coerceIn(0.0, 1.0)
                val f0 = 145.0 + 18.0 * sin(2.0 * PI * 1.5 * t)
                centerVocal = (sin(2.0 * PI * f0 * t) * 0.32 +
                        sin(2.0 * PI * (f0 * 2.0) * t) * 0.24 +
                        sin(2.0 * PI * 1150.0 * t) * 0.15 +
                        sin(2.0 * PI * 2450.0 * t) * 0.10) * syllableMod * 0.65
            }

            val sampleInBeat = i % beatIntervalSamples
            val beatTime = sampleInBeat.toDouble() / sampleRate
            var kick = 0.0
            if (beatTime < 0.22) {
                val kickFreq = 48.0 + 92.0 * exp(-beatTime * 32.0)
                kick = sin(2.0 * PI * kickFreq * beatTime) * exp(-beatTime * 15.0) * 0.55
            }

            val padL = sin(2.0 * PI * 220.0 * t) * 0.15 + sin(2.0 * PI * 329.6 * t) * 0.12
            val padR = cos(2.0 * PI * 220.0 * t) * 0.15 - sin(2.0 * PI * 329.6 * t) * 0.12

            val hiss = (rng.nextDouble() * 2.0 - 1.0) * 0.02
            stereo[i * 2] = (centerVocal + kick + padL + hiss).toFloat().coerceIn(-0.98f, 0.98f)
            stereo[i * 2 + 1] = (centerVocal + kick + padR + hiss).toFloat().coerceIn(-0.98f, 0.98f)
        }
        return stereo
    }

    /**
     * Saves FloatArray PCM to a valid 16-bit uncompressed WAV file with standard 44-byte RIFF header.
     */
    fun savePcmToWavFile(pcm: FloatArray, sampleRate: Int, outputFile: File) {
        val numSamples = pcm.size
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)
        val dataSize = numSamples * (bitsPerSample / 8)
        val chunkSize = 36 + dataSize

        FileOutputStream(outputFile).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(chunkSize)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1.toShort())
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray())
            header.putInt(dataSize)

            fos.write(header.array())

            val pcmData = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in pcm) {
                val s16 = (sample.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
                pcmData.putShort(s16)
            }
            fos.write(pcmData.array())
        }
    }

    /**
     * Synthesizes clean, cinematic sample clips for the multi-track timeline.
     */
    suspend fun createDemoProject(context: Context): List<TimelineClip> = withContext(Dispatchers.IO) {
        val clips = mutableListOf<TimelineClip>()
        val cacheDir = context.cacheDir

        val audioBuffer = generateSyntheticAudioBuffer()
        val wavFile = File(cacheDir, "demo_master_audio.wav")
        savePcmToWavFile(audioBuffer, 44100, wavFile)

        // 1. Main Track Clip 1: Cinematic Opener
        val bmp1 = renderStudioTitleBitmap(width = 1080, height = 1920, title = "MIDNIGHT CITY", subtitle = "DIRECTOR'S CUT")
        val bmpFile1 = File(cacheDir, "demo_clip_1.png")
        FileOutputStream(bmpFile1).use { bmp1.compress(Bitmap.CompressFormat.PNG, 100, it) }

        clips.add(
            TimelineClip(
                name = "Scene 01: Opener",
                trackType = TrackType.MAIN_VIDEO,
                startOnTimelineMs = 0L,
                durationMs = 4000L,
                thumbnailBitmap = bmp1,
                localUri = bmpFile1.absolutePath,
                transitionIn = TransitionType.FLASH_WHITE,
                transitionDurationMs = 600L,
                animationType = ClipAnimationType.ZOOM_IN,
                colorGrading = ColorGrading(saturation = 1.15f, contrast = 1.1f),
                keyframes = listOf(
                    Keyframe(timestampMs = 0L, scale = 1.0f, opacity = 0.3f),
                    Keyframe(timestampMs = 500L, scale = 1.05f, opacity = 1.0f),
                    Keyframe(timestampMs = 4000L, scale = 1.15f, opacity = 1.0f)
                )
            )
        )

        // 2. Main Track Clip 2: Animated Neon Waves
        val bmp2 = renderNeonWaveformBitmap(width = 1080, height = 1920)
        val bmpFile2 = File(cacheDir, "demo_clip_2.png")
        FileOutputStream(bmpFile2).use { bmp2.compress(Bitmap.CompressFormat.PNG, 100, it) }

        clips.add(
            TimelineClip(
                name = "Scene 02: Cyber Waves",
                trackType = TrackType.MAIN_VIDEO,
                startOnTimelineMs = 4000L,
                durationMs = 6000L,
                thumbnailBitmap = bmp2,
                localUri = bmpFile2.absolutePath,
                transitionIn = TransitionType.WHIP_PAN,
                transitionDurationMs = 500L,
                colorGrading = ColorGrading(lut = LutPreset.CYBERPUNK)
            )
        )

        // 3. PIP Overlay Track (Chroma Key Ready)
        val greenPip = renderGreenScreenPipBadge(width = 600, height = 600)
        val greenFile = File(cacheDir, "demo_pip_green.png")
        FileOutputStream(greenFile).use { greenPip.compress(Bitmap.CompressFormat.PNG, 100, it) }

        clips.add(
            TimelineClip(
                name = "PIP: Vinyl Disc",
                trackType = TrackType.PIP_OVERLAY,
                startOnTimelineMs = 1500L,
                durationMs = 5000L,
                thumbnailBitmap = greenPip,
                localUri = greenFile.absolutePath,
                chromaKey = ChromaKeySettings(
                    enabled = true,
                    keyColor = 0x00FF00,
                    similarity = 0.38f,
                    smoothness = 0.12f,
                    spillSuppression = 0.70f
                ),
                keyframes = listOf(
                    Keyframe(timestampMs = 1500L, scale = 0.4f, translationY = 300f, opacity = 0f),
                    Keyframe(timestampMs = 2000L, scale = 0.58f, translationY = 220f, opacity = 1f),
                    Keyframe(timestampMs = 6500L, scale = 0.58f, translationY = 220f, rotationDegrees = 45f, opacity = 1f)
                )
            )
        )

        // 4. Text & Captions Track
        clips.add(
            TimelineClip(
                name = "Subtitle 01",
                trackType = TrackType.TEXT_STICKER,
                startOnTimelineMs = 400L,
                durationMs = 3100L,
                textContent = "Every frame tells a story",
                textStyle = TextStyleSettings(
                    fontSizeSp = 26f,
                    textColor = Color(0xFF00E5FF),
                    strokeColor = Color.Black,
                    strokeWidth = 3f,
                    isKaraokeHighlighted = true
                )
            )
        )
        clips.add(
            TimelineClip(
                name = "Subtitle 02",
                trackType = TrackType.TEXT_STICKER,
                startOnTimelineMs = 4200L,
                durationMs = 4500L,
                textContent = "Feel the rhythm in motion",
                textStyle = TextStyleSettings(
                    fontSizeSp = 26f,
                    textColor = Color(0xFFFFD600),
                    strokeColor = Color.Black,
                    strokeWidth = 3f,
                    isKaraokeHighlighted = true
                )
            )
        )

        // 5. Music / BGM Track
        clips.add(
            TimelineClip(
                name = "Synthwave Pulse 120BPM",
                trackType = TrackType.MUSIC_BGM,
                startOnTimelineMs = 0L,
                durationMs = 10000L,
                localUri = wavFile.absolutePath,
                audioSamples = audioBuffer,
                volume = 0.85f
            )
        )

        // 6. Voiceover Track
        clips.add(
            TimelineClip(
                name = "Narration Track",
                trackType = TrackType.VOICEOVER,
                startOnTimelineMs = 1000L,
                durationMs = 7000L,
                localUri = wavFile.absolutePath,
                audioSamples = audioBuffer,
                volume = 0.60f
            )
        )

        clips
    }

    private fun renderStudioTitleBitmap(width: Int, height: Int, title: String, subtitle: String): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val gradient = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(0xFF0A0B10.toInt(), 0xFF141624.toInt(), 0xFF1A1C30.toInt()),
            null, Shader.TileMode.CLAMP
        )
        val bgPaint = Paint().apply { shader = gradient }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val glowPaint = Paint().apply {
            color = 0xFF7C4DFF.toInt()
            alpha = 80
            maskFilter = BlurMaskFilter(150f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(width * 0.3f, height * 0.35f, 250f, glowPaint)

        val glowCyan = Paint().apply {
            color = 0xFF00E5FF.toInt()
            alpha = 90
            maskFilter = BlurMaskFilter(160f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(width * 0.7f, height * 0.65f, 220f, glowCyan)

        val titlePaint = Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 76f
            isFakeBoldText = true
            letterSpacing = 0.08f
            textAlign = Paint.Align.CENTER
            setShadowLayer(16f, 0f, 4f, 0xFF00E5FF.toInt())
        }
        canvas.drawText(title, width / 2f, height * 0.48f, titlePaint)

        val subPaint = Paint().apply {
            color = 0xFF00E5FF.toInt()
            textSize = 32f
            letterSpacing = 0.25f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(subtitle, width / 2f, height * 0.535f, subPaint)

        return bitmap
    }

    private fun renderNeonWaveformBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint().apply { color = 0xFF0B0D18.toInt() }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val path = Path()
        val numPoints = 80
        val centerY = height / 2f

        for (w in 0..2) {
            path.reset()
            path.moveTo(0f, centerY)
            for (i in 0..numPoints) {
                val x = (i.toFloat() / numPoints) * width
                val y = centerY + sin(i * 0.3 + w * 1.5).toFloat() * (120f + w * 50f) * sin(i * PI / numPoints).toFloat()
                path.lineTo(x, y)
            }

            val strokePaint = Paint().apply {
                color = when (w) {
                    0 -> 0xFF00E5FF.toInt()
                    1 -> 0xFF7C4DFF.toInt()
                    else -> 0xFFFFD600.toInt()
                }
                style = Paint.Style.STROKE
                strokeWidth = 6f
                isAntiAlias = true
                setShadowLayer(14f, 0f, 0f, color)
            }
            canvas.drawPath(path, strokePaint)
        }

        return bitmap
    }

    private fun renderGreenScreenPipBadge(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(0xFF00FF00.toInt())

        val center = width / 2f
        val outerVinyl = Paint().apply {
            color = 0xFF141622.toInt()
            isAntiAlias = true
            setShadowLayer(12f, 0f, 6f, 0xFF222222.toInt())
        }
        canvas.drawCircle(center, center, width * 0.38f, outerVinyl)

        val groovePaint = Paint().apply {
            color = 0xFF2C3048.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
        canvas.drawCircle(center, center, width * 0.31f, groovePaint)
        canvas.drawCircle(center, center, width * 0.25f, groovePaint)

        val labelPaint = Paint().apply {
            color = 0xFF00E5FF.toInt()
            isAntiAlias = true
        }
        canvas.drawCircle(center, center, width * 0.14f, labelPaint)

        val holePaint = Paint().apply {
            color = 0xFF0A0B10.toInt()
            isAntiAlias = true
        }
        canvas.drawCircle(center, center, width * 0.04f, holePaint)

        return bitmap
    }
}
