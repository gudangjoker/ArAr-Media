package com.ararmedia.editor.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
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
    val eqGains by viewModel.eqGainsFlow.collectAsState()
    val selection by viewModel.audioSelection.collectAsState()
    val playheadMs by viewModel.dawPlaybackMs.collectAsState()
    val isPlaying by viewModel.isDawPlaying.collectAsState()
    val gainReductionDb by viewModel.gainReductionDb.collectAsState()
    val detectedBpm by viewModel.detectedBpm.collectAsState()
    val stemResult by viewModel.stemSplitResult.collectAsState()
    val vocalFader by viewModel.vocalFader.collectAsState()
    val backingFader by viewModel.backingFader.collectAsState()
    val requestedDawTab by viewModel.requestedDawTab.collectAsState()
    val isAiProcessing by viewModel.isAiProcessing.collectAsState()
    val aiStatusMessage by viewModel.aiStatusMessage.collectAsState()

    val isExporting by viewModel.isExporting.collectAsState()
    val exportProgress by viewModel.exportProgress.collectAsState()
    val exportMessage by viewModel.exportMessage.collectAsState()

    var activeTab by remember { mutableIntStateOf(0) } // 0=Waveform Tools, 1=10-Band EQ, 2=Dynamics, 3=Voice/FX, 4=Stem Splitter
    var activePitchSemitones by remember { mutableFloatStateOf(0f) }
    var activePresetId by remember { mutableIntStateOf(0) }
    var showExportSheet by remember { mutableStateOf(false) }

    LaunchedEffect(requestedDawTab) {
        if (requestedDawTab != null) {
            activeTab = requestedDawTab!!
            if (requestedDawTab == 4 && stemResult == null) {
                viewModel.runStemSeparation()
            }
            viewModel.consumeRequestedDawTab()
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importAudio(it) }
    }

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
                    // Left: Back to Home + Workspace Switcher Pill (Video | Audio)
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

                        Row(
                            modifier = Modifier
                                .background(StudioSurfaceVariant, RoundedCornerShape(16.dp))
                                .padding(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { viewModel.navigateTo(1) }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Video", fontSize = 11.sp, color = Color.LightGray)
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(StudioGoldAccent)
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Audio", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.Black)
                            }
                        }
                    }

                    // Right: Import + Export Audio
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { audioPickerLauncher.launch("audio/*") },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = "Import", tint = StudioCyanAccent, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Import", color = Color.White, fontSize = 11.sp)
                        }

                        Button(
                            onClick = { showExportSheet = true },
                            colors = ButtonDefaults.buttonColors(containerColor = StudioGoldAccent),
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
            // 1. High-Precision Waveform Canvas
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

                // Timecode & BPM Overlay
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(StudioSurfaceVariant.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(formatTimecode(playheadMs), fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Medium)
                    Text("${detectedBpm.toInt()} BPM", fontSize = 10.sp, color = StudioCyanAccent)
                }

                if (selection.isActive) {
                    val selDur = selection.endMs - selection.startMs
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(StudioSurfaceVariant, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${selection.startMs}ms - ${selection.endMs}ms (${selDur}ms)",
                            fontSize = 10.sp,
                            color = StudioCyanAccent
                        )
                    }
                }

                if (isAiProcessing || aiStatusMessage != null) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .background(StudioSurfaceVariant.copy(alpha = 0.92f), RoundedCornerShape(20.dp))
                            .border(1.dp, StudioGoldAccent.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isAiProcessing) {
                            CircularProgressIndicator(
                                color = StudioGoldAccent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                        Text(aiStatusMessage ?: "Processing...", fontSize = 11.sp, color = Color.White)
                    }
                }

                FloatingActionButton(
                    onClick = { viewModel.toggleDawPlayback() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        .size(42.dp),
                    containerColor = StudioGoldAccent,
                    contentColor = Color.Black
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause"
                    )
                }
            }

            // 2. Tab Selector
            ScrollableTabRow(
                selectedTabIndex = activeTab,
                containerColor = StudioSurface,
                contentColor = StudioGoldAccent,
                edgePadding = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(selected = activeTab == 0, onClick = { activeTab = 0 }, text = { Text("Waveform Edit", fontSize = 11.sp) })
                Tab(selected = activeTab == 1, onClick = { activeTab = 1 }, text = { Text("10-Band EQ", fontSize = 11.sp) })
                Tab(selected = activeTab == 2, onClick = { activeTab = 2 }, text = { Text("Compressor", fontSize = 11.sp) })
                Tab(selected = activeTab == 3, onClick = { activeTab = 3 }, text = { Text("Voice & Reverb", fontSize = 11.sp) })
                Tab(selected = activeTab == 4, onClick = { activeTab = 4 }, text = { Text("Stem Splitter", fontSize = 11.sp) })
            }

            // 3. Tab Contents
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.62f)
                    .background(StudioDarkBackground)
                    .padding(10.dp)
            ) {
                when (activeTab) {
                    0 -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "Waveform Selection & Mastering",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
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
                                    label = "Reverse",
                                    icon = Icons.Default.History,
                                    modifier = Modifier.weight(1f)
                                ) { viewModel.reverseAudio() }
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DawActionButton(
                                    label = "Fade In",
                                    icon = Icons.Default.TrendingUp,
                                    modifier = Modifier.weight(1f)
                                ) { viewModel.fadeInAudio() }

                                DawActionButton(
                                    label = "Fade Out",
                                    icon = Icons.Default.TrendingDown,
                                    modifier = Modifier.weight(1f)
                                ) { viewModel.fadeOutAudio() }
                            }

                            HorizontalDivider(color = StudioCardBorder, modifier = Modifier.padding(vertical = 4.dp))

                            Text("AI Audio Tools", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { viewModel.applyAiDenoise() },
                                    colors = ButtonDefaults.buttonColors(containerColor = StudioPurpleAccent),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.NoiseAware, contentDescription = "Denoise", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("RNNoise Denoise", fontSize = 11.sp)
                                }

                                Button(
                                    onClick = { viewModel.applyAiVoiceEnhance() },
                                    colors = ButtonDefaults.buttonColors(containerColor = StudioGoldAccent),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.RecordVoiceOver, contentDescription = "Voice Enhance", tint = Color.Black, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Voice Enhancer", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Button(
                                onClick = { activeTab = 4; viewModel.runStemSeparation() },
                                colors = ButtonDefaults.buttonColors(containerColor = StudioCyanAccent),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.CallSplit, contentDescription = "Split Stems", tint = Color.Black, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("AI 2-Stem Separation (Vocals & Backing)", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    1 -> {
                        EqSliderPanel(
                            bandGains = eqGains,
                            onGainChange = { band, gain -> viewModel.setEqGain(band, gain) }
                        )
                    }
                    2 -> {
                        CompressorPanel(
                            gainReductionDb = gainReductionDb,
                            onCompressorChange = { thresh, ratio, att, rel, makeup ->
                                viewModel.setCompressor(thresh, ratio, att, rel, makeup)
                            }
                        )
                    }
                    3 -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Pitch Shift & Voice Effects", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                FilledTonalButton(
                                    onClick = { viewModel.applyAiVoiceEnhance() },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = StudioGoldAccent, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("AI Voice Enhancer & Dereverb", fontSize = 10.sp, color = StudioGoldAccent)
                                }
                            }

                            Column {
                                Text(
                                    text = "Pitch: ${String.format(Locale.US, "%+.1f Semitones", activePitchSemitones)}",
                                    fontSize = 11.sp,
                                    color = Color.LightGray
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

                            val presets = listOf(
                                0 to "Natural",
                                1 to "Deep Podcast",
                                2 to "Chipmunk",
                                3 to "Megaphone",
                                4 to "Radio Broadcast",
                                5 to "Cyber Robot",
                                6 to "Monster",
                                7 to "Cathedral"
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for ((id, name) in presets) {
                                    val isSelected = activePresetId == id
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            activePresetId = id
                                            viewModel.setVoicePreset(id)
                                            activePitchSemitones = when (id) {
                                                1 -> -2.5f
                                                2 -> 7.0f
                                                6 -> -6.5f
                                                else -> 0.0f
                                            }
                                        },
                                        label = { Text(name, fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = StudioCyanAccent,
                                            selectedLabelColor = Color.Black
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text("Spatial Reverb & Delay", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            var roomSize by remember { mutableFloatStateOf(0.6f) }
                            var wetMix by remember { mutableFloatStateOf(0.0f) }
                            var delayMs by remember { mutableFloatStateOf(180f) }

                            fun updateReverb() {
                                viewModel.setReverb(roomSize, 0.25f, wetMix, delayMs)
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Room: ${(roomSize * 100).toInt()}%", fontSize = 10.sp, color = Color.LightGray)
                                    Slider(
                                        value = roomSize,
                                        onValueChange = { roomSize = it; updateReverb() },
                                        valueRange = 0.0f..0.95f,
                                        colors = SliderDefaults.colors(thumbColor = StudioGoldAccent, activeTrackColor = StudioGoldAccent)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Mix: ${(wetMix * 100).toInt()}%", fontSize = 10.sp, color = Color.LightGray)
                                    Slider(
                                        value = wetMix,
                                        onValueChange = { wetMix = it; updateReverb() },
                                        valueRange = 0.0f..0.8f,
                                        colors = SliderDefaults.colors(thumbColor = StudioGoldAccent, activeTrackColor = StudioGoldAccent)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Delay: ${delayMs.toInt()} ms", fontSize = 10.sp, color = Color.LightGray)
                                    Slider(
                                        value = delayMs,
                                        onValueChange = { delayMs = it; updateReverb() },
                                        valueRange = 0f..500f,
                                        colors = SliderDefaults.colors(thumbColor = StudioGoldAccent, activeTrackColor = StudioGoldAccent)
                                    )
                                }
                            }
                        }
                    }
                    4 -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "AI 2-Stem Separation (Vocals & Instrumental)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
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
                                        enabled = !isAiProcessing,
                                        colors = ButtonDefaults.buttonColors(containerColor = StudioCyanAccent)
                                    ) {
                                        if (isAiProcessing) {
                                            CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Separating Stems...", color = Color.Black)
                                        } else {
                                            Icon(Icons.Default.CallSplit, contentDescription = "Split", tint = Color.Black)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Separate Vocals & Instrumental", color = Color.Black, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(StudioSurfaceVariant, RoundedCornerShape(12.dp))
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Lead Vocals", color = StudioCyanAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Text("${(vocalFader * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
                                    }
                                    Slider(
                                        value = vocalFader,
                                        onValueChange = { viewModel.setVocalStemFader(it) },
                                        valueRange = 0f..1.5f,
                                        colors = SliderDefaults.colors(thumbColor = StudioCyanAccent, activeTrackColor = StudioCyanAccent)
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Instrumental Backing", color = StudioGoldAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Text("${(backingFader * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
                                    }
                                    Slider(
                                        value = backingFader,
                                        onValueChange = { viewModel.setBackingStemFader(it) },
                                        valueRange = 0f..1.5f,
                                        colors = SliderDefaults.colors(thumbColor = StudioGoldAccent, activeTrackColor = StudioGoldAccent)
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = {
                                                viewModel.setVocalStemFader(1.0f)
                                                viewModel.setBackingStemFader(0.0f)
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Solo Vocals", fontSize = 11.sp, color = StudioCyanAccent)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                viewModel.setVocalStemFader(0.0f)
                                                viewModel.setBackingStemFader(1.0f)
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Solo Instrumental", fontSize = 11.sp, color = StudioGoldAccent)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                viewModel.sendSeparatedStemToTimeline(useVocals = true)
                                                viewModel.navigateTo(1)
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = StudioCyanAccent),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Add Vocals to Video", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.SemiBold)
                                        }
                                        Button(
                                            onClick = {
                                                viewModel.sendSeparatedStemToTimeline(useVocals = false)
                                                viewModel.navigateTo(1)
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = StudioGoldAccent),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Add Backing to Video", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

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
                Text("Export Mastered Audio", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
                Text("Saves to Music/ArArMedia/", fontSize = 11.sp, color = Color.LightGray)

                Button(
                    onClick = {
                        viewModel.exportAudio(AudioExportFormat.LOSSLESS_WAV)
                        showExportSheet = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = StudioGoldAccent)
                ) {
                    Text("Export Lossless WAV (16-bit 44.1kHz)", color = Color.Black, fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = {
                        viewModel.exportAudio(AudioExportFormat.AAC_M4A)
                        showExportSheet = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = StudioCyanAccent)
                ) {
                    Text("Export M4A (AAC 192kbps)", color = Color.Black, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    if (isExporting) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = StudioSurfaceVariant,
            title = { Text("Exporting Audio", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(exportMessage ?: "Rendering audio...", color = Color.LightGray, fontSize = 12.sp)
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
