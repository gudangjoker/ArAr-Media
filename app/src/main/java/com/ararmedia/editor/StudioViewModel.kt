package com.ararmedia.editor

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ararmedia.editor.engine.*
import com.ararmedia.editor.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import kotlin.math.max

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    val timelineEngine = VideoTimelineEngine(application)
    val dspProcessor = AudioDspProcessor(sampleRate = 44100)
    val exportEngine = MediaExportEngine(application)
    val captureEngine = AudioCaptureEngine(application, sampleRate = 44100)

    // Navigation Tab: 0 = Home, 1 = Video Studio, 2 = Audio DAW
    private val _currentScreen = MutableStateFlow(0)
    val currentScreen: StateFlow<Int> = _currentScreen.asStateFlow()

    // --- VIDEO STUDIO STATE ---
    private val _timelineClips = MutableStateFlow<List<TimelineClip>>(emptyList())
    val timelineClips: StateFlow<List<TimelineClip>> = _timelineClips.asStateFlow()

    private val _selectedClipId = MutableStateFlow<String?>(null)
    val selectedClipId: StateFlow<String?> = _selectedClipId.asStateFlow()

    private val _playheadMs = MutableStateFlow(0L)
    val playheadMs: StateFlow<Long> = _playheadMs.asStateFlow()

    private val _isPlayingVideo = MutableStateFlow(false)
    val isPlayingVideo: StateFlow<Boolean> = _isPlayingVideo.asStateFlow()

    private val _currentFrameBitmap = MutableStateFlow<Bitmap?>(null)
    val currentFrameBitmap: StateFlow<Bitmap?> = _currentFrameBitmap.asStateFlow()

    private val _aspectRatio = MutableStateFlow(AspectRatio.TIKTOK_9_16)
    val aspectRatio: StateFlow<AspectRatio> = _aspectRatio.asStateFlow()

    private val _beatMarkers = MutableStateFlow<List<BeatMarker>>(emptyList())
    val beatMarkers: StateFlow<List<BeatMarker>> = _beatMarkers.asStateFlow()

    private val _detectedBpm = MutableStateFlow(120f)
    val detectedBpm: StateFlow<Float> = _detectedBpm.asStateFlow()

    private val _subtitles = MutableStateFlow<List<SubtitleEntry>>(emptyList())
    val subtitles: StateFlow<List<SubtitleEntry>> = _subtitles.asStateFlow()

    // Undo / Redo Stacks
    private val undoStack = mutableListOf<List<TimelineClip>>()
    private val redoStack = mutableListOf<List<TimelineClip>>()

    // Video Playback loop job
    private var videoPlaybackJob: Job? = null

    // --- AUDIO DAW STATE ---
    private val _audioSelection = MutableStateFlow(WaveformSelection())
    val audioSelection: StateFlow<WaveformSelection> = _audioSelection.asStateFlow()

    private val _audioBufferData = MutableStateFlow<FloatArray>(FloatArray(0))
    val audioBufferData: StateFlow<FloatArray> = _audioBufferData.asStateFlow()

    private val _stemSplitResult = MutableStateFlow<StemSplitResult?>(null)
    val stemSplitResult: StateFlow<StemSplitResult?> = _stemSplitResult.asStateFlow()

    private val _vocalFader = MutableStateFlow(1.0f)
    val vocalFader: StateFlow<Float> = _vocalFader.asStateFlow()

    private val _backingFader = MutableStateFlow(1.0f)
    val backingFader: StateFlow<Float> = _backingFader.asStateFlow()

    val gainReductionDb: StateFlow<Float> = dspProcessor.gainReductionDb
    val isDawPlaying: StateFlow<Boolean> = dspProcessor.isPlaying
    val dawPlaybackMs: StateFlow<Long> = dspProcessor.playbackPositionMs
    val isRecordingMic: StateFlow<Boolean> = captureEngine.isRecording
    val micRmsLevel: StateFlow<Float> = captureEngine.currentRmsLevel

    // --- EXPORT STATE ---
    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _exportProgress = MutableStateFlow(0f)
    val exportProgress: StateFlow<Float> = _exportProgress.asStateFlow()

    private val _exportMessage = MutableStateFlow<String?>(null)
    val exportMessage: StateFlow<String?> = _exportMessage.asStateFlow()

    init {
        // Automatically initialize synthetic media so the user has immediate 1-tap testing
        loadDemoProject()
    }

    override fun onCleared() {
        super.onCleared()
        dspProcessor.release()
        captureEngine.stopRecording()
    }

    fun navigateTo(screenIndex: Int) {
        _currentScreen.value = screenIndex
    }

    fun loadDemoProject() {
        viewModelScope.launch {
            val demoClips = DemoMediaGenerator.createDemoProject(getApplication())
            pushUndoState()
            _timelineClips.value = demoClips
            _selectedClipId.value = demoClips.firstOrNull()?.id

            // Load synthetic audio into DAW
            val synthAudio = DemoMediaGenerator.generateSyntheticAudioBuffer()
            dspProcessor.loadBuffer(synthAudio)
            _audioBufferData.value = synthAudio

            // Run beat detection for yellow markers
            val (bpm, beats) = dspProcessor.detectBeats()
            _detectedBpm.value = bpm
            _beatMarkers.value = beats

            // Run auto-captions
            val captions = dspProcessor.generateAutoCaptions()
            _subtitles.value = captions

            // Render initial preview frame
            updatePreviewFrame(0L)
        }
    }

    private fun pushUndoState() {
        undoStack.add(_timelineClips.value)
        redoStack.clear()
        if (undoStack.size > 25) {
            undoStack.removeAt(0)
        }
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            redoStack.add(_timelineClips.value)
            val prevState = undoStack.removeAt(undoStack.lastIndex)
            _timelineClips.value = prevState
            updatePreviewFrame(_playheadMs.value)
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            undoStack.add(_timelineClips.value)
            val nextState = redoStack.removeAt(redoStack.lastIndex)
            _timelineClips.value = nextState
            updatePreviewFrame(_playheadMs.value)
        }
    }

    // --- VIDEO PLAYBACK & TIMELINE CONTROLS ---

    fun toggleVideoPlay() {
        if (_isPlayingVideo.value) {
            pauseVideo()
        } else {
            playVideo()
        }
    }

    fun playVideo() {
        _isPlayingVideo.value = true
        videoPlaybackJob = viewModelScope.launch {
            val frameIntervalMs = 33L // ~30 FPS
            val totalDur = getTotalDurationMs()

            while (isActive && _isPlayingVideo.value) {
                var current = _playheadMs.value + frameIntervalMs
                if (current >= totalDur) {
                    current = 0L
                }
                _playheadMs.value = current
                updatePreviewFrame(current)
                delay(frameIntervalMs)
            }
        }
    }

    fun pauseVideo() {
        _isPlayingVideo.value = false
        videoPlaybackJob?.cancel()
        videoPlaybackJob = null
    }

    fun seekTimeline(timeMs: Long) {
        val total = getTotalDurationMs()
        val clamped = timeMs.coerceIn(0L, max(1000L, total))
        _playheadMs.value = clamped
        updatePreviewFrame(clamped)
    }

    fun selectClip(clipId: String?) {
        _selectedClipId.value = clipId
    }

    fun setAspectRatio(ratio: AspectRatio) {
        _aspectRatio.value = ratio
        timelineEngine.currentAspectRatio = ratio
        updatePreviewFrame(_playheadMs.value)
    }

    fun getTotalDurationMs(): Long {
        var maxMs = 0L
        for (c in _timelineClips.value) {
            val end = c.startOnTimelineMs + c.durationMs
            if (end > maxMs) maxMs = end
        }
        return maxMs
    }

    private fun updatePreviewFrame(timestampMs: Long) {
        viewModelScope.launch {
            val bmp = timelineEngine.renderFrameAt(
                playheadMs = timestampMs,
                clips = _timelineClips.value,
                outputWidth = _aspectRatio.value.width / 2, // Scaled down for 60fps smooth UI canvas
                outputHeight = _aspectRatio.value.height / 2
            )
            _currentFrameBitmap.value = bmp
        }
    }

    fun splitActiveClip() {
        val selId = _selectedClipId.value ?: return
        pushUndoState()
        val updated = timelineEngine.splitClipAtPlayhead(_timelineClips.value, selId, _playheadMs.value)
        _timelineClips.value = updated
        updatePreviewFrame(_playheadMs.value)
    }

    fun deleteActiveClip() {
        val selId = _selectedClipId.value ?: return
        pushUndoState()
        _timelineClips.value = _timelineClips.value.filter { it.id != selId }
        _selectedClipId.value = null
        updatePreviewFrame(_playheadMs.value)
    }

    fun duplicateActiveClip() {
        val selId = _selectedClipId.value ?: return
        val clip = _timelineClips.value.firstOrNull { it.id == selId } ?: return
        pushUndoState()
        val duplicated = clip.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = "${clip.name} (Copy)",
            startOnTimelineMs = clip.startOnTimelineMs + clip.durationMs
        )
        _timelineClips.value = _timelineClips.value + duplicated
        _selectedClipId.value = duplicated.id
        updatePreviewFrame(_playheadMs.value)
    }

    fun setClipSpeed(speed: Float) {
        val selId = _selectedClipId.value ?: return
        pushUndoState()
        _timelineClips.value = _timelineClips.value.map {
            if (it.id == selId) {
                val newDuration = ((it.sourceDurationMs).toFloat() / speed).toLong()
                it.copy(speed = speed, durationMs = max(100L, newDuration))
            } else it
        }
        updatePreviewFrame(_playheadMs.value)
    }

    fun toggleClipReverse() {
        val selId = _selectedClipId.value ?: return
        pushUndoState()
        _timelineClips.value = _timelineClips.value.map {
            if (it.id == selId) it.copy(isReversed = !it.isReversed) else it
        }
    }

    fun freezeFrameAtPlayhead() {
        val selId = _selectedClipId.value ?: return
        val currentBmp = _currentFrameBitmap.value ?: return
        pushUndoState()

        val freezeDuration = 3000L
        val freezeClip = TimelineClip(
            name = "Freeze Frame",
            trackType = TrackType.MAIN_VIDEO,
            startOnTimelineMs = _playheadMs.value,
            durationMs = freezeDuration,
            thumbnailBitmap = currentBmp.copy(Bitmap.Config.ARGB_8888, false),
            isFreezeFrame = true
        )

        // Shift subsequent clips
        val shifted = _timelineClips.value.map {
            if (it.startOnTimelineMs >= _playheadMs.value) {
                it.copy(startOnTimelineMs = it.startOnTimelineMs + freezeDuration)
            } else it
        }
        _timelineClips.value = shifted + freezeClip
        updatePreviewFrame(_playheadMs.value)
    }

    fun addKeyframeAtPlayhead() {
        val selId = _selectedClipId.value ?: return
        pushUndoState()
        _timelineClips.value = _timelineClips.value.map { clip ->
            if (clip.id == selId) {
                val newKf = Keyframe(timestampMs = _playheadMs.value, scale = 1.15f, rotationDegrees = 5f)
                val updatedKfs = (clip.keyframes + newKf).sortedBy { it.timestampMs }
                clip.copy(keyframes = updatedKfs)
            } else clip
        }
        updatePreviewFrame(_playheadMs.value)
    }

    fun updateColorGrading(grading: ColorGrading) {
        val selId = _selectedClipId.value ?: return
        _timelineClips.value = _timelineClips.value.map {
            if (it.id == selId) it.copy(colorGrading = grading) else it
        }
        updatePreviewFrame(_playheadMs.value)
    }

    fun updateChromaKey(chroma: ChromaKeySettings) {
        val selId = _selectedClipId.value ?: return
        _timelineClips.value = _timelineClips.value.map {
            if (it.id == selId) it.copy(chromaKey = chroma) else it
        }
        updatePreviewFrame(_playheadMs.value)
    }

    fun setTransition(transition: TransitionType) {
        val selId = _selectedClipId.value ?: return
        _timelineClips.value = _timelineClips.value.map {
            if (it.id == selId) it.copy(transitionIn = transition) else it
        }
        updatePreviewFrame(_playheadMs.value)
    }

    fun extractAudioFromActiveClip() {
        val selId = _selectedClipId.value ?: return
        val clip = _timelineClips.value.firstOrNull { it.id == selId } ?: return
        pushUndoState()

        val extractedAudioClip = TimelineClip(
            name = "Audio (${clip.name})",
            trackType = TrackType.MUSIC_BGM,
            startOnTimelineMs = clip.startOnTimelineMs,
            durationMs = clip.durationMs,
            localUri = clip.localUri,
            volume = 1.0f
        )
        _timelineClips.value = _timelineClips.value + extractedAudioClip
    }

    fun startLiveVoiceover() {
        captureEngine.startRecording { buffer, file ->
            val voClip = TimelineClip(
                name = "Live Mic Take",
                trackType = TrackType.VOICEOVER,
                startOnTimelineMs = _playheadMs.value,
                durationMs = (buffer.size * 1000L) / 44100,
                localUri = file.absolutePath,
                volume = 1.0f
            )
            _timelineClips.value = _timelineClips.value + voClip
        }
    }

    fun stopLiveVoiceover() {
        captureEngine.stopRecording()
    }

    // --- AUDIO DAW CONTROLS ---

    fun toggleDawPlayback() {
        if (dspProcessor.isPlaying.value) {
            dspProcessor.stopPlayback()
        } else {
            dspProcessor.startPlayback()
        }
    }

    fun seekDaw(timeMs: Long) {
        dspProcessor.seekTo(timeMs)
    }

    fun setAudioSelection(startMs: Long, endMs: Long) {
        _audioSelection.value = WaveformSelection(startMs = startMs, endMs = endMs, isActive = true)
    }

    fun clearAudioSelection() {
        _audioSelection.value = WaveformSelection()
    }

    fun trimAudioSelection() {
        val sel = _audioSelection.value
        if (!sel.isActive || sel.endMs <= sel.startMs) return
        val trimmed = dspProcessor.trimSelection(sel.startMs, sel.endMs)
        _audioBufferData.value = trimmed
        clearAudioSelection()
    }

    fun cutMiddleAudio() {
        val sel = _audioSelection.value
        if (!sel.isActive || sel.endMs <= sel.startMs) return
        val cut = dspProcessor.cutMiddleAndStitch(sel.startMs, sel.endMs)
        _audioBufferData.value = cut
        clearAudioSelection()
    }

    fun silenceAudioSelection() {
        val sel = _audioSelection.value
        if (!sel.isActive || sel.endMs <= sel.startMs) return
        val silenced = dspProcessor.silenceRegion(sel.startMs, sel.endMs)
        _audioBufferData.value = silenced.copyOf()
    }

    fun normalizeAudio(peakDb: Float = 0f) {
        val norm = dspProcessor.normalize(peakDb)
        _audioBufferData.value = norm.copyOf()
    }

    fun fadeInAudio() {
        val faded = dspProcessor.fadeIn(1500L)
        _audioBufferData.value = faded.copyOf()
    }

    fun fadeOutAudio() {
        val faded = dspProcessor.fadeOut(1500L)
        _audioBufferData.value = faded.copyOf()
    }

    fun reverseAudio() {
        val rev = dspProcessor.reverseAudio()
        _audioBufferData.value = rev.copyOf()
    }

    fun setEqGain(band: Int, gainDb: Float) {
        dspProcessor.setEqBand(band, gainDb)
    }

    fun setCompressor(thresh: Float, ratio: Float, att: Float, rel: Float, makeup: Float) {
        dspProcessor.setCompressor(thresh, ratio, att, rel, makeup)
    }

    fun setReverb(room: Float, damp: Float, wet: Float, delay: Float) {
        dspProcessor.setReverb(room, damp, wet, delay)
    }

    fun setPitchShift(semitones: Float) {
        dspProcessor.setPitchShift(semitones)
    }

    fun setVoicePreset(presetId: Int) {
        dspProcessor.setVoicePreset(presetId)
    }

    fun applyAiDenoise() {
        viewModelScope.launch {
            val denoised = dspProcessor.applyAiStudioDenoise(18f)
            _audioBufferData.value = denoised.copyOf()
        }
    }

    fun runStemSeparation() {
        viewModelScope.launch {
            val result = dspProcessor.splitStems()
            _stemSplitResult.value = result
        }
    }

    fun setVocalStemFader(vol: Float) {
        _vocalFader.value = vol
        mixStems()
    }

    fun setBackingStemFader(vol: Float) {
        _backingFader.value = vol
        mixStems()
    }

    private fun mixStems() {
        val stems = _stemSplitResult.value ?: return
        val voc = stems.vocalBuffer
        val back = stems.backingBuffer
        val vVol = _vocalFader.value
        val bVol = _backingFader.value
        val mixed = FloatArray(stems.numFrames)
        for (i in 0 until stems.numFrames) {
            mixed[i] = (voc[i] * vVol + back[i] * bVol).coerceIn(-1.0f, 1.0f)
        }
        dspProcessor.loadBuffer(mixed)
        _audioBufferData.value = mixed
    }

    // --- MEDIA EXPORTS ---

    fun exportVideo(
        res: VideoResolution = VideoResolution.FHD_1080P,
        fps: ExportFrameRate = ExportFrameRate.FPS_30,
        quality: BitrateQuality = BitrateQuality.RECOMMENDED
    ) {
        if (_isExporting.value) return
        _isExporting.value = true
        _exportProgress.value = 0f
        _exportMessage.value = "Encoding Video (${res.label}, ${fps.fps} FPS)..."

        viewModelScope.launch {
            try {
                val outputLocation = exportEngine.exportVideo(
                    clips = _timelineClips.value,
                    timelineEngine = timelineEngine,
                    resolution = res,
                    frameRate = fps,
                    bitrateQuality = quality
                ) { progress ->
                    _exportProgress.value = progress
                }
                _exportMessage.value = "Success! $outputLocation"
            } catch (e: Exception) {
                _exportMessage.value = "Export error: ${e.message}"
            } finally {
                delay(3000)
                _isExporting.value = false
            }
        }
    }

    fun exportAudio(format: AudioExportFormat = AudioExportFormat.LOSSLESS_WAV) {
        if (_isExporting.value) return
        _isExporting.value = true
        _exportProgress.value = 0f
        _exportMessage.value = "Exporting Master Audio (${if (format == AudioExportFormat.LOSSLESS_WAV) "WAV Lossless" else "M4A AAC"})..."

        viewModelScope.launch {
            try {
                val buffer = dspProcessor.getActiveBuffer()
                val path = exportEngine.exportAudio(buffer, dspProcessor.sampleRate, format) {
                    _exportProgress.value = it
                }
                _exportMessage.value = "Success! $path"
            } catch (e: Exception) {
                _exportMessage.value = "Export error: ${e.message}"
            } finally {
                delay(3000)
                _isExporting.value = false
            }
        }
    }

    fun dismissExportDialog() {
        _isExporting.value = false
        _exportMessage.value = null
    }
}
