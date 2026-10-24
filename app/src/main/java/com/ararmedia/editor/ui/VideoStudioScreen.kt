package com.ararmedia.editor.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ararmedia.editor.StudioViewModel
import com.ararmedia.editor.engine.BitrateQuality
import com.ararmedia.editor.engine.ExportFrameRate
import com.ararmedia.editor.engine.VideoResolution
import com.ararmedia.editor.model.*
import com.ararmedia.editor.ui.components.*
import com.ararmedia.editor.ui.theme.*
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoStudioScreen(
    viewModel: StudioViewModel,
    modifier: Modifier = Modifier
) {
    val clips by viewModel.timelineClips.collectAsState()
    val selectedClipId by viewModel.selectedClipId.collectAsState()
    val playheadMs by viewModel.playheadMs.collectAsState()
    val isPlaying by viewModel.isPlayingVideo.collectAsState()
    val currentFrame by viewModel.currentFrameBitmap.collectAsState()
    val aspectRatio by viewModel.aspectRatio.collectAsState()
    val canvasBgMode by viewModel.canvasBgMode.collectAsState()
    val beatMarkers by viewModel.beatMarkers.collectAsState()
    val detectedBpm by viewModel.detectedBpm.collectAsState()
    val isRecordingMic by viewModel.isRecordingMic.collectAsState()
    val requestedToolPanel by viewModel.requestedToolPanel.collectAsState()
    val isAiProcessing by viewModel.isAiProcessing.collectAsState()
    val aiStatusMessage by viewModel.aiStatusMessage.collectAsState()

    val isExporting by viewModel.isExporting.collectAsState()
    val exportProgress by viewModel.exportProgress.collectAsState()
    val exportMessage by viewModel.exportMessage.collectAsState()

    var activeBottomPanel by remember { mutableStateOf<String?>(null) }
    // Panels: "color", "chroma", "speed", "transition", "animation", "keyframe", "text", "audio_fx", "canvas", "export"

    LaunchedEffect(requestedToolPanel) {
        if (requestedToolPanel != null) {
            activeBottomPanel = requestedToolPanel
            viewModel.consumeRequestedToolPanel()
        }
    }

    val mainMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importVisualMedia(it, TrackType.MAIN_VIDEO) }
    }

    val pipMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importVisualMedia(it, TrackType.PIP_OVERLAY) }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importAudio(it) }
    }

    val speechRecognizerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                val spokenLines = matches.first()
                    .split(Regex("[.,;!?]+"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                viewModel.generateAutoCaptionsToTimeline(spokenLines)
            }
        }
    }

    val selectedClip = clips.firstOrNull { it.id == selectedClipId }

    Scaffold(
        topBar = {
            Surface(
                color = StudioDarkBackground,
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left: Close to Home + Studio Mode Switcher
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = { viewModel.navigateTo(0) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Home", tint = Color.White, modifier = Modifier.size(20.dp))
                        }

                        // Workspace Switcher Pill (Video | Audio DAW)
                        Row(
                            modifier = Modifier
                                .background(StudioSurfaceVariant, RoundedCornerShape(16.dp))
                                .padding(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(StudioCyanAccent)
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Video", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.Black)
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { viewModel.navigateTo(2) }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Audio", fontSize = 11.sp, color = Color.LightGray)
                            }
                        }
                    }

                    // Right: Undo, Redo, Ratio, Export
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(onClick = { viewModel.undo() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Undo, contentDescription = "Undo", tint = Color.LightGray, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { viewModel.redo() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Redo, contentDescription = "Redo", tint = Color.LightGray, modifier = Modifier.size(18.dp))
                        }

                        TextButton(
                            onClick = { activeBottomPanel = if (activeBottomPanel == "canvas") null else "canvas" },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text(aspectRatio.label, color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        Button(
                            onClick = { activeBottomPanel = if (activeBottomPanel == "export") null else "export" },
                            colors = ButtonDefaults.buttonColors(containerColor = StudioCyanAccent),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("Export", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        containerColor = StudioDarkBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 1. Interactive Video Canvas Preview Box (Supports Pinch-to-Zoom, Drag & Rotate on Canvas)
            var canvasSize by remember { mutableStateOf(IntSize.Zero) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(if (activeBottomPanel != null) 0.33f else 0.44f)
                    .background(Color.Black)
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(selectedClipId) {
                        detectTransformGestures { _, pan, zoom, rotation ->
                            if (selectedClipId != null) {
                                viewModel.transformSelectedClipOnCanvas(
                                    panX = pan.x,
                                    panY = pan.y,
                                    zoomChange = zoom,
                                    rotationChange = rotation,
                                    canvasBoxWidth = canvasSize.width.toFloat(),
                                    canvasBoxHeight = canvasSize.height.toFloat()
                                )
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (currentFrame != null) {
                    Image(
                        bitmap = currentFrame!!.asImageBitmap(),
                        contentDescription = "Canvas Preview",
                        modifier = Modifier
                            .aspectRatio(aspectRatio.ratio)
                            .fillMaxHeight()
                            .border(
                                width = if (selectedClip != null) 1.5.dp else 1.dp,
                                color = if (selectedClip != null) StudioCyanAccent.copy(alpha = 0.65f) else StudioCardBorder
                            )
                    )
                } else {
                    CircularProgressIndicator(color = StudioCyanAccent, modifier = Modifier.size(34.dp))
                }

                // AI Processing / Status Toast Pill
                if (isAiProcessing || aiStatusMessage != null) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                            .background(StudioSurfaceVariant.copy(alpha = 0.92f), RoundedCornerShape(20.dp))
                            .border(1.dp, StudioCyanAccent.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isAiProcessing) {
                            CircularProgressIndicator(
                                color = StudioCyanAccent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp)
                            )
                        } else {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = StudioCyanAccent,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Text(
                            text = aiStatusMessage ?: "Processing...",
                            fontSize = 11.sp,
                            color = Color.White
                        )
                    }
                }

                // CapCut Transport Strip right below Preview
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTimecode(playheadMs),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { viewModel.seekTimeline(0L) },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Start", tint = Color.LightGray, modifier = Modifier.size(18.dp))
                        }

                        IconButton(
                            onClick = { viewModel.toggleVideoPlay() },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    if (isRecordingMic) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(modifier = Modifier.size(8.dp).background(StudioRedAccent, CircleShape))
                            Text("REC", color = StudioRedAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Text(
                            text = formatTimecode(viewModel.getTotalDurationMs()),
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                }
            }

            // 2. Active Tool / Effect Config Panel (Collapsible)
            if (activeBottomPanel != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StudioSurface)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    when (activeBottomPanel) {
                        "color" -> {
                            if (selectedClip != null) {
                                ColorGradingPanel(
                                    colorGrading = selectedClip.colorGrading,
                                    onColorGradingChange = { viewModel.updateColorGrading(it) }
                                )
                            } else {
                                SelectClipHint("Select a video or PIP clip on the timeline to adjust filters & color grading")
                            }
                        }
                        "chroma" -> {
                            if (selectedClip != null) {
                                ChromaKeyPanel(
                                    chromaKey = selectedClip.chromaKey,
                                    onChromaChange = { viewModel.updateChromaKey(it) },
                                    onAutoSampleColor = { viewModel.autoSampleChromaKeyColor() }
                                )
                            } else {
                                SelectClipHint("Select a video or PIP clip to use AI Auto Cutout or Chroma Key")
                            }
                        }
                        "speed" -> {
                            SpeedAndCurvePanel(
                                currentSpeed = selectedClip?.speed ?: 1.0f,
                                currentCurve = selectedClip?.speedCurve ?: SpeedCurvePreset.NORMAL,
                                onSpeedChange = { viewModel.setClipSpeed(it) },
                                onCurveChange = { viewModel.setClipSpeedCurve(it) }
                            )
                        }
                        "animation" -> {
                            ClipAnimationPanel(
                                currentAnimation = selectedClip?.animationType ?: ClipAnimationType.NONE,
                                onAnimationSelected = { viewModel.setClipAnimation(it) }
                            )
                        }
                        "transition" -> {
                            TransitionSelectorPanel(
                                currentTransition = selectedClip?.transitionIn ?: TransitionType.NONE,
                                onTransitionSelected = { viewModel.setTransition(it) }
                            )
                        }
                        "keyframe" -> {
                            KeyframeEditorPanel(
                                selectedClip = selectedClip,
                                playheadMs = playheadMs,
                                onSaveKeyframe = { scale, tx, ty, rot, op ->
                                    viewModel.addOrUpdateKeyframeAtPlayhead(scale, tx, ty, rot, op)
                                },
                                onClearKeyframes = { viewModel.clearClipKeyframes() }
                            )
                        }
                        "text" -> {
                            TextCaptionEditorPanel(
                                selectedClip = selectedClip,
                                onAddOrUpdateText = { text, sizeSp, color, isKaraoke, showPill, editId ->
                                    viewModel.addOrUpdateTextClip(text, sizeSp, color, isKaraoke, showPill, editId)
                                    activeBottomPanel = null
                                },
                                onGenerateAutoCaptions = {
                                    viewModel.generateAutoCaptionsToTimeline()
                                    activeBottomPanel = null
                                },
                                onStartSpeechDictation = {
                                    try {
                                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak caption lines...")
                                        }
                                        speechRecognizerLauncher.launch(intent)
                                    } catch (_: Exception) {
                                        viewModel.generateAutoCaptionsToTimeline()
                                    }
                                }
                            )
                        }
                        "audio_fx" -> {
                            AudioAndAiFxPanel(
                                selectedClip = selectedClip,
                                detectedBpm = detectedBpm,
                                beatCount = beatMarkers.size,
                                onVolumeChange = { vol, muted -> viewModel.updateClipVolume(vol, muted) },
                                onApplyAiDenoise = { viewModel.applyAiDenoise() },
                                onSelectVoicePreset = { viewModel.setVoicePreset(it) },
                                onDetectBeats = { viewModel.detectBeatsOnTimeline() },
                                onOpenDaw = { viewModel.navigateTo(2) }
                            )
                        }
                        "canvas" -> {
                            CanvasSettingsPanel(
                                currentRatio = aspectRatio,
                                currentBgMode = canvasBgMode,
                                onRatioSelected = { viewModel.setAspectRatio(it) },
                                onBgModeSelected = { viewModel.setCanvasBackgroundMode(it) }
                            )
                        }
                        "export" -> {
                            VideoExportDialog(
                                onExport = { res, fps, qual ->
                                    viewModel.exportVideo(res, fps, qual)
                                    activeBottomPanel = null
                                }
                            )
                        }
                    }

                    IconButton(
                        onClick = { activeBottomPanel = null },
                        modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close Panel", tint = Color.LightGray, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // 3. Multi-Track Magnetic Timeline (with Left/Right Trim Handles, Mini Waveforms & Thumbnails)
            MultiTrackTimeline(
                clips = clips,
                selectedClipId = selectedClipId,
                playheadMs = playheadMs,
                totalDurationMs = viewModel.getTotalDurationMs(),
                beatMarkers = beatMarkers,
                onSelectClip = { viewModel.selectClip(it) },
                onSeek = { viewModel.seekTimeline(it) },
                onSplitClip = { viewModel.splitActiveClip() },
                onDeleteClip = { viewModel.deleteActiveClip() },
                onDuplicateClip = { viewModel.duplicateActiveClip() },
                onMoveClipStart = { id, newStart -> viewModel.moveClipStart(id, newStart) },
                onTrimClipStart = { id, newStart -> viewModel.trimClipStart(id, newStart) },
                onTrimClipEnd = { id, newDur -> viewModel.trimClipEnd(id, newDur) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(if (activeBottomPanel != null) 0.34f else 0.42f)
            )

            // 4. CapCut Bottom Action Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(StudioSurface)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StudioToolButton(icon = Icons.Default.AddPhotoAlternate, label = "Media", iconTint = StudioCyanAccent) {
                    mainMediaLauncher.launch("*/*")
                }
                StudioToolButton(icon = Icons.Default.ContentCut, label = "Split", isEnabled = selectedClipId != null) {
                    viewModel.splitActiveClip()
                }
                StudioToolButton(
                    icon = Icons.Default.Speed,
                    label = "Speed",
                    isEnabled = selectedClipId != null,
                    isActive = activeBottomPanel == "speed"
                ) {
                    activeBottomPanel = if (activeBottomPanel == "speed") null else "speed"
                }
                StudioToolButton(
                    icon = Icons.Default.AutoAwesomeMotion,
                    label = "Animation",
                    isEnabled = selectedClipId != null,
                    isActive = activeBottomPanel == "animation"
                ) {
                    activeBottomPanel = if (activeBottomPanel == "animation") null else "animation"
                }
                StudioToolButton(
                    icon = Icons.Default.AutoFixHigh,
                    label = "Cutout",
                    isEnabled = selectedClipId != null,
                    iconTint = StudioGreenAccent,
                    isActive = activeBottomPanel == "chroma"
                ) {
                    activeBottomPanel = if (activeBottomPanel == "chroma") null else "chroma"
                }
                StudioToolButton(
                    icon = Icons.Default.TextFields,
                    label = "Text",
                    iconTint = StudioGoldAccent,
                    isActive = activeBottomPanel == "text"
                ) {
                    activeBottomPanel = if (activeBottomPanel == "text") null else "text"
                }
                StudioToolButton(
                    icon = Icons.Default.ClosedCaption,
                    label = "Auto Cap",
                    iconTint = StudioCyanAccent
                ) {
                    viewModel.generateAutoCaptionsToTimeline()
                }
                StudioToolButton(
                    icon = Icons.Default.PictureInPictureAlt,
                    label = "Overlay",
                    iconTint = StudioPurpleAccent
                ) {
                    pipMediaLauncher.launch("*/*")
                }
                StudioToolButton(
                    icon = Icons.Default.GraphicEq,
                    label = "Audio FX",
                    iconTint = StudioGoldAccent,
                    isActive = activeBottomPanel == "audio_fx"
                ) {
                    activeBottomPanel = if (activeBottomPanel == "audio_fx") null else "audio_fx"
                }
                StudioToolButton(
                    icon = Icons.Default.NoiseAware,
                    label = if (selectedClip?.isAiDenoised == true) "Denoised" else "Denoise",
                    iconTint = if (selectedClip?.isAiDenoised == true) StudioGreenAccent else Color.White
                ) {
                    viewModel.applyAiDenoise()
                }
                StudioToolButton(
                    icon = Icons.Default.FilterVintage,
                    label = "Adjust",
                    isEnabled = selectedClipId != null,
                    isActive = activeBottomPanel == "color"
                ) {
                    activeBottomPanel = if (activeBottomPanel == "color") null else "color"
                }
                StudioToolButton(
                    icon = Icons.Default.Animation,
                    label = "Transition",
                    isEnabled = selectedClipId != null,
                    isActive = activeBottomPanel == "transition"
                ) {
                    activeBottomPanel = if (activeBottomPanel == "transition") null else "transition"
                }
                StudioToolButton(
                    icon = Icons.Default.ControlCamera,
                    label = "Keyframe",
                    isEnabled = selectedClipId != null,
                    isActive = activeBottomPanel == "keyframe"
                ) {
                    activeBottomPanel = if (activeBottomPanel == "keyframe") null else "keyframe"
                }
                StudioToolButton(
                    icon = Icons.Default.MotionPhotosAuto,
                    label = if (selectedClip?.isStabilized == true) "Stabilized" else "Stabilize",
                    isEnabled = selectedClipId != null,
                    iconTint = if (selectedClip?.isStabilized == true) StudioGreenAccent else Color.White
                ) {
                    viewModel.toggleClipStabilization()
                }
                StudioToolButton(
                    icon = Icons.Default.Flip,
                    label = "Mirror",
                    isEnabled = selectedClipId != null,
                    iconTint = if (selectedClip?.isMirrored == true) StudioCyanAccent else Color.White
                ) {
                    viewModel.toggleClipMirror()
                }
                StudioToolButton(
                    icon = Icons.Default.Rotate90DegreesCcw,
                    label = "Rotate",
                    isEnabled = selectedClipId != null
                ) {
                    viewModel.rotateClip90()
                }
                StudioToolButton(
                    icon = Icons.Default.AcUnit,
                    label = "Freeze",
                    isEnabled = selectedClipId != null
                ) {
                    viewModel.freezeFrameAtPlayhead()
                }
                StudioToolButton(
                    icon = Icons.Default.History,
                    label = if (selectedClip?.isReversed == true) "Reversed" else "Reverse",
                    isEnabled = selectedClipId != null,
                    iconTint = if (selectedClip?.isReversed == true) StudioGoldAccent else Color.White
                ) {
                    viewModel.toggleClipReverse()
                }
                StudioToolButton(
                    icon = Icons.Default.Audiotrack,
                    label = "Extract",
                    isEnabled = selectedClipId != null
                ) {
                    viewModel.extractAudioFromActiveClip()
                }
                StudioToolButton(
                    icon = Icons.Default.LibraryMusic,
                    label = "Sounds",
                    iconTint = StudioGoldAccent
                ) {
                    audioPickerLauncher.launch("audio/*")
                }
                StudioToolButton(
                    icon = Icons.Default.Mic,
                    label = if (isRecordingMic) "Stop" else "Record",
                    iconTint = if (isRecordingMic) StudioRedAccent else StudioCyanAccent
                ) {
                    if (isRecordingMic) viewModel.stopLiveVoiceover() else viewModel.startLiveVoiceover()
                }
                StudioToolButton(
                    icon = Icons.Default.AspectRatio,
                    label = "Canvas",
                    isActive = activeBottomPanel == "canvas"
                ) {
                    activeBottomPanel = if (activeBottomPanel == "canvas") null else "canvas"
                }
            }
        }
    }

    // Export Progress Dialog
    if (isExporting) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = StudioSurfaceVariant,
            title = { Text("Exporting Video", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(exportMessage ?: "Rendering frames...", color = Color.LightGray, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { exportProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = StudioCyanAccent
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("${(exportProgress * 100).toInt()}%", color = StudioCyanAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun SelectClipHint(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(message, color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
private fun StudioToolButton(
    icon: ImageVector,
    label: String,
    isEnabled: Boolean = true,
    isActive: Boolean = false,
    iconTint: Color = Color.White,
    onClick: () -> Unit
) {
    val effectiveTint = when {
        !isEnabled -> Color.DarkGray
        isActive -> StudioCyanAccent
        else -> iconTint
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isActive) StudioCyanAccent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(enabled = isEnabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = effectiveTint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isEnabled) (if (isActive) StudioCyanAccent else Color.LightGray) else Color.DarkGray
        )
    }
}

@Composable
private fun SpeedAndCurvePanel(
    currentSpeed: Float,
    currentCurve: SpeedCurvePreset,
    onSpeedChange: (Float) -> Unit,
    onCurveChange: (SpeedCurvePreset) -> Unit
) {
    var speed by remember(currentSpeed) { mutableFloatStateOf(currentSpeed) }
    var speedTab by remember { mutableIntStateOf(if (currentCurve != SpeedCurvePreset.NORMAL) 1 else 0) }

    Column(modifier = Modifier.fillMaxWidth().padding(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = speedTab == 0,
                onClick = { speedTab = 0 },
                label = { Text("Normal Speed", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = StudioCyanAccent,
                    selectedLabelColor = Color.Black
                )
            )
            FilterChip(
                selected = speedTab == 1,
                onClick = { speedTab = 1 },
                label = { Text("Curve Speed", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = StudioCyanAccent,
                    selectedLabelColor = Color.Black
                )
            )
        }

        if (speedTab == 0) {
            Text("Speed: ${String.format(Locale.US, "%.1fx", speed)}", color = Color.White, fontSize = 11.sp)
            Slider(
                value = speed,
                onValueChange = {
                    speed = it
                    onSpeedChange(it)
                },
                valueRange = 0.2f..4.0f,
                colors = SliderDefaults.colors(thumbColor = StudioCyanAccent, activeTrackColor = StudioCyanAccent)
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(0.5f, 1.0f, 1.5f, 2.0f, 4.0f).forEach { s ->
                    TextButton(onClick = {
                        speed = s
                        onSpeedChange(s)
                    }) {
                        Text("${s}x", fontSize = 10.sp, color = if (speed == s) StudioCyanAccent else Color.LightGray)
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SpeedCurvePreset.values().forEach { preset ->
                    FilterChip(
                        selected = currentCurve == preset,
                        onClick = { onCurveChange(preset) },
                        label = {
                            Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                Text(preset.title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text(preset.description, fontSize = 9.sp)
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StudioCyanAccent,
                            selectedLabelColor = Color.Black
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipAnimationPanel(
    currentAnimation: ClipAnimationType,
    onAnimationSelected: (ClipAnimationType) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Text("Clip Animation (In / Combo)", color = StudioCyanAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ClipAnimationType.values().forEach { anim ->
                FilterChip(
                    selected = currentAnimation == anim,
                    onClick = { onAnimationSelected(anim) },
                    label = { Text(anim.title, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioCyanAccent,
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }
    }
}

@Composable
private fun CanvasSettingsPanel(
    currentRatio: AspectRatio,
    currentBgMode: CanvasBackgroundMode,
    onRatioSelected: (AspectRatio) -> Unit,
    onBgModeSelected: (CanvasBackgroundMode) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Aspect Ratio", fontSize = 11.sp, color = Color.LightGray)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AspectRatio.values().forEach { r ->
                FilterChip(
                    selected = currentRatio == r,
                    onClick = { onRatioSelected(r) },
                    label = { Text(r.label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioCyanAccent,
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }

        Text("Canvas Background", fontSize = 11.sp, color = Color.LightGray)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CanvasBackgroundMode.values().forEach { mode ->
                FilterChip(
                    selected = currentBgMode == mode,
                    onClick = { onBgModeSelected(mode) },
                    label = { Text(mode.title, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioCyanAccent,
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }
    }
}

@Composable
private fun AudioAndAiFxPanel(
    selectedClip: TimelineClip?,
    detectedBpm: Float,
    beatCount: Int,
    onVolumeChange: (Float, Boolean) -> Unit,
    onApplyAiDenoise: () -> Unit,
    onSelectVoicePreset: (Int) -> Unit,
    onDetectBeats: () -> Unit,
    onOpenDaw: () -> Unit
) {
    var vol by remember(selectedClip?.id) { mutableFloatStateOf(selectedClip?.volume ?: 1.0f) }
    var muted by remember(selectedClip?.id) { mutableStateOf(selectedClip?.isMuted ?: false) }
    var activePreset by remember(selectedClip?.id) { mutableIntStateOf(selectedClip?.voicePresetId ?: 0) }

    Column(modifier = Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (selectedClip != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Clip Volume: ${(vol * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = muted,
                        onCheckedChange = {
                            muted = it
                            onVolumeChange(vol, muted)
                        }
                    )
                    Text("Mute", fontSize = 11.sp, color = Color.LightGray)
                }
            }
            Slider(
                value = vol,
                onValueChange = {
                    vol = it
                    onVolumeChange(vol, muted)
                },
                valueRange = 0f..2.0f,
                colors = SliderDefaults.colors(thumbColor = StudioCyanAccent, activeTrackColor = StudioCyanAccent)
            )
        }

        // Voice Changer Presets
        Text("Voice Effects", fontSize = 11.sp, color = Color.LightGray)
        val presets = listOf(
            0 to "Original",
            1 to "Deep Podcast",
            2 to "Chipmunk",
            3 to "Megaphone",
            4 to "Radio",
            5 to "Robot",
            6 to "Monster",
            7 to "Cathedral"
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for ((id, title) in presets) {
                FilterChip(
                    selected = activePreset == id,
                    onClick = {
                        activePreset = id
                        onSelectVoicePreset(id)
                    },
                    label = { Text(title, fontSize = 10.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioGoldAccent,
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }

        // AI Audio Actions Row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onApplyAiDenoise,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.NoiseAware, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("AI Denoise", fontSize = 10.sp)
            }
            FilledTonalButton(
                onClick = onDetectBeats,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Auto Beat (${detectedBpm.toInt()} BPM)", fontSize = 10.sp)
            }
            FilledTonalButton(
                onClick = onOpenDaw,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Pro DAW ($beatCount)", fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun KeyframeEditorPanel(
    selectedClip: TimelineClip?,
    playheadMs: Long,
    onSaveKeyframe: (Float, Float, Float, Float, Float) -> Unit,
    onClearKeyframes: () -> Unit
) {
    if (selectedClip == null) {
        SelectClipHint("Select a clip on the timeline to add or edit Keyframes ♦")
        return
    }
    var scale by remember(selectedClip.id) { mutableFloatStateOf(1.0f) }
    var transX by remember(selectedClip.id) { mutableFloatStateOf(0f) }
    var transY by remember(selectedClip.id) { mutableFloatStateOf(0f) }
    var rotation by remember(selectedClip.id) { mutableFloatStateOf(0f) }
    var opacity by remember(selectedClip.id) { mutableFloatStateOf(1.0f) }

    Column(modifier = Modifier.fillMaxWidth().padding(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Keyframe ♦ @ ${playheadMs}ms (${selectedClip.keyframes.size} keys • Pinch/Drag on Canvas supported)",
                color = StudioGoldAccent,
                fontSize = 10.sp
            )
            TextButton(onClick = onClearKeyframes) {
                Text("Clear", color = StudioRedAccent, fontSize = 10.sp)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Scale: ${String.format(Locale.US, "%.2fx", scale)}", fontSize = 10.sp, color = Color.White)
                Slider(value = scale, onValueChange = { scale = it; onSaveKeyframe(scale, transX, transY, rotation, opacity) }, valueRange = 0.2f..2.5f)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Rotate: ${rotation.toInt()}°", fontSize = 10.sp, color = Color.White)
                Slider(value = rotation, onValueChange = { rotation = it; onSaveKeyframe(scale, transX, transY, rotation, opacity) }, valueRange = -180f..180f)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Pos X: ${transX.toInt()}", fontSize = 10.sp, color = Color.White)
                Slider(value = transX, onValueChange = { transX = it; onSaveKeyframe(scale, transX, transY, rotation, opacity) }, valueRange = -400f..400f)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Pos Y: ${transY.toInt()}", fontSize = 10.sp, color = Color.White)
                Slider(value = transY, onValueChange = { transY = it; onSaveKeyframe(scale, transX, transY, rotation, opacity) }, valueRange = -600f..600f)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Opacity: ${(opacity * 100).toInt()}%", fontSize = 10.sp, color = Color.White)
                Slider(value = opacity, onValueChange = { opacity = it; onSaveKeyframe(scale, transX, transY, rotation, opacity) }, valueRange = 0.1f..1.0f)
            }
        }
    }
}

@Composable
private fun TextCaptionEditorPanel(
    selectedClip: TimelineClip?,
    onAddOrUpdateText: (String, Float, Color, Boolean, Boolean, String?) -> Unit,
    onGenerateAutoCaptions: () -> Unit,
    onStartSpeechDictation: () -> Unit
) {
    val isEditingTextClip = selectedClip?.trackType == TrackType.TEXT_STICKER
    var textValue by remember(selectedClip?.id) {
        mutableStateOf(if (isEditingTextClip) selectedClip!!.textContent else "New Subtitle")
    }
    var fontSize by remember(selectedClip?.id) {
        mutableFloatStateOf(if (isEditingTextClip) selectedClip!!.textStyle.fontSizeSp else 28f)
    }
    var isKaraoke by remember(selectedClip?.id) {
        mutableStateOf(if (isEditingTextClip) selectedClip!!.textStyle.isKaraokeHighlighted else true)
    }
    var showPill by remember(selectedClip?.id) {
        mutableStateOf(if (isEditingTextClip) selectedClip!!.textStyle.showBackgroundPill else true)
    }
    var selectedColor by remember(selectedClip?.id) {
        mutableStateOf(if (isEditingTextClip) selectedClip!!.textStyle.textColor else StudioCyanAccent)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = textValue,
            onValueChange = { textValue = it },
            label = { Text("Text or Caption", fontSize = 10.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(StudioCyanAccent, StudioGoldAccent, Color.White, StudioPinkAccent, StudioGreenAccent).forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(c, CircleShape)
                            .border(if (selectedColor == c) 2.dp else 0.dp, Color.White, CircleShape)
                            .clickable { selectedColor = c }
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = isKaraoke, onCheckedChange = { isKaraoke = it })
                Text("Karaoke", fontSize = 10.sp, color = Color.White)
                Spacer(modifier = Modifier.width(4.dp))
                Checkbox(checked = showPill, onCheckedChange = { showPill = it })
                Text("BG Pill", fontSize = 10.sp, color = Color.White)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = {
                    onAddOrUpdateText(
                        textValue,
                        fontSize,
                        selectedColor,
                        isKaraoke,
                        showPill,
                        if (isEditingTextClip) selectedClip?.id else null
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = StudioCyanAccent),
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isEditingTextClip) "Update" else "+ Add Text", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = onGenerateAutoCaptions,
                colors = ButtonDefaults.buttonColors(containerColor = StudioGoldAccent),
                modifier = Modifier.weight(1f)
            ) {
                Text("Auto Captions", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            FilledTonalIconButton(onClick = onStartSpeechDictation) {
                Icon(Icons.Default.Mic, contentDescription = "Voice Dictation", tint = StudioCyanAccent)
            }
        }
    }
}

@Composable
private fun TransitionSelectorPanel(currentTransition: TransitionType, onTransitionSelected: (TransitionType) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Text("Camera & Video Transitions", color = StudioCyanAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransitionType.values().forEach { t ->
                FilterChip(
                    selected = currentTransition == t,
                    onClick = { onTransitionSelected(t) },
                    label = { Text(t.title, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioCyanAccent,
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }
    }
}

@Composable
private fun VideoExportDialog(
    onExport: (VideoResolution, ExportFrameRate, BitrateQuality) -> Unit
) {
    var selectedRes by remember { mutableStateOf(VideoResolution.FHD_1080P) }
    var selectedFps by remember { mutableStateOf(ExportFrameRate.FPS_30) }
    var selectedQuality by remember { mutableStateOf(BitrateQuality.RECOMMENDED) }

    Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
        Text("Export Video", style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Text("Resolution", fontSize = 11.sp, color = Color.LightGray)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VideoResolution.values().forEach { r ->
                FilterChip(
                    selected = selectedRes == r,
                    onClick = { selectedRes = r },
                    label = { Text(r.label, fontSize = 10.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text("Frame Rate", fontSize = 11.sp, color = Color.LightGray)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExportFrameRate.values().forEach { f ->
                FilterChip(
                    selected = selectedFps == f,
                    onClick = { selectedFps = f },
                    label = { Text("${f.fps} FPS", fontSize = 10.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text("Code Rate (Bitrate)", fontSize = 11.sp, color = Color.LightGray)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BitrateQuality.values().forEach { q ->
                FilterChip(
                    selected = selectedQuality == q,
                    onClick = { selectedQuality = q },
                    label = { Text(q.label, fontSize = 10.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = { onExport(selectedRes, selectedFps, selectedQuality) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = StudioCyanAccent)
        ) {
            Text("Export MP4", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}
