package dev.menggu.androiddeck

import androidx.glance.appwidget.updateAll
import dev.menggu.androiddeck.quick.ControlsWidget
import android.app.Application
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.menggu.androiddeck.data.Ack
import dev.menggu.androiddeck.data.ConnState
import dev.menggu.androiddeck.data.DeckAction
import dev.menggu.androiddeck.data.DeckButton
import dev.menggu.androiddeck.data.DeckClient
import dev.menggu.androiddeck.data.DeckHttpException
import dev.menggu.androiddeck.data.DeckJson
import dev.menggu.androiddeck.data.DeckPage
import dev.menggu.androiddeck.data.Discovery
import dev.menggu.androiddeck.data.DiscoveredServer
import dev.menggu.androiddeck.data.Prefs
import dev.menggu.androiddeck.data.Profile
import dev.menggu.androiddeck.data.SavedServer
import dev.menggu.androiddeck.data.ServerInfo
import dev.menggu.androiddeck.data.Settings
import dev.menggu.androiddeck.data.Shortcut
import dev.menggu.androiddeck.data.WakeOnLan
import dev.menggu.androiddeck.data.newId
import dev.menggu.androiddeck.quick.QuickActions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

/** Where a button lives: on a page, or inside a folder button on that page. */
data class Location(val pageId: String, val folderId: String? = null)

