package com.ararmedia.editor.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.ararmedia.editor.model.AudioBufferData
import com.ararmedia.editor.model.BeatMarker
import com.ararmedia.editor.model.StemSplitResult
import com.ararmedia.editor.model.SubtitleEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

class AudioDspProcessor(val sampleRate: Int = 44100) {

    private var nativeHandle: Long = 0L

    private val _gainReductionDb = MutableStateFlow(0f)
    val gainReductionDb: StateFlow<Float> = _gainReductionDb.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 10-Band EQ gains
    val eqGains = FloatArray(10) { 0f }

    // Active working buffer
    private var activeBuffer: FloatArray = FloatArray(0)

    init {
        if (MediaNativeBridge.isLoaded()) {
            nativeHandle = MediaNativeBridge.initDspEngine(sampleRate, 1)
        }
    }

    fun release() {
        stopPlayback()
        if (nativeHandle != 0L) {
            MediaNativeBridge.destroyDspEngine(nativeHandle)
            nativeHandle = 0L
        }
        audioTrack?.release()
        audioTrack = null
    }

    fun loadBuffer(buffer: FloatArray) {
        activeBuffer = buffer.copyOf()
        _playbackPositionMs.value = 0L
    }

    fun getActiveBuffer(): FloatArray = activeBuffer.copyOf()

    fun getDurationMs(): Long {
        if (sampleRate <= 0 || activeBuffer.isEmpty()) return 0L
        return (activeBuffer.size.toLong() * 1000L) / sampleRate
    }

    // --- EQ Controls ---
    fun setEqBand(bandIndex: Int, gainDb: Float) {
        if (bandIndex in 0..9) {
            eqGains[bandIndex] = gainDb
            if (nativeHandle != 0L) {
                MediaNativeBridge.setEqBandGain(nativeHandle, bandIndex, gainDb)
            }
        }
    }

    // --- Compressor Controls ---
    fun setCompressor(
        thresholdDb: Float,
        ratio: Float,
        attackMs: Float,
        releaseMs: Float,
        makeupGainDb: Float
    ) {
        if (nativeHandle != 0L) {
            MediaNativeBridge.setCompressor(nativeHandle, thresholdDb, ratio, attackMs, releaseMs, makeupGainDb)
        }
    }

    // --- Reverb Controls ---
    fun setReverb(roomSize: Float, damping: Float, wetMix: Float, delayMs: Float) {
        if (nativeHandle != 0L) {
            MediaNativeBridge.setReverb(nativeHandle, roomSize, damping, wetMix, delayMs)
        }
    }

    // --- Pitch & Voice Preset ---
    fun setPitchShift(semitones: Float) {
        if (nativeHandle != 0L) {
            MediaNativeBridge.setPitchShift(nativeHandle, semitones)
        }
    }

    fun setVoicePreset(presetId: Int) {
        if (nativeHandle != 0L) {
            MediaNativeBridge.setVoicePreset(nativeHandle, presetId)
        }
    }

