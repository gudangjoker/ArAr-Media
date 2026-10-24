package com.ararmedia.editor.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class DecodedAudio(
    val fileName: String,
    val samples: FloatArray,
    val stereoSamples: FloatArray = FloatArray(0),
    val waveformPeaks: FloatArray = FloatArray(0),
    val pcmCachePath: String = "",
    val sampleRate: Int = 44100,
    val durationMs: Long,
    val cachedFilePath: String
)

/**
 * Primitive growing FloatArray builder to avoid boxing millions of Float objects on the heap.
 */
private class PrimitiveFloatBuilder(initialCapacity: Int = 44100 * 15) {
    var data = FloatArray(initialCapacity)
        private set
    var size = 0
        private set

    fun add(value: Float) {
        if (size == data.size) {
            val newCap = max(data.size * 2, size + 44100 * 10)
            data = Arrays.copyOf(data, newCap)
        }
        data[size++] = value
    }

    fun toFloatArray(): FloatArray = Arrays.copyOf(data, size)
}

object AudioDecoder {

    /**
     * Computes a compact 512-point peak envelope from a mono PCM buffer for zero-lag timeline waveform drawing.
     */
    fun computeWaveformPeaks(samples: FloatArray, numBuckets: Int = 512): FloatArray {
        if (samples.isEmpty() || numBuckets <= 0) return FloatArray(0)
        val out = FloatArray(numBuckets)
        val bucketSize = max(1, samples.size / numBuckets)
        for (b in 0 until numBuckets) {
            val start = b * bucketSize
            val end = min(samples.size, start + bucketSize)
            var peak = 0f
            val step = max(1, (end - start) / 16)
            var i = start
            while (i < end) {
                val a = abs(samples[i])
                if (a > peak) peak = a
                i += step
            }
            out[b] = peak.coerceIn(0.02f, 1.0f)
        }
        return out
    }

    /**
     * Writes a mono FloatArray PCM buffer to a 16-bit Little-Endian disk cache file (.pcm16)
     * so long tracks can be streamed from disk with minimal heap footprint.
     */
    fun writePcmCacheFile(targetFile: File, samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        return try {
            BufferedOutputStream(FileOutputStream(targetFile), 65536).use { bos ->
                val chunkBytes = ByteArray(8192)
                val bb = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
                var idx = 0
                while (idx < samples.size) {
                    bb.clear()
                    val count = min(4096, samples.size - idx)
                    for (i in 0 until count) {
                        val s = (samples[idx + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        bb.putShort(s)
                    }
                    bos.write(chunkBytes, 0, count * 2)
                    idx += count
                }
            }
            targetFile.absolutePath
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Reads a window of mono PCM samples from a disk-backed .pcm16 cache file into [outBuffer].
     */
    fun readPcmWindowFromDisk(
        pcmCachePath: String,
        startSample: Int,
        count: Int,
        outBuffer: FloatArray
    ): Int {
        if (pcmCachePath.isEmpty() || count <= 0) return 0
        val file = File(pcmCachePath)
        if (!file.exists()) return 0
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val totalSamplesInFile = (raf.length() / 2L).toInt()
                if (startSample >= totalSamplesInFile) return 0
                val safeStart = startSample.coerceAtLeast(0)
                val toRead = min(count, totalSamplesInFile - safeStart)
                raf.seek(safeStart.toLong() * 2L)
                val byteBuf = ByteArray(toRead * 2)
                val bytesRead = raf.read(byteBuf)
                if (bytesRead <= 0) return 0
                val shortsRead = bytesRead / 2
                val bb = ByteBuffer.wrap(byteBuf, 0, shortsRead * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until shortsRead) {
                    outBuffer[i] = (bb.short / 32768.0f).coerceIn(-1f, 1f)
                }
                shortsRead
            }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Decodes any audio or video file (WAV, MP3, AAC, M4A, MP4, FLAC, OGG) from an Android content URI
     * or local File into a normalized 44.1kHz PCM buffer + Disk-Backed .pcm16 cache + 512-point Waveform Peaks.
     */
    suspend fun decodeUri(context: Context, uri: Uri): DecodedAudio = withContext(Dispatchers.IO) {
        var fileName = "Imported_Media_${System.currentTimeMillis()}"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex) ?: fileName
                }
            }
        } catch (_: Exception) {}

        // Persist read URI permission if possible for project auto-save reload
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}

