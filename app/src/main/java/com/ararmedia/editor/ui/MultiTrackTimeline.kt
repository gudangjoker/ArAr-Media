package com.ararmedia.editor.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ararmedia.editor.model.BeatMarker
import com.ararmedia.editor.model.TimelineClip
import com.ararmedia.editor.model.TrackType
import com.ararmedia.editor.ui.components.TimeRuler
import com.ararmedia.editor.ui.theme.*
import kotlin.math.max

@Composable
fun MultiTrackTimeline(
    clips: List<TimelineClip>,
    selectedClipId: String?,
    playheadMs: Long,
    totalDurationMs: Long,
    beatMarkers: List<BeatMarker>,
    onSelectClip: (String?) -> Unit,
    onSeek: (Long) -> Unit,
    onSplitClip: () -> Unit,
    onDeleteClip: () -> Unit,
    onDuplicateClip: () -> Unit,
    modifier: Modifier = Modifier
) {
    var zoomScale by remember { mutableFloatStateOf(80f) } // 80 pixels per second
    val horizontalScrollState = rememberScrollState()

    val transformState = rememberTransformableState { zoomChange, _, _ ->
        zoomScale = (zoomScale * zoomChange).coerceIn(30f, 300f)
    }

    val totalWidthPx = max(1000f, (totalDurationMs.toFloat() / 1000f) * zoomScale + 400f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(StudioDarkBackground)
    ) {
        // Timeline Header: Tools (Split, Duplicate, Delete) & Timecode
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(StudioSurface)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Split / Razor
                FilledTonalIconButton(
                    onClick = onSplitClip,
                    enabled = selectedClipId != null,
                    modifier = Modifier.size(34.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = StudioSurfaceVariant)
                ) {
                    Icon(Icons.Default.ContentCut, contentDescription = "Split", tint = StudioCyanAccent, modifier = Modifier.size(18.dp))
                }

                // Duplicate
                FilledTonalIconButton(
                    onClick = onDuplicateClip,
                    enabled = selectedClipId != null,
                    modifier = Modifier.size(34.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = StudioSurfaceVariant)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate", tint = StudioGoldAccent, modifier = Modifier.size(18.dp))
                }

                // Delete
                FilledTonalIconButton(
                    onClick = onDeleteClip,
                    enabled = selectedClipId != null,
                    modifier = Modifier.size(34.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = StudioSurfaceVariant)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = StudioRedAccent, modifier = Modifier.size(18.dp))
                }
            }

            // Zoom indicator
            Text(
                text = "Zoom: ${(zoomScale / 80f * 100).toInt()}%",
                fontSize = 11.sp,
                color = Color.Gray
            )
        }

        // Time Ruler
        TimeRuler(
            totalDurationMs = totalDurationMs,
            currentPlayheadMs = playheadMs,
            zoomScale = zoomScale,
            scrollOffsetPx = horizontalScrollState.value.toFloat(),
            beatMarkers = beatMarkers,
            onSeek = onSeek
        )

        // 5 Dedicated Magnetic Tracks Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .transformable(transformState)
        ) {
            // Scrollable Tracks Content
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScrollState)
            ) {
                // Spacer for track headers on left
                Spacer(modifier = Modifier.width(54.dp))

                Box(modifier = Modifier.width(totalWidthPx.dp)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(totalDurationMs, zoomScale) {
                                detectTapGestures { offset ->
                                    val timeMs = ((offset.x / zoomScale) * 1000L).toLong()
                                    onSeek(timeMs.coerceIn(0L, totalDurationMs))
                                    onSelectClip(null)
                                }
                            }
                    ) {
                        TrackLane(
                            trackType = TrackType.MAIN_VIDEO,
                            clips = clips.filter { it.trackType == TrackType.MAIN_VIDEO },
                            selectedClipId = selectedClipId,
                            zoomScale = zoomScale,
                            onSelectClip = onSelectClip
                        )
                        TrackLane(
                            trackType = TrackType.PIP_OVERLAY,
                            clips = clips.filter { it.trackType == TrackType.PIP_OVERLAY },
                            selectedClipId = selectedClipId,
                            zoomScale = zoomScale,
                            onSelectClip = onSelectClip
                        )
                        TrackLane(
                            trackType = TrackType.TEXT_STICKER,
                            clips = clips.filter { it.trackType == TrackType.TEXT_STICKER },
                            selectedClipId = selectedClipId,
                            zoomScale = zoomScale,
                            onSelectClip = onSelectClip
                        )
                        TrackLane(
                            trackType = TrackType.MUSIC_BGM,
                            clips = clips.filter { it.trackType == TrackType.MUSIC_BGM },
                            selectedClipId = selectedClipId,
                            zoomScale = zoomScale,
                            onSelectClip = onSelectClip
                        )
                        TrackLane(
                            trackType = TrackType.VOICEOVER,
                            clips = clips.filter { it.trackType == TrackType.VOICEOVER },
                            selectedClipId = selectedClipId,
                            zoomScale = zoomScale,
                            onSelectClip = onSelectClip
                        )
                    }

                    // Red Playhead Scrubber Line
                    val playheadX = (playheadMs / 1000f) * zoomScale
                    Box(
                        modifier = Modifier
                            .offset(x = playheadX.dp)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(StudioCyanAccent)
                    )
                }
            }

            // Fixed Left Track Badges
            Column(
                modifier = Modifier
                    .width(54.dp)
                    .fillMaxHeight()
                    .background(StudioSurfaceVariant.copy(alpha = 0.95f))
            ) {
                TrackBadge(icon = Icons.Default.Videocam, title = "V1", color = TrackMainVideoColor)
                TrackBadge(icon = Icons.Default.Layers, title = "PIP", color = TrackPipColor)
                TrackBadge(icon = Icons.Default.TextFields, title = "TXT", color = TrackTextColor)
                TrackBadge(icon = Icons.Default.MusicNote, title = "BGM", color = TrackMusicColor)
                TrackBadge(icon = Icons.Default.Mic, title = "REC", color = TrackVoiceoverColor)
            }
        }
    }
}

