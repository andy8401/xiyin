package com.andy8401.mygo.car.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andy8401.mygo.car.bridge.GoBridge
import com.andy8401.mygo.car.bridge.PluginInstaller
import com.andy8401.mygo.car.data.AppSettings
import com.andy8401.mygo.car.data.Account
import com.andy8401.mygo.car.data.Browse
import com.andy8401.mygo.car.data.BrowseItem
import com.andy8401.mygo.car.data.Json
import com.andy8401.mygo.car.data.LyricsText
import com.andy8401.mygo.car.data.MusicRepository
import com.andy8401.mygo.car.data.PlayerState
import com.andy8401.mygo.car.data.PluginOrigin
import com.andy8401.mygo.car.data.PluginSetting
import com.andy8401.mygo.car.data.PluginUpdate
import com.andy8401.mygo.car.data.SearchResult
import com.andy8401.mygo.car.data.SignInPrompt
import com.andy8401.mygo.car.data.SourceInfo
import com.andy8401.mygo.car.data.Track
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One state holder for the whole car UI. Go owns playback truth; the UI only
 *  renders snapshots and forwards user actions. */
class MainViewModel : ViewModel() {

    enum class Page { HOME, BROWSE, SEARCH, LIBRARY, PLUGINS, SETTINGS }

    /** The two panes of the now-playing screen. */
    enum class PlayerPane { LYRICS, QUEUE }

    /** One row of the plugin manager: the loaded plugin plus where it came from
     *  and whether GitHub offers a newer release. */
    data class PluginRow(
        val id: String,
        val name: String,
        val version: String,
        /** Empty for a plugin installed from a local zip. */
        val repo: String = "",
        val settingCount: Int = 0,
        val latest: String = "",
        val updateAvailable: Boolean = false,
        /** Set when the update check for this plugin failed (offline, rate limit). */
        val updateError: String = "",
        /** The plugin exports signIn(): only then may the row offer 登录. */
        val canSignIn: Boolean = false
    )

