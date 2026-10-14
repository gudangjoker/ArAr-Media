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
     * Synthesizes a real multi-harmonic + speech-cadence + rhythmic beat PCM audio buffer (10 seconds, 44100Hz).
     * Includes background ambient noise so AI Denoise can be demonstrated immediately!
     */
    fun generateSyntheticAudioBuffer(sampleRate: Int = 44100, durationSec: Int = 10): FloatArray {
        val totalSamples = sampleRate * durationSec
        val buffer = FloatArray(totalSamples)

        val beatIntervalSamples = (sampleRate * 0.5).toInt() // 120 BPM = 500ms per beat

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            var sample = 0.0

            // 1. Fundamental Vocal Harmonics (simulate vocal utterance "ArAr Media")
            val speechEnvelope = (sin(2.0 * PI * 0.8 * t).pow(2.0)).coerceIn(0.0, 1.0)
            val f0 = 140.0 // Male speech fundamental
            val vocalHarmonics = sin(2.0 * PI * f0 * t) * 0.35 +
                    sin(2.0 * PI * (f0 * 2.0) * t) * 0.25 +
                    sin(2.0 * PI * (f0 * 3.0) * t) * 0.18 +
                    sin(2.0 * PI * 1200.0 * t) * 0.12 + // Formant F2
                    sin(2.0 * PI * 2400.0 * t) * 0.08    // Formant F3
            sample += vocalHarmonics * speechEnvelope * 0.65

            // 2. Rhythmic Kick Drum at 120 BPM
            val sampleInBeat = i % beatIntervalSamples
            val beatTime = sampleInBeat.toDouble() / sampleRate
            if (beatTime < 0.25) {
                // Pitch chirp from 140Hz down to 45Hz
                val kickFreq = 45.0 + 95.0 * exp(-beatTime * 30.0)
                val kickEnv = exp(-beatTime * 14.0)
                sample += sin(2.0 * PI * kickFreq * beatTime) * kickEnv * 0.7
            }

            // 3. Hi-Hat on 8th notes (every 250ms)
            val eighthSample = i % (beatIntervalSamples / 2)
            val eighthTime = eighthSample.toDouble() / sampleRate
            if (eighthTime < 0.05) {
                val hatEnv = exp(-eighthTime * 80.0)
                val noise = (Random.nextDouble() * 2.0 - 1.0)
                sample += noise * hatEnv * 0.18
            }

            // 4. Studio Background Hiss / Room Noise Floor (for AI Denoise testing)
            val backgroundHiss = (Random.nextDouble() * 2.0 - 1.0) * 0.04
            sample += backgroundHiss

            buffer[i] = sample.toFloat().coerceIn(-0.98f, 0.98f)
        }

        return buffer
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
            // RIFF header
            header.put("RIFF".toByteArray())
            header.putInt(chunkSize)
            header.put("WAVE".toByteArray())
            // fmt chunk
            header.put("fmt ".toByteArray())
            header.putInt(16) // Subchunk1Size for PCM
            header.putShort(1.toShort()) // AudioFormat 1 = PCM
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            // data chunk
            header.put("data".toByteArray())
            header.putInt(dataSize)

            fos.write(header.array())

            // Write 16-bit PCM samples
            val pcmData = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in pcm) {
                val s16 = (sample.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
                pcmData.putShort(s16)
            }
            fos.write(pcmData.array())
        }
    }

    /**
     * Synthesizes demo visual frames & video clips for the 5-Track Magnetic Timeline.
     */
    suspend fun createDemoProject(context: Context): List<TimelineClip> = withContext(Dispatchers.IO) {
        val clips = mutableListOf<TimelineClip>()
        val cacheDir = context.cacheDir

        // 1. Synthetic WAV Audio Buffer
        val audioBuffer = generateSyntheticAudioBuffer()
        val wavFile = File(cacheDir, "demo_master_audio.wav")
        savePcmToWavFile(audioBuffer, 44100, wavFile)

        // 2. Render Main Track Clip 1: Studio Title Card
        val bmp1 = renderStudioTitleBitmap(width = 1080, height = 1920, title = "ArAr Media Pro", subtitle = "Offline Video & Audio Studio")
        val bmpFile1 = File(cacheDir, "demo_clip_1.png")
        FileOutputStream(bmpFile1).use { bmp1.compress(Bitmap.CompressFormat.PNG, 100, it) }

        clips.add(
            TimelineClip(
                name = "Intro: Studio Title",
                trackType = TrackType.MAIN_VIDEO,
                startOnTimelineMs = 0L,
                durationMs = 4000L,
                thumbnailBitmap = bmp1,
                localUri = bmpFile1.absolutePath,
                transitionIn = TransitionType.FLASH_WHITE,
                transitionDurationMs = 600L,
                colorGrading = ColorGrading(saturation = 1.15f, contrast = 1.1f),
                keyframes = listOf(
                    Keyframe(timestampMs = 0L, scale = 1.0f, opacity = 0.2f),
                    Keyframe(timestampMs = 500L, scale = 1.05f, opacity = 1.0f),
                    Keyframe(timestampMs = 4000L, scale = 1.15f, opacity = 1.0f)
                )
            )
        )

        // 3. Render Main Track Clip 2: Audio/Video Waveform Scene
        val bmp2 = renderNeonWaveformBitmap(width = 1080, height = 1920)
        val bmpFile2 = File(cacheDir, "demo_clip_2.png")
        FileOutputStream(bmpFile2).use { bmp2.compress(Bitmap.CompressFormat.PNG, 100, it) }

        clips.add(
            TimelineClip(
                name = "Scene 2: Cyber Waves",
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

        // 4. Render PIP Green Screen Overlay Track
        val greenPip = renderGreenScreenPipBadge(width = 600, height = 600)
        val greenFile = File(cacheDir, "demo_pip_green.png")
        FileOutputStream(greenFile).use { greenPip.compress(Bitmap.CompressFormat.PNG, 100, it) }

        clips.add(
            TimelineClip(
                name = "PIP: Chroma Badge",
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
                    Keyframe(timestampMs = 2000L, scale = 0.65f, translationY = 200f, opacity = 1f),
                    Keyframe(timestampMs = 6500L, scale = 0.65f, translationY = 200f, opacity = 1f)
                )
            )
        )

        // 5. Track 3: Text & Auto-Captions
        clips.add(
            TimelineClip(
                name = "Caption: Title",
                trackType = TrackType.TEXT_STICKER,
                startOnTimelineMs = 500L,
                durationMs = 3000L,
                textContent = "100% OFFLINE MULTI-TRACK STUDIO",
                textStyle = TextStyleSettings(
                    fontSizeSp = 28f,
                    textColor = Color(0xFF00E5FF),
                    strokeColor = Color.Black,
                    strokeWidth = 3f,
                    isKaraokeHighlighted = true
                )
            )
        )
        clips.add(
            TimelineClip(
                name = "Caption: DSP",
                trackType = TrackType.TEXT_STICKER,
                startOnTimelineMs = 4200L,
                durationMs = 4500L,
                textContent = "C++ 10-Band EQ + AI Voice Denoise",
                textStyle = TextStyleSettings(
                    fontSizeSp = 26f,
                    textColor = Color(0xFFFFD600),
                    strokeColor = Color.Black,
                    strokeWidth = 3f
                )
            )
        )

        // 6. Track 4: Music / BGM Track with Beat Sync
        clips.add(
            TimelineClip(
                name = "BGM: Cyber Beat 120BPM",
                trackType = TrackType.MUSIC_BGM,
                startOnTimelineMs = 0L,
                durationMs = 10000L,
                localUri = wavFile.absolutePath,
                volume = 0.85f
            )
        )

        // 7. Track 5: Voiceover Track
        clips.add(
            TimelineClip(
                name = "VO: Studio Voice Harmonic",
                trackType = TrackType.VOICEOVER,
                startOnTimelineMs = 1000L,
                durationMs = 7000L,
                localUri = wavFile.absolutePath,
                volume = 1.0f
            )
        )

        clips
    }

    private fun renderStudioTitleBitmap(width: Int, height: Int, title: String, subtitle: String): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Dark Studio Gradient
        val gradient = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(0xFF0A0B10.toInt(), 0xFF141624.toInt(), 0xFF1A1C30.toInt()),
            null, Shader.TileMode.CLAMP
        )
        val bgPaint = Paint().apply { shader = gradient }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Cyan / Purple Accent Circles
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

        // Title Text
        val titlePaint = Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 72f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
            setShadowLayer(16f, 0f, 4f, 0xFF00E5FF.toInt())
        }
        canvas.drawText(title, width / 2f, height * 0.48f, titlePaint)

        // Subtitle Text
        val subPaint = Paint().apply {
            color = 0xFF00E5FF.toInt()
            textSize = 34f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(subtitle, width / 2f, height * 0.54f, subPaint)

        return bitmap
    }

    private fun renderNeonWaveformBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Cyberpunk background
        val bgPaint = Paint().apply { color = 0xFF0B0D18.toInt() }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Draw multiple glowing waveform curves
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

        // Pure Chroma Green Background (#00FF00)
        canvas.drawColor(0xFF00FF00.toInt())

        // Draw an attractive metallic golden emblem in the center
        val center = width / 2f
        val emblemPaint = Paint().apply {
            color = 0xFFFFD600.toInt()
            isAntiAlias = true
            setShadowLayer(12f, 0f, 6f, 0xFF333333.toInt())
        }
        canvas.drawCircle(center, center, width * 0.38f, emblemPaint)

        val innerPaint = Paint().apply {
            color = 0xFF12131C.toInt()
            isAntiAlias = true
        }
        canvas.drawCircle(center, center, width * 0.32f, innerPaint)

        val textPaint = Paint().apply {
            color = 0xFF00E5FF.toInt()
            textSize = 54f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("ARAR", center, center + 15f, textPaint)

        return bitmap
    }
}