    // --- Real-Time Streaming Audio Playback (Anti-OOM 4096-sample ring chunks) ---
    fun startPlayback(startMs: Long = _playbackPositionMs.value) {
        if (activeBuffer.isEmpty()) return
        stopPlayback()

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBufSize * 2, 4096 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        _isPlaying.value = true

        playbackJob = coroutineScope.launch {
            val chunkSize = 4096
            val inChunk = FloatArray(chunkSize)
            val outChunk = FloatArray(chunkSize)

            val startSample = min(((startMs * sampleRate) / 1000L).toInt(), activeBuffer.size)
            var currentSample = startSample

            while (isActive && currentSample < activeBuffer.size) {
                val samplesToProcess = min(chunkSize, activeBuffer.size - currentSample)
                System.arraycopy(activeBuffer, currentSample, inChunk, 0, samplesToProcess)

                var grDb = 0f
                if (nativeHandle != 0L) {
                    grDb = MediaNativeBridge.processAudioPcm(nativeHandle, inChunk, outChunk, samplesToProcess)
                } else {
                    System.arraycopy(inChunk, 0, outChunk, 0, samplesToProcess)
                }
                _gainReductionDb.value = grDb

                audioTrack?.write(outChunk, 0, samplesToProcess, AudioTrack.WRITE_BLOCKING)

                currentSample += samplesToProcess
                val currentMs = (currentSample.toLong() * 1000L) / sampleRate
                _playbackPositionMs.value = currentMs
            }

            _isPlaying.value = false
            _gainReductionDb.value = 0f
            if (currentSample >= activeBuffer.size) {
                _playbackPositionMs.value = 0L
            }
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        try {
            audioTrack?.stop()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        _isPlaying.value = false
        _gainReductionDb.value = 0f
    }

    fun seekTo(positionMs: Long) {
        val wasPlaying = _isPlaying.value
        if (wasPlaying) {
            stopPlayback()
        }
        _playbackPositionMs.value = positionMs.coerceIn(0L, getDurationMs())
        if (wasPlaying) {
            startPlayback(_playbackPositionMs.value)
        }
    }

    // --- Destructive & Non-Destructive Waveform Operations ---

    fun trimSelection(startMs: Long, endMs: Long): FloatArray {
        val startSample = ((startMs * sampleRate) / 1000L).toInt().coerceIn(0, activeBuffer.size)
        val endSample = ((endMs * sampleRate) / 1000L).toInt().coerceIn(startSample, activeBuffer.size)
        val newLen = endSample - startSample
        val newBuf = FloatArray(newLen)
        if (newLen > 0) {
            System.arraycopy(activeBuffer, startSample, newBuf, 0, newLen)
        }
        activeBuffer = newBuf
        _playbackPositionMs.value = 0L
        return activeBuffer
    }

    fun cutMiddleAndStitch(startMs: Long, endMs: Long): FloatArray {
        val startSample = ((startMs * sampleRate) / 1000L).toInt().coerceIn(0, activeBuffer.size)
        val endSample = ((endMs * sampleRate) / 1000L).toInt().coerceIn(startSample, activeBuffer.size)
        val cutLen = endSample - startSample
        val newLen = activeBuffer.size - cutLen
        val newBuf = FloatArray(max(0, newLen))

        if (startSample > 0) {
            System.arraycopy(activeBuffer, 0, newBuf, 0, startSample)
        }
        val remaining = activeBuffer.size - endSample
        if (remaining > 0) {
            System.arraycopy(activeBuffer, endSample, newBuf, startSample, remaining)
        }
        activeBuffer = newBuf
        _playbackPositionMs.value = min(_playbackPositionMs.value, getDurationMs())
        return activeBuffer
    }

    fun silenceRegion(startMs: Long, endMs: Long): FloatArray {
        val startSample = ((startMs * sampleRate) / 1000L).toInt().coerceIn(0, activeBuffer.size)
        val endSample = ((endMs * sampleRate) / 1000L).toInt().coerceIn(startSample, activeBuffer.size)
        for (i in startSample until endSample) {
            activeBuffer[i] = 0f
        }
        return activeBuffer
    }

    fun normalize(targetPeakDb: Float = 0f): FloatArray {
        var maxVal = 0f
        for (sample in activeBuffer) {
            val v = abs(sample)
            if (v > maxVal) maxVal = v
        }
        if (maxVal > 1e-5f) {
            val targetGain = Math.pow(10.0, targetPeakDb / 20.0).toFloat()
            val scale = targetGain / maxVal
            for (i in activeBuffer.indices) {
                activeBuffer[i] = (activeBuffer[i] * scale).coerceIn(-1.0f, 1.0f)
            }
        }
        return activeBuffer
    }

    fun fadeIn(durationMs: Long = 1000L): FloatArray {
        val fadeSamples = min(((durationMs * sampleRate) / 1000L).toInt(), activeBuffer.size)
        for (i in 0 until fadeSamples) {
            val progress = i.toFloat() / fadeSamples
            activeBuffer[i] *= progress
        }
        return activeBuffer
    }

    fun fadeOut(durationMs: Long = 1000L): FloatArray {
        val fadeSamples = min(((durationMs * sampleRate) / 1000L).toInt(), activeBuffer.size)
        val startSample = activeBuffer.size - fadeSamples
        for (i in 0 until fadeSamples) {
            val progress = 1.0f - (i.toFloat() / fadeSamples)
            activeBuffer[startSample + i] *= progress
        }
        return activeBuffer
    }

    fun reverseAudio(): FloatArray {
        val len = activeBuffer.size
        for (i in 0 until len / 2) {
            val tmp = activeBuffer[i]
            activeBuffer[i] = activeBuffer[len - 1 - i]
            activeBuffer[len - 1 - i] = tmp
        }
        return activeBuffer
    }

    // --- Offline Audio AI Suite ---

    suspend fun applyAiStudioDenoise(reductionDb: Float = 15f): FloatArray = withContext(Dispatchers.Default) {
        if (activeBuffer.isEmpty() || !MediaNativeBridge.isLoaded()) return@withContext activeBuffer
        val outBuf = FloatArray(activeBuffer.size)
        MediaNativeBridge.spectralDenoise(activeBuffer, outBuf, sampleRate, reductionDb)
        activeBuffer = outBuf
        activeBuffer
    }

    suspend fun splitStems(): StemSplitResult = withContext(Dispatchers.Default) {
        val numFrames = activeBuffer.size
        val vocalOut = FloatArray(numFrames)
        val backingOut = FloatArray(numFrames)

        if (numFrames > 0 && MediaNativeBridge.isLoaded()) {
            // Build pseudo-stereo array for separation
            val stereoInput = FloatArray(numFrames * 2)
            for (i in 0 until numFrames) {
                stereoInput[i * 2] = activeBuffer[i]
                stereoInput[i * 2 + 1] = activeBuffer[i]
            }
            MediaNativeBridge.splitStems(stereoInput, vocalOut, backingOut, numFrames)
        }
        StemSplitResult(
            vocalBuffer = vocalOut,
            backingBuffer = backingOut,
            numFrames = numFrames,
            sampleRate = sampleRate
        )
    }

    suspend fun detectBeats(): Pair<Float, List<BeatMarker>> = withContext(Dispatchers.Default) {
        if (activeBuffer.isEmpty() || !MediaNativeBridge.isLoaded()) {
            return@withContext Pair(120f, emptyList())
        }
        val rawResult = MediaNativeBridge.detectBeats(activeBuffer, sampleRate)
        if (rawResult == null || rawResult.isEmpty()) {
            return@withContext Pair(120f, emptyList())
        }
        val bpm = rawResult[0]
        val markers = mutableListOf<BeatMarker>()
        for (i in 1 until rawResult.size) {
            markers.add(BeatMarker(timestampMs = rawResult[i].toLong(), intensity = 1.0f))
        }
        Pair(bpm, markers)
    }

    suspend fun generateAutoCaptions(): List<SubtitleEntry> = withContext(Dispatchers.Default) {
        if (activeBuffer.isEmpty() || !MediaNativeBridge.isLoaded()) {
            return@withContext emptyList()
        }
        val cadence = MediaNativeBridge.detectSpeechCadence(activeBuffer, sampleRate)
        val list = mutableListOf<SubtitleEntry>()
        if (cadence != null && cadence.size >= 2) {
            var idx = 1
            for (i in 0 until cadence.size - 1 step 2) {
                val start = cadence[i]
                val end = cadence[i + 1]
                list.add(
                    SubtitleEntry(
                        startMs = start,
                        endMs = end,
                        text = "Voice Take #$idx [${(end - start) / 1000.0}s]"
                    )
                )
                idx++
            }
        }
        list
    }
}
