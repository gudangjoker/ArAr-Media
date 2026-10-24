package com.ararmedia.editor.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ararmedia.editor.model.BeatMarker
import com.ararmedia.editor.model.TimelineClip
import com.ararmedia.editor.model.TrackType
import com.ararmedia.editor.ui.components.TimeRuler
import com.ararmedia.editor.ui.theme.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
    onMoveClipStart: (String, Long) -> Unit = { _, _ -> },
    onTrimClipStart: (String, Long) -> Unit = { _, _ -> },
    onTrimClipEnd: (String, Long) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var zoomScale by remember { mutableFloatStateOf(80f) } // 80 dp per second
    val horizontalScrollState = rememberScrollState()
    val density = LocalDensity.current.density

    val transformState = rememberTransformableState { zoomChange, _, _ ->
        zoomScale = (zoomScale * zoomChange).coerceIn(30f, 300f)
    }

    val totalWidthDp = max(900f, (totalDurationMs.toFloat() / 1000f) * zoomScale + 360f)
    val zoomScalePxPerSec = zoomScale * density

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(StudioDarkBackground)
    ) {
        // Compact Timeline Control Strip: Quick Split, Duplicate, Delete & Zoom
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(StudioSurface)
                .padding(horizontal = 10.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onSplitClip,
                    enabled = selectedClipId != null,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCut,
                        contentDescription = "Split",
                        tint = if (selectedClipId != null) StudioCyanAccent else Color.DarkGray,
                        modifier = Modifier.size(15.dp)
                    )
                }

                IconButton(
                    onClick = onDuplicateClip,
                    enabled = selectedClipId != null,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Duplicate",
                        tint = if (selectedClipId != null) Color.LightGray else Color.DarkGray,
                        modifier = Modifier.size(15.dp)
                    )
                }

                IconButton(
                    onClick = onDeleteClip,
                    enabled = selectedClipId != null,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Delete",
                        tint = if (selectedClipId != null) StudioRedAccent else Color.DarkGray,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(
                    onClick = { zoomScale = (zoomScale * 0.8f).coerceIn(30f, 300f) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Zoom Out", tint = Color.Gray, modifier = Modifier.size(14.dp))
                }
                Text(
                    text = "${(zoomScale / 80f * 100).toInt()}%",
                    fontSize = 10.sp,
                    color = Color.LightGray
                )
                IconButton(
                    onClick = { zoomScale = (zoomScale * 1.25f).coerceIn(30f, 300f) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Zoom In", tint = Color.Gray, modifier = Modifier.size(14.dp))
                }
            }
        }

        // Time Ruler Row
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(34.dp)
                    .background(Color(0xFF141622)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(13.dp)
                )
            }
            TimeRuler(
                totalDurationMs = totalDurationMs,
                currentPlayheadMs = playheadMs,
                zoomScale = zoomScalePxPerSec,
                scrollOffsetPx = horizontalScrollState.value.toFloat(),
                beatMarkers = beatMarkers,
                onSeek = onSeek,
                modifier = Modifier.weight(1f)
            )
        }

        // 5 Magnetic Tracks Area
        val verticalScrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .transformable(transformState)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(verticalScrollState)
            ) {
                // Left Track Headers
                Column(
                    modifier = Modifier
                        .width(46.dp)
                        .background(StudioSurfaceVariant.copy(alpha = 0.95f))
                ) {
                    TrackHeaderIcon(icon = Icons.Default.Videocam, title = "V1", color = StudioCyanAccent)
                    TrackHeaderIcon(icon = Icons.Default.PictureInPictureAlt, title = "PIP", color = StudioPurpleAccent)
                    TrackHeaderIcon(icon = Icons.Default.TextFields, title = "TXT", color = StudioGoldAccent)
                    TrackHeaderIcon(icon = Icons.Default.MusicNote, title = "BGM", color = StudioGreenAccent)
                    TrackHeaderIcon(icon = Icons.Default.Mic, title = "VO", color = StudioPinkAccent)
                }

                // Horizontally Scrollable Tracks Content
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(horizontalScrollState)
                ) {
                    Box(modifier = Modifier.width(totalWidthDp.dp)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(totalDurationMs, zoomScalePxPerSec) {
                                    detectTapGestures { offset ->
                                        val timeMs = ((offset.x / zoomScalePxPerSec) * 1000L).toLong()
                                        onSeek(timeMs.coerceIn(0L, max(1000L, totalDurationMs)))
                                        onSelectClip(null)
                                    }
                                }
                        ) {
                            TrackLane(
                                trackType = TrackType.MAIN_VIDEO,
                                clips = clips.filter { it.trackType == TrackType.MAIN_VIDEO },
                                selectedClipId = selectedClipId,
                                zoomScale = zoomScale,
                                density = density,
                                onSelectClip = onSelectClip,
                                onMoveClipStart = onMoveClipStart,
                                onTrimClipStart = onTrimClipStart,
                                onTrimClipEnd = onTrimClipEnd
                            )
                            TrackLane(
                                trackType = TrackType.PIP_OVERLAY,
                                clips = clips.filter { it.trackType == TrackType.PIP_OVERLAY },
                                selectedClipId = selectedClipId,
                                zoomScale = zoomScale,
                                density = density,
                                onSelectClip = onSelectClip,
                                onMoveClipStart = onMoveClipStart,
                                onTrimClipStart = onTrimClipStart,
                                onTrimClipEnd = onTrimClipEnd
                            )
                            TrackLane(
                                trackType = TrackType.TEXT_STICKER,
                                clips = clips.filter { it.trackType == TrackType.TEXT_STICKER },
                                selectedClipId = selectedClipId,
                                zoomScale = zoomScale,
                                density = density,
                                onSelectClip = onSelectClip,
                                onMoveClipStart = onMoveClipStart,
                                onTrimClipStart = onTrimClipStart,
                                onTrimClipEnd = onTrimClipEnd
                            )
                            TrackLane(
                                trackType = TrackType.MUSIC_BGM,
                                clips = clips.filter { it.trackType == TrackType.MUSIC_BGM },
                                selectedClipId = selectedClipId,
                                zoomScale = zoomScale,
                                density = density,
                                onSelectClip = onSelectClip,
                                onMoveClipStart = onMoveClipStart,
                                onTrimClipStart = onTrimClipStart,
                                onTrimClipEnd = onTrimClipEnd
                            )
                            TrackLane(
                                trackType = TrackType.VOICEOVER,
                                clips = clips.filter { it.trackType == TrackType.VOICEOVER },
                                selectedClipId = selectedClipId,
                                zoomScale = zoomScale,
                                density = density,
                                onSelectClip = onSelectClip,
                                onMoveClipStart = onMoveClipStart,
                                onTrimClipStart = onTrimClipStart,
                                onTrimClipEnd = onTrimClipEnd
                            )
                        }

                        // Vertical Playhead Scrubber Line across all 5 tracks (5 * 44dp = 220dp)
                        val playheadXDp = (playheadMs / 1000f) * zoomScale
                        Box(
                            modifier = Modifier
                                .offset(x = playheadXDp.dp)
                                .width(2.dp)
                                .height(220.dp)
                                .background(Color.White)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackHeaderIcon(icon: ImageVector, title: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .border(0.5.dp, Color(0xFF23273A)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = title, tint = color, modifier = Modifier.size(14.dp))
            Text(title, fontSize = 8.sp, fontWeight = FontWeight.Medium, color = Color.LightGray)
        }
    }
}

