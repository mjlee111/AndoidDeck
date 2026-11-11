package dev.menggu.androiddeck

import dev.menggu.androiddeck.ui.theme.accentOn
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.menggu.androiddeck.ui.connect.DevicesScreen
import dev.menggu.androiddeck.ui.main.MainScreen
import dev.menggu.androiddeck.ui.theme.DeckTheme
import dev.menggu.androiddeck.ui.theme.LocalPalette

class MainActivity : ComponentActivity() {
    private val vm: DeckViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before the first frame, so the deck does not start in portrait and then rotate.
        requestedOrientation = orientationFor(vm.settings.value.orientation)
        enableEdgeToEdge()
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            DeckTheme(settings.theme, settings.accent) {
                KeepScreenOn(settings.keepScreenOn)
                FullScreen(settings.fullScreen)
                Orientation(settings.orientation)
                LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onForeground() }
                LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onBackground() }
                AppRoot(vm)
            }
        }
    }

    // The system re-shows the bars after dialogs, the keyboard or app switches; hide them again.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && vm.settings.value.fullScreen) applyFullScreen(this, true)
    }
}

private fun applyFullScreen(activity: Activity, on: Boolean) {
    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    if (on) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
}

/** "User" variants follow the system rotation lock when choosing between the two sides. */
private fun orientationFor(setting: Int): Int = when (setting) {
    1 -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    2 -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    else -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
}

@Composable
private fun Orientation(setting: Int) {
    val activity = LocalView.current.context as? Activity ?: return
    DisposableEffect(setting) {
        val wanted = orientationFor(setting)
        if (activity.requestedOrientation != wanted) activity.requestedOrientation = wanted
        onDispose { }
    }
}

@Composable
private fun FullScreen(on: Boolean) {
    val activity = LocalView.current.context as? Activity ?: return
    DisposableEffect(on) {
        applyFullScreen(activity, on)
        onDispose { }
    }
}

@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun AppRoot(vm: DeckViewModel) {
    val p = LocalPalette.current
    val servers by vm.servers.collectAsStateWithLifecycle()
    var showDevices by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        // A new message replaces the one showing.
        vm.messages.collectLatest { m ->
            val result = snackbar.showSnackbar(
                m.text,
                actionLabel = m.action,
                duration = if (m.action != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) m.onAction?.invoke()
        }
    }
    Box(Modifier.fillMaxSize().background(p.bg)) {
        if (servers.isEmpty()) {
            DevicesScreen(vm, onboarding = true, onClose = {})
        } else {
            MainScreen(vm, onOpenDevices = { showDevices = true }, covered = showDevices)
            if (showDevices) DevicesScreen(vm, onboarding = false, onClose = { showDevices = false })
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
        ) { data ->
            Snackbar(data, containerColor = p.surfaceHigher, contentColor = p.text, actionColor = p.accentOn(p.surfaceHigher))
        }
    }
}
