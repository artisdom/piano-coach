package org.pianocoach

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import org.pianocoach.ui.C
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.pianocoach.ui.CoachTheme
import org.pianocoach.ui.LibraryScreen
import org.pianocoach.ui.PlayScreen
import org.pianocoach.ui.SettingsScreen

private enum class Screen { Library, Play, Settings }

class MainActivity : ComponentActivity() {
    private val coach: Coach by viewModels()

    /** Bluetooth LE scanning: location before Android 12, nearby devices after. */
    private val blePermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val askBle = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) coach.startBluetooth()
        else Toast.makeText(this, "Bluetooth needs this permission to find the piano", Toast.LENGTH_LONG).show()
    }

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && coach.loadUri(uri)) screen = Screen.Play
    }

    private var screen by mutableStateOf(Screen.Library)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        immersive()
        setContent {
            CoachTheme {
              // Full screen; system bars appear over it only when swiped in.
              Box(Modifier.fillMaxSize().background(C.bg).windowInsetsPadding(WindowInsets.displayCutout)) {
                // Asked when Bluetooth is on (at start, or when turned on in Settings).
                LaunchedEffect(coach.prefs.bluetooth) {
                    if (coach.prefs.bluetooth) requestBluetooth()
                }
                // A song started from the piano keys (or the launch song): show it.
                LaunchedEffect(coach.playRequests) {
                    if (coach.playRequests > 0 && screen == Screen.Library) {
                        lastMain = Screen.Play
                        screen = Screen.Play
                    }
                }
                BackHandler(enabled = screen != Screen.Library) {
                    screen = if (screen == Screen.Settings && coach.loaded != null && lastMain == Screen.Play) Screen.Play else Screen.Library
                }
                when (screen) {
                    Screen.Library -> LibraryScreen(
                        coach,
                        onPlay = { screen = Screen.Play; lastMain = Screen.Play },
                        onSettings = { lastMain = Screen.Library; screen = Screen.Settings },
                        onOpenFile = { openFile.launch(arrayOf("audio/midi", "audio/x-midi", "audio/sp-midi", "application/octet-stream", "*/*")) },
                    )
                    Screen.Play -> PlayScreen(
                        coach,
                        onBack = { coach.pause(); screen = Screen.Library },
                        onSettings = { lastMain = Screen.Play; screen = Screen.Settings },
                    )
                    Screen.Settings -> SettingsScreen(coach, onBack = { screen = lastMain })
                }
                coach.toast?.let {
                    LaunchedEffect(it) {
                        Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
                        coach.toast = null
                    }
                }
              }
            }
        }
    }

    private var lastMain = Screen.Library

    /** Hide the system bars (again after a dialog such as a permission prompt). */
    private fun immersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    private fun requestBluetooth() {
        val missing = blePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) coach.startBluetooth() else askBle.launch(missing.toTypedArray())
    }
}
