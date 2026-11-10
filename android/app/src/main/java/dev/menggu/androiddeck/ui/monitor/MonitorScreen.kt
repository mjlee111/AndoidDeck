package dev.menggu.androiddeck.ui.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.menggu.androiddeck.DeckViewModel
import dev.menggu.androiddeck.data.ConnState
import dev.menggu.androiddeck.data.DeckClient
import dev.menggu.androiddeck.data.Stats
import dev.menggu.androiddeck.data.StatsHistory
import dev.menggu.androiddeck.ui.components.BarMeter
import dev.menggu.androiddeck.ui.components.DeckCard
import dev.menggu.androiddeck.ui.components.RingGauge
import dev.menggu.androiddeck.ui.components.Sparkline
import dev.menggu.androiddeck.ui.components.StatusDot
import dev.menggu.androiddeck.ui.components.fmtBytes
import dev.menggu.androiddeck.ui.components.fmtRate
import dev.menggu.androiddeck.ui.components.fmtUptime
import dev.menggu.androiddeck.ui.theme.LocalPalette
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun MonitorScreen(vm: DeckViewModel, client: DeckClient) {
    val p = LocalPalette.current
    val stats by client.stats.collectAsStateWithLifecycle()
    val history by client.history.collectAsStateWithLifecycle()
    val clients by vm.clients.collectAsStateWithLifecycle()
    val servers by vm.servers.collectAsStateWithLifecycle()
    val full: (LazyGridScope.(@Composable () -> Unit) -> Unit) = { content ->
        item(span = { GridItemSpan(maxLineSpan) }) { content() }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(156.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (clients.size > 1) {
            full {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(servers, key = { it.id }) { s ->
                        clients[s.id]?.let { c ->
                            DeviceMini(c, selected = c.id == client.id, onClick = { vm.select(c.id) })
                        }
                    }
                }
            }
        }
        val s = stats
        if (s == null) {
            full {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = p.textDim, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                }
            }
            return@LazyVerticalGrid
        }
        full { HeaderRow(s) }
        item {
            GaugeCard("CPU", s.cpu.percent, p.cpu, listOfNotNull(
                s.cpu.temp?.let { "${it.roundToInt()}°C" },
                s.cpu.freq?.let { String.format(Locale.US, "%.1f GHz", it / 1000) },
            ).joinToString(" · ").ifEmpty { "${s.cpu.cores.size} threads" })
        }
        item { GaugeCard("Memory", s.mem.percent, p.mem, "${fmtBytes(s.mem.used)} / ${fmtBytes(s.mem.total)}") }
        s.gpus.forEachIndexed { i, g ->
            item(key = "gpu$i") {
                val sub = listOfNotNull(
                    if (g.memUsed != null && g.memTotal != null) "${fmtBytes(g.memUsed)} / ${fmtBytes(g.memTotal)}" else null,
                    g.temp?.let { "${it.roundToInt()}°C" },
                ).joinToString(" · ")
                GaugeCard(if (s.gpus.size > 1) "GPU ${i + 1}" else "GPU", g.util ?: 0.0, p.gpu, sub, title = g.name)
            }
        }
        s.disks.firstOrNull()?.let { d ->
            item { GaugeCard("Disk", d.percent, p.disk, "${fmtBytes(d.used)} / ${fmtBytes(d.total)}", title = d.mount) }
        }
        full { HistoryCard(history) }
        if (s.cpu.cores.size > 1) full { CoresCard(s.cpu.cores) }
        full { NetworkCard(s, history) }
        if (s.disks.size > 1) full { DisksCard(s) }
        if (s.procs.isNotEmpty()) full { ProcessesCard(s) }
    }
}

@Composable
private fun DeviceMini(c: DeckClient, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val st by c.state.collectAsStateWithLifecycle()
    val s by c.stats.collectAsStateWithLifecycle()
    Column(
        Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(p.surface)
            .border(1.5.dp, if (selected) p.text.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(st)
            Spacer(Modifier.width(8.dp))
            Text(c.server.name, style = MaterialTheme.typography.labelLarge, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        val stats = s
        if (st != ConnState.Connected || stats == null) {
            Text(if (st == ConnState.Connected) "Loading…" else "Offline", style = MaterialTheme.typography.bodySmall, color = p.textDim)
            Spacer(Modifier.height(18.dp))
        } else {
            MiniMeter("CPU", stats.cpu.percent, p.cpu)
            Spacer(Modifier.height(6.dp))
            MiniMeter("RAM", stats.mem.percent, p.mem)
        }
    }
}

@Composable
private fun MiniMeter(label: String, percent: Double, color: Color) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = p.textDim, modifier = Modifier.width(34.dp))
        BarMeter((percent / 100).toFloat(), color, Modifier.weight(1f), height = 5.dp)
        Text("${percent.roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = p.text,
            modifier = Modifier.width(38.dp).padding(start = 6.dp))
    }
}

@Composable
private fun HeaderRow(s: Stats) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            s.cpu.name ?: "System",
            style = MaterialTheme.typography.bodySmall,
            color = p.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Rounded.Schedule, null, tint = p.textDim, modifier = Modifier.size(14.dp))
        Text(" ${fmtUptime(s.uptime)}", style = MaterialTheme.typography.bodySmall, color = p.textDim)
        s.battery?.let { b ->
            Spacer(Modifier.width(12.dp))
            Icon(if (b.plugged) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryFull, null, tint = p.textDim, modifier = Modifier.size(14.dp))
            Text(" ${b.percent}%", style = MaterialTheme.typography.bodySmall, color = p.textDim)
        }
    }
}

