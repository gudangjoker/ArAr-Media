package com.ararmedia.editor.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.ararmedia.editor.model.BeatMarker
import com.ararmedia.editor.model.SpeedCurvePreset
import com.ararmedia.editor.model.StemSplitResult
import com.ararmedia.editor.model.SubtitleEntry
import com.ararmedia.editor.model.TimelineClip
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

class AudioDspProcessor(val sampleRate: Int = 44100) {

    private var nativeHandle: Long = 0L

    private val _gainReductionDb = MutableStateFlow(0f)
    val gainReductionDb: StateFlow<Float> = _gainReductionDb.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    private val _eqGainsFlow = MutableStateFlow(FloatArray(10) { 0f })
    val eqGainsFlow: StateFlow<FloatArray> = _eqGainsFlow.asStateFlow()

    val eqGains: FloatArray
        get() = _eqGainsFlow.value

    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var playbackStartTimelineMs: Long = 0L

    @Volatile
    private var playbackStartWallMs: Long = 0L

    private var activeBuffer: FloatArray = FloatArray(0)
    private var activeStereoBuffer: FloatArray = FloatArray(0)

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

    fun loadBuffer(buffer: FloatArray, stereoBuffer: FloatArray = FloatArray(0)) {
        activeBuffer = buffer.copyOf()
        activeStereoBuffer = if (stereoBuffer.isNotEmpty()) stereoBuffer.copyOf() else FloatArray(0)
        _playbackPositionMs.value = 0L
    }

    fun getActiveBuffer(): FloatArray = activeBuffer.copyOf()

