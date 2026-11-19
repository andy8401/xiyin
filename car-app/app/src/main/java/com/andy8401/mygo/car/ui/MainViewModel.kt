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
import com.andy8401.mygo.car.data.FavoriteItem
import com.andy8401.mygo.car.data.FavoriteStore
import com.andy8401.mygo.car.data.Json
import com.andy8401.mygo.car.data.LyricsText
import com.andy8401.mygo.car.data.MusicRepository
import com.andy8401.mygo.car.data.NavItem
import com.andy8401.mygo.car.data.PlayerState
import com.andy8401.mygo.car.data.PluginOrigin
import com.andy8401.mygo.car.data.PluginSetting
import com.andy8401.mygo.car.data.PluginUpdate
import com.andy8401.mygo.car.data.SearchResult
import com.andy8401.mygo.car.data.Section
import com.andy8401.mygo.car.data.SignInPrompt
import com.andy8401.mygo.car.data.SourceInfo
import com.andy8401.mygo.car.data.Track
import com.andy8401.mygo.car.data.toFavorite
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
        /** Category of the active plugin currently shown in the main pane. */
        val activeNav: NavItem? = null,
        val page: Page = Page.HOME,
        val browse: Browse? = null,
        val search: SearchResult = SearchResult(),
        val query: String = "",
        val player: PlayerState = PlayerState(),
        /** Host-local collection, keyed by source. Never comes from a plugin. */
        val favorites: List<FavoriteItem> = emptyList(),
        /** What the player bar's heart refers to right now (song or station). */
        val currentPlayable: FavoriteItem? = null,
        val settings: AppSettings = AppSettings(),
        val message: String = "",
        // --- now playing ---
        /** The full-screen player is a page of the app, not a local flag, so a
         *  queue row or the player bar can open it on a given pane. */
        val playerOpen: Boolean = false,
        val playerPane: PlayerPane = PlayerPane.LYRICS,
        /** Minimal driving view: one title, one play button, one progress bar. */
        val focusMode: Boolean = false,
        /** 播放界面（首页）是否最大化到 32:9 超宽。默认 16:9；离开播放页自动还原。 */
        val maximized: Boolean = false,
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
            get() = sources.filter { it.id != LOCAL_ID }.map { src ->
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

        /** Key of whatever the player bar would favourite, including tracks
         *  started by the notification or a playlist outside the current page. */
        val currentFavoriteKey: String
            get() = currentPlayable?.key ?: run {
                val track = player.currentTrack ?: return ""
                val src = player.pluginId.ifBlank { track.source }.ifBlank { activeSourceId }
                "$src|TRACK|${track.id}"
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

    /** Local library is exposed as a source alongside installed plugins, so
     *  the driver switches to it from the same plugin dropdown. */
    private fun localSource(): SourceInfo = SourceInfo(id = LOCAL_ID, name = "本地曲库")

    /** Favourites are owned by the host, not by Go or any plugin. */
    private var favoriteStore: FavoriteStore? = null

    fun init(context: Context) {
        if (_ui.value.ready) return
        if (favoriteStore == null) favoriteStore = FavoriteStore(context.applicationContext)
        val savedFavorites = favoriteStore!!.all()
        guarded.launch {
            val count = GoBridge.init(context)
            GoBridge.startPlaybackService(context)
            val plugins = MusicRepository.sources()
            // Local library is a source of its own, not a category inside every
            // plugin. Keep it last so a real plugin stays the default.
            val sources = plugins + localSource()
            val settings = MusicRepository.appSettings()
            val player = MusicRepository.queue()
            val firstPlugin = plugins.firstOrNull()
            val useLocal = firstPlugin == null
            val activeId = firstPlugin?.id ?: LOCAL_ID
            val localBrowse = if (useLocal) runCatching { MusicRepository.localTracks() }.getOrNull() else null
            _ui.value = _ui.value.copy(
                ready = true,
                version = MusicRepository.version(),
                sources = sources,
                activeSourceId = activeId,
                activeNav = defaultNav(firstPlugin),
                page = if (useLocal) Page.LIBRARY else Page.HOME,
                browse = localBrowse,
                player = player,
                favorites = savedFavorites,
                settings = settings,
                message = if (useLocal) "未安装插件，已显示本地曲库" else "已加载 $count 个音源"
            )
            if (!useLocal) refreshBrowse()
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
                val now = player.currentTrack
                val src = player.pluginId.ifBlank { now?.source ?: "" }
                // A station's queue rows are a moving window of programmes; the
                // thing the driver favourited is the station itself, so keep it.
                val keepStation = player.stationId.isNotBlank() &&
                    _ui.value.currentPlayable?.kind == BrowseItem.Kind.STATION.name
                _ui.value = _ui.value.copy(
                    player = player,
                    currentPlayable = if (keepStation) _ui.value.currentPlayable
                        else now?.let { it.toFavorite(src) }
                )
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
        val plugins = MusicRepository.sources()
        val sources = plugins + localSource()
        val origins = runCatching { MusicRepository.pluginOrigins() }.getOrDefault(_ui.value.origins)
        val oldActive = _ui.value.activeSourceId
        val activeIsLocal = oldActive == LOCAL_ID
        val pluginStillThere = plugins.any { it.id == oldActive }
        val active = when {
            activeIsLocal -> LOCAL_ID
            pluginStillThere -> oldActive
            plugins.isNotEmpty() -> plugins.first().id
            else -> LOCAL_ID
        }
        val activeSource = sources.firstOrNull { it.id == active }
        val currentNav = _ui.value.activeNav
        val nav = if (currentNav != null && activeSource?.navigation?.any { it.key == currentNav.key } == true) {
            currentNav
        } else {
            defaultNav(activeSource)
        }
        _ui.value = _ui.value.copy(
            sources = sources,
            origins = origins,
            activeSourceId = active,
            activeNav = nav,
            // Drop update rows for plugins that are gone.
            updates = _ui.value.updates.filter { u -> plugins.any { it.id == u.id } }
        )
        if (active == LOCAL_ID) {
            val local = runCatching { MusicRepository.localTracks() }.getOrNull()
            _ui.value = _ui.value.copy(page = Page.LIBRARY, activeNav = null, browse = local)
        } else if (!pluginStillThere && !activeIsLocal) {
            _ui.value = _ui.value.copy(
                page = Page.HOME,
                browse = Browse(title = "", sections = emptyList())
            )
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

    /** Default category is the plugin's built-in home, or its first declared item. */
    private fun defaultNav(source: SourceInfo?): NavItem? =
        source?.navigation?.firstOrNull { it.builtin == "home" }
            ?: source?.navigation?.firstOrNull()

    /**
     * Go facade maps an empty page (and "home") to a plugin's Home(). Builtin
     * categories are app-level concepts, so plugin page ids differ: cnradio's
     * builtin "stations" is Browse("all"), while another source may answer
     * Browse("stations"). Try the known ids in order and fall back to Home.
     */
    private fun navPageCandidates(item: NavItem?): List<String> = when {
        item == null || item.builtin == "home" -> listOf("")
        item.builtin == "stations" -> listOf("all", "stations", "")
        item.builtin.isNotBlank() -> listOf(item.builtin, "")
        item.page.isNotBlank() -> listOf(item.page)
        else -> listOf("")
    }

    suspend fun refreshBrowse() {
        val id = _ui.value.activeSourceId
        val nav = _ui.value.activeNav
        val expectedKey = nav?.key
        if (id.isBlank()) return
        // 收藏 is a host-side category: never sent to the plugin's browse().
        if (nav?.builtin == FAVORITES_NAV_ID) {
            if (id == _ui.value.activeSourceId) {
                _ui.value = _ui.value.copy(browse = favoritesBrowse(id))
            }
            return
        }
        if (id == LOCAL_ID) {
            val local = try {
                MusicRepository.localTracks()
            } catch (e: Exception) {
                if (id == _ui.value.activeSourceId) {
                    _ui.value = _ui.value.copy(
                        browse = Browse(title = "", sections = emptyList()),
                        message = "本地曲库加载失败：${e.message ?: "未知错误"}"
                    )
                }
                return
            }
            if (id == _ui.value.activeSourceId) _ui.value = _ui.value.copy(browse = local)
            return
        }
        val browse = try {
            var result: Browse? = null
            var lastFailure: Exception? = null
            for (page in navPageCandidates(nav)) {
                try {
                    result = MusicRepository.browse(id, page)
                    break
                } catch (e: Exception) {
                    lastFailure = e
                }
            }
            result ?: throw (lastFailure ?: IllegalStateException("没有可打开的页面"))
        } catch (e: Exception) {
            // A source that times out must not leave the page on "加载中…"
            // forever, and must not escape to the guard as a bare toast either:
            // the user needs both the empty state and the reason.
            if (id == _ui.value.activeSourceId && expectedKey == _ui.value.activeNav?.key) {
                _ui.value = _ui.value.copy(
                    browse = Browse(title = "", sections = emptyList()),
                    message = "加载失败：${e.message ?: "未知错误"}"
                )
            }
            return
        }
        // A network source can answer after the user has already switched plugin
        // or category; dropping the late result keeps the header and content in step.
        if (id != _ui.value.activeSourceId || expectedKey != _ui.value.activeNav?.key) return
        _ui.value = _ui.value.copy(browse = browse)
    }

    fun selectSource(id: String) {
        if (id == _ui.value.activeSourceId) return
        val source = _ui.value.sources.firstOrNull { it.id == id }
        if (id == LOCAL_ID) {
            _ui.value = _ui.value.copy(
                activeSourceId = id,
                activeNav = null,
                page = Page.LIBRARY,
                query = "",
                browse = Browse(title = "", sections = emptyList())
            )
            guarded.launch { refreshBrowse() }
            return
        }
        _ui.value = _ui.value.copy(
            activeSourceId = id,
            activeNav = defaultNav(source),
            page = Page.HOME,
            query = "",
            // Clear the old source's shelves instead of showing them under the
            // new source's title while the new page loads.
            browse = Browse(title = "", sections = emptyList())
        )
        guarded.launch { refreshBrowse() }
    }

    /** Select a category declared by the active plugin's navigation array. */
    fun selectNav(item: NavItem) {
        val id = _ui.value.activeSourceId
        if (id.isBlank()) return
        _ui.value = _ui.value.copy(
            activeNav = item,
            page = Page.HOME,
            query = "",
            browse = Browse(title = item.label, sections = emptyList())
        )
        guarded.launch { refreshBrowse() }
    }

    // ---------------------------------------------------------------- favourites

    /** Build the contents of a source's 收藏 category entirely from local rows. */
    private fun favoritesBrowse(sourceId: String): Browse {
        val rows = _ui.value.favorites.filter { it.sourceId == sourceId }
        return Browse(
            title = "收藏",
            subtitle = "仅保存在本机 · 共 ${rows.size} 项",
            sections = if (rows.isEmpty()) emptyList()
            else listOf(Section("我的收藏", rows.map { it.toBrowseItem() }))
        )
    }

    /** Select the fixed 收藏 category appended to every source's navigation. */
    fun selectFavoriteNav() {
        val source = _ui.value.activeSourceId
        if (source.isBlank()) return
        _ui.value = _ui.value.copy(
            activeNav = NavItem(builtin = FAVORITES_NAV_ID, name = "收藏", icon = "favorite"),
            page = Page.HOME,
            query = "",
            browse = favoritesBrowse(source)
        )
    }

    fun isFavorite(key: String): Boolean =
        key.isNotBlank() && _ui.value.favorites.any { it.key == key }

    /** Save or remove one row. No plugin is contacted; only local storage changes. */
    fun toggleFavorite(item: FavoriteItem) {
        val store = favoriteStore ?: return
        if (item.itemId.isBlank() || item.title.isBlank()) return
        val added = store.toggle(item)
        val next = _ui.value.copy(
            favorites = store.all(),
            message = if (added) "已收藏「${item.title}」" else "已取消收藏"
        )
        // Standing on the 收藏 page? Rebuild it so a removed row vanishes at once.
        _ui.value = if (next.activeNav?.builtin == FAVORITES_NAV_ID) {
            next.copy(browse = favoritesBrowse(next.activeSourceId))
        } else next
    }

    /** The player bar's heart: favourites whatever is actually playing. */
    fun toggleCurrentFavorite() {
        val item = _ui.value.currentPlayable ?: run {
            val track = _ui.value.player.currentTrack
            if (track == null) {
                _ui.value = _ui.value.copy(message = "当前没有可收藏的内容")
                return
            }
            val src = _ui.value.player.pluginId.ifBlank { track.source }.ifBlank { _ui.value.activeSourceId }
            track.toFavorite(src)
        }
        toggleFavorite(item)
    }

    fun navigate(page: Page) {
        // 最大化只属于播放界面（首页）；切走时还原为 16:9。
        _ui.value = _ui.value.copy(page = page, maximized = if (page == Page.HOME) _ui.value.maximized else false)
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
        val source = _ui.value.activeSourceId
        when {
            item.kind == BrowseItem.Kind.TRACK && item.track != null ->
                playTrack(item.track, favoriteSource = source)
            item.kind == BrowseItem.Kind.STATION && item.id.isNotBlank() ->
                playStation(item, favoriteSource = source)
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
    private fun playStation(item: BrowseItem, favoriteSource: String = "") {
        val source = favoriteSource.ifBlank { _ui.value.activeSourceId }
        _ui.value = _ui.value.copy(currentPlayable = item.toFavorite(source))
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

    fun playTrack(track: Track, queue: List<Track> = listOf(track), favoriteSource: String = "") {
        val source = track.source.ifBlank { _ui.value.activeSourceId }
        _ui.value = _ui.value.copy(currentPlayable = track.toFavorite(favoriteSource.ifBlank { source }))
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

    /** 播放界面的 16:9 ↔ 32:9 最大化切换；仅首页可用。 */
    fun toggleMaximized() {
        if (_ui.value.page != Page.HOME) return
        _ui.value = _ui.value.copy(maximized = !_ui.value.maximized)
    }

    fun setPlayerPane(pane: PlayerPane) {
        _ui.value = _ui.value.copy(playerPane = pane)
        if (pane == PlayerPane.LYRICS) guarded.launch { loadLyricsFor(_ui.value.player) }
    }

    /** Tap a queue row: jump the cursor there instead of rebuilding the queue. */
    fun playAt(index: Int) = guarded.launch {
        runCatching { MusicRepository.playAt(index) }
            .onSuccess {
                val player = MusicRepository.queue()
                val now = player.currentTrack
                val src = player.pluginId.ifBlank { now?.source ?: "" }
                _ui.value = _ui.value.copy(
                    player = player,
                    currentPlayable = now?.let { it.toFavorite(src) },
                    message = "切换到第 ${index + 1} 首"
                )
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

    companion object {
        const val LOCAL_ID = "local"
        /** Builtin id of the host-owned 收藏 category shown for every source. */
        const val FAVORITES_NAV_ID = "favorites"
        /** Must match facade/detail.go's detailKinds(). */
        val DETAIL_KINDS = setOf("show", "album", "artist", "playlist")
    }
}