    data class UiState(
        val ready: Boolean = false,
        val version: String = "",
        val sources: List<SourceInfo> = emptyList(),
        val activeSourceId: String = "",
        val page: Page = Page.HOME,
        val browse: Browse? = null,
        val search: SearchResult = SearchResult(),
        val query: String = "",
        val player: PlayerState = PlayerState(),
        val settings: AppSettings = AppSettings(),
        val message: String = "",
        // --- now playing ---
        /** The full-screen player is a page of the app, not a local flag, so a
         *  queue row or the player bar can open it on a given pane. */
        val playerOpen: Boolean = false,
        val playerPane: PlayerPane = PlayerPane.LYRICS,
        /** Minimal driving view: one title, one play button, one progress bar. */
        val focusMode: Boolean = false,
        val lyrics: LyricsText? = null,
        /** Track the current lyrics belong to, to drop late responses. */
        val lyricsTrackId: String = "",
        val lyricsLoading: Boolean = false,
        // --- plugin manager ---
        val origins: List<PluginOrigin> = emptyList(),
        val updates: List<PluginUpdate> = emptyList(),
        val updatesRunning: Boolean = false,
        /** Non-empty while an install or update check is in flight. */
        val busy: String = "",
        /** Plugin whose settings form is open; empty when the form is closed. */
        val settingsFor: String = "",
        val pluginSettings: List<PluginSetting> = emptyList(),
        /** Plugin awaiting an uninstall confirmation. */
        val confirmUninstall: String = "",
        // --- accounts ---
        /** Who is signed in, by plugin id. Absent means nobody. */
        val accounts: Map<String, Account> = emptyMap(),
        /** Plugin whose sign-in is in flight (the QR card is up meanwhile). */
        val signingIn: String = "",
        /** What a signing-in plugin wants shown: a QR code or a line of text. */
        val signInPrompt: SignInPrompt? = null
    ) {
        /** The rows the plugin manager renders. Derived, never stored, so a
         *  refresh of any of the three inputs cannot leave the list stale. */
        val plugins: List<PluginRow>
            get() = sources.map { src ->
                val origin = origins.firstOrNull { it.id == src.id }
                val update = updates.firstOrNull { it.id == src.id }
                PluginRow(
                    id = src.id,
                    name = src.name.ifBlank { src.id },
                    version = origin?.version?.takeIf { it.isNotBlank() } ?: src.version,
                    repo = origin?.repo ?: "",
                    settingCount = src.settings.size,
                    latest = update?.latest ?: "",
                    updateAvailable = update?.available == true && update.latest.isNotBlank(),
                    updateError = update?.error ?: "",
                    canSignIn = src.canSignIn
                )
            }
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /**
     * Go facade calls throw: a plugin can time out ("the plugin didn't answer
     * home in time"), a page id can be wrong, the m3u8 can 404. A bare
     * `viewModelScope.launch` hands that exception to the main dispatcher, which
     * kills the process — a slow radio API would take the head unit down while
     * the user is driving. Every coroutine in this class therefore runs through
     * [guarded] and reports the failure in the existing toast bubble.
     */
    private val guard = CoroutineExceptionHandler { _, t ->
        _ui.value = _ui.value.copy(
            message = t.message?.takeIf { it.isNotBlank() } ?: "操作失败",
            busy = "",
            lyricsLoading = false
        )
    }

    /** viewModelScope with [guard] attached: same Job, same lifetime. */
    private val guarded: CoroutineScope
        get() = CoroutineScope(viewModelScope.coroutineContext + guard)

    fun init(context: Context) {
        if (_ui.value.ready) return
        guarded.launch {
            val count = GoBridge.init(context)
            GoBridge.startPlaybackService(context)
            val sources = MusicRepository.sources()
            val settings = MusicRepository.appSettings()
            val player = MusicRepository.queue()
            _ui.value = _ui.value.copy(
                ready = true,
                version = MusicRepository.version(),
                sources = sources,
                activeSourceId = sources.firstOrNull()?.id ?: "",
                player = player,
                settings = settings,
                message = "已加载 $count 个音源"
            )
            refreshBrowse()
            refreshPluginMeta()
            refreshAccounts()
        }
        guarded.launch {
            MusicRepository.events.collect { event -> handleEvent(event) }
        }
        consumeInstallLink()
    }

    /** Consume a mygocar://install?repo=... link once the bridge is ready. */
    private fun consumeInstallLink() {
        val repo = com.andy8401.mygo.car.bridge.InstallRequest.pendingRepo ?: return
        com.andy8401.mygo.car.bridge.InstallRequest.pendingRepo = null
        guarded.launch {
            _ui.value = _ui.value.copy(message = "正在安装 $repo", page = Page.PLUGINS)
            when (val result = PluginInstaller.installFromRepo(repo)) {
                is PluginInstaller.Result.Ok -> {
                    _ui.value = _ui.value.copy(message = "已安装 ${result.id}")
                    afterPluginListChanged()
                }
                is PluginInstaller.Result.Failed ->
                    _ui.value = _ui.value.copy(message = "安装失败：${result.message}")
            }
        }
    }

    private fun handleEvent(event: String) {
        val o = runCatching { org.json.JSONObject(event) }.getOrNull()
        val type = o?.optString("event", "") ?: return
        when (type) {
            // A new track invalidates the words on screen; every other player
            // event only moves the cursor.
            "track" -> guarded.launch {
                val player = MusicRepository.queue()
                _ui.value = _ui.value.copy(player = player)
                loadLyricsFor(player)
            }
            "playing", "state" -> guarded.launch {
                _ui.value = _ui.value.copy(player = MusicRepository.queue())
            }
            "error" -> _ui.value = _ui.value.copy(message = o.optString("message", "错误"))
            "pluginInstalled", "pluginRemoved" -> guarded.launch { afterPluginListChanged() }
            // A plugin signing in shows either a page (host.webSignIn, handled
            // by SignInCoordinator) or a QR code, which arrives here.
            "signInPrompt" -> {
                val prompt = Json.signInPrompt(event)
                val cleared = prompt == null || (prompt.text.isBlank() && !prompt.hasQR)
                _ui.value = _ui.value.copy(signInPrompt = if (cleared) null else prompt)
            }
            "account" -> guarded.launch { refreshAccount(o.optString("plugin", "")) }
            "toast" -> _ui.value = _ui.value.copy(message = o.optString("toast", ""))
        }
    }

    /**
     * Re-read the plugin list after an install or uninstall. The active source
     * may have just been removed, in which case the shelves must be cleared
     * instead of showing a source that no longer exists.
     */
    private suspend fun afterPluginListChanged() {
        val sources = MusicRepository.sources()
        val origins = runCatching { MusicRepository.pluginOrigins() }.getOrDefault(_ui.value.origins)
        val stillThere = sources.any { it.id == _ui.value.activeSourceId }
        val active = if (stillThere) _ui.value.activeSourceId else sources.firstOrNull()?.id ?: ""
        _ui.value = _ui.value.copy(
            sources = sources,
            origins = origins,
            activeSourceId = active,
            // Drop update rows for plugins that are gone.
            updates = _ui.value.updates.filter { u -> sources.any { it.id == u.id } }
        )
        if (!stillThere) {
            _ui.value = _ui.value.copy(browse = Browse(title = "", sections = emptyList()))
            refreshBrowse()
        }
        // A freshly installed source may already have an account (its own
        // session store), and an uninstalled one must not keep a stale name.
        refreshAccounts()
    }

    private suspend fun refreshPluginMeta() {
        val origins = runCatching { MusicRepository.pluginOrigins() }.getOrDefault(emptyList())
        _ui.value = _ui.value.copy(origins = origins)
    }

    // ------------------------------------------------------------- accounts

    /**
     * Reads who is signed in to one source. Only sources that export account()
     * are asked; for the rest the entry is simply absent, which the UI renders
     * as "not signed in".
     */
    private suspend fun refreshAccount(pluginId: String) {
        if (pluginId.isBlank()) return
        val source = _ui.value.sources.firstOrNull { it.id == pluginId } ?: return
        if (!source.canAccount) {
            _ui.value = _ui.value.copy(accounts = _ui.value.accounts - pluginId)
            return
        }
        val account = runCatching { MusicRepository.account(pluginId) }.getOrNull()
        val signedIn = account != null && account.signedIn && account.name.isNotBlank()
        _ui.value = _ui.value.copy(
            accounts = if (signedIn) _ui.value.accounts + (pluginId to account!!)
            else _ui.value.accounts - pluginId
        )
    }

    /** Reads the account of every source that can have one. */
    private suspend fun refreshAccounts() {
        _ui.value.sources.filter { it.canAccount }.forEach { refreshAccount(it.id) }
    }

    /**
     * Starts a source's sign-in.
     *
     * This is the entry point that did not exist: 网易云 / QQ音乐 / 喜马拉雅 only
     * ask the host for a sign-in page from inside their own signIn(), so with
     * nothing calling it the page could never appear.
     */
    fun signIn(pluginId: String) {
        if (pluginId.isBlank() || _ui.value.signingIn.isNotBlank()) return
        _ui.value = _ui.value.copy(signingIn = pluginId, message = "正在登录…")
        guarded.launch {
            val result = runCatching { MusicRepository.signIn(pluginId) }
            val account = result.getOrNull()
            val failure = result.exceptionOrNull()
            _ui.value = _ui.value.copy(
                signingIn = "",
                signInPrompt = null,
                message = when {
                    failure != null -> "登录失败：${failure.message ?: "未知错误"}"
                    account != null && account.signedIn ->
                        "已登录${account.name.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}"
                    else -> "登录未完成"
                }
            )
            refreshAccount(pluginId)
        }
    }

    fun signOut(pluginId: String) {
        if (pluginId.isBlank()) return
        guarded.launch {
            val result = runCatching { MusicRepository.signOut(pluginId) }
            _ui.value = _ui.value.copy(
                accounts = _ui.value.accounts - pluginId,
                message = result.exceptionOrNull()?.let { "退出失败：${it.message ?: "未知错误"}" }
                    ?: "已退出登录"
            )
            refreshAccount(pluginId)
        }
    }

    /** Closes the QR card without cancelling the sign-in the plugin is waiting on. */
    fun dismissSignInPrompt() {
        _ui.value = _ui.value.copy(signInPrompt = null)
    }

    suspend fun refreshBrowse() {
        val id = _ui.value.activeSourceId
        if (id.isBlank()) return
        val browse = try {
            MusicRepository.browse(id, "")
        } catch (e: Exception) {
            // A source that times out must not leave the page on "加载中…"
            // forever, and must not escape to the guard as a bare toast either:
            // the user needs both the empty state and the reason.
            if (id == _ui.value.activeSourceId) {
                _ui.value = _ui.value.copy(
                    browse = Browse(title = "", sections = emptyList()),
                    message = "加载失败：${e.message ?: "未知错误"}"
                )
            }
            return
        }
        // A network source can answer after the user has already switched away;
        // dropping the late result keeps the header and the content in step.
        if (id != _ui.value.activeSourceId) return
        _ui.value = _ui.value.copy(browse = browse)
    }

    fun selectSource(id: String) {
        if (id == _ui.value.activeSourceId) return
        _ui.value = _ui.value.copy(
            activeSourceId = id,
            page = Page.HOME,
            query = "",
            // Clear the old source's shelves instead of showing them under the
            // new source's title while the new page loads.
            browse = Browse(title = "", sections = emptyList())
        )
        guarded.launch { refreshBrowse() }
    }

    fun navigate(page: Page) {
        _ui.value = _ui.value.copy(page = page)
        guarded.launch {
            when (page) {
                Page.LIBRARY -> _ui.value = _ui.value.copy(browse = MusicRepository.localTracks())
                Page.SETTINGS -> _ui.value = _ui.value.copy(settings = MusicRepository.appSettings())
                Page.PLUGINS -> refreshPluginMeta()
                Page.HOME -> refreshBrowse()
                else -> Unit
            }
        }
    }

    // ---------------------------------------------------------------- browsing

    fun openBrowsePage(pageId: String) {
        // A show page's "load more" row hands back "show:<id>:<offset>", which is
        // a detail request, not a Browse page. Sending it to browse() would make
        // the plugin answer "没有这个喜马拉雅页面".
        parseDetailPage(pageId)?.let { (kind, id, offset) ->
            openDetail(kind, id, offset)
            return
        }
        val id = _ui.value.activeSourceId
        guarded.launch {
            // A bad page id must not kill the process: Go throws across JNI.
            runCatching { MusicRepository.browse(id, pageId) }
                .onSuccess { browse ->
                    if (id != _ui.value.activeSourceId) return@onSuccess
                    _ui.value = _ui.value.copy(browse = browse, page = Page.BROWSE)
                }
                .onFailure { e -> _ui.value = _ui.value.copy(message = "打开页面失败：${e.message ?: "未知错误"}") }
        }
    }

    /**
     * Open a plugin's detail page (show / album / artist / playlist).
     *
     * These ids are NOT Browse page ids. Upstream exports Show/Album/Artist/
     * Playlist separately, and ximalaya-web's browse() only serves its own
     * fixed pages, so routing a bare show id through browse() is exactly the
     * bug that made every 喜马拉雅 card tap fail.
     */
    fun openDetail(kind: String, id: String, offset: Int = 0) {
        val source = _ui.value.activeSourceId
        if (source.isBlank() || id.isBlank()) return
        guarded.launch {
            runCatching { MusicRepository.detail(source, kind, id, offset) }
                .onSuccess { browse ->
                    if (source != _ui.value.activeSourceId) return@onSuccess
                    _ui.value = _ui.value.copy(browse = browse, page = Page.BROWSE)
                }
                .onFailure { e ->
                    _ui.value = _ui.value.copy(message = "打开详情失败：${e.message ?: "未知错误"}")
                }
        }
    }

    /** "show:73103039:30" -> (show, 73103039, 30). Anything else is null. */
    private fun parseDetailPage(pageId: String): Triple<String, String, Int>? {
        val parts = pageId.split(":")
        if (parts.size != 3) return null
        if (parts[0] !in DETAIL_KINDS) return null
        if (parts[1].isBlank()) return null
        val offset = parts[2].toIntOrNull() ?: return null
        return Triple(parts[0], parts[1], offset)
    }

    private fun detailKind(kind: BrowseItem.Kind): String? = when (kind) {
        BrowseItem.Kind.SHOW -> "show"
        BrowseItem.Kind.ALBUM -> "album"
        BrowseItem.Kind.ARTIST -> "artist"
        BrowseItem.Kind.PLAYLIST -> "playlist"
        else -> null
    }

    private var searchJob: Job? = null

    fun search(query: String) {
        _ui.value = _ui.value.copy(query = query, page = Page.SEARCH)
        searchJob?.cancel()
        if (query.isBlank()) {
            _ui.value = _ui.value.copy(search = SearchResult())
            return
        }
        searchJob = guarded.launch {
            // A search is two or three plugin calls (tracks + 专辑), so typing
            // must not fire one per keystroke.
            delay(300)
            val result = MusicRepository.search(_ui.value.activeSourceId, query)
            if (_ui.value.query == query) _ui.value = _ui.value.copy(search = result)
        }
    }

    /** Play a Browse item. Tracks play; stations start live; other kinds open their page. */
    fun playItem(item: BrowseItem) {
        val detail = detailKind(item.kind)
        when {
            item.kind == BrowseItem.Kind.TRACK && item.track != null -> playTrack(item.track)
            item.kind == BrowseItem.Kind.STATION && item.id.isNotBlank() -> playStation(item)
            detail != null && item.id.isNotBlank() -> openDetail(detail, item.id)
            // A Link carries the page it points at ("rank", "categories",
            // "search:kw"); an empty id is a description with nowhere to go.
            item.kind == BrowseItem.Kind.LINK && item.id.isNotBlank() -> openBrowsePage(item.morePage.ifBlank { item.id })
            item.id.isNotBlank() -> openBrowsePage(item.id)
        }
    }

    /**
     * cnradio's station tiles carry ids like live:<broadcast>, which are not
     * Browse page ids. They must go through StationNext, not Browse.
     */
    private fun playStation(item: BrowseItem) {
        val source = _ui.value.activeSourceId
        guarded.launch {
            runCatching { MusicRepository.playStation(source, item.id) }
                .onSuccess {
                    _ui.value = _ui.value.copy(
                        player = MusicRepository.queue(),
                        message = "正在播放 ${item.title}"
                    )
                }
                .onFailure { e ->
                    _ui.value = _ui.value.copy(message = "直播启动失败：${e.message ?: "未知错误"}")
                }
        }
    }

    fun playTrack(track: Track, queue: List<Track> = listOf(track)) {
        val source = track.source.ifBlank { _ui.value.activeSourceId }
        guarded.launch {
            val start = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            MusicRepository.play(source, queue, start)
            _ui.value = _ui.value.copy(player = MusicRepository.queue(), message = "正在播放 ${track.name}")
        }
    }

    fun playAll(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        playTrack(tracks.first(), tracks)
    }

    fun togglePlay() = guarded.launch {
        MusicRepository.togglePlay()
        _ui.value = _ui.value.copy(player = MusicRepository.queue())
    }

    fun next() = guarded.launch {
        runCatching { MusicRepository.next() }
        _ui.value = _ui.value.copy(player = MusicRepository.queue())
    }

    fun prev() = guarded.launch {
        runCatching { MusicRepository.prev() }
        _ui.value = _ui.value.copy(player = MusicRepository.queue())
    }

    fun volume(v: Double) = guarded.launch { MusicRepository.volume(v) }

    fun seekTo(ms: Long) = guarded.launch {
        runCatching { MusicRepository.seek(ms) }
    }

    // ------------------------------------------------------- now playing

    /**
     * Open the full-screen player. Without an explicit pane it picks lyrics
     * when the user has them enabled, so the default tap on the player bar
     * lands on the words.
     */
    fun openPlayer(pane: PlayerPane? = null) {
        val wanted = pane ?: if (_ui.value.settings.lyrics) PlayerPane.LYRICS else PlayerPane.QUEUE
        _ui.value = _ui.value.copy(playerOpen = true, playerPane = wanted)
        if (wanted == PlayerPane.LYRICS) guarded.launch { loadLyricsFor(_ui.value.player) }
    }

    fun closePlayer() { _ui.value = _ui.value.copy(playerOpen = false) }

    fun setFocusMode(on: Boolean) { _ui.value = _ui.value.copy(focusMode = on) }

    fun setPlayerPane(pane: PlayerPane) {
        _ui.value = _ui.value.copy(playerPane = pane)
        if (pane == PlayerPane.LYRICS) guarded.launch { loadLyricsFor(_ui.value.player) }
    }

    /** Tap a queue row: jump the cursor there instead of rebuilding the queue. */
    fun playAt(index: Int) = guarded.launch {
        runCatching { MusicRepository.playAt(index) }
            .onSuccess {
                val player = MusicRepository.queue()
                _ui.value = _ui.value.copy(player = player, message = "切换到第 ${index + 1} 首")
                loadLyricsFor(player)
            }
            .onFailure { e ->
                // A live station's rows are a rolling window, not a queue.
                _ui.value = _ui.value.copy(message = "切换失败：${e.message ?: "未知错误"}")
            }
    }

    fun setLyricsEnabled(on: Boolean) = guarded.launch {
        MusicRepository.setAppSettings(lyrics = on)
        _ui.value = _ui.value.copy(
            settings = _ui.value.settings.copy(lyrics = on),
            // Hiding the pane the user is standing on would leave an empty
            // screen, so fall through to the queue.
            playerPane = if (!on && _ui.value.playerPane == PlayerPane.LYRICS) PlayerPane.QUEUE else _ui.value.playerPane,
            message = if (on) "已显示歌词" else "已隐藏歌词"
        )
        if (on) loadLyricsFor(_ui.value.player)
    }

    /**
     * Fetch lyrics for the track in [player], unless they are already loaded.
     * A source without lyrics answers with supported=false plus a reason, which
     * is shown in the pane — never an error dialog.
     */
    private suspend fun loadLyricsFor(player: PlayerState, force: Boolean = false) {
        if (!_ui.value.settings.lyrics && !force) return
        val track = player.currentTrack
        if (track == null || track.id.isBlank()) return
        if (!force && track.id == _ui.value.lyricsTrackId && _ui.value.lyrics != null) return
        // music.Info.id is the plugin's own id; a track also carries its source.
        val plugin = player.pluginId.ifBlank { track.source }
        if (plugin.isBlank()) return

        _ui.value = _ui.value.copy(lyricsTrackId = track.id, lyricsLoading = true)
        val result = runCatching { MusicRepository.lyrics(plugin, track.id) }
        // The user may have skipped on while this was in flight.
        if (track.id != _ui.value.lyricsTrackId) return
        _ui.value = _ui.value.copy(
            lyricsLoading = false,
            lyrics = result.getOrElse { e ->
                LyricsText(reason = "歌词加载失败：${e.message ?: "未知错误"}")
            }
        )
    }

    fun setQuality(q: String) = guarded.launch {
        MusicRepository.setAppSettings(quality = q)
        _ui.value = _ui.value.copy(settings = _ui.value.settings.copy(quality = q), message = "音质设为 $q")
    }

    fun dismissMessage() { _ui.value = _ui.value.copy(message = "") }

    // ---------------------------------------------------- plugin management

    /** Install from a SAF-picked zip. The file is copied to private storage
     *  first because the Go process cannot open a content:// Uri. */
    fun installFromUri(context: Context, uri: Uri) = guarded.launch {
        _ui.value = _ui.value.copy(busy = "正在安装所选文件…")
        when (val result = PluginInstaller.installFromSaf(context, uri)) {
            is PluginInstaller.Result.Ok -> {
                _ui.value = _ui.value.copy(busy = "", message = "已安装 ${result.id}")
                afterPluginListChanged()
            }
            is PluginInstaller.Result.Failed ->
                _ui.value = _ui.value.copy(busy = "", message = "安装失败：${result.message}")
        }
    }

    /** Install or update from a GitHub owner/repo. Network runs inside Go. */
    fun installFromRepo(repo: String) = guarded.launch {
        val target = repo.trim()
        if (target.isBlank()) {
            _ui.value = _ui.value.copy(message = "请输入 owner/repo")
            return@launch
        }
        if (_ui.value.busy.isNotBlank()) return@launch
        _ui.value = _ui.value.copy(busy = "正在从 $target 安装…")
        when (val result = PluginInstaller.installFromRepo(target)) {
            is PluginInstaller.Result.Ok -> {
                _ui.value = _ui.value.copy(busy = "", message = "已安装/更新 ${result.id}")
                afterPluginListChanged()
            }
            is PluginInstaller.Result.Failed ->
                _ui.value = _ui.value.copy(busy = "", message = "安装失败：${result.message}")
        }
    }

    fun checkUpdates() = guarded.launch {
        if (_ui.value.updatesRunning) return@launch
        _ui.value = _ui.value.copy(updatesRunning = true, busy = "正在检查更新…")
        val result = runCatching { MusicRepository.pluginUpdates() }
        val updates = result.getOrDefault(emptyList())
        val failure = result.exceptionOrNull()
        _ui.value = _ui.value.copy(
            updatesRunning = false,
            busy = "",
            updates = updates,
            message = when {
                failure != null -> "检查更新失败：${failure.message ?: "网络错误"}"
                updates.none { it.repo.isNotBlank() } -> "没有来自仓库的插件，无需检查"
                updates.any { it.error.isNotBlank() } -> "部分插件检查失败：${updates.first { it.error.isNotBlank() }.error}"
                else -> "检查完成：${updates.count { it.available }} 个可更新"
            }
        )
    }

    fun openPluginSettings(id: String) = guarded.launch {
        val list = runCatching { MusicRepository.pluginSettings(id) }.getOrDefault(emptyList())
        _ui.value = _ui.value.copy(settingsFor = id, pluginSettings = list)
    }

    fun closePluginSettings() {
        _ui.value = _ui.value.copy(settingsFor = "", pluginSettings = emptyList())
    }

    fun setPluginSetting(pluginId: String, key: String, value: String) = guarded.launch {
        runCatching { MusicRepository.setPluginSetting(pluginId, key, value) }
            .onSuccess {
                _ui.value = _ui.value.copy(
                    pluginSettings = MusicRepository.pluginSettings(pluginId),
                    message = "已保存"
                )
            }
            .onFailure { e -> _ui.value = _ui.value.copy(message = "保存失败：${e.message ?: "未知错误"}") }
    }

    fun askUninstall(id: String) { _ui.value = _ui.value.copy(confirmUninstall = id) }

    fun cancelUninstall() { _ui.value = _ui.value.copy(confirmUninstall = "") }

    fun confirmUninstall() = guarded.launch {
        val id = _ui.value.confirmUninstall
        _ui.value = _ui.value.copy(confirmUninstall = "")
        if (id.isBlank()) return@launch
        when (val result = PluginInstaller.uninstall(id)) {
            is PluginInstaller.Result.Ok -> {
                _ui.value = _ui.value.copy(message = "已卸载 ${id}")
                afterPluginListChanged()
            }
            is PluginInstaller.Result.Failed ->
                _ui.value = _ui.value.copy(message = "卸载失败：${result.message}")
        }
    }

    private companion object {
        /** Must match facade/detail.go's detailKinds(). */
        val DETAIL_KINDS = setOf("show", "album", "artist", "playlist")
    }
}
