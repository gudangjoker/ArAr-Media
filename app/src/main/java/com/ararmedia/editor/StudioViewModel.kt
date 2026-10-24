package com.ararmedia.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ararmedia.editor.engine.*
import com.ararmedia.editor.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    val timelineEngine = VideoTimelineEngine(application)
    val dspProcessor = AudioDspProcessor(sampleRate = 44100)
    val exportEngine = MediaExportEngine(application)
    val captureEngine = AudioCaptureEngine(application, sampleRate = 44100)

    // Navigation Tab: 0 = Home, 1 = Video Studio, 2 = Audio DAW
    private val _currentScreen = MutableStateFlow(0)
    val currentScreen: StateFlow<Int> = _currentScreen.asStateFlow()

    private val _requestedToolPanel = MutableStateFlow<String?>(null)
    val requestedToolPanel: StateFlow<String?> = _requestedToolPanel.asStateFlow()

    private val _requestedDawTab = MutableStateFlow<Int?>(null)
    val requestedDawTab: StateFlow<Int?> = _requestedDawTab.asStateFlow()

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

    private val _canvasBgMode = MutableStateFlow(CanvasBackgroundMode.BLUR)
    val canvasBgMode: StateFlow<CanvasBackgroundMode> = _canvasBgMode.asStateFlow()

    private val _beatMarkers = MutableStateFlow<List<BeatMarker>>(emptyList())
    val beatMarkers: StateFlow<List<BeatMarker>> = _beatMarkers.asStateFlow()

    private val _detectedBpm = MutableStateFlow(120f)
    val detectedBpm: StateFlow<Float> = _detectedBpm.asStateFlow()

    private val _subtitles = MutableStateFlow<List<SubtitleEntry>>(emptyList())
    val subtitles: StateFlow<List<SubtitleEntry>> = _subtitles.asStateFlow()

    private val _captionLanguage = MutableStateFlow("id")
    val captionLanguage: StateFlow<String> = _captionLanguage.asStateFlow()

    // --- SAVED PROJECTS STATE ---
    private var activeProjectId: String = "project_default"
    private val _savedProjects = MutableStateFlow<List<SavedProjectSummary>>(emptyList())
    val savedProjects: StateFlow<List<SavedProjectSummary>> = _savedProjects.asStateFlow()

    // --- AI STATUS & FEEDBACK ---
    private val _isAiProcessing = MutableStateFlow(false)
    val isAiProcessing: StateFlow<Boolean> = _isAiProcessing.asStateFlow()

    private val _aiStatusMessage = MutableStateFlow<String?>(null)
    val aiStatusMessage: StateFlow<String?> = _aiStatusMessage.asStateFlow()

    // Undo / Redo Stacks
    private val undoStack = mutableListOf<List<TimelineClip>>()
    private val redoStack = mutableListOf<List<TimelineClip>>()

    private var videoPlaybackJob: Job? = null
    private var previewRenderJob: Job? = null
    private var statusToastJob: Job? = null

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

    val eqGainsFlow: StateFlow<FloatArray> = dspProcessor.eqGainsFlow
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
        viewModelScope.launch {
            val existing = ProjectPersistenceManager.listSavedProjects(getApplication())
            _savedProjects.value = existing
            val latest = existing.firstOrNull()
            if (latest != null) {
                val loaded = ProjectPersistenceManager.loadProject(getApplication(), latest.id)
                if (loaded != null && loaded.clips.isNotEmpty()) {
                    activeProjectId = loaded.id
                    _aspectRatio.value = loaded.aspectRatio
                    timelineEngine.currentAspectRatio = loaded.aspectRatio
                    _canvasBgMode.value = loaded.backgroundMode
                    timelineEngine.canvasBackgroundMode = loaded.backgroundMode
                    _timelineClips.value = loaded.clips
                    _selectedClipId.value = loaded.clips.firstOrNull()?.id
                    val firstAudio = loaded.clips.firstOrNull { it.audioSamples != null && it.audioSamples.isNotEmpty() }?.audioSamples
                    if (firstAudio != null) {
                        dspProcessor.loadBuffer(firstAudio)
                        _audioBufferData.value = firstAudio
                    }
                    updatePreviewFrame(0L)
                    return@launch
                }
            }
            loadDemoProject()
        }
    }

    private fun triggerAutoSaveProject() {
        val clipsSnapshot = _timelineClips.value
        if (clipsSnapshot.isEmpty()) return
        val projId = activeProjectId
        val title = clipsSnapshot.firstOrNull { it.trackType == TrackType.MAIN_VIDEO }?.name
            ?: clipsSnapshot.first().name
        val ratio = _aspectRatio.value
        val bg = _canvasBgMode.value
        val dur = getTotalDurationMs()
        viewModelScope.launch {
            ProjectPersistenceManager.saveProject(
                context = getApplication(),
                projectId = projId,
                title = title,
                aspectRatio = ratio,
                backgroundMode = bg,
                clips = clipsSnapshot,
                totalDurationMs = dur
            )
            _savedProjects.value = ProjectPersistenceManager.listSavedProjects(getApplication())
        }
    }

    fun openSavedProject(projectId: String) {
        pauseVideo()
        viewModelScope.launch {
            val loaded = ProjectPersistenceManager.loadProject(getApplication(), projectId) ?: return@launch
            timelineEngine.clearCache()
            activeProjectId = loaded.id
            _aspectRatio.value = loaded.aspectRatio
            timelineEngine.currentAspectRatio = loaded.aspectRatio
            _canvasBgMode.value = loaded.backgroundMode
            timelineEngine.canvasBackgroundMode = loaded.backgroundMode
            _timelineClips.value = loaded.clips
            _selectedClipId.value = loaded.clips.firstOrNull()?.id
            _playheadMs.value = 0L
            updatePreviewFrame(0L)
            navigateTo(1)
        }
    }

    fun deleteSavedProject(projectId: String) {
        viewModelScope.launch {
            ProjectPersistenceManager.deleteProject(getApplication(), projectId)
            _savedProjects.value = ProjectPersistenceManager.listSavedProjects(getApplication())
            showStatusToast("Proyek dihapus")
        }
    }

    fun setCaptionLanguage(langCode: String) {
        _captionLanguage.value = langCode
    }

    override fun onCleared() {
        super.onCleared()
        dspProcessor.release()
        captureEngine.stopRecording()
    }

    fun showStatusToast(message: String) {
        statusToastJob?.cancel()
        _aiStatusMessage.value = message
        statusToastJob = viewModelScope.launch {
            delay(2800)
            _aiStatusMessage.value = null
        }
    }

    fun navigateTo(screenIndex: Int) {
        if (_isPlayingVideo.value && screenIndex != 1) {
            pauseVideo()
        }
        if (dspProcessor.isPlaying.value && screenIndex != 2) {
            dspProcessor.stopPlayback()
        }
        _currentScreen.value = screenIndex
    }

    fun openVideoStudioWithTool(toolPanel: String?) {
        _requestedToolPanel.value = toolPanel
        navigateTo(1)
    }

    fun consumeRequestedToolPanel() {
        _requestedToolPanel.value = null
    }

    fun openAudioDawWithTab(tabIndex: Int) {
        _requestedDawTab.value = tabIndex
        navigateTo(2)
    }

    fun consumeRequestedDawTab() {
        _requestedDawTab.value = null
    }

    fun startNewBlankProject() {
        pauseVideo()
        pushUndoState()
        activeProjectId = "project_${System.currentTimeMillis()}"
        timelineEngine.clearCache()
        _timelineClips.value = emptyList()
        _selectedClipId.value = null
        _playheadMs.value = 0L
        _subtitles.value = emptyList()
        _beatMarkers.value = emptyList()
        updatePreviewFrame(0L)
        navigateTo(1)
    }

    fun loadDemoProject() {
        viewModelScope.launch {
            timelineEngine.clearCache()
            activeProjectId = "project_sample"
            val demoClips = DemoMediaGenerator.createDemoProject(getApplication())
            pushUndoState()
            _timelineClips.value = demoClips
            _selectedClipId.value = demoClips.firstOrNull()?.id

            val synthAudio = DemoMediaGenerator.generateSyntheticAudioBuffer()
            val synthStereo = DemoMediaGenerator.generateSyntheticStereoBuffer()
            dspProcessor.loadBuffer(synthAudio, synthStereo)
            _audioBufferData.value = synthAudio

            val (bpm, beats) = dspProcessor.detectBeats(synthAudio)
            _detectedBpm.value = bpm
            _beatMarkers.value = beats

            val captions = dspProcessor.generateAutoCaptions(
                sourceBuffer = synthAudio,
                languageCode = _captionLanguage.value,
                context = getApplication()
            )
            _subtitles.value = captions

            updatePreviewFrame(0L)
            triggerAutoSaveProject()
        }
    }

    private fun pushUndoState() {
        undoStack.add(_timelineClips.value)
        redoStack.clear()
        if (undoStack.size > 30) {
            undoStack.removeAt(0)
        }
        triggerAutoSaveProject()
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
        val totalDur = max(1000L, getTotalDurationMs())
        if (_playheadMs.value >= totalDur - 50L) {
            _playheadMs.value = 0L
        }

        // Start real-time chunked audio streaming immediately (0ms pre-mix delay)
        dspProcessor.startTimelineStreamingPlayback(
            clipsProvider = { _timelineClips.value },
            startMs = _playheadMs.value,
            totalDurationProvider = { max(1000L, getTotalDurationMs()) }
        )

        videoPlaybackJob?.cancel()
        videoPlaybackJob = viewModelScope.launch {
            while (isActive && _isPlayingVideo.value) {
                val frameStartWall = android.os.SystemClock.elapsedRealtime()
                val currentTotalDur = max(1000L, getTotalDurationMs())

                // Lock video playhead directly to Hardware Audio DAC playback head position (Zero A/V Drift)
                val hardwareAudioMs = dspProcessor.getCurrentHardwarePlaybackMs() % currentTotalDur
                _playheadMs.value = hardwareAudioMs

                val bmp = timelineEngine.renderFrameAt(
                    playheadMs = hardwareAudioMs,
                    clips = _timelineClips.value,
                    outputWidth = (_aspectRatio.value.width * 2) / 5,
                    outputHeight = (_aspectRatio.value.height * 2) / 5
                )
                _currentFrameBitmap.value = bmp

                val renderTookMs = android.os.SystemClock.elapsedRealtime() - frameStartWall
                val sleepMs = (30L - renderTookMs).coerceAtLeast(2L)
                delay(sleepMs)
            }
        }
    }

    fun pauseVideo() {
        _isPlayingVideo.value = false
        videoPlaybackJob?.cancel()
        videoPlaybackJob = null
        dspProcessor.stopPlayback()
        updatePreviewFrame(_playheadMs.value)
    }

    fun seekTimeline(timeMs: Long) {
        val total = getTotalDurationMs()
        val clamped = timeMs.coerceIn(0L, max(1000L, total))
        _playheadMs.value = clamped
        updatePreviewFrame(clamped)
        if (_isPlayingVideo.value) {
            dspProcessor.startTimelineStreamingPlayback(
                clipsProvider = { _timelineClips.value },
                startMs = clamped,
                totalDurationProvider = { max(1000L, getTotalDurationMs()) }
            )
        }
    }

    /**
     * Imports a Video or Image file onto either MAIN_VIDEO or PIP_OVERLAY track.
     */
    fun importVisualMedia(uri: Uri, targetTrack: TrackType = TrackType.MAIN_VIDEO) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val mimeType = context.contentResolver.getType(uri) ?: ""
                var fileName = "Media_${System.currentTimeMillis()}"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIdx) ?: fileName
                    }
                }

                val safeName = fileName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                val cacheFile = File(context.cacheDir, "media_${System.currentTimeMillis()}_$safeName")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val isVideo = mimeType.startsWith("video/") ||
                        safeName.endsWith(".mp4", true) ||
                        safeName.endsWith(".mov", true) ||
                        safeName.endsWith(".mkv", true) ||
                        safeName.endsWith(".webm", true)

                var durationMs = 4000L
                var thumbnailBmp: Bitmap? = null
                var extractedAudio: FloatArray? = null
                var extractedStereo: FloatArray = FloatArray(0)
                var pcmCachePath = ""
                var waveformPeaks = FloatArray(0)

                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}

                if (isVideo) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(cacheFile.absolutePath)
                        val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        durationMs = durStr?.toLongOrNull()?.coerceAtLeast(500L) ?: 5000L
                        val rawThumb = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        if (rawThumb != null) {
                            thumbnailBmp = rawThumb.copy(Bitmap.Config.ARGB_8888, false)
                        }
                    } catch (_: Exception) {
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                    }
                    val decodedAudio = AudioDecoder.decodeFile(cacheFile, fileName)
                    if (decodedAudio.samples.isNotEmpty()) {
                        extractedAudio = decodedAudio.samples
                        extractedStereo = decodedAudio.stereoSamples
                        pcmCachePath = decodedAudio.pcmCachePath
                        waveformPeaks = decodedAudio.waveformPeaks
                    }
                } else {
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    thumbnailBmp = BitmapFactory.decodeFile(cacheFile.absolutePath, opts)
                    durationMs = 4000L
                }

                val startMs = if (targetTrack == TrackType.MAIN_VIDEO) {
                    val lastMainEnd = _timelineClips.value
                        .filter { it.trackType == TrackType.MAIN_VIDEO }
                        .maxOfOrNull { it.startOnTimelineMs + it.durationMs } ?: 0L
                    lastMainEnd
                } else {
                    _playheadMs.value
                }

                val newClip = TimelineClip(
                    name = fileName,
                    trackType = targetTrack,
                    startOnTimelineMs = startMs,
                    durationMs = durationMs,
                    sourceDurationMs = durationMs,
                    isVideoSource = isVideo,
                    localUri = cacheFile.absolutePath,
                    pcmCachePath = pcmCachePath,
                    waveformPeaks = waveformPeaks,
                    thumbnailBitmap = thumbnailBmp,
                    audioSamples = extractedAudio,
                    keyframes = if (targetTrack == TrackType.PIP_OVERLAY) {
                        listOf(Keyframe(timestampMs = startMs, scale = 0.55f, translationY = 180f))
                    } else emptyList()
                )

                if (extractedAudio != null && extractedAudio.isNotEmpty()) {
                    dspProcessor.loadBuffer(extractedAudio, extractedStereo)
                    _audioBufferData.value = extractedAudio
                    val (bpm, beats) = dspProcessor.detectBeats(extractedAudio)
                    _detectedBpm.value = bpm
                    _beatMarkers.value = beats
                }

                withContext(Dispatchers.Main) {
                    pushUndoState()
                    _timelineClips.value = _timelineClips.value + newClip
                    _selectedClipId.value = newClip.id
                    updatePreviewFrame(_playheadMs.value)
                    showStatusToast("Imported $fileName")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Imports an external audio file (MP3, WAV, AAC, M4A, OGG) from device storage.
     */
    fun importAudio(uri: Uri) {
        viewModelScope.launch {
            try {
                val decoded = AudioDecoder.decodeUri(getApplication(), uri)
                if (decoded.samples.isNotEmpty()) {
                    pushUndoState()
                    val newClip = TimelineClip(
                        name = decoded.fileName,
                        trackType = TrackType.MUSIC_BGM,
                        startOnTimelineMs = _playheadMs.value,
                        durationMs = decoded.durationMs,
                        sourceDurationMs = decoded.durationMs,
                        localUri = decoded.cachedFilePath,
                        pcmCachePath = decoded.pcmCachePath,
                        waveformPeaks = decoded.waveformPeaks,
                        audioSamples = decoded.samples,
                        volume = 1.0f
                    )
                    _timelineClips.value = _timelineClips.value + newClip
                    _selectedClipId.value = newClip.id

                    dspProcessor.loadBuffer(decoded.samples, decoded.stereoSamples)
                    _audioBufferData.value = decoded.samples

                    val (bpm, beats) = dspProcessor.detectBeats(decoded.samples)
                    _detectedBpm.value = bpm
                    _beatMarkers.value = beats

                    updatePreviewFrame(_playheadMs.value)
                    showStatusToast("Audio imported • ${bpm.toInt()} BPM")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun addOrUpdateTextClip(
        text: String,
        fontSizeSp: Float = 28f,
        textColor: Color = Color(0xFF00E5FF),
        isKaraoke: Boolean = true,
        showPill: Boolean = true,
        editExistingClipId: String? = null
    ) {
        if (text.isBlank()) return
        pushUndoState()

        if (editExistingClipId != null) {
            _timelineClips.value = _timelineClips.value.map { clip ->
                if (clip.id == editExistingClipId) {
                    clip.copy(
                        name = text.take(18),
                        textContent = text,
                        textStyle = clip.textStyle.copy(
                            fontSizeSp = fontSizeSp,
                            textColor = textColor,
                            isKaraokeHighlighted = isKaraoke,
                            showBackgroundPill = showPill
                        )
                    )
                } else clip
            }
        } else {
            val newTextClip = TimelineClip(
                name = text.take(18),
                trackType = TrackType.TEXT_STICKER,
                startOnTimelineMs = _playheadMs.value,
                durationMs = 3000L,
                textContent = text,
                textStyle = TextStyleSettings(
                    fontSizeSp = fontSizeSp,
                    textColor = textColor,
                    strokeColor = Color.Black,
                    strokeWidth = 3f,
                    isKaraokeHighlighted = isKaraoke,
                    showBackgroundPill = showPill
                )
            )
            _timelineClips.value = _timelineClips.value + newTextClip
            _selectedClipId.value = newTextClip.id
        }
        updatePreviewFrame(_playheadMs.value)
    }

    /**
     * Generates AI Auto-Captions from the active voiceover/video/audio track on the timeline.
     */
    fun generateAutoCaptionsToTimeline(recognizedSpeechLines: List<String> = emptyList()) {
        viewModelScope.launch {
            _isAiProcessing.value = true
            try {
                // Prioritize Voiceover -> Main Video Audio -> BGM -> Active DSP Buffer
                val sourceClip = _timelineClips.value.firstOrNull {
                    it.trackType == TrackType.VOICEOVER && it.audioSamples != null && it.audioSamples.isNotEmpty()
                } ?: _timelineClips.value.firstOrNull {
                    it.trackType == TrackType.MAIN_VIDEO && it.audioSamples != null && it.audioSamples.isNotEmpty()
                } ?: _timelineClips.value.firstOrNull {
                    it.audioSamples != null && it.audioSamples.isNotEmpty()
                }

                val targetBuffer = sourceClip?.audioSamples ?: dspProcessor.getActiveBuffer()
                val clipStartOffset = sourceClip?.startOnTimelineMs ?: 0L

                val captions = dspProcessor.generateAutoCaptions(
                    sourceBuffer = targetBuffer,
                    recognizedLines = recognizedSpeechLines,
                    languageCode = _captionLanguage.value,
                    context = getApplication()
                )
                _subtitles.value = captions
                if (captions.isEmpty()) {
                    showStatusToast("No speech phrases detected in audio")
                    return@launch
                }

                pushUndoState()
                val captionClips = captions.mapIndexed { idx, entry ->
                    TimelineClip(
                        name = entry.text.take(18),
                        trackType = TrackType.TEXT_STICKER,
                        startOnTimelineMs = clipStartOffset + entry.startMs,
                        durationMs = max(500L, entry.endMs - entry.startMs),
                        textContent = entry.text,
                        textStyle = TextStyleSettings(
                            fontSizeSp = 26f,
                            textColor = if (idx % 2 == 0) Color(0xFF00E5FF) else Color(0xFFFFD600),
                            strokeColor = Color.Black,
                            strokeWidth = 3f,
                            isKaraokeHighlighted = true
                        )
                    )
                }
                val filtered = _timelineClips.value.filterNot { it.trackType == TrackType.TEXT_STICKER }
                _timelineClips.value = filtered + captionClips
                _selectedClipId.value = captionClips.firstOrNull()?.id
                updatePreviewFrame(_playheadMs.value)
                showStatusToast("AI Auto-Captions generated (${captionClips.size} segments)")
            } finally {
                _isAiProcessing.value = false
            }
        }
    }

    fun selectClip(clipId: String?) {
        _selectedClipId.value = clipId
        if (clipId != null) {
            val clip = _timelineClips.value.firstOrNull { it.id == clipId }
            if (clip?.audioSamples != null && clip.audioSamples.isNotEmpty()) {
                dspProcessor.loadBuffer(clip.audioSamples)
                _audioBufferData.value = clip.audioSamples
            }
        }
    }

    /**
     * Magnetic snapping helper: snaps timeMs to Playhead or nearest Beat Marker if within 55ms.
     */
    private fun snapTimeMs(rawMs: Long): Long {
        val snapThresholdMs = 55L
        val clamped = rawMs.coerceAtLeast(0L)
        if (abs(clamped - _playheadMs.value) <= snapThresholdMs) {
            return _playheadMs.value
        }
        for (beat in _beatMarkers.value) {
            if (abs(clamped - beat.timestampMs) <= snapThresholdMs) {
                return beat.timestampMs
            }
        }
        return clamped
    }

    fun moveClipStart(clipId: String, newStartMs: Long) {
        val snappedStart = snapTimeMs(newStartMs)
        _timelineClips.value = _timelineClips.value.map {
            if (it.id == clipId) it.copy(startOnTimelineMs = snappedStart) else it
        }
        updatePreviewFrame(_playheadMs.value)
    }

    /**
     * Trims the left (start) edge of a clip on the timeline.
     */
    fun trimClipStart(clipId: String, newStartMs: Long) {
        _timelineClips.value = _timelineClips.value.map { clip ->
            if (clip.id == clipId) {
                val endMs = clip.startOnTimelineMs + clip.durationMs
                val clampedNewStart = snapTimeMs(newStartMs).coerceIn(0L, max(0L, endMs - 300L))
                val deltaMs = clampedNewStart - clip.startOnTimelineMs
                val newDuration = (endMs - clampedNewStart).coerceAtLeast(300L)
                val newSourceOffset = (clip.sourceOffsetMs + (deltaMs * clip.speed).toLong()).coerceAtLeast(0L)
                clip.copy(
                    startOnTimelineMs = clampedNewStart,
                    durationMs = newDuration,
                    sourceOffsetMs = newSourceOffset
                )
            } else clip
        }
        updatePreviewFrame(_playheadMs.value)
    }

    /**
     * Trims the right (end) edge of a clip on the timeline.
     */
    fun trimClipEnd(clipId: String, newDurationMs: Long) {
        _timelineClips.value = _timelineClips.value.map { clip ->
            if (clip.id == clipId) {
                val rawEndMs = clip.startOnTimelineMs + newDurationMs.coerceAtLeast(300L)
                val snappedEndMs = snapTimeMs(rawEndMs)
                val maxDur = if (clip.isVideoSource || clip.audioSamples != null) {
                    max(3000L, ((clip.sourceDurationMs - clip.sourceOffsetMs) / clip.speed).toLong())
                } else 30000L
                val finalDuration = (snappedEndMs - clip.startOnTimelineMs).coerceIn(300L, maxDur)
                clip.copy(durationMs = finalDuration)
            } else clip
        }
        updatePreviewFrame(_playheadMs.value)
    }

    /**
     * Interactive touch gesture manipulation directly on the Video Canvas Preview.
     */
    fun transformSelectedClipOnCanvas(
        panX: Float,
        panY: Float,
        zoomChange: Float,
        rotationChange: Float,
        canvasBoxWidth: Float,
        canvasBoxHeight: Float
    ) {
        val selId = _selectedClipId.value ?: return
        val clip = _timelineClips.value.firstOrNull { it.id == selId } ?: return
        if (canvasBoxWidth <= 10f || canvasBoxHeight <= 10f) return

        if (clip.trackType == TrackType.TEXT_STICKER) {
            val newXRatio = (clip.textStyle.positionXRatio + (panX / canvasBoxWidth)).coerceIn(0.08f, 0.92f)
            val newYRatio = (clip.textStyle.positionYRatio + (panY / canvasBoxHeight)).coerceIn(0.08f, 0.92f)
            val newFontSize = (clip.textStyle.fontSizeSp * zoomChange).coerceIn(12f, 72f)
            _timelineClips.value = _timelineClips.value.map {
                if (it.id == selId) {
                    it.copy(
                        textStyle = it.textStyle.copy(
                            positionXRatio = newXRatio,
                            positionYRatio = newYRatio,
                            fontSizeSp = newFontSize
                        )
                    )
                } else it
            }
        } else {
            val currentKf = timelineEngine.interpolateKeyframe(clip, _playheadMs.value)
            val dx1080 = (panX / canvasBoxWidth) * 1080f
            val dy1920 = (panY / canvasBoxHeight) * 1920f
            val newScale = (currentKf.scale * zoomChange).coerceIn(0.2f, 3.0f)
            val newRot = (currentKf.rotationDegrees + rotationChange) % 360f
            addOrUpdateKeyframeAtPlayhead(
                scale = newScale,
                translationX = (currentKf.translationX + dx1080).coerceIn(-600f, 600f),
                translationY = (currentKf.translationY + dy1920).coerceIn(-900f, 900f),
                rotationDegrees = newRot,
                opacity = currentKf.opacity,
                recordUndo = false
            )
        }
        updatePreviewFrame(_playheadMs.value)
    }

    fun updateClipVolume(volume: Float, isMuted: Boolean) {
        val selId = _selectedClipId.value ?: return
        _timelineClips.value = _timelineClips.value.map {
            if (it.id == selId) it.copy(volume = volume.coerceIn(0f, 2f), isMuted = isMuted) else it
        }
    }

    fun toggleClipStabilization() {
        val selId = _selectedClipId.value ?: return
        pushUndoState()
        var nowStabilized = false
        _timelineClips.value = _timelineClips.value.map {
            if (it.id == selId) {
                nowStabilized = !it.isStabilized
                it.copy(isStabilized = nowStabilized)
            } else it
        }
        updatePreviewFrame(_playheadMs.value)
        showStatusToast(if (nowStabilized) "AI Motion Stabilization Enabled" else "Motion Stabilization Off")
    }

    fun toggleClipMirror() {
        val selId = _selectedClipId.value ?: return
        pushUndoState()
        _timelineClips.value = _timelineClips.value.map {
            if (it.id == selId) it.copy(isMirrored = !it.isMirrored) else it
        }
        updatePreviewFrame(_playheadMs.value)
    }

    fun rotateClip90() {
        val selId = _selectedClipId.value ?: return
        val clip = _timelineClips.value.firstOrNull { it.id == selId } ?: return
        val kf = timelineEngine.interpolateKeyframe(clip, _playheadMs.value)
        addOrUpdateKeyframeAtPlayhead(
            scale = kf.scale,
            translationX = kf.translationX,
            translationY = kf.translationY,
            rotationDegrees = (kf.rotationDegrees + 90f) % 360f,
            opacity = kf.opacity
        )
    }

    fun setAspectRatio(ratio: AspectRatio) {
        _aspectRatio.value = ratio
        timelineEngine.currentAspectRatio = ratio
        updatePreviewFrame(_playheadMs.value)
    }

    fun setCanvasBackgroundMode(mode: CanvasBackgroundMode) {
        _canvasBgMode.value = mode
        timelineEngine.canvasBackgroundMode = mode
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
        previewRenderJob?.cancel()
        previewRenderJob = viewModelScope.launch {
            val bmp = timelineEngine.renderFrameAt(
                playheadMs = timestampMs,
                clips = _timelineClips.value,
                outputWidth = _aspectRatio.value.width / 2,
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
        triggerAutoSaveProject()
    }

    /**
     * AI Smart Scene Cut Detection: Automatically detects camera shot changes and splits the clip.
     */
    fun applyAiSceneCutDetection() {
        val selId = _selectedClipId.value ?: _timelineClips.value.firstOrNull { it.trackType == TrackType.MAIN_VIDEO }?.id ?: return
        viewModelScope.launch {
            _isAiProcessing.value = true
            try {
                val (updatedClips, cutsCount) = timelineEngine.detectSceneCutsAndSplit(_timelineClips.value, selId)
                if (cutsCount > 0) {
                    pushUndoState()
                    _timelineClips.value = updatedClips
                    updatePreviewFrame(_playheadMs.value)
                    triggerAutoSaveProject()
                    showStatusToast("AI Scene Cut: $cutsCount pergantian adegan dipotong")
                } else {
                    showStatusToast("Tidak ditemukan pergantian adegan tajam pada klip ini")
                }
            } finally {
                _isAiProcessing.value = false
            }
        }
    }

    /**
     * AI Auto-Reframe (9:16 Face Tracking): Tracks the primary subject's face using bundled ML Kit Face Detection
     * and generates smooth pan/zoom keyframes.
     */
    fun applyAiAutoReframe() {
        val selId = _selectedClipId.value ?: _timelineClips.value.firstOrNull { it.trackType == TrackType.MAIN_VIDEO }?.id ?: return
        val clip = _timelineClips.value.firstOrNull { it.id == selId } ?: return
        viewModelScope.launch {
            _isAiProcessing.value = true
            try {
                // Switch canvas to 9:16 vertical if currently landscape
                if (_aspectRatio.value != AspectRatio.TIKTOK_9_16) {
                    _aspectRatio.value = AspectRatio.TIKTOK_9_16
                    timelineEngine.currentAspectRatio = AspectRatio.TIKTOK_9_16
                }
                val trackedKeyframes = timelineEngine.autoReframeClipWithFaceTracking(clip)
                pushUndoState()
                _timelineClips.value = _timelineClips.value.map {
                    if (it.id == selId) {
                        it.copy(
                            keyframes = trackedKeyframes,
                            isAutoReframed = true
                        )
                    } else it
                }
                updatePreviewFrame(_playheadMs.value)
                triggerAutoSaveProject()
                showStatusToast("AI Auto-Reframe aktif (${trackedKeyframes.size} titik lacak wajah)")
            } finally {
                _isAiProcessing.value = false
            }
        }
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
                it.copy(speed = speed, durationMs = max(200L, newDuration))
            } else it
        }
        updatePreviewFrame(_playheadMs.value)
    }

    fun setClipSpeedCurve(curve: SpeedCurvePreset) {
        val selId = _selectedClipId.value ?: return
        pushUndoState()
        _timelineClips.value = _timelineClips.value.map {
            if (it.id == selId) it.copy(speedCurve = curve) else it
        }
        updatePreviewFrame(_playheadMs.value)
        showStatusToast("Speed Curve: ${curve.title}")
    }

    fun setClipAnimation(animation: ClipAnimationType) {
        val selId = _selectedClipId.value ?: return
        pushUndoState()
        _timelineClips.value = _timelineClips.value.map {
            if (it.id == selId) it.copy(animationType = animation) else it
        }
        updatePreviewFrame(_playheadMs.value)
    }

    fun toggleClipReverse() {
        val selId = _selectedClipId.value ?: return
        pushUndoState()
        _timelineClips.value = _timelineClips.value.map {
            if (it.id == selId) it.copy(isReversed = !it.isReversed) else it
        }
        updatePreviewFrame(_playheadMs.value)
    }

    fun freezeFrameAtPlayhead() {
        val currentBmp = _currentFrameBitmap.value ?: return
        pushUndoState()

        val freezeDuration = 2500L
        val freezeClip = TimelineClip(
            name = "Freeze Frame",
            trackType = TrackType.MAIN_VIDEO,
            startOnTimelineMs = _playheadMs.value,
            durationMs = freezeDuration,
            thumbnailBitmap = currentBmp.copy(Bitmap.Config.ARGB_8888, false),
            isFreezeFrame = true
        )

        val shifted = _timelineClips.value.map {
            if (it.trackType == TrackType.MAIN_VIDEO && it.startOnTimelineMs >= _playheadMs.value) {
                it.copy(startOnTimelineMs = it.startOnTimelineMs + freezeDuration)
            } else it
        }
        _timelineClips.value = shifted + freezeClip
        _selectedClipId.value = freezeClip.id
        updatePreviewFrame(_playheadMs.value)
    }

    fun addOrUpdateKeyframeAtPlayhead(
        scale: Float = 1.15f,
        translationX: Float = 0f,
        translationY: Float = 0f,
        rotationDegrees: Float = 0f,
        opacity: Float = 1.0f,
        recordUndo: Boolean = true
    ) {
        val selId = _selectedClipId.value ?: return
        if (recordUndo) pushUndoState()
        _timelineClips.value = _timelineClips.value.map { clip ->
            if (clip.id == selId) {
                val nowMs = _playheadMs.value
                val filtered = clip.keyframes.filterNot { abs(it.timestampMs - nowMs) < 100L }
                val newKf = Keyframe(
                    timestampMs = nowMs,
                    scale = scale,
                    translationX = translationX,
                    translationY = translationY,
                    rotationDegrees = rotationDegrees,
                    opacity = opacity
                )
                val updatedKfs = (filtered + newKf).sortedBy { it.timestampMs }
                clip.copy(keyframes = updatedKfs)
            } else clip
        }
        updatePreviewFrame(_playheadMs.value)
    }

    fun clearClipKeyframes() {
        val selId = _selectedClipId.value ?: return
        pushUndoState()
        _timelineClips.value = _timelineClips.value.map {
            if (it.id == selId) it.copy(keyframes = emptyList()) else it
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

    fun autoSampleChromaKeyColor() {
        val selId = _selectedClipId.value ?: return
        val clip = _timelineClips.value.firstOrNull { it.id == selId } ?: return
        viewModelScope.launch {
            val sampledRgb = timelineEngine.sampleDominantBorderColor(clip, _playheadMs.value)
            val updatedChroma = clip.chromaKey.copy(enabled = true, keyColor = sampledRgb)
            updateChromaKey(updatedChroma)
            showStatusToast("Sampled background key color")
        }
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
        viewModelScope.launch {
            var samples = clip.audioSamples
            if (samples == null && clip.localUri.isNotEmpty()) {
                val decoded = AudioDecoder.decodeFile(File(clip.localUri), clip.name)
                if (decoded.samples.isNotEmpty()) {
                    samples = decoded.samples
                }
            }
            if (samples == null || samples.isEmpty()) {
                samples = dspProcessor.getActiveBuffer()
            }

            pushUndoState()
            val extractedAudioClip = TimelineClip(
                name = "Audio (${clip.name})",
                trackType = TrackType.MUSIC_BGM,
                startOnTimelineMs = clip.startOnTimelineMs,
                durationMs = clip.durationMs,
                localUri = clip.localUri,
                audioSamples = samples,
                volume = 1.0f
            )
            _timelineClips.value = _timelineClips.value + extractedAudioClip
            _selectedClipId.value = extractedAudioClip.id
            showStatusToast("Audio extracted to BGM track")
        }
    }

    fun detectBeatsOnTimeline() {
        viewModelScope.launch {
            _isAiProcessing.value = true
            try {
                val selClip = _timelineClips.value.firstOrNull { it.id == _selectedClipId.value }
                val audioToAnalyze = if (selClip?.audioSamples != null && selClip.audioSamples.isNotEmpty()) {
                    selClip.audioSamples
                } else {
                    val totalDur = max(1000L, getTotalDurationMs())
                    dspProcessor.mixTimelineClipsToMasterPcm(_timelineClips.value, totalDur, applyMasterDsp = false)
                }

                val (bpm, beats) = dspProcessor.detectBeats(audioToAnalyze)
                _detectedBpm.value = bpm
                _beatMarkers.value = beats
                showStatusToast("Auto-Beat Sync: ${bpm.toInt()} BPM (${beats.size} beats)")
            } finally {
                _isAiProcessing.value = false
            }
        }
    }

    fun startLiveVoiceover() {
        val recordStartMs = _playheadMs.value
        captureEngine.startRecording { buffer, file ->
            if (buffer.isNotEmpty()) {
                pushUndoState()
                val voClip = TimelineClip(
                    name = "Voiceover (${(buffer.size / 44100).coerceAtLeast(1)}s)",
                    trackType = TrackType.VOICEOVER,
                    startOnTimelineMs = recordStartMs,
                    durationMs = max(500L, (buffer.size * 1000L) / 44100),
                    localUri = file.absolutePath,
                    audioSamples = buffer,
                    volume = 1.0f
                )
                _timelineClips.value = _timelineClips.value + voClip
                _selectedClipId.value = voClip.id
                dspProcessor.loadBuffer(buffer)
                _audioBufferData.value = buffer
                showStatusToast("Voiceover recorded to timeline")
            }
        }
    }

    fun stopLiveVoiceover() {
        captureEngine.stopRecording()
    }

    // --- AUDIO DAW & AI AUDIO CONTROLS ---

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

    private fun syncActiveDawBufferToSelectedClip(updatedBuffer: FloatArray) {
        _audioBufferData.value = updatedBuffer.copyOf()
        val selId = _selectedClipId.value ?: return
        _timelineClips.value = _timelineClips.value.map { clip ->
            if (clip.id == selId && clip.audioSamples != null) {
                val newDurMs = max(300L, (updatedBuffer.size.toLong() * 1000L) / dspProcessor.sampleRate)
                clip.copy(audioSamples = updatedBuffer.copyOf(), durationMs = newDurMs)
            } else clip
        }
    }

    fun trimAudioSelection() {
        val sel = _audioSelection.value
        if (!sel.isActive || sel.endMs <= sel.startMs) return
        val trimmed = dspProcessor.trimSelection(sel.startMs, sel.endMs)
        syncActiveDawBufferToSelectedClip(trimmed)
        clearAudioSelection()
    }

    fun cutMiddleAudio() {
        val sel = _audioSelection.value
        if (!sel.isActive || sel.endMs <= sel.startMs) return
        val cut = dspProcessor.cutMiddleAndStitch(sel.startMs, sel.endMs)
        syncActiveDawBufferToSelectedClip(cut)
        clearAudioSelection()
    }

    fun silenceAudioSelection() {
        val sel = _audioSelection.value
        if (!sel.isActive || sel.endMs <= sel.startMs) return
        val silenced = dspProcessor.silenceRegion(sel.startMs, sel.endMs)
        syncActiveDawBufferToSelectedClip(silenced)
    }

    fun normalizeAudio(peakDb: Float = 0f) {
        val norm = dspProcessor.normalize(peakDb)
        syncActiveDawBufferToSelectedClip(norm)
        showStatusToast("Normalized audio to ${peakDb.toInt()} dB")
    }

    fun fadeInAudio() {
        val faded = dspProcessor.fadeIn(1500L)
        syncActiveDawBufferToSelectedClip(faded)
    }

    fun fadeOutAudio() {
        val faded = dspProcessor.fadeOut(1500L)
        syncActiveDawBufferToSelectedClip(faded)
    }

    fun reverseAudio() {
        val rev = dspProcessor.reverseAudio()
        syncActiveDawBufferToSelectedClip(rev)
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
        val selId = _selectedClipId.value
        if (selId != null) {
            _timelineClips.value = _timelineClips.value.map {
                if (it.id == selId) it.copy(voicePresetId = presetId) else it
            }
        }
    }

    fun applyAiDenoise() {
        viewModelScope.launch {
            _isAiProcessing.value = true
            try {
                val selId = _selectedClipId.value
                val selClip = _timelineClips.value.firstOrNull { it.id == selId }
                if (selClip?.audioSamples != null && selClip.audioSamples.isNotEmpty()) {
                    val denoisedClipPcm = dspProcessor.denoiseBuffer(selClip.audioSamples, 20f)
                    pushUndoState()
                    _timelineClips.value = _timelineClips.value.map {
                        if (it.id == selId) it.copy(audioSamples = denoisedClipPcm, isAiDenoised = true) else it
                    }
                    dspProcessor.loadBuffer(denoisedClipPcm)
                    _audioBufferData.value = denoisedClipPcm.copyOf()
                } else {
                    val denoised = dspProcessor.applyAiStudioDenoise(20f)
                    _audioBufferData.value = denoised.copyOf()
                }
                triggerAutoSaveProject()
                showStatusToast("RNNoise Neural Denoise diterapkan")
            } finally {
                _isAiProcessing.value = false
            }
        }
    }

    fun applyAiVoiceEnhance() {
        viewModelScope.launch {
            _isAiProcessing.value = true
            try {
                val selId = _selectedClipId.value
                val selClip = _timelineClips.value.firstOrNull { it.id == selId }
                if (selClip?.audioSamples != null && selClip.audioSamples.isNotEmpty()) {
                    val enhancedPcm = dspProcessor.enhanceVoiceBuffer(selClip.audioSamples, 0.80f)
                    pushUndoState()
                    _timelineClips.value = _timelineClips.value.map {
                        if (it.id == selId) {
                            it.copy(
                                audioSamples = enhancedPcm,
                                isVoiceEnhanced = true,
                                isAiDenoised = true
                            )
                        } else it
                    }
                    dspProcessor.loadBuffer(enhancedPcm)
                    _audioBufferData.value = enhancedPcm.copyOf()
                } else {
                    val enhanced = dspProcessor.applyAiVoiceEnhancer(0.80f)
                    _audioBufferData.value = enhanced.copyOf()
                }
                triggerAutoSaveProject()
                showStatusToast("AI Voice Enhancer & Dereverb diterapkan")
            } finally {
                _isAiProcessing.value = false
            }
        }
    }

    fun runStemSeparation() {
        viewModelScope.launch {
            _isAiProcessing.value = true
            try {
                val result = dspProcessor.splitStems()
                _stemSplitResult.value = result
                showStatusToast("AI 2-Stem Separation complete")
            } finally {
                _isAiProcessing.value = false
            }
        }
    }

    fun sendSeparatedStemToTimeline(useVocals: Boolean) {
        val stems = _stemSplitResult.value ?: return
        val buffer = if (useVocals) stems.vocalBuffer else stems.backingBuffer
        if (buffer.isEmpty()) return
        pushUndoState()
        val durationMs = max(500L, (buffer.size.toLong() * 1000L) / stems.sampleRate)
        val stemClip = TimelineClip(
            name = if (useVocals) "AI Stem: Lead Vocals" else "AI Stem: Instrumental",
            trackType = if (useVocals) TrackType.VOICEOVER else TrackType.MUSIC_BGM,
            startOnTimelineMs = 0L,
            durationMs = durationMs,
            sourceDurationMs = durationMs,
            audioSamples = buffer.copyOf(),
            volume = 1.0f
        )
        _timelineClips.value = _timelineClips.value + stemClip
        _selectedClipId.value = stemClip.id
        showStatusToast("${stemClip.name} added to timeline")
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
        pauseVideo()
        _isExporting.value = true
        _exportProgress.value = 0f
        _exportMessage.value = "Encoding Video + Master Audio (${res.label}, ${fps.fps} FPS)..."

        viewModelScope.launch {
            try {
                val totalDur = max(1000L, getTotalDurationMs())
                val masterPcm = dspProcessor.mixTimelineClipsToMasterPcm(_timelineClips.value, totalDur)

                val outputLocation = exportEngine.exportVideo(
                    clips = _timelineClips.value,
                    timelineEngine = timelineEngine,
                    masterAudioPcm = masterPcm,
                    sampleRate = dspProcessor.sampleRate,
                    resolution = res,
                    frameRate = fps,
                    bitrateQuality = quality
                ) { progress ->
                    _exportProgress.value = progress
                }
                _exportMessage.value = "Saved: $outputLocation"
            } catch (e: Exception) {
                _exportMessage.value = "Export failed: ${e.message}"
            } finally {
                delay(2200)
                _isExporting.value = false
            }
        }
    }

    fun exportAudio(format: AudioExportFormat = AudioExportFormat.LOSSLESS_WAV) {
        if (_isExporting.value) return
        dspProcessor.stopPlayback()
        _isExporting.value = true
        _exportProgress.value = 0f
        _exportMessage.value = "Exporting Audio (${if (format == AudioExportFormat.LOSSLESS_WAV) "WAV Lossless" else "M4A AAC"})..."

        viewModelScope.launch {
            try {
                val processedBuffer = dspProcessor.renderProcessedBuffer()
                val path = exportEngine.exportAudio(processedBuffer, dspProcessor.sampleRate, format) {
                    _exportProgress.value = it
                }
                _exportMessage.value = "Saved: $path"
            } catch (e: Exception) {
                _exportMessage.value = "Export failed: ${e.message}"
            } finally {
                delay(2200)
                _isExporting.value = false
            }
        }
    }

    fun dismissExportDialog() {
        _isExporting.value = false
        _exportMessage.value = null
    }
}
