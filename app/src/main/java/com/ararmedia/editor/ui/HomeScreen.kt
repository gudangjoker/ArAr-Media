package com.ararmedia.editor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ararmedia.editor.R
import com.ararmedia.editor.StudioViewModel
import com.ararmedia.editor.engine.MediaNativeBridge
import com.ararmedia.editor.ui.theme.*

@Composable
fun HomeScreen(
    viewModel: StudioViewModel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StudioDarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // App Logo & Branding Header
        Image(
            painter = painterResource(id = R.drawable.app_logo),
            contentDescription = "ArAr Media Logo",
            modifier = Modifier
                .size(86.dp)
                .clip(CircleShape)
                .border(2.dp, StudioCyanAccent, CircleShape)
        )

        Text(
            text = "ArAr Media",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Text(
            text = "Offline Pro Video & Audio Editing Studio",
            fontSize = 13.sp,
            color = StudioCyanAccent
        )

        // Privacy & Zero Telemetry Badge
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = StudioSurfaceVariant),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.Security, contentDescription = "Privacy", tint = StudioGreenAccent)
                Column {
                    Text("100% Offline • Zero Telemetry", fontWeight = FontWeight.Bold, color = StudioGreenAccent, fontSize = 12.sp)
                    Text("Zero cloud dependencies and strictly NO internet permissions. Your creative media remains 100% on your device.", color = Color.LightGray, fontSize = 11.sp)
                }
            }
        }

        // Primary Studio Launch Cards
        StudioNavCard(
            title = "Pro Video Studio",
            subtitle = "CapCut-Level 5-Track Magnetic Timeline, Chroma Key, Keyframes, Speed Curves & LUT Color Grading",
            icon = Icons.Default.MovieFilter,
            badge = "CAPCUT LEVEL",
            accentColor = StudioCyanAccent,
            onClick = { viewModel.navigateTo(1) }
        )

        StudioNavCard(
            title = "Precision Audio DAW & Mastering",
            subtitle = "Audacity Waveform Editing, 10-Band Biquad EQ, Dynamics Limiter, AI Voice Denoise & Stem Separation",
            icon = Icons.Default.GraphicEq,
            badge = "AUDACITY LEVEL",
            accentColor = StudioGoldAccent,
            onClick = { viewModel.navigateTo(2) }
        )

        StudioNavCard(
            title = "Load Synthetic Demo Studio",
            subtitle = "Reloads procedural multi-track video clip sequence and multi-frequency voice-harmonic PCM WAV buffer",
            icon = Icons.Default.AutoAwesome,
            badge = "1-TAP DEMO",
            accentColor = StudioPurpleAccent,
            onClick = {
                viewModel.loadDemoProject()
                viewModel.navigateTo(1)
            }
        )

        // Native Engine Status
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = StudioSurface),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Native Multimedia Engine Status:", fontSize = 11.sp, color = Color.Gray)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.size(8.dp).background(if (MediaNativeBridge.isLoaded()) StudioGreenAccent else StudioRedAccent, CircleShape))
                    Text(
                        if (MediaNativeBridge.isLoaded()) "C++ libarar_media_engine.so Loaded & Accelerated" else "Native fallback",
                        fontSize = 11.sp,
                        color = Color.White
                    )
                }
                Text("Streaming Buffer: 4096-sample PCM (Anti-OOM Guaranteed)", fontSize = 10.sp, color = Color.Gray)
                Text("Video Export: MediaCodec Hardware AVC/H.264 into Movies/ArArMedia/", fontSize = 10.sp, color = Color.Gray)
                Text("Audio Export: Lossless WAV & AAC into Music/ArArMedia/", fontSize = 10.sp, color = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun StudioNavCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    badge: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, StudioCardBorder)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(accentColor.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = title, tint = accentColor, modifier = Modifier.size(28.dp))
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                    Box(
                        modifier = Modifier
                            .background(accentColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(badge, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = accentColor)
                    }
                }
                Text(subtitle, fontSize = 11.sp, color = Color.LightGray)
            }
        }
    }
}
