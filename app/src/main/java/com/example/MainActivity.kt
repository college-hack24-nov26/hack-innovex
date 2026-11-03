package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.ui.DrishtiMainScreen
import com.example.ui.DrishtiViewModel
import com.example.ui.theme.DrishtiTheme
import com.example.ui.theme.ObsidianBackground

class MainActivity : ComponentActivity() {

    private val viewModel: DrishtiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Keep display awake during active assistive walking guidance
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        try {
            enableEdgeToEdge()
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "Edge to edge error: ${e.message}")
        }

        setContent {
            DrishtiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ObsidianBackground
                ) {
                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestMultiplePermissions()
                    ) { permissions ->
                        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
                        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
                        val locationGranted = (permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false) ||
                                (permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false)

                        try {
                            viewModel.setPermissionsGranted(
                                camera = cameraGranted,
                                audio = audioGranted,
                                location = locationGranted
                            )

                            if (!cameraGranted) {
                                viewModel.alertManager.speakNowLoudly(
                                    "Camera permission required for obstacle detection.",
                                    isDanger = true
                                )
                            }
                        } catch (e: Throwable) {
                            android.util.Log.e("MainActivity", "Permission callback error: ${e.message}")
                        }
                    }

                    LaunchedEffect(Unit) {
                        try {
                            val requiredPermissions = mutableListOf(
                                Manifest.permission.CAMERA,
                                Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.SEND_SMS
                            )

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
                            }

                            val allGranted = requiredPermissions.all { perm ->
                                ContextCompat.checkSelfPermission(
                                    this@MainActivity,
                                    perm
                                ) == PackageManager.PERMISSION_GRANTED
                            }

                            if (!allGranted) {
                                permissionLauncher.launch(requiredPermissions.toTypedArray())
                            } else {
                                viewModel.setPermissionsGranted(
                                    camera = true,
                                    audio = true,
                                    location = true
                                )
                            }
                        } catch (e: Throwable) {
                            android.util.Log.e("MainActivity", "LaunchedEffect error: ${e.message}")
                        }
                    }

                    DrishtiMainScreen(viewModel = viewModel)
                }
            }
        }
    }

    /**
     * Intercepts hardware keys for eyes-free navigation:
     * - Volume Up: Captures frame and invokes Gemini "Look Around" scene describer.
     * - Volume Down: Starts Push-to-Talk Voice Assistant.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.repeatCount == 0) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    triggerKeyHaptic()
                    viewModel.triggerLookAround()
                    return true // Consume key event (prevents ringer volume increase)
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    triggerKeyHaptic()
                    viewModel.startVoiceListening()
                    return true // Consume key event (prevents ringer volume decrease)
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun triggerKeyHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(45L)
            }
        } catch (_: Exception) {
            // Ignore vibration failure
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}