@Composable
private fun GaugeCard(label: String, percent: Double, color: Color, sub: String, title: String? = null) {
    val p = LocalPalette.current
    DeckCard(Modifier.fillMaxWidth()) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = p.textDim)
        if (title != null) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = p.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        RingGauge((percent / 100).toFloat(), color, Modifier.fillMaxWidth(0.82f).aspectRatio(1f).align(Alignment.CenterHorizontally)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${percent.roundToInt()}", style = MaterialTheme.typography.headlineLarge, color = p.text)
                Text("%", style = MaterialTheme.typography.titleSmall, color = p.textDim, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall, color = p.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(" $label", style = MaterialTheme.typography.labelMedium, color = p.textDim)
    }
}

@Composable
private fun HistoryCard(h: StatsHistory) {
    val p = LocalPalette.current
    DeckCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LAST 60 S", style = MaterialTheme.typography.labelSmall, color = p.textDim, modifier = Modifier.weight(1f))
            Legend(p.cpu, "CPU")
            Spacer(Modifier.width(12.dp))
            Legend(p.mem, "RAM")
            if (h.gpu.any { it > 0f }) {
                Spacer(Modifier.width(12.dp))
                Legend(p.gpu, "GPU")
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(120.dp)) {
            Sparkline(h.mem, p.mem, Modifier.fillMaxSize(), fill = false)
            if (h.gpu.any { it > 0f }) Sparkline(h.gpu, p.gpu, Modifier.fillMaxSize(), fill = false)
            Sparkline(h.cpu, p.cpu, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun CoresCard(cores: List<Double>) {
    val p = LocalPalette.current
    DeckCard(Modifier.fillMaxWidth()) {
        Text("CPU THREADS · ${cores.size}", style = MaterialTheme.typography.labelSmall, color = p.textDim)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            cores.forEach { v ->
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(p.surfaceHigh)) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .fillMaxHeight((v / 100).toFloat().coerceIn(0.02f, 1f))
                            .background(p.cpu.copy(alpha = 0.35f + 0.65f * (v / 100).toFloat().coerceIn(0f, 1f))),
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkCard(s: Stats, h: StatsHistory) {
    val p = LocalPalette.current
    DeckCard(Modifier.fillMaxWidth()) {
        Text("NETWORK", style = MaterialTheme.typography.labelSmall, color = p.textDim)
        Spacer(Modifier.height(10.dp))
        Row {
            RateBlock(Icons.Rounded.ArrowDownward, "Down", s.net.down, p.down, Modifier.weight(1f))
            RateBlock(Icons.Rounded.ArrowUpward, "Up", s.net.up, p.up, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        val top = maxOf(h.down.maxOrNull() ?: 0f, h.up.maxOrNull() ?: 0f, 1024f) * 1.15f
        Box(Modifier.fillMaxWidth().height(80.dp)) {
            Sparkline(h.up, p.up, Modifier.fillMaxSize(), maxValue = top, fill = false)
            Sparkline(h.down, p.down, Modifier.fillMaxSize(), maxValue = top)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Disk  read ${fmtRate(s.diskIo.read)} · write ${fmtRate(s.diskIo.write)}",
            style = MaterialTheme.typography.bodySmall, color = p.textDim,
        )
    }
}

@Composable
private fun RateBlock(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, rate: Double, color: Color, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
            Text(" $label", style = MaterialTheme.typography.labelMedium, color = p.textDim)
        }
        Text(fmtRate(rate), style = MaterialTheme.typography.titleLarge, color = p.text)
    }
}

@Composable
private fun DisksCard(s: Stats) {
    val p = LocalPalette.current
    DeckCard(Modifier.fillMaxWidth()) {
        Text("DRIVES", style = MaterialTheme.typography.labelSmall, color = p.textDim)
        s.disks.forEach { d ->
            Spacer(Modifier.height(12.dp))
            Row {
                Text(d.mount, style = MaterialTheme.typography.labelLarge, color = p.text, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${fmtBytes(d.used)} / ${fmtBytes(d.total)}", style = MaterialTheme.typography.bodySmall, color = p.textDim)
            }
            Spacer(Modifier.height(6.dp))
            BarMeter((d.percent / 100).toFloat(), if (d.percent > 90) p.danger else p.disk)
        }
    }
}

@Composable
private fun ProcessesCard(s: Stats) {
    val p = LocalPalette.current
    DeckCard(Modifier.fillMaxWidth()) {
        Row {
            Text("TOP PROCESSES", style = MaterialTheme.typography.labelSmall, color = p.textDim, modifier = Modifier.weight(1f))
            Text("CPU", style = MaterialTheme.typography.labelSmall, color = p.textDim, modifier = Modifier.width(56.dp))
            Text("MEM", style = MaterialTheme.typography.labelSmall, color = p.textDim, modifier = Modifier.width(64.dp))
        }
        s.procs.forEach { pr ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pr.name, style = MaterialTheme.typography.bodyMedium, color = p.text, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(String.format(Locale.US, "%.1f%%", pr.cpu), style = MaterialTheme.typography.bodySmall, color = p.text, modifier = Modifier.width(56.dp))
                Text(fmtBytes(pr.mem), style = MaterialTheme.typography.bodySmall, color = p.textDim, modifier = Modifier.width(64.dp))
            }
        }
    }
}
