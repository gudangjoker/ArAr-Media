package com.ararmedia.editor.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.*
import android.os.Build
import android.provider.MediaStore
import com.ararmedia.editor.model.TimelineClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min

enum class VideoResolution(val label: String, val width: Int, val height: Int) {
    HD_720P("720p HD", 720, 1280),
    FHD_1080P("1080p Full HD", 1080, 1920),
    UHD_4K("4K UHD", 2160, 3840)
}

enum class ExportFrameRate(val fps: Int) {
    FPS_24(24),
    FPS_30(30),
    FPS_60(60)
}

enum class BitrateQuality(val label: String, val multiplier: Float) {
    LOW("Low", 0.6f),
    RECOMMENDED("Recommended", 1.0f),
    HIGH("High Quality", 1.8f)
}

enum class AudioExportFormat {
    LOSSLESS_WAV,
    AAC_M4A
}

class MediaExportEngine(private val context: Context) {

    /**
     * Exports multi-track timeline (Video H.264 AVC + Mastered Audio AAC) into Movies/ArArMedia/ArArMedia_YYYYMMDD_HHMMSS.mp4.
     */
    suspend fun exportVideo(
        clips: List<TimelineClip>,
        timelineEngine: VideoTimelineEngine,
        masterAudioPcm: FloatArray = FloatArray(0),
        sampleRate: Int = 44100,
        resolution: VideoResolution = VideoResolution.FHD_1080P,
        frameRate: ExportFrameRate = ExportFrameRate.FPS_30,
        bitrateQuality: BitrateQuality = BitrateQuality.RECOMMENDED,
        onProgress: (Float) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "ArArMedia_$timeStamp.mp4"

        var totalDurationMs = 0L
        for (clip in clips) {
            val end = clip.startOnTimelineMs + clip.durationMs
            if (end > totalDurationMs) totalDurationMs = end
        }
        if (totalDurationMs <= 0L) totalDurationMs = 3000L

        val isLandscape = timelineEngine.currentAspectRatio.width > timelineEngine.currentAspectRatio.height
        val rawWidth = if (isLandscape) resolution.height else resolution.width
        val rawHeight = if (isLandscape) resolution.width else resolution.height

        // Align dimensions to multiple of 16 (H.264 macroblock requirement)
        val alignedWidth = (rawWidth / 16) * 16
        val alignedHeight = (rawHeight / 16) * 16

        val tempOutputFile = File(context.cacheDir, fileName)
        if (tempOutputFile.exists()) tempOutputFile.delete()

        val fps = frameRate.fps
        val baseBitrate = (alignedWidth * alignedHeight * fps * 0.14f * bitrateQuality.multiplier).toInt()

        // 1. Pre-encode Audio AAC packets in memory so MediaMuxer can mux Video + Audio tracks cleanly
        val expectedAudioSamples = ((totalDurationMs * sampleRate) / 1000L).toInt()
        val paddedAudio = FloatArray(max(sampleRate, expectedAudioSamples))
        if (masterAudioPcm.isNotEmpty()) {
            System.arraycopy(masterAudioPcm, 0, paddedAudio, 0, min(masterAudioPcm.size, paddedAudio.size))
        }
        val encodedAudio = encodePcmToAacPackets(paddedAudio, sampleRate)

        // 2. Configure Video H.264 Encoder
        val videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val isSemiPlanar = isSemiPlanarYuvPreferred(videoEncoder.codecInfo)

        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, alignedWidth, alignedHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, baseBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoEncoder.start()

        val muxer = MediaMuxer(tempOutputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        val totalFrames = max(1, ((totalDurationMs * fps) / 1000L).toInt())
        val frameDurationUs = 1_000_000L / fps

        try {
            for (frameIdx in 0 until totalFrames) {
                val currentMs = (frameIdx * 1000L) / fps
                val frameBitmap = timelineEngine.renderFrameAt(currentMs, clips, alignedWidth, alignedHeight)

                val yuvData = bitmapToYuv420(frameBitmap, alignedWidth, alignedHeight, isSemiPlanar)
                frameBitmap.recycle()

                var inputSubmitted = false
                var retries = 0
                while (!inputSubmitted && retries < 100) {
                    val inputBufIndex = videoEncoder.dequeueInputBuffer(10_000)
                    if (inputBufIndex >= 0) {
                        val inputBuffer = videoEncoder.getInputBuffer(inputBufIndex)
                        inputBuffer?.clear()
                        inputBuffer?.put(yuvData)

                        val pts = frameIdx * frameDurationUs
                        val flags = if (frameIdx == totalFrames - 1) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        videoEncoder.queueInputBuffer(inputBufIndex, 0, yuvData.size, pts, flags)
                        inputSubmitted = true
                    } else {
                        retries++
                    }

                    // Drain available output frames while encoding
                    while (true) {
                        val outIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 0)
                        if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (!muxerStarted) {
                                videoTrackIndex = muxer.addTrack(videoEncoder.outputFormat)
                                if (encodedAudio.outputFormat != null) {
                                    audioTrackIndex = muxer.addTrack(encodedAudio.outputFormat)
                                }
                                muxer.start()
                                muxerStarted = true
                            }
                        } else if (outIndex >= 0) {
                            val outBuffer = videoEncoder.getOutputBuffer(outIndex)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0 && muxerStarted && videoTrackIndex >= 0 && outBuffer != null) {
                                outBuffer.position(bufferInfo.offset)
                                outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(videoTrackIndex, outBuffer, bufferInfo)
                            }
                            videoEncoder.releaseOutputBuffer(outIndex, false)
                        } else {
                            break
                        }
                    }
                }

                val progress = ((frameIdx + 1).toFloat() / totalFrames) * 0.92f
                onProgress(progress)
            }

