package dev.menggu.androiddeck.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

const val DEFAULT_PORT = 47777
const val DISCOVERY_PORT = 47778

val DeckJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

// ---- Servers ------------------------------------------------------------------------------------

@Serializable
data class ServerInfo(
    val id: String,
    val name: String,
    val os: String = "linux",
    val osVersion: String? = null,
    val version: String? = null,
    val protocol: Int = 1,
    val port: Int = DEFAULT_PORT,
    val tls: Boolean = false,
    /** SHA-256 of the server certificate (hex). Pinned at pairing. */
    val fp: String? = null,
)

/** A PC this phone has paired with. The token authorizes every request. */
@Serializable
data class SavedServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val os: String,
    val token: String,
    /** Pinned certificate fingerprint; every connection must present exactly this certificate. */
    val fp: String? = null,
    /** Wake-on-LAN target, learned from the PC once connected. */
    val mac: String? = null,
    val broadcast: String? = null,
)

data class DiscoveredServer(val info: ServerInfo, val host: String, val seenAt: Long)

@Serializable
data class WolInfo(val mac: String, val broadcast: String? = null)

@Serializable
data class PairResponse(val token: String, val server: ServerInfo, val wol: WolInfo? = null)

@Serializable
data class ErrorResponse(val error: String? = null)

@Serializable
data class Features(
    val keys: String? = null,
    val mouse: String? = null,
    val volume: Boolean = false,
    val mic: Boolean = false,
    val mixer: Boolean = false,
    val media: Boolean = false,
    val commands: Boolean = true,
)

// ---- Deck profile (stored on the PC) ------------------------------------------------------------

@Serializable
data class Profile(val version: Int = 1, val pages: List<DeckPage> = emptyList())

@Serializable
data class DeckPage(
    val id: String = newId(),
    val name: String = "Page",
    val buttons: List<DeckButton> = emptyList(),
)

@Serializable
data class DeckButton(
    val id: String = newId(),
    val label: String = "",
    val icon: ButtonIcon = ButtonIcon(),
    val color: String? = null,
    val action: DeckAction = DeckAction(),
    /** Contents when action.type == "folder" (one level deep). */
    val children: List<DeckButton>? = null,
)

/**
 * kind: "none" (derived from the action), "symbol" (built-in vector), "app" (PC app icon).
 * device: PC whose app icon to use (null = the PC this deck belongs to).
 */
@Serializable
data class ButtonIcon(val kind: String = "none", val value: String? = null, val device: String? = null)

/**
 * Flat action model shared with the server.
 * type: none | app | hotkey | text | media | volume | mic | mixer | url | command | system | multi
 *       | folder | wake  (folder and wake are handled on the phone)
 * device: run on another paired PC (null = the PC this deck belongs to).
 */
@Serializable
data class DeckAction(
    val type: String = "none",
    val appId: String? = null,
    val target: String? = null,
    val name: String? = null,
    val keys: List<String>? = null,
    val text: String? = null,
    val key: String? = null,
    val op: String? = null,
    val value: Double? = null,
    val url: String? = null,
    val command: String? = null,
    val device: String? = null,
    /** Steps of a "multi" action, each optionally preceded by [delay] milliseconds. */
    val steps: List<DeckAction>? = null,
    val delay: Int? = null,
)

@Serializable
data class AppEntry(val id: String, val name: String, val target: String, val hasIcon: Boolean = false)

@Serializable
data class AppsResponse(val apps: List<AppEntry> = emptyList(), val scanning: Boolean = false)

// ---- Live data ----------------------------------------------------------------------------------

@Serializable
data class VolumeState(val available: Boolean = false, val level: Double? = null, val muted: Boolean? = null)

@Serializable
data class MixerSession(
    val id: String,
    val name: String,
    val level: Double = 1.0,
    val muted: Boolean = false,
    val appId: String? = null,
)

@Serializable
data class MixerState(val available: Boolean = false, val sessions: List<MixerSession> = emptyList())

@Serializable
data class MediaState(
    val available: Boolean = false,
    val playing: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val source: String? = null,
    /** Server-relative ("/api/media/art?k=…") or an internet URL. */
    val art: String? = null,
)

/** A deck button pinned to the home-screen widget / quick-settings tiles (snapshot). */
@Serializable
data class Shortcut(val serverId: String, val button: DeckButton)

fun shortFingerprint(fp: String?): String =
    fp?.take(16)?.uppercase()?.chunked(4)?.joinToString(" ") ?: "–"

@Serializable
data class Stats(
    val ts: Double = 0.0,
    val cpu: CpuStats = CpuStats(),
    val mem: MemStats = MemStats(),
    val swap: MemStats? = null,
    val gpus: List<GpuStats> = emptyList(),
    val net: NetStats = NetStats(),
    val diskIo: DiskIo = DiskIo(),
    val disks: List<DiskStats> = emptyList(),
    val battery: Battery? = null,
    val uptime: Long = 0,
    val procs: List<ProcStats> = emptyList(),
)

@Serializable
data class CpuStats(
    val name: String? = null,
    val percent: Double = 0.0,
    val cores: List<Double> = emptyList(),
    val freq: Double? = null,
    val temp: Double? = null,
    val load: List<Double>? = null,
)

@Serializable
data class MemStats(val percent: Double = 0.0, val used: Double = 0.0, val total: Double = 0.0)

@Serializable
data class GpuStats(
    val name: String = "GPU",
    val util: Double? = null,
    val memUsed: Double? = null,
    val memTotal: Double? = null,
    val temp: Double? = null,
)

@Serializable
data class NetStats(val up: Double = 0.0, val down: Double = 0.0)

@Serializable
data class DiskIo(val read: Double = 0.0, val write: Double = 0.0)

@Serializable
data class DiskStats(val mount: String = "", val percent: Double = 0.0, val used: Double = 0.0, val total: Double = 0.0)

@Serializable
data class Battery(val percent: Int = 0, val plugged: Boolean = false)

@Serializable
data class ProcStats(val name: String = "", val cpu: Double = 0.0, val mem: Double = 0.0)

/** Rolling 60-sample history for charts. */
data class StatsHistory(
    val cpu: List<Float> = emptyList(),
    val mem: List<Float> = emptyList(),
    val gpu: List<Float> = emptyList(),
    val up: List<Float> = emptyList(),
    val down: List<Float> = emptyList(),
) {
    fun push(s: Stats): StatsHistory = StatsHistory(
        cpu = (cpu + s.cpu.percent.toFloat()).takeLast(SIZE),
        mem = (mem + s.mem.percent.toFloat()).takeLast(SIZE),
        gpu = (gpu + (s.gpus.firstOrNull()?.util?.toFloat() ?: 0f)).takeLast(SIZE),
        up = (up + s.net.up.toFloat()).takeLast(SIZE),
        down = (down + s.net.down.toFloat()).takeLast(SIZE),
    )

    companion object {
        const val SIZE = 60
    }
}
