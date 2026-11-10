package dev.menggu.androiddeck.ui.deck

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.menggu.androiddeck.DeckViewModel
import dev.menggu.androiddeck.data.AppEntry
import dev.menggu.androiddeck.data.DeckClient
import dev.menggu.androiddeck.ui.components.DeckTextField
import dev.menggu.androiddeck.ui.components.SecondaryButton
import dev.menggu.androiddeck.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/** Full-screen list of the PC's installed applications with their real icons. */
@Composable
fun AppPicker(client: DeckClient, title: String, onPick: (AppEntry) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val p = LocalPalette.current
    val apps by client.apps.collectAsStateWithLifecycle()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun load(refresh: Boolean) {
        loading = true
        error = null
        scope.launch {
            client.loadApps(refresh).onFailure { error = DeckViewModel.friendlyError(it) }
            loading = false
        }
    }
    LaunchedEffect(client.id) { if (client.apps.value == null) load(false) }

    val filtered = remember(apps, query) {
        val q = query.trim().lowercase()
        (apps ?: emptyList()).filter { q.isEmpty() || it.name.lowercase().contains(q) }
    }

    Column(
        Modifier.fillMaxSize().background(p.bg).windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = p.text) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = p.text)
                Text(
                    "${apps?.size ?: 0} apps on ${client.server.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.textDim,
                )
            }
            IconButton(onClick = { load(true) }, enabled = !loading) {
                Icon(Icons.Rounded.Refresh, "Rescan apps", tint = if (loading) p.textFaint else p.text)
            }
        }
        DeckTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search apps",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading && apps == null -> CenterNote {
                    CircularProgressIndicator(color = p.textDim, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(14.dp))
                    Text("Reading apps from ${client.server.name}…", color = p.textDim, style = MaterialTheme.typography.bodyMedium)
                    Text("The first scan can take a little while.", color = p.textFaint, style = MaterialTheme.typography.bodySmall)
                }
                error != null && apps == null -> CenterNote {
                    Text(error ?: "", color = p.textDim, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    SecondaryButton("Try again", { load(false) }, icon = Icons.Rounded.Refresh)
                }
                filtered.isEmpty() -> CenterNote {
                    Text(if (query.isBlank()) "No apps found" else "No match for “$query”", color = p.textDim)
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(88.dp),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(filtered, key = { it.id }) { app ->
                        Column(
                            Modifier.clip(RoundedCornerShape(18.dp)).clickable { onPick(app) }.padding(vertical = 12.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(p.surface),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (app.hasIcon) {
                                    AsyncImage(model = client.iconUrl(app.id), contentDescription = null, modifier = Modifier.size(42.dp))
                                } else {
                                    Icon(Icons.Rounded.Apps, null, tint = p.textDim, modifier = Modifier.size(26.dp))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                app.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = p.text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            if (loading && apps != null) {
                CircularProgressIndicator(
                    color = p.textDim, strokeWidth = 2.dp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun CenterNote(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}
