package com.ararmedia.editor.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ararmedia.editor.R
import com.ararmedia.editor.StudioViewModel
import com.ararmedia.editor.model.TrackType
import com.ararmedia.editor.ui.components.formatTimecode
import com.ararmedia.editor.ui.theme.*

@Composable
fun HomeScreen(
    viewModel: StudioViewModel,
    modifier: Modifier = Modifier
) {
    val clips by viewModel.timelineClips.collectAsState()
    val previewBmp by viewModel.currentFrameBitmap.collectAsState()
    val aspectRatio by viewModel.aspectRatio.collectAsState()

    val newProjectMediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.startNewBlankProject()
            viewModel.importVisualMedia(uri, TrackType.MAIN_VIDEO)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StudioDarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        // Minimal Studio Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "ArAr Media",
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Column {
                    Text(
                        text = "ArAr Media",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Video & Audio Studio",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            FilledTonalIconButton(
                onClick = { viewModel.navigateTo(2) },
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = StudioSurfaceVariant)
            ) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = "Audio DAW",
                    tint = StudioCyanAccent
                )
            }
        }

        // "+ New Project" Primary Hero Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF00C6FF),
                            Color(0xFF0072FF)
                        )
                    )
                )
                .clickable { newProjectMediaPicker.launch("*/*") }
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "New Project",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Column {
                    Text(
                        text = "New Project",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Import video or photo to start editing",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }

        // Quick Studio Tools Grid
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                QuickToolItem(
                    icon = Icons.Default.ClosedCaption,
                    label = "Auto Captions",
                    tint = StudioCyanAccent,
                    modifier = Modifier.weight(1f)
                ) {
                    viewModel.openVideoStudioWithTool("text")
                }
                QuickToolItem(
                    icon = Icons.Default.AutoFixHigh,
                    label = "Remove BG",
                    tint = StudioGreenAccent,
                    modifier = Modifier.weight(1f)
                ) {
                    viewModel.openVideoStudioWithTool("chroma")
                }
                QuickToolItem(
                    icon = Icons.Default.CallSplit,
                    label = "Vocal Remover",
                    tint = StudioPurpleAccent,
                    modifier = Modifier.weight(1f)
                ) {
                    viewModel.openAudioDawWithTab(4)
                }
                QuickToolItem(
                    icon = Icons.Default.NoiseAware,
                    label = "AI Denoise",
                    tint = StudioGoldAccent,
                    modifier = Modifier.weight(1f)
                ) {
                    viewModel.openAudioDawWithTab(0)
                    viewModel.applyAiDenoise()
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                QuickToolItem(
                    icon = Icons.Default.Speed,
                    label = "Speed Curve",
                    tint = StudioPinkAccent,
                    modifier = Modifier.weight(1f)
                ) {
                    viewModel.openVideoStudioWithTool("speed")
                }
                QuickToolItem(
                    icon = Icons.Default.MotionPhotosAuto,
                    label = "Stabilize",
                    tint = StudioCyanAccent,
                    modifier = Modifier.weight(1f)
                ) {
                    viewModel.navigateTo(1)
                    viewModel.toggleClipStabilization()
                }
                QuickToolItem(
                    icon = Icons.Default.FilterVintage,
                    label = "Color LUTs",
                    tint = StudioGoldAccent,
                    modifier = Modifier.weight(1f)
                ) {
                    viewModel.openVideoStudioWithTool("color")
                }
                QuickToolItem(
                    icon = Icons.Default.GraphicEq,
                    label = "Audio DAW",
                    tint = StudioGreenAccent,
                    modifier = Modifier.weight(1f)
                ) {
                    viewModel.openAudioDawWithTab(1)
                }
            }
        }

        // Active Project / Drafts Section
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Active Project",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                TextButton(
                    onClick = {
                        viewModel.loadDemoProject()
                        viewModel.navigateTo(1)
                    }
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Reset Sample",
                        tint = TextSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset Sample", fontSize = 12.sp, color = TextSecondary)
                }
            }

            // Active Project Card
            Card(
                onClick = { viewModel.navigateTo(1) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, StudioCardBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 64.dp, height = 84.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(StudioSurfaceVariant)
                            .border(1.dp, StudioCardBorder, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (previewBmp != null && !previewBmp!!.isRecycled) {
                            Image(
                                bitmap = previewBmp!!.asImageBitmap(),
                                contentDescription = "Project Preview",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                Icons.Default.Movie,
                                contentDescription = null,
                                tint = TextSecondary
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (clips.isNotEmpty()) clips.first().name else "Untitled Timeline",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = "${clips.size} clips • ${formatTimecode(viewModel.getTotalDurationMs())} • ${aspectRatio.label}",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.navigateTo(1) },
                                colors = ButtonDefaults.buttonColors(containerColor = StudioCyanAccent),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Open Video Editor", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(
                                onClick = { viewModel.navigateTo(2) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Audio Studio", color = Color.White, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickToolItem(
    icon: ImageVector,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(StudioSurface, RoundedCornerShape(14.dp))
                .border(1.dp, StudioCardBorder, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = label,
            fontSize = 11.sp,
            color = TextSecondary,
            fontWeight = FontWeight.Medium
        )
    }
}
