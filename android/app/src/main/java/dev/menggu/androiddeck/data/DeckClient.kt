package dev.menggu.androiddeck.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

sealed interface ConnState {
    data object Idle : ConnState
    data object Connecting : ConnState
    data object Connected : ConnState
    data class Offline(val message: String) : ConnState
    data object Unauthorized : ConnState
}

data class Ack(val id: String, val ok: Boolean, val error: String?)

class DeckHttpException(val code: Int, message: String) : Exception(message)

/**
 * One live connection to one PC: a secure WebSocket for actions, pointer input and pushed state,
 * HTTPS for profile, app list and icons. Reconnects with backoff while started.
 */
class DeckClient(server: SavedServer, private val scope: CoroutineScope) {
    var server: SavedServer = server
        private set
    val id: String get() = server.id
    val baseUrl: String get() = Net.baseUrl(server.host, server.port)

    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _info = MutableStateFlow<ServerInfo?>(null)
    val info: StateFlow<ServerInfo?> = _info.asStateFlow()

    private val _features = MutableStateFlow(Features())
    val features: StateFlow<Features> = _features.asStateFlow()

    private val _volume = MutableStateFlow(VolumeState())
    val volume: StateFlow<VolumeState> = _volume.asStateFlow()

    private val _mic = MutableStateFlow(VolumeState())
    val mic: StateFlow<VolumeState> = _mic.asStateFlow()

    private val _mixer = MutableStateFlow(MixerState())
    val mixer: StateFlow<MixerState> = _mixer.asStateFlow()

    private val _media = MutableStateFlow(MediaState())
    val media: StateFlow<MediaState> = _media.asStateFlow()

    private val _wol = MutableStateFlow<WolInfo?>(null)
    val wol: StateFlow<WolInfo?> = _wol.asStateFlow()

    private val _stats = MutableStateFlow<Stats?>(null)
    val stats: StateFlow<Stats?> = _stats.asStateFlow()

    private val _history = MutableStateFlow(StatsHistory())
    val history: StateFlow<StatsHistory> = _history.asStateFlow()

    private val _profile = MutableStateFlow<Profile?>(null)
    val profile: StateFlow<Profile?> = _profile.asStateFlow()

    private val _apps = MutableStateFlow<List<AppEntry>?>(null)
    val apps: StateFlow<List<AppEntry>?> = _apps.asStateFlow()

    private val _acks = MutableSharedFlow<Ack>(extraBufferCapacity = 32)
    val acks: SharedFlow<Ack> = _acks.asSharedFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var wantStats = false
    @Volatile private var wantMixer = false
    private var reconnectJob: Job? = null
    private var attempt = 0
    private val saveMutex = Mutex()
    private val savesInFlight = AtomicInteger(0)

    init {
        Net.register(server)
    }

    // ---- connection -----------------------------------------------------------------------------

    fun start() {
        if (running) return
        running = true
        attempt = 0
        open()
    }

    fun stop() {
        running = false
        reconnectJob?.cancel()
        socket?.close(1000, null)
        socket = null
        if (_state.value != ConnState.Unauthorized) _state.value = ConnState.Idle
    }

    /** Skip the backoff wait, e.g. when discovery just saw this PC come back. */
    fun retryNow() {
        val st = _state.value
        if (!running || st == ConnState.Connected || st == ConnState.Connecting || st == ConnState.Unauthorized) return
        reconnectJob?.cancel()
        attempt = 0
        open()
    }

