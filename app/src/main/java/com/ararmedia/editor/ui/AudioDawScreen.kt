package com.ararmedia.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ararmedia.editor.StudioViewModel
import com.ararmedia.editor.engine.AudioExportFormat
import com.ararmedia.editor.ui.components.*
import com.ararmedia.editor.ui.theme.*
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioDawScreen(
    viewModel: StudioViewModel,
    modifier: Modifier = Modifier
) {
    val audioSamples by viewModel.audioBufferData.collectAsState()
    val selection by viewModel.audioSelection.collectAsState()
    val playheadMs by viewModel.dawPlaybackMs.collectAsState()
    val isPlaying by viewModel.isDawPlaying.collectAsState()
    val gainReductionDb by viewModel.gainReductionDb.collectAsState()
    val detectedBpm by viewModel.detectedBpm.collectAsState()
    val stemResult by viewModel.stemSplitResult.collectAsState()
    val vocalFader by viewModel.vocalFader.collectAsState()
    val backingFader by viewModel.backingFader.collectAsState()
    val isExporting by viewModel.isExporting.collectAsState()
    val exportProgress by viewModel.exportProgress.collectAsState()
    val exportMessage by viewModel.exportMessage.collectAsState()

    var activeTab by remember { mutableIntStateOf(0) } // 0=Waveform Tools, 1=10-Band EQ, 2=Compressor, 3=Voice/FX, 4=AI Stem Splitter
    var activePitchSemitones by remember { mutableFloatStateOf(0f) }
    var showExportSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ArAr Audio DAW & Mastering", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(formatTimecode(playheadMs), fontSize = 11.sp, color = StudioGoldAccent)
                            Text("• BPM: ${detectedBpm.toInt()}", fontSize = 11.sp, color = StudioCyanAccent)
                        }
                    }
                },
                actions = {
                    // Export Master Audio
                    Button(
                        onClick = { showExportSheet = true },
                        colors = ButtonDefaults.buttonColors(containerColor = StudioGoldAccent),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Export Audio", color = Color.Black, fontSize = 12.sp)
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
            // 1. Zoomable High-Precision Dual-Channel Waveform Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.38f)
                    .background(Color(0xFF0D0F18))
                    .padding(8.dp)
            ) {
                WaveformCanvas(
                    audioSamples = audioSamples,
                    sampleRate = 44100,
                    currentPlayheadMs = playheadMs,
                    selection = selection,
                    onSelectionChange = { start, end -> viewModel.setAudioSelection(start, end) },
                    onSeek = { viewModel.seekDaw(it) }
                )

                // Selection timecode overlay pill
                if (selection.isActive) {
                    val selDur = selection.endMs - selection.startMs
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(StudioSurfaceVariant, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Sel: ${selection.startMs}ms - ${selection.endMs}ms (${selDur}ms)",
                            fontSize = 10.sp,
                            color = StudioCyanAccent
                        )
                    }
                }

                // Transport Play/Pause Floating Pill
                FloatingActionButton(
                    onClick = { viewModel.toggleDawPlayback() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        .size(44.dp),
                    containerColor = StudioGoldAccent,
                    contentColor = Color.Black
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause"
                    )
                }
            }

            // 2. Tab Selector: Waveform Tools | 10-Band EQ | Dynamics | Voice/FX | AI Stems
            ScrollableTabRow(
                selectedTabIndex = activeTab,
                containerColor = StudioSurface,
                contentColor = StudioGoldAccent,
                edgePadding = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(selected = activeTab == 0, onClick = { activeTab = 0 }, text = { Text("Waveform Tools", fontSize = 11.sp) })
                Tab(selected = activeTab == 1, onClick = { activeTab = 1 }, text = { Text("10-Band EQ", fontSize = 11.sp) })
                Tab(selected = activeTab == 2, onClick = { activeTab = 2 }, text = { Text("Dynamics / Limiter", fontSize = 11.sp) })
                Tab(selected = activeTab == 3, onClick = { activeTab = 3 }, text = { Text("Voice / FX", fontSize = 11.sp) })
                Tab(selected = activeTab == 4, onClick = { activeTab = 4 }, text = { Text("AI Stem Splitter", fontSize = 11.sp) })
            }

            // 3. Tab Contents
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.62f)
                    .background(StudioDarkBackground)
                    .padding(8.dp)
            ) {
                when (activeTab) {
                    0 -> {
                        // Waveform Region Actions
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "Waveform Selection Actions (Audacity Precision):",
                                fontSize = 12.sp,
                                color = StudioCyanAccent
                            )

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DawActionButton(
                                    label = "Trim Selection",
                                    icon = Icons.Default.Crop,
                                    isEnabled = selection.isActive,
                                    modifier = Modifier.weight(1f)
                                ) { viewModel.trimAudioSelection() }

                                DawActionButton(
                                    label = "Cut & Stitch",
                                    icon = Icons.Default.ContentCut,
                                    isEnabled = selection.isActive,
                                    modifier = Modifier.weight(1f)
                                ) { viewModel.cutMiddleAudio() }

                                DawActionButton(
                                    label = "Silence Region",
                                    icon = Icons.Default.VolumeMute,
                                    isEnabled = selection.isActive,
                                    modifier = Modifier.weight(1f)
                                ) { viewModel.silenceAudioSelection() }
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DawActionButton(
                                    label = "Normalize (0 dB)",
                                    icon = Icons.Default.Equalizer,
                                    modifier = Modifier.weight(1f)
                                ) { viewModel.normalizeAudio(0f) }

                                DawActionButton(
                                    label = "Normalize (-1 dB)",
                                    icon = Icons.Default.GraphicEq,
                                    modifier = Modifier.weight(1f)
                                ) { viewModel.normalizeAudio(-1f) }

                                DawActionButton(
                                    label = "Reverse Audio",
                                    icon = Icons.Default.RotateLeft,
                                    modifier = Modifier.weight(1f)
                                ) { viewModel.reverseAudio() }
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DawActionButton(
                                    label = "Fade In (1.5s)",
                                    icon = Icons.Default.TrendingUp,
                                    modifier = Modifier.weight(1f)
                                ) { viewModel.fadeInAudio() }

                                DawActionButton(
                                    label = "Fade Out (1.5s)",
                                    icon = Icons.Default.TrendingDown,
                                    modifier = Modifier.weight(1f)
                                ) { viewModel.fadeOutAudio() }
                            }

                            Divider(color = StudioCardBorder, modifier = Modifier.padding(vertical = 4.dp))

                            // Offline Audio AI Suite
                            Text("Offline Audio AI Suite:", fontSize = 12.sp, color = StudioGoldAccent)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { viewModel.applyAiDenoise() },
                                    colors = ButtonDefaults.buttonColors(containerColor = StudioPurpleAccent),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.NoiseAware, contentDescription = "Denoise", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("AI Studio Denoise", fontSize = 11.sp)
                                }

                                Button(
                                    onClick = { activeTab = 4; viewModel.runStemSeparation() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B0FF)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.CallSplit, contentDescription = "Split Stems", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Separate Stems", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    1 -> {
                        // 10-Band Parametric EQ
                        EqSliderPanel(
                            bandGains = viewModel.dspProcessor.eqGains,
                            onGainChange = { band, gain -> viewModel.setEqGain(band, gain) }
                        )
                    }
                    2 -> {
                        // Dynamics Processor (Compressor & Limiter)
                        CompressorPanel(
                            gainReductionDb = gainReductionDb,
                            onCompressorChange = { thresh, ratio, att, rel, makeup ->
                                viewModel.setCompressor(thresh, ratio, att, rel, makeup)
                            }
                        )
                    }
                    3 -> {
                        // Voice Changer & Reverb Engine
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Voice Changer & Pitch Shifter:", fontSize = 12.sp, color = StudioCyanAccent)

                            // Pitch Shift Slider (-12 to +12 Semitones)
                            Column {
                                Text(
                                    text = "Pitch Shift: ${String.format(Locale.US, "%+.1f Semitones", activePitchSemitones)}",
                                    fontSize = 11.sp,
                                    color = Color.White
                                )
                                Slider(
                                    value = activePitchSemitones,
                                    onValueChange = {
                                        activePitchSemitones = it
                                        viewModel.setPitchShift(it)
                                    },
                                    valueRange = -12f..12f,
                                    colors = SliderDefaults.colors(thumbColor = StudioCyanAccent, activeTrackColor = StudioCyanAccent)
                                )
                            }

                            // Presets
                            Text("Voice Changer Presets:", fontSize = 11.sp, color = Color.LightGray)
                            val presets = listOf(
                                0 to "Standard Natural",
                                1 to "Deep Podcast Voice",
                                2 to "Helium / Chipmunk",
                                3 to "Vintage Megaphone",
                                4 to "Studio Radio Broadcast",
                                5 to "Cyber Robot",
                                6 to "Monster Voice",
                                7 to "Cathedral Reverb"
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for ((id, name) in presets) {
                                    Button(
                                        onClick = { viewModel.setVoicePreset(id) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = StudioSurfaceVariant)
                                    ) {
                                        Text(name, fontSize = 11.sp, color = Color.White)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Spatial Reverb Controls
                            Text("Freeverb Spatial Reverb:", fontSize = 12.sp, color = StudioGoldAccent)
                            var roomSize by remember { mutableFloatStateOf(0.6f) }
                            var wetMix by remember { mutableFloatStateOf(0.3f) }
                            var delayMs by remember { mutableFloatStateOf(180f) }

                            fun updateReverb() {
                                viewModel.setReverb(roomSize, 0.25f, wetMix, delayMs)
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Room Size: ${(roomSize * 100).toInt()}%", fontSize = 10.sp, color = Color.White)
                                    Slider(
                                        value = roomSize,
                                        onValueChange = { roomSize = it; updateReverb() },
                                        valueRange = 0.0f..0.95f,
                                        colors = SliderDefaults.colors(thumbColor = StudioGoldAccent, activeTrackColor = StudioGoldAccent)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Wet Mix: ${(wetMix * 100).toInt()}%", fontSize = 10.sp, color = Color.White)
                                    Slider(
                                        value = wetMix,
                                        onValueChange = { wetMix = it; updateReverb() },
                                        valueRange = 0.0f..0.8f,
                                        colors = SliderDefaults.colors(thumbColor = StudioGoldAccent, activeTrackColor = StudioGoldAccent)
                                    )
                                }
                            }
                        }
                    }
                    4 -> {
                        // AI Stem Splitter Panel (Vocals vs Accompaniment)
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "AI 2-Stem Isolation (Lead Vocals vs Instrumental):",
                                fontSize = 12.sp,
                                color = StudioCyanAccent
                            )

                            if (stemResult == null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(140.dp)
                                        .background(StudioSurfaceVariant, RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Button(
                                        onClick = { viewModel.runStemSeparation() },
                                        colors = ButtonDefaults.buttonColors(containerColor = StudioCyanAccent)
                                    ) {
                                        Icon(Icons.Default.CallSplit, contentDescription = "Split", tint = Color.Black)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Extract Vocals & Instrumental Stems", color = Color.Black)
                                    }
                                }
                            } else {
                                // Stem Faders
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(StudioSurfaceVariant, RoundedCornerShape(12.dp))
                                        .padding(12.dp)
                                ) {
                                    // Vocals Fader
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Lead Vocals Stem:", color = StudioCyanAccent, fontSize = 12.sp)
                                        Text("${(vocalFader * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
                                    }
                                    Slider(
                                        value = vocalFader,
                                        onValueChange = { viewModel.setVocalStemFader(it) },
                                        valueRange = 0f..1.5f,
                                        colors = SliderDefaults.colors(thumbColor = StudioCyanAccent, activeTrackColor = StudioCyanAccent)
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    // Backing Track Fader
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Instruments & Backing Stem:", color = StudioGoldAccent, fontSize = 12.sp)
                                        Text("${(backingFader * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
                                    }
                                    Slider(
                                        value = backingFader,
                                        onValueChange = { viewModel.setBackingStemFader(it) },
                                        valueRange = 0f..1.5f,
                                        colors = SliderDefaults.colors(thumbColor = StudioGoldAccent, activeTrackColor = StudioGoldAccent)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Audio Export Bottom Sheet
    if (showExportSheet) {
        ModalBottomSheet(
            onDismissRequest = { showExportSheet = false },
            containerColor = StudioSurfaceVariant
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Export Mastered Audio", style = MaterialTheme.typography.titleMedium, color = StudioGoldAccent)
                Text("Direct output to Music/ArArMedia/", fontSize = 11.sp, color = Color.LightGray)

                Button(
                    onClick = {
                        viewModel.exportAudio(AudioExportFormat.LOSSLESS_WAV)
                        showExportSheet = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = StudioGoldAccent)
                ) {
                    Text("Export Uncompressed Lossless WAV (16-bit 44.1kHz)", color = Color.Black)
                }

                Button(
                    onClick = {
                        viewModel.exportAudio(AudioExportFormat.AAC_M4A)
                        showExportSheet = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = StudioCyanAccent)
                ) {
                    Text("Export Compressed M4A (AAC 192kbps)", color = Color.Black)
                }
            }
        }
    }

    // Export Progress Dialog
    if (isExporting) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = StudioSurfaceVariant,
            title = { Text("Master Audio Export", color = StudioGoldAccent) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(exportMessage ?: "Rendering PCM samples...", color = Color.White, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { exportProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = StudioGoldAccent
                    )
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun DawActionButton(
    label: String,
    icon: ImageVector,
    isEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = isEnabled,
        modifier = modifier.height(42.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = StudioSurfaceVariant,
            disabledContainerColor = Color(0xFF161824)
        )
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = if (isEnabled) StudioCyanAccent else Color.DarkGray, modifier = Modifier.size(16.dp))
            Text(label, fontSize = 9.sp, color = if (isEnabled) Color.White else Color.DarkGray)
        }
    }
}