            // Drain remaining video EOS buffers
            var eosReached = false
            var drainTries = 0
            while (!eosReached && drainTries < 50) {
                val outIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        videoTrackIndex = muxer.addTrack(videoEncoder.outputFormat)
                        if (encodedAudio.outputFormat != null) {
                            audioTrackIndex = muxer.addTrack(encodedAudio.outputFormat)
                        }
                        muxer.start()
                        muxerStarted = true
                    }
                } else if (outIndex >= 0) {
                    val outBuffer = videoEncoder.getOutputBuffer(outIndex)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted && videoTrackIndex >= 0 && outBuffer != null) {
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(videoTrackIndex, outBuffer, bufferInfo)
                    }
                    videoEncoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        eosReached = true
                    }
                } else {
                    drainTries++
                }
            }

            // Write pre-encoded AAC audio packets into the MP4 container
            if (muxerStarted && audioTrackIndex >= 0) {
                val audioBufInfo = MediaCodec.BufferInfo()
                for (pkt in encodedAudio.packets) {
                    val byteBuf = ByteBuffer.wrap(pkt.bytes)
                    audioBufInfo.set(0, pkt.bytes.size, pkt.ptsUs, pkt.flags)
                    muxer.writeSampleData(audioTrackIndex, byteBuf, audioBufInfo)
                }
            }
            onProgress(1.0f)
        } finally {
            try {
                videoEncoder.stop()
                videoEncoder.release()
            } catch (_: Exception) {}
            try {
                if (muxerStarted) {
                    muxer.stop()
                }
                muxer.release()
            } catch (_: Exception) {}
        }

        saveToMoviesMediaStore(context, tempOutputFile, fileName)
    }

    private data class EncodedPacket(val bytes: ByteArray, val ptsUs: Long, val flags: Int)
    private data class EncodedAudioTrack(val outputFormat: MediaFormat?, val packets: List<EncodedPacket>)

    private fun encodePcmToAacPackets(pcm: FloatArray, sampleRate: Int): EncodedAudioTrack {
        val packets = mutableListOf<EncodedPacket>()
        var outputFormat: MediaFormat? = null
        var encoder: MediaCodec? = null

        try {
            val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            val shortPcm = ShortArray(pcm.size)
            for (i in pcm.indices) {
                shortPcm[i] = (pcm[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
            }

            var pcmOffset = 0
            val chunkSize = 2048
            var inputDone = false
            var outputDone = false
            var emptyLoops = 0

            while (!outputDone && emptyLoops < 50) {
                if (!inputDone) {
                    val inIndex = encoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = encoder.getInputBuffer(inIndex)
                        inBuf?.clear()
                        val count = (shortPcm.size - pcmOffset).coerceAtMost(chunkSize)
                        if (count <= 0) {
                            encoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val byteBuf = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
                            for (s in 0 until count) {
                                byteBuf.putShort(shortPcm[pcmOffset + s])
                            }
                            inBuf?.put(byteBuf.array())
                            val pts = (pcmOffset.toLong() * 1_000_000L) / sampleRate
                            val isEos = (pcmOffset + count >= shortPcm.size)
                            encoder.queueInputBuffer(
                                inIndex,
                                0,
                                count * 2,
                                pts,
                                if (isEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            )
                            pcmOffset += count
                            if (isEos) inputDone = true
                        }
                    }
                }

                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 5_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputFormat = encoder.outputFormat
                } else if (outIndex >= 0) {
                    val outBuf = encoder.getOutputBuffer(outIndex)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && outBuf != null) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val bytes = ByteArray(bufferInfo.size)
                        outBuf.get(bytes)
                        packets.add(EncodedPacket(bytes, bufferInfo.presentationTimeUs, bufferInfo.flags))
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                } else {
                    if (inputDone) emptyLoops++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                encoder?.stop()
                encoder?.release()
            } catch (_: Exception) {}
        }

        return EncodedAudioTrack(outputFormat, packets)
    }

    private fun isSemiPlanarYuvPreferred(codecInfo: MediaCodecInfo): Boolean {
        return try {
            val caps = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Converts an ARGB_8888 Bitmap to YUV420 (NV12 Semi-Planar or I420 Planar) byte array.
     */
    private fun bitmapToYuv420(bitmap: Bitmap, width: Int, height: Int, semiPlanarNv12: Boolean = true): ByteArray {
        val yuv = ByteArray(width * height * 3 / 2)
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        var yIndex = 0
        var uvIndex = width * height
        var uIndex = width * height
        var vIndex = uIndex + (width * height / 4)

        for (j in 0 until height) {
            for (i in 0 until width) {
                val color = argb[j * width + i]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    if (semiPlanarNv12) {
                        yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                        yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                    } else {
                        yuv[uIndex++] = u.coerceIn(0, 255).toByte()
                        yuv[vIndex++] = v.coerceIn(0, 255).toByte()
                    }
                }
            }
        }
        return yuv
    }

    /**
     * Exports mastered or extracted audio into Music/ArArMedia/.
     */
    suspend fun exportAudio(
        pcmSamples: FloatArray,
        sampleRate: Int = 44100,
        format: AudioExportFormat = AudioExportFormat.LOSSLESS_WAV,
        onProgress: (Float) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val ext = if (format == AudioExportFormat.LOSSLESS_WAV) "wav" else "m4a"
        val fileName = "ArArMedia_Master_$timeStamp.$ext"

        val tempFile = File(context.cacheDir, fileName)
        if (format == AudioExportFormat.LOSSLESS_WAV) {
            DemoMediaGenerator.savePcmToWavFile(pcmSamples, sampleRate, tempFile)
            onProgress(1.0f)
        } else {
            encodePcmToAacM4a(pcmSamples, sampleRate, tempFile, onProgress)
        }

        saveToMusicMediaStore(context, tempFile, fileName, format)
    }

    private fun encodePcmToAacM4a(
        pcm: FloatArray,
        sampleRate: Int,
        outputFile: File,
        onProgress: (Float) -> Unit
    ) {
        val encoded = encodePcmToAacPackets(pcm, sampleRate)
        if (encoded.outputFormat == null) return

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            val trackIndex = muxer.addTrack(encoded.outputFormat)
            muxer.start()
            muxerStarted = true

            val bufferInfo = MediaCodec.BufferInfo()
            val total = max(1, encoded.packets.size)
            encoded.packets.forEachIndexed { idx, pkt ->
                val byteBuf = ByteBuffer.wrap(pkt.bytes)
                bufferInfo.set(0, pkt.bytes.size, pkt.ptsUs, pkt.flags)
                muxer.writeSampleData(trackIndex, byteBuf, bufferInfo)
                onProgress((idx + 1).toFloat() / total)
            }
        } finally {
            try {
                if (muxerStarted) muxer.stop()
                muxer.release()
            } catch (_: Exception) {}
        }
        onProgress(1.0f)
    }

    private fun saveToMoviesMediaStore(context: Context, file: File, fileName: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ArArMedia")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { os ->
                file.inputStream().use { it.copyTo(os) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return "Saved to Movies/ArArMedia/$fileName"
        }
        return file.absolutePath
    }

    private fun saveToMusicMediaStore(
        context: Context,
        file: File,
        fileName: String,
        format: AudioExportFormat
    ): String {
        val resolver = context.contentResolver
        val mime = if (format == AudioExportFormat.LOSSLESS_WAV) "audio/wav" else "audio/mp4"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/ArArMedia")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { os ->
                file.inputStream().use { it.copyTo(os) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return "Saved to Music/ArArMedia/$fileName"
        }
        return file.absolutePath
    }
}
