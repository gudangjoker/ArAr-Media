package com.ararmedia.editor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ararmedia.editor.StudioViewModel
import com.ararmedia.editor.engine.BitrateQuality
import com.ararmedia.editor.engine.ExportFrameRate
import com.ararmedia.editor.engine.VideoResolution
import com.ararmedia.editor.model.AspectRatio
import com.ararmedia.editor.model.TransitionType
import com.ararmedia.editor.ui.components.*
import com.ararmedia.editor.ui.theme.*

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
    val beatMarkers by viewModel.beatMarkers.collectAsState()
    val isRecordingMic by viewModel.isRecordingMic.collectAsState()

    val isExporting by viewModel.isExporting.collectAsState()
    val exportProgress by viewModel.exportProgress.collectAsState()
    val exportMessage by viewModel.exportMessage.collectAsState()

    var activeBottomPanel by remember { mutableStateOf<String?>(null) } // "color", "chroma", "speed", "transition", "export"

    val selectedClip = clips.firstOrNull { it.id == selectedClipId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ArAr Video Studio", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Text(formatTimecode(playheadMs), fontSize = 11.sp, color = StudioCyanAccent)
                    }
                },
                actions = {
                    // Undo & Redo
                    IconButton(onClick = { viewModel.undo() }) {
                        Icon(Icons.Default.Undo, contentDescription = "Undo", tint = Color.LightGray)
                    }
                    IconButton(onClick = { viewModel.redo() }) {
                        Icon(Icons.Default.Redo, contentDescription = "Redo", tint = Color.LightGray)
                    }
                    // Aspect Ratio Dropdown Menu
                    var showAspectMenu by remember { mutableStateOf(false) }
                    TextButton(onClick = { showAspectMenu = true }) {
                        Text(aspectRatio.label.split(" ")[0], color = StudioCyanAccent, fontSize = 12.sp)
                    }
                    DropdownMenu(
                        expanded = showAspectMenu,
                        onDismissRequest = { showAspectMenu = false },
                        modifier = Modifier.background(StudioSurfaceVariant)
                    ) {
                        AspectRatio.values().forEach { ratio ->
                            DropdownMenuItem(
                                text = { Text(ratio.label, color = Color.White, fontSize = 12.sp) },
                                onClick = {
                                    viewModel.setAspectRatio(ratio)
                                    showAspectMenu = false
                                }
                            )
                        }
                    }
                    // Export Button
                    Button(
                        onClick = { activeBottomPanel = "export" },
                        colors = ButtonDefaults.buttonColors(containerColor = StudioCyanAccent),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Export", color = Color.Black, fontSize = 12.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = StudioDarkBackground)
            )
        },
        containerColor = StudioDarkBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 1. Video Canvas Preview Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.42f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (currentFrame != null) {
                    Image(
                        bitmap = currentFrame!!.asImageBitmap(),
                        contentDescription = "Canvas Preview",
                        modifier = Modifier
                            .aspectRatio(aspectRatio.ratio)
                            .fillMaxHeight()
                            .border(1.dp, StudioCardBorder)
                    )
                } else {
                    CircularProgressIndicator(color = StudioCyanAccent, modifier = Modifier.size(36.dp))
                }

                // Transport Controls Overlay (Play/Pause, Live Recording indicator)
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    IconButton(
                        onClick = { viewModel.toggleVideoPlay() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.White
                        )
                    }

                    if (isRecordingMic) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(modifier = Modifier.size(8.dp).background(StudioRedAccent, CircleShape))
                            Text("REC", color = StudioRedAccent, fontSize = 11.sp)
                        }
                    }
                }
            }

            // 2. Active Tool / Effect Config Panel (Collapsible)
            if (activeBottomPanel != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StudioSurface)
                        .padding(8.dp)
                ) {
                    when (activeBottomPanel) {
                        "color" -> {
                            if (selectedClip != null) {
                                ColorGradingPanel(
                                    colorGrading = selectedClip.colorGrading,
                                    onColorGradingChange = { viewModel.updateColorGrading(it) }
                                )
                            } else {
                                Text("Select a clip on the timeline first", color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                        "chroma" -> {
                            if (selectedClip != null) {
                                ChromaKeyPanel(
                                    chromaKey = selectedClip.chromaKey,
                                    onChromaChange = { viewModel.updateChromaKey(it) }
                                )
                            } else {
                                Text("Select a clip on the timeline first", color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                        "speed" -> {
                            SpeedControlPanel(
                                currentSpeed = selectedClip?.speed ?: 1.0f,
                                onSpeedChange = { viewModel.setClipSpeed(it) }
                            )
                        }
                        "transition" -> {
                            TransitionSelectorPanel(
                                currentTransition = selectedClip?.transitionIn ?: TransitionType.NONE,
                                onTransitionSelected = { viewModel.setTransition(it) }
                            )
                        }
                        "export" -> {
                            VideoExportDialog(
                                onExport = { res, fps, qual ->
                                    viewModel.exportVideo(res, fps, qual)
                                    activeBottomPanel = null
                                },
                                onDismiss = { activeBottomPanel = null }
                            )
                        }
                    }

                    IconButton(
                        onClick = { activeBottomPanel = null },
                        modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // 3. Multi-Track Magnetic Timeline (CapCut Level)
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
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.38f)
            )

            // 4. Essential Daily Video Tools Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(StudioSurface)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StudioToolButton(icon = Icons.Default.ContentCut, label = "Split", isEnabled = selectedClipId != null) {
                    viewModel.splitActiveClip()
                }
                StudioToolButton(icon = Icons.Default.Speed, label = "Speed", isEnabled = selectedClipId != null) {
                    activeBottomPanel = if (activeBottomPanel == "speed") null else "speed"
                }
                StudioToolButton(icon = Icons.Default.Transform, label = "Keyframe ♦", isEnabled = selectedClipId != null) {
                    viewModel.addKeyframeAtPlayhead()
                }
                StudioToolButton(icon = Icons.Default.FilterVintage, label = "Color & LUT", isEnabled = selectedClipId != null) {
                    activeBottomPanel = if (activeBottomPanel == "color") null else "color"
                }
                StudioToolButton(icon = Icons.Default.Layers, label = "Chroma Key", isEnabled = selectedClipId != null) {
                    activeBottomPanel = if (activeBottomPanel == "chroma") null else "chroma"
                }
                StudioToolButton(icon = Icons.Default.Animation, label = "Transitions", isEnabled = selectedClipId != null) {
                    activeBottomPanel = if (activeBottomPanel == "transition") null else "transition"
                }
                StudioToolButton(icon = Icons.Default.AcUnit, label = "Freeze", isEnabled = selectedClipId != null) {
                    viewModel.freezeFrameAtPlayhead()
                }
                StudioToolButton(icon = Icons.Default.RotateLeft, label = "Reverse", isEnabled = selectedClipId != null) {
                    viewModel.toggleClipReverse()
                }
                StudioToolButton(icon = Icons.Default.Audiotrack, label = "Extract Audio", isEnabled = selectedClipId != null) {
                    viewModel.extractAudioFromActiveClip()
                }
                // Live Mic Voiceover Toggle
                StudioToolButton(
                    icon = Icons.Default.Mic,
                    label = if (isRecordingMic) "Stop Mic" else "Voiceover",
                    iconTint = if (isRecordingMic) StudioRedAccent else StudioCyanAccent
                ) {
                    if (isRecordingMic) viewModel.stopLiveVoiceover() else viewModel.startLiveVoiceover()
                }
            }
        }
    }

    // Export Progress Dialog
    if (isExporting) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = StudioSurfaceVariant,
            title = { Text("Hardware Media Export", color = StudioCyanAccent) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(exportMessage ?: "Rendering frames...", color = Color.White, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { exportProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = StudioCyanAccent
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("${(exportProgress * 100).toInt()}%", color = Color.LightGray, fontSize = 12.sp)
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun StudioToolButton(
    icon: ImageVector,
    label: String,
    isEnabled: Boolean = true,
    iconTint: Color = Color.White,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        IconButton(
            onClick = onClick,
            enabled = isEnabled,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (isEnabled) iconTint else Color.DarkGray,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = label,
            fontSize = 9.sp,
            color = if (isEnabled) Color.LightGray else Color.DarkGray
        )
    }
}

@Composable
private fun SpeedControlPanel(currentSpeed: Float, onSpeedChange: (Float) -> Unit) {
    var speed by remember { mutableFloatStateOf(currentSpeed) }
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Text("Playback Speed: ${String.format(java.util.Locale.US, "%.1fx", speed)}", color = StudioCyanAccent, fontSize = 12.sp)
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
            listOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f).forEach { s ->
                TextButton(onClick = {
                    speed = s
                    onSpeedChange(s)
                }) {
                    Text("${s}x", fontSize = 10.sp, color = if (speed == s) StudioCyanAccent else Color.White)
                }
            }
        }
    }
}

@Composable
private fun TransitionSelectorPanel(currentTransition: TransitionType, onTransitionSelected: (TransitionType) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Text("Transition Junction Effect:", color = StudioCyanAccent, fontSize = 12.sp)
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
    onExport: (VideoResolution, ExportFrameRate, BitrateQuality) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRes by remember { mutableStateOf(VideoResolution.FHD_1080P) }
    var selectedFps by remember { mutableStateOf(ExportFrameRate.FPS_30) }
    var selectedQuality by remember { mutableStateOf(BitrateQuality.RECOMMENDED) }

    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Text("Video Export Settings", style = MaterialTheme.typography.titleSmall, color = StudioCyanAccent)
        Spacer(modifier = Modifier.height(8.dp))

        Text("Resolution:", fontSize = 11.sp, color = Color.LightGray)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VideoResolution.values().forEach { r ->
                FilterChip(
                    selected = selectedRes == r,
                    onClick = { selectedRes = r },
                    label = { Text(r.label, fontSize = 10.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text("Frame Rate:", fontSize = 11.sp, color = Color.LightGray)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExportFrameRate.values().forEach { f ->
                FilterChip(
                    selected = selectedFps == f,
                    onClick = { selectedFps = f },
                    label = { Text("${f.fps} FPS", fontSize = 10.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = { onExport(selectedRes, selectedFps, selectedQuality) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = StudioCyanAccent)
        ) {
            Text("Render into Movies/ArArMedia (.mp4)", color = Color.Black)
        }
    }
}