    /**
     * Master Hardware Audio Clock: returns the exact millisecond currently exiting the speaker DAC.
     * Eliminates A/V drift during Video Studio preview playback.
     */
    fun getCurrentHardwarePlaybackMs(): Long {
        if (!_isPlaying.value) return _playbackPositionMs.value
        val track = audioTrack
        if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            val headFrames = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (headFrames > 0L) {
                val hardwareElapsedMs = (headFrames * 1000L) / sampleRate
                return playbackStartTimelineMs + hardwareElapsedMs
            }
        }
        val wallElapsed = (SystemClock.elapsedRealtime() - playbackStartWallMs).coerceAtLeast(0L)
        return playbackStartTimelineMs + wallElapsed
    }

    /**
     * Renders an audio buffer offline through the C++ DSP Rack (10-Band EQ, Pitch Shift, Voice Preset, Compressor, Reverb).
     */
    fun renderProcessedBuffer(inputPcm: FloatArray = activeBuffer): FloatArray {
        if (inputPcm.isEmpty()) return FloatArray(0)
        if (!MediaNativeBridge.isLoaded() || nativeHandle == 0L) return inputPcm.copyOf()

        val outPcm = FloatArray(inputPcm.size)
        val chunkSize = 4096
        val inChunk = FloatArray(chunkSize)
        val outChunk = FloatArray(chunkSize)

        var offset = 0
        while (offset < inputPcm.size) {
            val count = min(chunkSize, inputPcm.size - offset)
            System.arraycopy(inputPcm, offset, inChunk, 0, count)
            MediaNativeBridge.processAudioPcm(nativeHandle, inChunk, outChunk, count)
            System.arraycopy(outChunk, 0, outPcm, offset, count)
            offset += count
        }
        return outPcm
    }

    /**
     * Mixes a single real-time chunk [chunkSamples] at [timelineStartSample] directly from active clips
     * (supporting both in-memory samples and disk-backed .pcm16 caches) for zero-delay streaming playback.
     */
    fun mixTimelineChunk(
        clips: List<TimelineClip>,
        timelineStartSample: Int,
        chunkSamples: Int,
        outChunk: FloatArray
    ) {
        java.util.Arrays.fill(outChunk, 0, chunkSamples, 0f)
        val timelineEndSample = timelineStartSample + chunkSamples
        var mixedAny = false

        for (clip in clips) {
            if (clip.isMuted || clip.volume <= 0.001f) continue
            val clipStartSample = ((clip.startOnTimelineMs * sampleRate) / 1000L).toInt()
            val clipDurSamples = ((clip.durationMs * sampleRate) / 1000L).toInt()
            val clipEndSample = clipStartSample + clipDurSamples

            if (clipEndSample <= timelineStartSample || clipStartSample >= timelineEndSample) continue

            val overlapStart = max(timelineStartSample, clipStartSample)
            val overlapEnd = min(timelineEndSample, clipEndSample)
            if (overlapEnd <= overlapStart) continue

            val srcOffsetSamples = ((clip.sourceOffsetMs * sampleRate) / 1000L).toInt()
            val speed = clip.speed.coerceIn(0.2f, 4.0f)
            val vol = clip.volume.coerceIn(0f, 2f)

            val memSamples = clip.audioSamples
            if (memSamples != null && memSamples.isNotEmpty()) {
                mixedAny = true
                for (tSample in overlapStart until overlapEnd) {
                    val relSample = tSample - clipStartSample
                    val scaledOffset = mapAudioRelSample(relSample, clipDurSamples, speed, clip.speedCurve)
                    val srcIdx = if (clip.isReversed) {
                        memSamples.size - 1 - (srcOffsetSamples + scaledOffset)
                    } else {
                        srcOffsetSamples + scaledOffset
                    }
                    if (srcIdx in memSamples.indices) {
                        val dstIdx = tSample - timelineStartSample
                        outChunk[dstIdx] = (outChunk[dstIdx] + memSamples[srcIdx] * vol).coerceIn(-1f, 1f)
                    }
                }
            } else if (clip.pcmCachePath.isNotEmpty()) {
                val relFirst = overlapStart - clipStartSample
                val diskStart = srcOffsetSamples + (relFirst * speed).toInt()
                val diskCount = max(chunkSamples, ((overlapEnd - overlapStart) * speed).toInt() + 16)
                val tempDiskBuf = FloatArray(diskCount)
                val readCount = AudioDecoder.readPcmWindowFromDisk(clip.pcmCachePath, diskStart, diskCount, tempDiskBuf)
                if (readCount > 0) {
                    mixedAny = true
                    for (tSample in overlapStart until overlapEnd) {
                        val localIdx = ((tSample - overlapStart) * speed).toInt().coerceIn(0, readCount - 1)
                        val dstIdx = tSample - timelineStartSample
                        outChunk[dstIdx] = (outChunk[dstIdx] + tempDiskBuf[localIdx] * vol).coerceIn(-1f, 1f)
                    }
                }
            }
        }

        if (!mixedAny && activeBuffer.isNotEmpty() && timelineStartSample < activeBuffer.size) {
            val copyCount = min(chunkSamples, activeBuffer.size - timelineStartSample)
            for (i in 0 until copyCount) {
                outChunk[i] = activeBuffer[timelineStartSample + i]
            }
        }
    }

    private fun mapAudioRelSample(
        relSample: Int,
        clipDurationSamples: Int,
        speed: Float,
        curve: SpeedCurvePreset
    ): Int {
        if (curve == SpeedCurvePreset.NORMAL || clipDurationSamples <= 100) {
            return (relSample * speed).toInt()
        }
        val u = (relSample.toDouble() / clipDurationSamples.toDouble()).coerceIn(0.0, 1.0)
        val warpedU = when (curve) {
            SpeedCurvePreset.NORMAL -> u
            SpeedCurvePreset.MONTAGE -> 0.5 + 0.5 * sin((u - 0.5) * PI)
            SpeedCurvePreset.HERO -> u - 0.18 * sin(u * 2.0 * PI)
            SpeedCurvePreset.BULLET -> 0.5 + 0.5 * (2.0 * u - 1.0).pow(3.0)
            SpeedCurvePreset.JUMP_CUT -> (u + 0.08 * sin(u * 6.0 * PI)).coerceIn(0.0, 1.0)
            SpeedCurvePreset.FLASH_IN -> 1.0 - (1.0 - u).pow(2.4)
        }
        return (warpedU * clipDurationSamples * speed).toInt()
    }

    /**
     * Real-time chunked timeline audio streaming: starts AudioTrack immediately with zero pre-mix wait
     * and locks hardware playback head position for A/V sync.
     */
    fun startTimelineStreamingPlayback(
        clipsProvider: () -> List<TimelineClip>,
        startMs: Long,
        totalDurationProvider: () -> Long
    ) {
        stopPlayback()

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val chunkSize = 1024 // ~23.2ms low-latency audio quantum
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBufSize, chunkSize * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        playbackStartTimelineMs = startMs
        playbackStartWallMs = SystemClock.elapsedRealtime()
        _playbackPositionMs.value = startMs

        audioTrack?.setVolume(1.0f)
        audioTrack?.play()
        _isPlaying.value = true

        playbackJob = coroutineScope.launch {
            val rawChunk = FloatArray(chunkSize)
            val dspChunk = FloatArray(chunkSize)
            val shortChunk = ShortArray(chunkSize)

            var currentSample = ((startMs * sampleRate) / 1000L).toInt().coerceAtLeast(0)

            while (isActive && _isPlaying.value) {
                val totalDurMs = max(1000L, totalDurationProvider())
                val totalSamples = ((totalDurMs * sampleRate) / 1000L).toInt()

                if (currentSample >= totalSamples) {
                    // Loop smoothly back to 0ms
                    currentSample = 0
                    playbackStartTimelineMs = 0L
                    playbackStartWallMs = SystemClock.elapsedRealtime()
                    try {
                        audioTrack?.pause()
                        audioTrack?.flush()
                        audioTrack?.play()
                    } catch (_: Exception) {}
                }

                val count = min(chunkSize, max(1, totalSamples - currentSample))
                mixTimelineChunk(clipsProvider(), currentSample, count, rawChunk)

                var grDb = 0f
                if (nativeHandle != 0L) {
                    grDb = MediaNativeBridge.processAudioPcm(nativeHandle, rawChunk, dspChunk, count)
                } else {
                    System.arraycopy(rawChunk, 0, dspChunk, 0, count)
                }
                _gainReductionDb.value = grDb

                for (s in 0 until count) {
                    shortChunk[s] = (dspChunk[s].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
                }

                audioTrack?.write(shortChunk, 0, count)
                currentSample += count
                _playbackPositionMs.value = getCurrentHardwarePlaybackMs()
            }
        }
    }

    /**
     * Mixes all active timeline audio clips into a unified master timeline PCM buffer for MP4 Export.
     */
    fun mixTimelineClipsToMasterPcm(
        clips: List<TimelineClip>,
        totalDurationMs: Long,
        applyMasterDsp: Boolean = true
    ): FloatArray {
        val totalSamples = max(sampleRate, ((totalDurationMs * sampleRate) / 1000L).toInt())
        val mixBuffer = FloatArray(totalSamples)
        val chunkSize = 4096
        val chunkBuf = FloatArray(chunkSize)

        var offset = 0
        while (offset < totalSamples) {
            val count = min(chunkSize, totalSamples - offset)
            mixTimelineChunk(clips, offset, count, chunkBuf)
            System.arraycopy(chunkBuf, 0, mixBuffer, offset, count)
            offset += count
        }

        return if (applyMasterDsp) renderProcessedBuffer(mixBuffer) else mixBuffer
    }

    fun getDurationMs(): Long {
        if (sampleRate <= 0 || activeBuffer.isEmpty()) return 0L
        return (activeBuffer.size.toLong() * 1000L) / sampleRate
    }

    fun setEqBand(bandIndex: Int, gainDb: Float) {
        if (bandIndex in 0..9) {
            val updated = _eqGainsFlow.value.copyOf()
            updated[bandIndex] = gainDb
            _eqGainsFlow.value = updated
            if (nativeHandle != 0L) {
                MediaNativeBridge.setEqBandGain(nativeHandle, bandIndex, gainDb)
            }
        }
    }

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

    fun setReverb(roomSize: Float, damping: Float, wetMix: Float, delayMs: Float) {
        if (nativeHandle != 0L) {
            MediaNativeBridge.setReverb(nativeHandle, roomSize, damping, wetMix, delayMs)
        }
    }

    fun setPitchShift(semitones: Float) {
        if (nativeHandle != 0L) {
            MediaNativeBridge.setPitchShift(nativeHandle, semitones)
        }
    }

    fun setVoicePreset(presetId: Int) {
        if (nativeHandle != 0L) {
            MediaNativeBridge.setVoicePreset(nativeHandle, presetId)
        }
        val presetEq = FloatArray(10) { 0f }
        when (presetId) {
            1 -> { presetEq[1] = 3.5f; presetEq[2] = 4.0f; presetEq[5] = 1.5f; presetEq[8] = -2.0f }
            2 -> { presetEq[2] = -4.0f; presetEq[6] = 4.5f; presetEq[7] = 3.5f }
            3 -> {
                presetEq[0] = -15f; presetEq[1] = -15f; presetEq[2] = -15f
                presetEq[4] = 3.0f; presetEq[5] = 8.0f; presetEq[6] = 5.0f
                presetEq[8] = -14f; presetEq[9] = -14f
            }
            4 -> { presetEq[1] = 2.5f; presetEq[2] = 3.0f; presetEq[6] = 3.5f; presetEq[7] = 2.0f }
            5 -> { presetEq[5] = 3.0f }
            6 -> { presetEq[0] = 6.0f; presetEq[1] = 6.0f; presetEq[2] = 4.0f; presetEq[7] = -5.0f }
        }
        _eqGainsFlow.value = presetEq
    }

    fun startPlayback(startMs: Long = _playbackPositionMs.value) {
        if (activeBuffer.isEmpty()) return
        stopPlayback()

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
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
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBufSize, 2048 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        playbackStartTimelineMs = startMs
        playbackStartWallMs = SystemClock.elapsedRealtime()
        audioTrack?.setVolume(1.0f)
        audioTrack?.play()
        _isPlaying.value = true

        playbackJob = coroutineScope.launch {
            val chunkSize = 1024
            val inChunk = FloatArray(chunkSize)
            val outChunk = FloatArray(chunkSize)
            val shortChunk = ShortArray(chunkSize)

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

                for (s in 0 until samplesToProcess) {
                    shortChunk[s] = (outChunk[s].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
                }

                audioTrack?.write(shortChunk, 0, samplesToProcess)

                currentSample += samplesToProcess
                _playbackPositionMs.value = getCurrentHardwarePlaybackMs()
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
        val clamped = positionMs.coerceAtLeast(0L)
        playbackStartTimelineMs = clamped
        playbackStartWallMs = SystemClock.elapsedRealtime()
        _playbackPositionMs.value = clamped
        if (wasPlaying && activeBuffer.isNotEmpty()) {
            startPlayback(clamped)
        }
    }

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
            val targetGain = 10.0.pow(targetPeakDb / 20.0).toFloat()
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

    suspend fun denoiseBuffer(inputBuffer: FloatArray, reductionDb: Float = 20f): FloatArray = withContext(Dispatchers.Default) {
        if (inputBuffer.isEmpty() || !MediaNativeBridge.isLoaded()) return@withContext inputBuffer
        val outBuf = FloatArray(inputBuffer.size)
        MediaNativeBridge.spectralDenoise(inputBuffer, outBuf, sampleRate, reductionDb)
        outBuf
    }

    suspend fun applyAiStudioDenoise(reductionDb: Float = 20f): FloatArray = withContext(Dispatchers.Default) {
        if (activeBuffer.isEmpty() || !MediaNativeBridge.isLoaded()) return@withContext activeBuffer
        val outBuf = denoiseBuffer(activeBuffer, reductionDb)
        activeBuffer = outBuf
        activeBuffer
    }

    suspend fun enhanceVoiceBuffer(inputBuffer: FloatArray, dereverbStrength: Float = 0.80f): FloatArray = withContext(Dispatchers.Default) {
        if (inputBuffer.isEmpty() || !MediaNativeBridge.isLoaded()) return@withContext inputBuffer
        val outBuf = FloatArray(inputBuffer.size)
        MediaNativeBridge.enhanceVoicePcm(inputBuffer, outBuf, sampleRate, dereverbStrength)
        outBuf
    }

    suspend fun applyAiVoiceEnhancer(dereverbStrength: Float = 0.80f): FloatArray = withContext(Dispatchers.Default) {
        if (activeBuffer.isEmpty() || !MediaNativeBridge.isLoaded()) return@withContext activeBuffer
        val outBuf = enhanceVoiceBuffer(activeBuffer, dereverbStrength)
        activeBuffer = outBuf
        activeBuffer
    }

    suspend fun splitStems(inputMono: FloatArray = activeBuffer, inputStereo: FloatArray = activeStereoBuffer): StemSplitResult = withContext(Dispatchers.Default) {
        val numFrames = inputMono.size
        val vocalOut = FloatArray(numFrames)
        val backingOut = FloatArray(numFrames)

        if (numFrames > 0 && MediaNativeBridge.isLoaded()) {
            val stereoInput = if (inputStereo.size == numFrames * 2) {
                inputStereo
            } else {
                val dualMono = FloatArray(numFrames * 2)
                for (i in 0 until numFrames) {
                    dualMono[i * 2] = inputMono[i]
                    dualMono[i * 2 + 1] = inputMono[i]
                }
                dualMono
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

    suspend fun detectBeats(sourceBuffer: FloatArray = activeBuffer): Pair<Float, List<BeatMarker>> = withContext(Dispatchers.Default) {
        if (sourceBuffer.isEmpty() || !MediaNativeBridge.isLoaded()) {
            return@withContext Pair(120f, emptyList())
        }
        val rawResult = MediaNativeBridge.detectBeats(sourceBuffer, sampleRate)
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

    /**
     * Generates AI Auto-Captions using C++ VAD segmentation + On-Device Neural Speech-to-Text
     * (piping 16kHz PCM directly to Android SpeechRecognizer on API 33+, with acoustic prosody fallback).
     */
    suspend fun generateAutoCaptions(
        sourceBuffer: FloatArray = activeBuffer,
        recognizedLines: List<String> = emptyList(),
        languageCode: String = "id",
        context: Context? = null
    ): List<SubtitleEntry> = withContext(Dispatchers.Default) {
        if (sourceBuffer.isEmpty() || !MediaNativeBridge.isLoaded()) {
            return@withContext emptyList()
        }
        val cadence = MediaNativeBridge.detectSpeechCadence(sourceBuffer, sampleRate)
        val list = mutableListOf<SubtitleEntry>()
        if (cadence != null && cadence.size >= 2) {
            var idx = 0
            for (i in 0 until cadence.size - 1 step 2) {
                val start = cadence[i]
                val end = cadence[i + 1]
                val label = if (idx < recognizedLines.size && recognizedLines[idx].isNotBlank()) {
                    recognizedLines[idx]
                } else {
                    OfflineSpeechTranscriber.transcribeSegmentPcm(
                        context = context,
                        pcm44k = sourceBuffer,
                        sampleRate = sampleRate,
                        startMs = start,
                        endMs = end,
                        languageCode = languageCode,
                        segmentIndex = idx
                    )
                }
                list.add(
                    SubtitleEntry(
                        startMs = start,
                        endMs = end,
                        text = label
                    )
                )
                idx++
            }
        }
        list
    }
}