    private fun open() {
        if (_state.value != ConnState.Connected) _state.value = ConnState.Connecting
        val req = Request.Builder().url("$baseUrl/ws").build()
        socket = Net.clientFor(server.host, server.port).newWebSocket(req, Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            attempt = 0
            _state.value = ConnState.Connected
            webSocket.send(subscribeMessage())
            scope.launch { refreshProfile() }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            handleMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket === socket) connectionLost("Connection closed")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== socket) return
            if (response?.code == 401) {
                socket = null
                running = false
                _state.value = ConnState.Unauthorized
                return
            }
            connectionLost(t.message ?: "Connection failed")
        }
    }

    private fun connectionLost(reason: String) {
        socket = null
        if (!running) {
            _state.value = ConnState.Idle
            return
        }
        _state.value = ConnState.Offline(reason)
        val wait = min(8_000L, 600L shl min(attempt, 4))
        attempt++
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(wait)
            if (running && socket == null) open()
        }
    }

    private fun subscribeMessage(): String =
        buildJsonObject { put("t", "sub"); put("stats", wantStats); put("mixer", wantMixer) }.toString()

    fun setSubscriptions(stats: Boolean, mixer: Boolean) {
        if (stats == wantStats && mixer == wantMixer) return
        wantStats = stats
        wantMixer = mixer
        socket?.send(subscribeMessage())
    }

    private fun handleMessage(text: String) {
        val obj = runCatching { DeckJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        runCatching {
            when (obj["t"]?.jsonPrimitive?.contentOrNull) {
                "welcome" -> {
                    obj["server"]?.let { _info.value = DeckJson.decodeFromJsonElement(ServerInfo.serializer(), it) }
                    obj["features"]?.let { _features.value = DeckJson.decodeFromJsonElement(Features.serializer(), it) }
                    obj["volume"]?.let { _volume.value = DeckJson.decodeFromJsonElement(VolumeState.serializer(), it) }
                    obj["mic"]?.let { _mic.value = DeckJson.decodeFromJsonElement(VolumeState.serializer(), it) }
                    obj["media"]?.takeIf { it !is JsonNull }?.let {
                        _media.value = DeckJson.decodeFromJsonElement(MediaState.serializer(), it)
                    }
                    obj["wol"]?.takeIf { it !is JsonNull }?.let {
                        _wol.value = DeckJson.decodeFromJsonElement(WolInfo.serializer(), it)
                    }
                }
                "stats" -> {
                    val s = DeckJson.decodeFromJsonElement(Stats.serializer(), obj)
                    _stats.value = s
                    _history.update { it.push(s) }
                }
                "volume" -> _volume.value = DeckJson.decodeFromJsonElement(VolumeState.serializer(), obj)
                "mic" -> _mic.value = DeckJson.decodeFromJsonElement(VolumeState.serializer(), obj)
                "mixer" -> _mixer.value = DeckJson.decodeFromJsonElement(MixerState.serializer(), obj)
                "media" -> _media.value = DeckJson.decodeFromJsonElement(MediaState.serializer(), obj)
                "profile" -> if (savesInFlight.get() == 0) {
                    obj["profile"]?.let { _profile.value = DeckJson.decodeFromJsonElement(Profile.serializer(), it) }
                }
                "ack" -> _acks.tryEmit(parseAck(obj))
            }
        }
    }

    private fun parseAck(obj: JsonObject) = Ack(
        id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
        ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false,
        error = obj["error"]?.jsonPrimitive?.contentOrNull,
    )

    val isConnected: Boolean get() = _state.value == ConnState.Connected

    /** Sends an action; returns its request id, or null when not connected. */
    fun send(action: DeckAction): String? {
        val ws = socket ?: return null
        if (!isConnected) return null
        val rid = newId()
        val msg = buildJsonObject {
            put("t", "action")
            put("id", rid)
            // "device" routing is resolved on the phone; the PC never sees it.
            put("action", DeckJson.encodeToJsonElement(DeckAction.serializer(), action.copy(device = null)))
        }
        return if (ws.send(msg.toString())) rid else null
    }

    // ---- trackpad (fire-and-forget) ---------------------------------------------------------------

    private fun raw(json: String) {
        if (isConnected) socket?.send(json)
    }

    fun mouseMove(dx: Float, dy: Float) = raw("""{"t":"mouse","dx":${fmt(dx)},"dy":${fmt(dy)}}""")
    fun scroll(dx: Float, dy: Float) = raw("""{"t":"scroll","dx":${fmt(dx)},"dy":${fmt(dy)}}""")
    fun click(button: String, count: Int = 1) = raw("""{"t":"click","button":"$button","count":$count}""")
    fun mouseButton(button: String, down: Boolean) = raw("""{"t":"button","button":"$button","down":$down}""")
    fun typeText(text: String) = raw(buildJsonObject { put("t", "type"); put("text", text) }.toString())
    fun keyCombo(keys: List<String>) = raw(
        buildJsonObject { put("t", "key"); put("keys", JsonArray(keys.map { JsonPrimitive(it) })) }.toString(),
    )

    private fun fmt(v: Float) = String.format(Locale.US, "%.2f", v)

    // ---- HTTP -----------------------------------------------------------------------------------

    private suspend fun <T> call(req: Request, client: OkHttpClient, parse: (String) -> T): T =
        withContext(Dispatchers.IO) { execute(client, req, parse) }

    private val http: OkHttpClient get() = Net.clientFor(server.host, server.port)

    suspend fun refreshProfile(): Result<Profile> = runCatching {
        call(Request.Builder().url("$baseUrl/api/profile").build(), http) {
            DeckJson.decodeFromString(Profile.serializer(), it)
        }.also { _profile.value = it }
    }

    /** Optimistic local update; the caller persists it with [saveProfile]. */
    fun setLocalProfile(p: Profile) {
        _profile.value = p
    }

    suspend fun saveProfile(p: Profile): Result<Profile> {
        savesInFlight.incrementAndGet()
        return try {
            saveMutex.withLock {
                runCatching {
                    val body = DeckJson.encodeToString(Profile.serializer(), p).toRequestBody(JSON)
                    call(Request.Builder().url("$baseUrl/api/profile").put(body).build(), http) {
                        DeckJson.decodeFromString(Profile.serializer(), it)
                    }
                }
            }
        } finally {
            savesInFlight.decrementAndGet()
        }
    }

    suspend fun loadApps(refresh: Boolean): Result<List<AppEntry>> = runCatching {
        val url = "$baseUrl/api/apps" + if (refresh) "?refresh=1" else ""
        call(Request.Builder().url(url).build(), Net.slowClientFor(server.host, server.port)) {
            DeckJson.decodeFromString(AppsResponse.serializer(), it).apps
        }.also { _apps.value = it }
    }

    fun iconUrl(appId: String): String = "$baseUrl/api/icon/$appId"

    fun artUrl(art: String?): String? = when {
        art.isNullOrBlank() -> null
        art.startsWith("/") -> baseUrl + art
        else -> art
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun <T> execute(client: OkHttpClient, req: Request, parse: (String) -> T): T {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { DeckJson.decodeFromString(ErrorResponse.serializer(), body).error }.getOrNull()
                    throw DeckHttpException(resp.code, msg ?: "HTTP ${resp.code}")
                }
                return parse(body)
            }
        }

        /** First contact with a PC typed in by hand: returns its info and the certificate it presented. */
        suspend fun probe(host: String, port: Int): Pair<ServerInfo, String> = withContext(Dispatchers.IO) {
            var seen: String? = null
            val client = Net.probeClient { seen = it }
            val info = execute(client, Request.Builder().url("${Net.baseUrl(host, port)}/api/info").build()) {
                DeckJson.decodeFromString(ServerInfo.serializer(), it)
            }
            info to (seen ?: throw IllegalStateException("The PC did not present a certificate"))
        }

        /** Pairs over a connection that only trusts [fp], so the token never reaches another machine. */
        suspend fun pair(host: String, port: Int, fp: String, pin: String, device: String): PairResponse =
            withContext(Dispatchers.IO) {
                val body = buildJsonObject { put("pin", pin); put("device", device) }.toString().toRequestBody(JSON)
                execute(Net.clientPinnedTo(fp), Request.Builder().url("${Net.baseUrl(host, port)}/api/pair").post(body).build()) {
                    DeckJson.decodeFromString(PairResponse.serializer(), it)
                }
            }

        /** Stateless action call used by the home-screen widget and quick-settings tiles. */
        suspend fun runOnce(server: SavedServer, action: DeckAction): Result<Unit> = withContext(Dispatchers.IO) {
            runCatching {
                Net.register(server)
                val body = DeckJson.encodeToString(DeckAction.serializer(), action.copy(device = null)).toRequestBody(JSON)
                val req = Request.Builder().url("${Net.baseUrl(server.host, server.port)}/api/action").post(body).build()
                execute(Net.clientFor(server.host, server.port), req) { }
            }
        }
    }
}
