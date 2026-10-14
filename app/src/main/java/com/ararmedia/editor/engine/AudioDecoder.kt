package com.ararmedia.editor.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

data class DecodedAudio(
    val fileName: String,
    val samples: FloatArray,
    val sampleRate: Int,
    val durationMs: Long,
    val cachedFilePath: String
)

object AudioDecoder {

    /**
     * Decodes any audio file (MP3, WAV, AAC, M4A, FLAC, OGG) from an Android content URI
     * into a high-precision FloatArray PCM buffer using offline hardware MediaCodec.
     */
    suspend fun decodeUri(context: Context, uri: Uri): DecodedAudio = withContext(Dispatchers.IO) {
        // 1. Resolve Display Name
        var fileName = "Imported_Audio_${System.currentTimeMillis()}"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex) ?: fileName
                }
            }
        } catch (_: Exception) {}

        // 2. Cache file locally for fast random access
        val cacheFile = File(context.cacheDir, "import_${System.currentTimeMillis()}_$fileName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        }

        // 3. Extract and Decode using MediaExtractor + MediaCodec
        val extractor = MediaExtractor()
        extractor.setDataSource(cacheFile.absolutePath)

        var audioTrackIndex = -1
        var audioFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                audioFormat = format
                break
            }
        }

        if (audioTrackIndex == -1 || audioFormat == null) {
            extractor.release()
            return@withContext DecodedAudio(
                fileName = fileName,
                samples = FloatArray(0),
                sampleRate = 44100,
                durationMs = 0L,
                cachedFilePath = cacheFile.absolutePath
            )
        }

        extractor.selectTrack(audioTrackIndex)
        val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else 44100
        val channelCount = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else 1

        val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
            audioFormat.getLong(MediaFormat.KEY_DURATION)
        } else 0L
        val durationMs = max(1000L, durationUs / 1000L)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(audioFormat, null, null, 0)
        codec.start()

        val pcmFloats = mutableListOf<Float>()
        val bufferInfo = MediaCodec.BufferInfo()
        var isEos = false

        try {
            while (!isEos) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuffer = codec.getInputBuffer(inIndex)
                    inBuffer?.clear()
                    val sampleSize = inBuffer?.let { extractor.readSampleData(it, 0) } ?: -1
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        val pts = extractor.sampleTime
                        codec.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                        extractor.advance()
                    }
                }

                var outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                while (outIndex >= 0) {
                    val outBuffer = codec.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val shortBuffer = outBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val numShorts = bufferInfo.size / 2

                        // Downmix to mono if stereo/multi-channel
                        if (channelCount > 1) {
                            var i = 0
                            while (i < numShorts) {
                                var sum = 0f
                                for (ch in 0 until channelCount) {
                                    if (i + ch < numShorts) {
                                        sum += shortBuffer.get(i + ch) / 32768.0f
                                    }
                                }
                                pcmFloats.add(sum / channelCount)
                                i += channelCount
                            }
                        } else {
                            for (i in 0 until numShorts) {
                                pcmFloats.add(shortBuffer.get(i) / 32768.0f)
                            }
                        }
                    }

                    codec.releaseOutputBuffer(outIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        isEos = true
                        break
                    }
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }
        } finally {
            try {
                codec.stop()
                codec.release()
            } catch (_: Exception) {}
            extractor.release()
        }

        val finalSamples = pcmFloats.toFloatArray()
        val actualDurationMs = if (finalSamples.isNotEmpty()) {
            (finalSamples.size.toLong() * 1000L) / sampleRate
        } else durationMs

        DecodedAudio(
            fileName = fileName,
            samples = finalSamples,
            sampleRate = sampleRate,
            durationMs = actualDurationMs,
            cachedFilePath = cacheFile.absolutePath
        )
    }
}