@Composable
private fun TrackBadge(icon: ImageVector, title: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .border(0.5.dp, Color(0xFF2C3048)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = title, tint = color, modifier = Modifier.size(16.dp))
            Text(title, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
private fun TrackLane(
    trackType: TrackType,
    clips: List<TimelineClip>,
    selectedClipId: String?,
    zoomScale: Float,
    onSelectClip: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .border(0.5.dp, Color(0xFF1E2132))
            .background(Color(0xFF0F111A))
    ) {
        for (clip in clips) {
            val leftOffset = (clip.startOnTimelineMs / 1000f) * zoomScale
            val clipWidth = max(28f, (clip.durationMs / 1000f) * zoomScale)
            val isSelected = clip.id == selectedClipId

            val baseColor = when (trackType) {
                TrackType.MAIN_VIDEO -> Color(0xFF1E3A8A)
                TrackType.PIP_OVERLAY -> Color(0xFF5B21B6)
                TrackType.TEXT_STICKER -> Color(0xFF92400E)
                TrackType.MUSIC_BGM -> Color(0xFF065F46)
                TrackType.VOICEOVER -> Color(0xFF831843)
            }

            Box(
                modifier = Modifier
                    .offset(x = leftOffset.dp)
                    .width(clipWidth.dp)
                    .fillMaxHeight()
                    .padding(vertical = 3.dp, horizontal = 1.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(baseColor)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) StudioCyanAccent else Color.White.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .clickable { onSelectClip(clip.id) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Keyframe diamond indicator if clip has keyframes
                    if (clip.keyframes.isNotEmpty()) {
                        Text("♦ ", color = StudioGoldAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = if (clip.textContent.isNotEmpty()) clip.textContent else clip.name,
                        color = Color.White,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
