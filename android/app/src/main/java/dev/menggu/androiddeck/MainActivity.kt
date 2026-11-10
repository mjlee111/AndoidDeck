package dev.menggu.androiddeck

import android.app.Activity
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
        enableEdgeToEdge()
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            DeckTheme(settings.theme) {
                KeepScreenOn(settings.keepScreenOn)
                FullScreen(settings.fullScreen)
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
        vm.messages.collect {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(it)
        }
    }
    Box(Modifier.fillMaxSize().background(p.bg)) {
        if (servers.isEmpty()) {
            DevicesScreen(vm, onboarding = true, onClose = {})
        } else {
            MainScreen(vm, onOpenDevices = { showDevices = true })
            if (showDevices) DevicesScreen(vm, onboarding = false, onClose = { showDevices = false })
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 68.dp),
        ) { data ->
            Snackbar(data, containerColor = p.surfaceHigher, contentColor = p.text)
        }
    }
}
