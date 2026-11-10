package dev.menggu.androiddeck

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
                LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onForeground() }
                LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onBackground() }
                AppRoot(vm)
            }
        }
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
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 76.dp),
        ) { data ->
            Snackbar(data, containerColor = p.surfaceHigher, contentColor = p.text)
        }
    }
}