        val cacheFile = File(
            context.cacheDir,
            "import_${System.currentTimeMillis()}_${fileName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")}"
        )
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        }

        decodeFile(cacheFile, fileName)
    }

    suspend fun decodeFile(file: File, displayFileName: String = file.name): DecodedAudio = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() < 44) {
            return@withContext DecodedAudio(
                fileName = displayFileName,
                samples = FloatArray(0),
                sampleRate = 44100,
                durationMs = 0L,
                cachedFilePath = file.absolutePath
            )
        }

        // 1. Fast-path native RIFF WAV parser (handles 8/16/24/32-bit PCM & IEEE Float WAV)
        val wavResult = tryParseRiffWav(file, displayFileName)
        if (wavResult != null && wavResult.samples.isNotEmpty()) {
            return@withContext wavResult
        }

        // 2. Hardware MediaExtractor + MediaCodec decoder for MP3, AAC, M4A, OGG, MP4 video audio tracks
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
        } catch (_: Exception) {
            return@withContext DecodedAudio(
                fileName = displayFileName,
                samples = FloatArray(0),
                sampleRate = 44100,
                durationMs = 0L,
                cachedFilePath = file.absolutePath
            )
        }

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
                fileName = displayFileName,
                samples = FloatArray(0),
                sampleRate = 44100,
                durationMs = 0L,
                cachedFilePath = file.absolutePath
            )
        }

        extractor.selectTrack(audioTrackIndex)
        val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
        val sourceSampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else 44100
        val channelCount = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else 1

        val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
            audioFormat.getLong(MediaFormat.KEY_DURATION)
        } else 0L
        val durationMs = max(500L, durationUs / 1000L)

        if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
            extractor.release()
            return@withContext tryParseRiffWav(file, displayFileName) ?: DecodedAudio(
                fileName = displayFileName,
                samples = FloatArray(0),
                sampleRate = 44100,
                durationMs = durationMs,
                cachedFilePath = file.absolutePath
            )
        }

        val estimatedSamples = max(44100 * 10, ((durationMs * sourceSampleRate) / 1000L).toInt())
        val pcmMono = PrimitiveFloatBuilder(min(estimatedSamples, 44100 * 180))
        // Keep stereo buffer up to 6 minutes for 2-stem separation without heap overflow
        val maxStereoFrames = 44100 * 360
        val pcmStereo = PrimitiveFloatBuilder(min(estimatedSamples * 2, 44100 * 120))
        val bufferInfo = MediaCodec.BufferInfo()
        var codec: MediaCodec? = null

        try {
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            var isInputEos = false
            var isOutputEos = false

            while (!isOutputEos) {
                if (!isInputEos) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuffer = codec.getInputBuffer(inIndex)
                        inBuffer?.clear()
                        val sampleSize = inBuffer?.let { extractor.readSampleData(it, 0) } ?: -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isInputEos = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
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

                        if (channelCount >= 2) {
                            var i = 0
                            while (i + 1 < numShorts) {
                                val left = (shortBuffer.get(i) / 32768.0f).coerceIn(-1f, 1f)
                                val right = (shortBuffer.get(i + 1) / 32768.0f).coerceIn(-1f, 1f)
                                pcmMono.add((left + right) * 0.5f)
                                if (pcmStereo.size < maxStereoFrames * 2) {
                                    pcmStereo.add(left)
                                    pcmStereo.add(right)
                                }
                                i += channelCount
                            }
                        } else {
                            for (i in 0 until numShorts) {
                                val s = (shortBuffer.get(i) / 32768.0f).coerceIn(-1f, 1f)
                                pcmMono.add(s)
                                if (pcmStereo.size < maxStereoFrames * 2) {
                                    pcmStereo.add(s)
                                    pcmStereo.add(s)
                                }
                            }
                        }
                    }

                    codec.releaseOutputBuffer(outIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        isOutputEos = true
                        break
                    }
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {}
            try {
                extractor.release()
            } catch (_: Exception) {}
        }

        val rawMono = pcmMono.toFloatArray()
        val rawStereo = pcmStereo.toFloatArray()

        val targetRate = 44100
        val finalMono = if (sourceSampleRate != targetRate && rawMono.isNotEmpty()) {
            resampleMono(rawMono, sourceSampleRate, targetRate)
        } else rawMono

        val finalStereo = if (sourceSampleRate != targetRate && rawStereo.isNotEmpty()) {
            resampleStereo(rawStereo, sourceSampleRate, targetRate)
        } else rawStereo

        val actualDurationMs = if (finalMono.isNotEmpty()) {
            (finalMono.size.toLong() * 1000L) / targetRate
        } else durationMs

        val pcmCacheFile = File(file.parentFile, "${file.name}.pcm16")
        val pcmCachePath = writePcmCacheFile(pcmCacheFile, finalMono)
        val peaks = computeWaveformPeaks(finalMono, 512)

        DecodedAudio(
            fileName = displayFileName,
            samples = finalMono,
            stereoSamples = finalStereo,
            waveformPeaks = peaks,
            pcmCachePath = pcmCachePath,
            sampleRate = targetRate,
            durationMs = actualDurationMs,
            cachedFilePath = file.absolutePath
        )
    }

    private fun tryParseRiffWav(file: File, displayFileName: String): DecodedAudio? {
        try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(12)
                if (fis.read(header) < 12) return null
                val riff = String(header, 0, 4, Charsets.US_ASCII)
                val wave = String(header, 8, 4, Charsets.US_ASCII)
                if (riff != "RIFF" || wave != "WAVE") return null

                var audioFormat = 1
                var channels = 1
                var sampleRate = 44100
                var bitsPerSample = 16
                var dataBytes: ByteArray? = null

                val chunkHeader = ByteArray(8)
                while (fis.read(chunkHeader) == 8) {
                    val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                    val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    if (chunkSize < 0) break

                    if (chunkId == "fmt ") {
                        val fmtBytes = ByteArray(chunkSize)
                        fis.read(fmtBytes)
                        val bb = ByteBuffer.wrap(fmtBytes).order(ByteOrder.LITTLE_ENDIAN)
                        audioFormat = bb.short.toInt() and 0xFFFF
                        channels = (bb.short.toInt() and 0xFFFF).coerceAtLeast(1)
                        sampleRate = bb.int
                        bb.int // byteRate
                        bb.short // blockAlign
                        bitsPerSample = (bb.short.toInt() and 0xFFFF)
                    } else if (chunkId == "data") {
                        val safeSize = min(chunkSize.toLong(), file.length()).toInt()
                        dataBytes = ByteArray(safeSize)
                        var totalRead = 0
                        while (totalRead < safeSize) {
                            val r = fis.read(dataBytes, totalRead, safeSize - totalRead)
                            if (r <= 0) break
                            totalRead += r
                        }
                        break
                    } else {
                        fis.skip(chunkSize.toLong())
                    }
                }

                val rawData = dataBytes ?: return null
                val bytesPerSample = max(1, bitsPerSample / 8)
                val frameSize = bytesPerSample * channels
                val numFrames = rawData.size / frameSize
                if (numFrames <= 0) return null

                val mono = FloatArray(numFrames)
                val stereo = FloatArray(numFrames * 2)
                val bb = ByteBuffer.wrap(rawData).order(ByteOrder.LITTLE_ENDIAN)

                for (f in 0 until numFrames) {
                    var sum = 0f
                    var left = 0f
                    var right = 0f
                    for (ch in 0 until channels) {
                        val sampleVal = when {
                            audioFormat == 3 && bitsPerSample == 32 -> bb.float
                            bitsPerSample == 16 -> bb.short / 32768.0f
                            bitsPerSample == 8 -> ((bb.get().toInt() and 0xFF) - 128) / 128.0f
                            bitsPerSample == 24 -> {
                                val b0 = bb.get().toInt() and 0xFF
                                val b1 = bb.get().toInt() and 0xFF
                                val b2 = bb.get().toInt()
                                val s24 = (b2 shl 16) or (b1 shl 8) or b0
                                s24 / 8388608.0f
                            }
                            bitsPerSample == 32 -> bb.int / 2147483648.0f
                            else -> 0f
                        }.coerceIn(-1f, 1f)

                        if (ch == 0) left = sampleVal
                        if (ch == 1) right = sampleVal
                        sum += sampleVal
                    }
                    if (channels == 1) right = left
                    mono[f] = (sum / channels).coerceIn(-1f, 1f)
                    stereo[f * 2] = left
                    stereo[f * 2 + 1] = right
                }

                val targetRate = 44100
                val finalMono = if (sampleRate != targetRate) resampleMono(mono, sampleRate, targetRate) else mono
                val finalStereo = if (sampleRate != targetRate) resampleStereo(stereo, sampleRate, targetRate) else stereo
                val durationMs = (finalMono.size.toLong() * 1000L) / targetRate

                val pcmCacheFile = File(file.parentFile, "${file.name}.pcm16")
                val pcmCachePath = writePcmCacheFile(pcmCacheFile, finalMono)
                val peaks = computeWaveformPeaks(finalMono, 512)

                return DecodedAudio(
                    fileName = displayFileName,
                    samples = finalMono,
                    stereoSamples = finalStereo,
                    waveformPeaks = peaks,
                    pcmCachePath = pcmCachePath,
                    sampleRate = targetRate,
                    durationMs = durationMs,
                    cachedFilePath = file.absolutePath
                )
            }
        } catch (_: Exception) {
            return null
        }
    }

    private fun resampleMono(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate <= 0 || dstRate <= 0 || input.isEmpty()) return input
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outLen = max(1, (input.size / ratio).toInt())
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val idx0 = srcPos.toInt().coerceIn(0, input.size - 1)
            val idx1 = min(idx0 + 1, input.size - 1)
            val frac = (srcPos - idx0).toFloat()
            out[i] = input[idx0] * (1f - frac) + input[idx1] * frac
        }
        return out
    }

    private fun resampleStereo(inputStereo: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        val inFrames = inputStereo.size / 2
        if (srcRate <= 0 || dstRate <= 0 || inFrames <= 0) return inputStereo
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outFrames = max(1, (inFrames / ratio).toInt())
        val out = FloatArray(outFrames * 2)
        for (i in 0 until outFrames) {
            val srcPos = i * ratio
            val idx0 = srcPos.toInt().coerceIn(0, inFrames - 1)
            val idx1 = min(idx0 + 1, inFrames - 1)
            val frac = (srcPos - idx0).toFloat()
            out[i * 2] = inputStereo[idx0 * 2] * (1f - frac) + inputStereo[idx1 * 2] * frac
            out[i * 2 + 1] = inputStereo[idx0 * 2 + 1] * (1f - frac) + inputStereo[idx1 * 2 + 1] * frac
        }
        return out
    }
}
