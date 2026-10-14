package com.ararmedia.editor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ararmedia.editor.ui.AudioDawScreen
import com.ararmedia.editor.ui.HomeScreen
import com.ararmedia.editor.ui.VideoStudioScreen
import com.ararmedia.editor.ui.theme.ArArMediaTheme
import com.ararmedia.editor.ui.theme.StudioCyanAccent
import com.ararmedia.editor.ui.theme.StudioSurface

class MainActivity : ComponentActivity() {

    private val viewModel: StudioViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions handled; studio operates fully offline
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()

        setContent {
            ArArMediaTheme {
                MainStudioApp(viewModel)
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}

@Composable
fun MainStudioApp(viewModel: StudioViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsState()

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = StudioSurface,
                tonalElevation = 6.dp
            ) {
                NavigationBarItem(
                    selected = currentScreen == 0,
                    onClick = { viewModel.navigateTo(0) },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = StudioCyanAccent,
                        selectedTextColor = StudioCyanAccent,
                        indicatorColor = StudioCyanAccent.copy(alpha = 0.15f),
                        unselectedIconColor = Color.LightGray,
                        unselectedTextColor = Color.LightGray
                    )
                )

                NavigationBarItem(
                    selected = currentScreen == 1,
                    onClick = { viewModel.navigateTo(1) },
                    icon = { Icon(Icons.Default.Movie, contentDescription = "Video Studio") },
                    label = { Text("Video Studio") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = StudioCyanAccent,
                        selectedTextColor = StudioCyanAccent,
                        indicatorColor = StudioCyanAccent.copy(alpha = 0.15f),
                        unselectedIconColor = Color.LightGray,
                        unselectedTextColor = Color.LightGray
                    )
                )

                NavigationBarItem(
                    selected = currentScreen == 2,
                    onClick = { viewModel.navigateTo(2) },
                    icon = { Icon(Icons.Default.GraphicEq, contentDescription = "Audio DAW") },
                    label = { Text("Audio DAW") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = StudioCyanAccent,
                        selectedTextColor = StudioCyanAccent,
                        indicatorColor = StudioCyanAccent.copy(alpha = 0.15f),
                        unselectedIconColor = Color.LightGray,
                        unselectedTextColor = Color.LightGray
                    )
                )
            }
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = MaterialTheme.colorScheme.background
        ) {
            when (currentScreen) {
                0 -> HomeScreen(viewModel = viewModel)
                1 -> VideoStudioScreen(viewModel = viewModel)
                2 -> AudioDawScreen(viewModel = viewModel)
            }
        }
    }
}