/** A snackbar line, optionally with one action such as "Undo". */
class UiMessage(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

@Serializable
private data class DeckExport(val format: String = "androiddeck.deck", val version: Int = 1, val profile: Profile)

/**
 * Owns every paired PC connection at once so the user can switch devices instantly and buttons
 * can target any PC. Discovery runs continuously while the app is visible: it heals changed IP
 * addresses of paired PCs and surfaces new PCs for pairing.
 */
private const val ACK_TIMEOUT_MS = 3_000L

class DeckViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)

    private val _servers = MutableStateFlow(prefs.loadServers())
    val servers: StateFlow<List<SavedServer>> = _servers.asStateFlow()

    private val _clients = MutableStateFlow<Map<String, DeckClient>>(emptyMap())
    val clients: StateFlow<Map<String, DeckClient>> = _clients.asStateFlow()

    private val _selectedId = MutableStateFlow(prefs.selectedId)
    val selected: StateFlow<DeckClient?> = combine(_clients, _selectedId, _servers) { c, id, list ->
        (id?.let { c[it] }) ?: list.firstOrNull()?.let { c[it.id] }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _discovered = MutableStateFlow<Map<String, DiscoveredServer>>(emptyMap())
    /** PCs answering discovery that this phone has not paired with yet. */
    val newServers: StateFlow<List<DiscoveredServer>> = combine(_discovered, _servers) { d, s ->
        val paired = s.map { it.id }.toSet()
        d.values.filter { it.info.id !in paired }.sortedBy { it.info.name.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _settings = MutableStateFlow(prefs.loadSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _shortcuts = MutableStateFlow(prefs.loadShortcuts())
    val shortcuts: StateFlow<List<Shortcut>> = _shortcuts.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    /** buttonId -> success flag, shown briefly as a ring on the tile. */
    private val _flashes = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val flashes: StateFlow<Map<String, Boolean>> = _flashes.asStateFlow()

    private val pendingActions = ConcurrentHashMap<String, String>()
    private val clientJobs = HashMap<String, Job>()
    private var foreground = false
    private var discoveryJob: Job? = null
    private var stopJob: Job? = null
    private var monitorVisible = false
    private var audioVisible = false
    private var devicesScreenOpen = false

    init {
        _servers.value.forEach { addClient(it, start = false) }
        viewModelScope.launch { selected.collect { applySubscriptions() } }
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    fun onForeground() {
        foreground = true
        stopJob?.cancel()
        _clients.value.values.forEach { it.start() }
        startDiscovery()
    }

    fun onBackground() {
        foreground = false
        discoveryJob?.cancel()
        // Short app switches keep the sockets; longer absences release them.
        stopJob = viewModelScope.launch {
            delay(20_000)
            _clients.value.values.forEach { it.stop() }
        }
    }

    override fun onCleared() {
        _clients.value.values.forEach { it.stop() }
    }

    // ---- clients --------------------------------------------------------------------------------

    fun client(id: String?): DeckClient? = id?.let { _clients.value[it] }

    /** The PC an action runs on: its explicit device, else the PC whose deck holds the button. */
    fun targetOf(action: DeckAction, deckOwner: DeckClient?): DeckClient? = client(action.device) ?: deckOwner

    private fun addClient(s: SavedServer, start: Boolean) {
        val c = DeckClient(s, viewModelScope)
        _clients.update { it + (s.id to c) }
        clientJobs[s.id] = viewModelScope.launch {
            launch { c.acks.collect { onAck(it) } }
            launch {
                c.info.filterNotNull().collect { info ->
                    val cur = _servers.value.find { it.id == s.id } ?: return@collect
                    if (cur.name != info.name || cur.os != info.os) {
                        updateServer(s.id) { it.copy(name = info.name, os = info.os) }
                    }
                }
            }
            launch {
                c.wol.filterNotNull().collect { wol ->
                    val cur = _servers.value.find { it.id == s.id } ?: return@collect
                    if (cur.mac != wol.mac || cur.broadcast != wol.broadcast) {
                        updateServer(s.id) { it.copy(mac = wol.mac, broadcast = wol.broadcast) }
                    }
                }
            }
            launch { c.profile.filterNotNull().collect { syncShortcuts(s.id, it) } }
        }
        applySubscriptions()
        if (start) c.start()
    }

    private fun removeClient(id: String) {
        clientJobs.remove(id)?.cancel()
        _clients.value[id]?.stop()
        _clients.update { it - id }
    }

    private fun updateServer(id: String, f: (SavedServer) -> SavedServer) {
        _servers.update { list -> list.map { if (it.id == id) f(it) else it } }
        prefs.saveServers(_servers.value)
    }

    fun select(id: String) {
        _selectedId.value = id
        prefs.selectedId = id
        // The home-screen controls follow the selected PC.
        viewModelScope.launch { runCatching { ControlsWidget().updateAll(getApplication()) } }
    }

    fun forget(id: String) {
        val s = _servers.value.find { it.id == id }
        removeClient(id)
        _servers.update { list -> list.filterNot { it.id == id } }
        prefs.saveServers(_servers.value)
        if (_selectedId.value == id) {
            _selectedId.value = _servers.value.firstOrNull()?.id
            prefs.selectedId = _selectedId.value
        }
        updateShortcuts { list -> list.filterNot { it.serverId == id } }
        s?.let { message("Forgot ${it.name}") }
    }

    // ---- discovery & pairing --------------------------------------------------------------------

    fun setDevicesScreenOpen(open: Boolean) {
        devicesScreenOpen = open
        if (open) scanNow()
    }

    private fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = viewModelScope.launch {
            while (isActive) {
                scanOnce()
                val fast = devicesScreenOpen || _servers.value.isEmpty() ||
                    _clients.value.values.any { it.state.value is ConnState.Offline }
                delay(if (fast) 2_500 else 10_000)
            }
        }
    }

    fun scanNow() {
        viewModelScope.launch { scanOnce() }
    }

    private suspend fun scanOnce() {
        if (_scanning.value) return
        _scanning.value = true
        val found = try {
            Discovery.scan()
        } finally {
            _scanning.value = false
        }
        val now = System.currentTimeMillis()
        _discovered.update { old -> (old + found.associateBy { it.info.id }).filterValues { now - it.seenAt < 25_000 } }

        for (d in found) {
            val saved = _servers.value.find { it.id == d.info.id } ?: continue
            val client = _clients.value[saved.id]
            val moved = saved.host != d.host || saved.port != d.info.port
            // Only re-point a client that is not working; the pinned certificate still has to match.
            if (moved && client?.state?.value != ConnState.Connected) {
                val updated = saved.copy(host = d.host, port = d.info.port, name = d.info.name, os = d.info.os)
                updateServer(saved.id) { updated }
                removeClient(saved.id)
                addClient(updated, start = foreground)
            } else if (client?.state?.value is ConnState.Offline) {
                client.retryNow()
            }
        }
    }

    /** Manual add: returns the PC's info and the certificate fingerprint it presented. */
    suspend fun probe(host: String, port: Int): Result<Pair<ServerInfo, String>> =
        runCatching { DeckClient.probe(host.trim(), port) }.mapFailure()

    suspend fun pair(host: String, port: Int, fp: String, pin: String): Result<SavedServer> = runCatching {
        val resp = DeckClient.pair(host, port, fp, pin, deviceName())
        val s = SavedServer(
            id = resp.server.id, name = resp.server.name, host = host, port = port, os = resp.server.os,
            token = resp.token, fp = fp.lowercase(), mac = resp.wol?.mac, broadcast = resp.wol?.broadcast,
        )
        if (_servers.value.any { it.id == s.id }) {
            removeClient(s.id)
            _servers.update { list -> list.map { if (it.id == s.id) s else it } }
        } else {
            _servers.update { it + s }
        }
        prefs.saveServers(_servers.value)
        addClient(s, start = foreground)
        select(s.id)
        s
    }.mapFailure()

    private fun deviceName(): String {
        val ctx = getApplication<Application>()
        return runCatching { AndroidSettings.Global.getString(ctx.contentResolver, AndroidSettings.Global.DEVICE_NAME) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    private fun <T> Result<T>.mapFailure(): Result<T> = recoverCatching { e ->
        throw Exception(friendlyError(e), e)
    }

    // ---- actions --------------------------------------------------------------------------------

    private val _toggled = MutableStateFlow(prefs.loadToggled())
    /** Two-state buttons that are on. */
    val toggled: StateFlow<Set<String>> = _toggled.asStateFlow()
    private val pendingToggles = ConcurrentHashMap<String, String>()

    private fun flipToggle(buttonId: String) {
        _toggled.update { if (buttonId in it) it - buttonId else it + buttonId }
        prefs.saveToggled(_toggled.value)
    }

    /** Push-to-talk: keys go down on press and up on release. */
    fun hold(button: DeckButton, deckOwner: DeckClient?, down: Boolean) {
        val keys = button.action.keys.orEmpty()
        if (keys.isEmpty()) return
        val c = targetOf(button.action, deckOwner) ?: return
        if (!c.isConnected) {
            if (down) {
                flash(button.id, false)
                message("${c.server.name} is not connected")
            }
            return
        }
        c.holdKeys(keys, down)
    }

    /** Runs a button's long-press action, with the same feedback as a press. */
    fun pressLong(button: DeckButton, deckOwner: DeckClient?) {
        val a = button.longAction ?: return
        press(button.copy(action = a, toggle = null), deckOwner)
    }

    fun press(button: DeckButton, deckOwner: DeckClient?) {
        // A two-state button that is on runs its second action, then turns off (and the reverse).
        val toggle = button.toggle
        if (toggle != null && button.id in _toggled.value) {
            val second = toggle.action.copy(device = toggle.action.device ?: button.action.device)
            return pressAction(button, second, deckOwner, toggles = true)
        }
        pressAction(button, button.action, deckOwner, toggles = toggle != null)
    }

    private fun pressAction(button: DeckButton, action: DeckAction, deckOwner: DeckClient?, toggles: Boolean) {
        when (action.type) {
            "none", "folder" -> return
            "wake" -> {
                wake(action.device ?: deckOwner?.id)
                flash(button.id, true)
                return
            }
        }
        val c = targetOf(action, deckOwner) ?: return
        val rid = c.send(action)
        if (rid == null) {
            flash(button.id, false)
            message("${c.server.name} is not connected")
        } else {
            pendingActions[rid] = button.id
            if (toggles) {
                // Flip right away; a failure or timeout flips it back.
                flipToggle(button.id)
                pendingToggles[rid] = button.id
            }
            viewModelScope.launch {
                delay(ackTimeout(action))
                if (pendingActions.remove(rid) != null) {
                    pendingToggles.remove(rid)?.let(::flipToggle)
                    flash(button.id, false)
                    message("No response from ${c.server.name}")
                }
            }
        }
    }

    /** Time to wait for an ack: a base, plus multi-action delays and typing time. */
    private fun ackTimeout(a: DeckAction): Long {
        fun extra(x: DeckAction): Long =
            (x.delay ?: 0).toLong() + (x.text?.length ?: 0) * 30L + (x.steps?.sumOf { extra(it) } ?: 0L)
        return ACK_TIMEOUT_MS + extra(a)
    }

    fun run(action: DeckAction) {
        val c = targetOf(action, selected.value) ?: return
        if (c.send(action) == null) message("${c.server.name} is not connected")
    }

    fun wake(serverId: String?) {
        val s = _servers.value.find { it.id == serverId } ?: return
        viewModelScope.launch {
            WakeOnLan.send(s)
                .onSuccess {
                    message("Wake signal sent to ${s.name}")
                    _clients.value[s.id]?.retryNow()
                }
                .onFailure { message(it.message ?: "Couldn't send the wake signal") }
        }
    }

    private fun onAck(a: Ack) {
        val buttonId = pendingActions.remove(a.id)
        pendingToggles.remove(a.id)?.let { if (!a.ok) flipToggle(it) }
        if (!a.ok) message(a.error ?: "Action failed")
        if (buttonId != null) flash(buttonId, a.ok)
    }

    private fun flash(buttonId: String, ok: Boolean) {
        _flashes.update { it + (buttonId to ok) }
        viewModelScope.launch {
            delay(700)
            _flashes.update { it - buttonId }
        }
    }

    fun setMonitorVisible(visible: Boolean) {
        monitorVisible = visible
        applySubscriptions()
    }

    fun setAudioVisible(visible: Boolean) {
        audioVisible = visible
        applySubscriptions()
    }

    /** Per PC: how many widgets on screen need the stats and mixer streams. Main thread only. */
    private val liveHolds = mutableMapOf<String, IntArray>()

    /** Keeps a PC's stats and/or mixer stream on until the returned function is called. */
    fun holdLive(clientId: String, stats: Boolean, mixer: Boolean): () -> Unit {
        val h = liveHolds.getOrPut(clientId) { IntArray(2) }
        if (stats) h[0]++
        if (mixer) h[1]++
        applySubscriptions()
        var released = false
        return {
            if (!released) {
                released = true
                if (stats) h[0]--
                if (mixer) h[1]--
                applySubscriptions()
            }
        }
    }

    /**
     * Stats stream from every PC while the System tab is open, the mixer from the selected PC while
     * Audio is open, and either from any PC a widget on screen needs.
     */
    private fun applySubscriptions() {
        val sel = selected.value?.id
        _clients.value.values.forEach {
            val h = liveHolds[it.id]
            it.setSubscriptions(
                stats = monitorVisible || (h?.get(0) ?: 0) > 0,
                mixer = (audioVisible && it.id == sel) || (h?.get(1) ?: 0) > 0,
            )
        }
    }

    // ---- profile editing ------------------------------------------------------------------------

    private fun editProfile(target: DeckClient? = selected.value, transform: (Profile) -> Profile) {
        val c = target ?: return
        val cur = c.profile.value ?: return
        val next = transform(cur)
        if (next == cur) return
        c.setLocalProfile(next)
        viewModelScope.launch {
            c.saveProfile(next).onFailure {
                message("Couldn't save the deck: ${friendlyError(it)}")
                c.refreshProfile()
            }
        }
    }

    fun addPage(name: String): String {
        val page = DeckPage(name = name.ifBlank { "Page" })
        editProfile { it.copy(pages = it.pages + page) }
        return page.id
    }

    fun renamePage(pageId: String, name: String) = editProfile { p ->
        p.copy(pages = p.pages.map { if (it.id == pageId) it.copy(name = name.ifBlank { it.name }) else it })
    }

    fun movePage(pageId: String, delta: Int) = editProfile { p ->
        val i = p.pages.indexOfFirst { it.id == pageId }
        val j = i + delta
        if (i < 0 || j !in p.pages.indices) p
        else p.copy(pages = p.pages.toMutableList().apply { add(j, removeAt(i)) })
    }

    fun deletePage(pageId: String) = editProfile { p ->
        if (p.pages.size <= 1) p else p.copy(pages = p.pages.filterNot { it.id == pageId })
    }

    /** Copies a page (with fresh ids) to the end of another PC's deck. */
    fun copyPageTo(pageId: String, targetId: String) {
        val page = selected.value?.profile?.value?.pages?.find { it.id == pageId } ?: return
        val target = client(targetId) ?: return
        if (target.profile.value == null) {
            message("${target.server.name} is not connected")
            return
        }
        editProfile(target) { it.copy(pages = it.pages + page.withFreshIds()) }
        message("Copied “${page.name}” to ${target.server.name}")
    }

    private fun DeckPage.withFreshIds() = copy(id = newId(), buttons = buttons.map { it.withFreshIds() })
    private fun DeckButton.withFreshIds(): DeckButton = copy(id = newId(), children = children?.map { it.withFreshIds() })

    private fun List<DeckButton>.removeId(id: String): List<DeckButton> =
        filterNot { it.id == id }.map { b -> b.children?.let { b.copy(children = it.filterNot { c -> c.id == id }) } ?: b }

    private fun Profile.locate(id: String): Location? {
        for (page in pages) {
            if (page.buttons.any { it.id == id }) return Location(page.id)
            page.buttons.firstOrNull { f -> f.children?.any { it.id == id } == true }?.let { return Location(page.id, it.id) }
        }
        return null
    }

    private fun Profile.listAt(loc: Location): List<DeckButton> {
        val page = pages.find { it.id == loc.pageId } ?: return emptyList()
        return if (loc.folderId == null) page.buttons else page.buttons.find { it.id == loc.folderId }?.children.orEmpty()
    }

    private fun Profile.mapList(loc: Location, f: (List<DeckButton>) -> List<DeckButton>): Profile =
        copy(pages = pages.map { page ->
            when {
                page.id != loc.pageId -> page
                loc.folderId == null -> page.copy(buttons = f(page.buttons))
                else -> page.copy(buttons = page.buttons.map { b ->
                    if (b.id == loc.folderId) b.copy(children = f(b.children ?: emptyList())) else b
                })
            }
        })

    /** Inserts or replaces a button; moves it when [loc] differs from where it currently is. */
    fun saveButton(button: DeckButton, loc: Location) {
        // Folders cannot nest: a folder saved into a folder lands on the page instead.
        val target = if (button.action.type == "folder") loc.copy(folderId = null) else loc
        val clean = if (button.action.type == "folder") button.copy(children = button.children ?: emptyList())
        else button.copy(children = null)
        editProfile { p ->
            if (p.locate(button.id) == target) {
                p.mapList(target) { list -> list.map { if (it.id == button.id) clean else it } }
            } else {
                val removed = p.copy(pages = p.pages.map { it.copy(buttons = it.buttons.removeId(button.id)) })
                removed.mapList(target) { it + clean }
            }
        }
        refreshShortcut(button)
    }

    fun deleteButton(buttonId: String) {
        val before = selected.value?.profile?.value
        val loc = before?.locate(buttonId)
        val list = loc?.let { before.listAt(it) }.orEmpty()
        val index = list.indexOfFirst { it.id == buttonId }
        val removed = list.getOrNull(index)
        val pinned = _shortcuts.value.filter { it.button.id == buttonId }
        editProfile { p -> p.copy(pages = p.pages.map { it.copy(buttons = it.buttons.removeId(buttonId)) }) }
        updateShortcuts { all -> all.filterNot { it.button.id == buttonId } }
        if (removed != null && loc != null) {
            message("Deleted “${removed.label.ifBlank { "button" }}”", "Undo") {
                editProfile { p -> p.mapList(loc) { l -> l.toMutableList().apply { add(index.coerceIn(0, size), removed) } } }
                if (pinned.isNotEmpty()) updateShortcuts { all -> all + pinned.filterNot { s -> all.any { it.button.id == s.button.id } } }
            }
        }
    }

    /** Copies a button (and a folder's contents) right after the original. */
    fun duplicateButton(buttonId: String) {
        editProfile { p ->
            val loc = p.locate(buttonId) ?: return@editProfile p
            p.mapList(loc) { list ->
                val i = list.indexOfFirst { it.id == buttonId }
                if (i < 0) return@mapList list
                val b = list[i]
                val copy = b.copy(id = newId(), children = b.children?.map { it.copy(id = newId()) })
                list.toMutableList().apply { add(i + 1, copy) }
            }
        }
    }

    /** Reorders the widget / Quick Settings buttons (the first ones are the tiles). */
    fun moveShortcut(index: Int, delta: Int) = updateShortcuts { list ->
        val to = index + delta
        if (index !in list.indices || to !in list.indices) list
        else list.toMutableList().apply { add(to, removeAt(index)) }
    }

    fun removeShortcut(index: Int) = updateShortcuts { list -> list.filterIndexed { i, _ -> i != index } }

    fun reorder(loc: Location, buttons: List<DeckButton>) = editProfile { p -> p.mapList(loc) { buttons } }

    // ---- export / import ------------------------------------------------------------------------

    fun exportDeck(): String? {
        val p = selected.value?.profile?.value ?: return null
        return DeckJson.encodeToString(DeckExport.serializer(), DeckExport(profile = p))
    }

    fun importDeck(json: String, replace: Boolean) {
        val profile = runCatching { DeckJson.decodeFromString(DeckExport.serializer(), json).profile }
            .recoverCatching { DeckJson.decodeFromString(Profile.serializer(), json) }
            .getOrNull()
        if (profile == null || profile.pages.isEmpty()) {
            message("That file is not an AndroidDeck deck")
            return
        }
        val fresh = profile.pages.map { it.withFreshIds() }
        editProfile { cur -> if (replace) cur.copy(pages = fresh) else cur.copy(pages = cur.pages + fresh) }
        message(if (replace) "Deck replaced (${fresh.size} pages)" else "Added ${fresh.size} pages")
    }

    // ---- widget & quick tiles -------------------------------------------------------------------

    fun isShortcut(buttonId: String) = _shortcuts.value.any { it.button.id == buttonId }

    /** Returns false when the widget is already full. */
    fun setShortcut(button: DeckButton, serverId: String, on: Boolean): Boolean {
        if (on && !isShortcut(button.id) && _shortcuts.value.size >= QuickActions.MAX_SHORTCUTS) return false
        updateShortcuts { list ->
            val without = list.filterNot { it.button.id == button.id }
            if (on) {
                val existing = list.indexOfFirst { it.button.id == button.id }
                val entry = Shortcut(serverId, button)
                if (existing >= 0) without.toMutableList().apply { add(existing, entry) } else without + entry
            } else {
                without
            }
        }
        return true
    }

    private fun refreshShortcut(button: DeckButton) = updateShortcuts { list ->
        list.map { if (it.button.id == button.id) it.copy(button = button) else it }
    }

    /** Keeps pinned snapshots in step with edits made on any phone; drops deleted buttons. */
    private fun syncShortcuts(serverId: String, profile: Profile) {
        val all = profile.pages.flatMap { p -> p.buttons + p.buttons.flatMap { it.children ?: emptyList() } }.associateBy { it.id }
        updateShortcuts { list ->
            list.mapNotNull { sc ->
                if (sc.serverId != serverId) sc else all[sc.button.id]?.let { sc.copy(button = it) }
            }
        }
    }

    private fun updateShortcuts(f: (List<Shortcut>) -> List<Shortcut>) {
        val next = f(_shortcuts.value)
        if (next == _shortcuts.value) return
        _shortcuts.value = next
        prefs.saveShortcuts(next)
        viewModelScope.launch { QuickActions.refreshSurfaces(getApplication()) }
    }

    // ---- settings & messages --------------------------------------------------------------------

    fun updateSettings(f: (Settings) -> Settings) {
        val before = _settings.value
        _settings.update(f)
        prefs.saveSettings(_settings.value)
        if (before.accent != _settings.value.accent) {
            viewModelScope.launch { QuickActions.refreshSurfaces(getApplication()) }
        }
    }

    fun message(text: String, action: String, onAction: () -> Unit) {
        _messages.tryEmit(UiMessage(text, action, onAction))
    }

    fun message(text: String) {
        _messages.tryEmit(UiMessage(text))
    }

    companion object {
        fun friendlyError(e: Throwable): String {
            val chain = generateSequence(e) { it.cause }.toList()
            return when {
                e is DeckHttpException -> e.message ?: "Request failed"
                chain.any { it is SSLException && it.message?.contains("does not match") == true } ||
                    chain.any { it.message?.contains("does not match the paired PC") == true } ->
                    "This PC's certificate changed. Forget it and pair again."
                chain.any { it is ConnectException } -> "Can't reach the PC. Is the server running?"
                chain.any { it is SocketTimeoutException } -> "The PC didn't answer in time"
                chain.any { it is UnknownHostException } -> "Unknown address"
                chain.any { it is SSLException } -> "Secure connection failed. Update the PC server."
                else -> e.message ?: e.javaClass.simpleName
            }
        }
    }
}