@Composable
private fun TrackLane(
    trackType: TrackType,
    clips: List<TimelineClip>,
    selectedClipId: String?,
    zoomScale: Float,
    density: Float,
    onSelectClip: (String) -> Unit,
    onMoveClipStart: (String, Long) -> Unit,
    onTrimClipStart: (String, Long) -> Unit,
    onTrimClipEnd: (String, Long) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .border(0.5.dp, Color(0xFF1A1D2C))
            .background(Color(0xFF0E1018))
    ) {
        for (clip in clips) {
            val leftOffsetDp = (clip.startOnTimelineMs / 1000f) * zoomScale
            val clipWidthDp = max(32f, (clip.durationMs / 1000f) * zoomScale)
            val isSelected = clip.id == selectedClipId

            val baseColor = when (trackType) {
                TrackType.MAIN_VIDEO -> Color(0xFF1D3557)
                TrackType.PIP_OVERLAY -> Color(0xFF432371)
                TrackType.TEXT_STICKER -> Color(0xFF7C3F00)
                TrackType.MUSIC_BGM -> Color(0xFF0B5240)
                TrackType.VOICEOVER -> Color(0xFF6B1D3E)
            }

            var dragStartMs by remember(clip.id, clip.startOnTimelineMs) { mutableLongStateOf(clip.startOnTimelineMs) }
            var accumDragPx by remember(clip.id) { mutableFloatStateOf(0f) }

            Box(
                modifier = Modifier
                    .offset(x = leftOffsetDp.dp)
                    .width(clipWidthDp.dp)
                    .fillMaxHeight()
                    .padding(vertical = 3.dp, horizontal = 0.5.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (clip.isMuted) baseColor.copy(alpha = 0.45f) else baseColor)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .clickable { onSelectClip(clip.id) }
                    .pointerInput(clip.id, zoomScale, density) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                onSelectClip(clip.id)
                                dragStartMs = clip.startOnTimelineMs
                                accumDragPx = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                accumDragPx += dragAmount.x
                                val deltaMs = ((accumDragPx / (zoomScale * density)) * 1000f).toLong()
                                onMoveClipStart(clip.id, (dragStartMs + deltaMs).coerceAtLeast(0L))
                            }
                        )
                    }
            ) {
                // Optional visual thumbnail strip for Video/PIP clips
                if ((trackType == TrackType.MAIN_VIDEO || trackType == TrackType.PIP_OVERLAY) &&
                    clip.thumbnailBitmap != null && !clip.thumbnailBitmap.isRecycled
                ) {
                    Image(
                        bitmap = clip.thumbnailBitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(0.32f)
                    )
                }

                // Optional mini waveform for Audio/Voiceover clips
                if ((trackType == TrackType.MUSIC_BGM || trackType == TrackType.VOICEOVER) &&
                    clip.audioSamples != null && clip.audioSamples.isNotEmpty()
                ) {
                    Canvas(modifier = Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                        val samples = clip.audioSamples
                        val bars = (size.width / 4f).toInt().coerceIn(8, 120)
                        val step = max(1, samples.size / bars)
                        val midY = size.height / 2f
                        for (b in 0 until bars) {
                            val idx = (b * step).coerceIn(0, samples.size - 1)
                            val amp = abs(samples[idx]).coerceIn(0.05f, 1.0f)
                            val h = amp * midY * 0.9f
                            val x = (b.toFloat() / bars) * size.width
                            drawLine(
                                color = Color.White.copy(alpha = 0.35f),
                                start = Offset(x, midY - h),
                                end = Offset(x, midY + h),
                                strokeWidth = 2f
                            )
                        }
                    }
                }

                // Clip Content Label & Status Indicators
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = if (isSelected) 12.dp else 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (clip.keyframes.isNotEmpty()) {
                        Text("♦", color = StudioGoldAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    if (clip.transitionIn.name != "NONE") {
                        Text("⚡", color = StudioCyanAccent, fontSize = 8.sp)
                    }
                    if (clip.speed != 1.0f || clip.speedCurve.name != "NORMAL") {
                        Text(
                            text = if (clip.speedCurve.name != "NORMAL") clip.speedCurve.title else "${clip.speed}x",
                            color = StudioCyanAccent,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = if (clip.textContent.isNotEmpty()) clip.textContent else clip.name,
                        color = Color.White,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Left & Right Trim Handles when Selected
                if (isSelected) {
                    var initStartMs by remember(clip.id, clip.startOnTimelineMs) { mutableLongStateOf(clip.startOnTimelineMs) }
                    var leftDragPx by remember(clip.id) { mutableFloatStateOf(0f) }

                    // Left Trim Handle
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(10.dp)
                            .fillMaxHeight()
                            .background(Color.White, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                            .pointerInput(clip.id, zoomScale, density) {
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        initStartMs = clip.startOnTimelineMs
                                        leftDragPx = 0f
                                    },
                                    onHorizontalDrag = { change, dragAmount ->
                                        change.consume()
                                        leftDragPx += dragAmount
                                        val deltaMs = ((leftDragPx / (zoomScale * density)) * 1000f).toLong()
                                        onTrimClipStart(clip.id, initStartMs + deltaMs)
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(14.dp)
                                .background(Color.Black, RoundedCornerShape(1.dp))
                        )
                    }

                    var initDurMs by remember(clip.id, clip.durationMs) { mutableLongStateOf(clip.durationMs) }
                    var rightDragPx by remember(clip.id) { mutableFloatStateOf(0f) }

                    // Right Trim Handle
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(10.dp)
                            .fillMaxHeight()
                            .background(Color.White, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                            .pointerInput(clip.id, zoomScale, density) {
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        initDurMs = clip.durationMs
                                        rightDragPx = 0f
                                    },
                                    onHorizontalDrag = { change, dragAmount ->
                                        change.consume()
                                        rightDragPx += dragAmount
                                        val deltaMs = ((rightDragPx / (zoomScale * density)) * 1000f).toLong()
                                        onTrimClipEnd(clip.id, (initDurMs + deltaMs).coerceAtLeast(300L))
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(14.dp)
                                .background(Color.Black, RoundedCornerShape(1.dp))
                        )
                    }
                }
            }
        }
    }
}
