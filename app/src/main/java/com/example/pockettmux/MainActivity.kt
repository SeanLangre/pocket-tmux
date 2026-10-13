package com.example.pockettmux

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private val vm: TerminalViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                        var showSettings by rememberSaveable { mutableStateOf(!vm.config.isComplete) }
                        if (showSettings) {
                            val canLeave = vm.config.isComplete
                            BackHandler(enabled = canLeave) { showSettings = false }
                            SettingsScreen(
                                vm,
                                onSaved = { showSettings = false },
                                onCancel = if (canLeave) ({ showSettings = false }) else null,
                            )
                        } else {
                            TerminalScreen(vm, onOpenSettings = { showSettings = true })
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
        if (vm.config.isComplete) startForegroundService(Intent(this, KeepAliveService::class.java))
    }

    override fun onStop() {
        super.onStop()
        vm.onBackground()
    }
}
