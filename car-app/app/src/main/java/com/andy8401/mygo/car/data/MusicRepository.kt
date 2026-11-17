package com.andy8401.mygo.car.data

import com.andy8401.mygo.car.bridge.BridgeListener
import facade.Facade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Thin suspend wrapper over the synchronous JNI facade. All calls run on IO. */
object MusicRepository {

    /** Go -> Kotlin events (state/queue/toast/error). */
    val events: Flow<String> = BridgeListener.events

    suspend fun init(dataDir: String): Int = withContext(Dispatchers.IO) { Facade.init(dataDir).toInt() }

    /** Host capability version the Go facade reports (plugins gate on it). */
    suspend fun version(): String = withContext(Dispatchers.IO) { Facade.version() }

    suspend fun sources(): List<SourceInfo> = withContext(Dispatchers.IO) {
        Json.sources(Facade.listPluginsJSON())
    }

    suspend fun browse(sourceId: String, page: String = ""): Browse? = withContext(Dispatchers.IO) {
        Json.browse(Facade.browseJSON(sourceId, page))
    }

    /**
     * Open a plugin's detail page. A show/album/artist/playlist id must NOT go
     * through browse(): ximalaya-web's browse() only serves its own fixed pages
     * and answers "没有这个喜马拉雅页面" for a bare show id.
     */
    suspend fun detail(sourceId: String, kind: String, id: String, offset: Int = 0): Browse? =
        withContext(Dispatchers.IO) {
            Json.browse(Facade.openDetailJSON(sourceId, kind, id, offset.toLong()))
        }

    /** Declared settings of a plugin with their current values. */
    suspend fun pluginSettings(pluginId: String): List<PluginSetting> = withContext(Dispatchers.IO) {
        Json.pluginSettings(Facade.settingsJSON(pluginId))
    }

    suspend fun setPluginSetting(pluginId: String, key: String, value: String) =
        withContext(Dispatchers.IO) { Facade.setSettingJSON(pluginId, key, value) }

    suspend fun pluginOrigins(): List<PluginOrigin> = withContext(Dispatchers.IO) {
        Json.pluginOrigins(Facade.pluginOriginsJSON())
    }

    /** Asks GitHub for each repo-installed plugin's latest release. Needs network. */
    suspend fun pluginUpdates(): List<PluginUpdate> = withContext(Dispatchers.IO) {
        Json.pluginUpdates(Facade.checkPluginUpdatesJSON())
    }

    suspend fun search(sourceId: String, query: String): SearchResult = withContext(Dispatchers.IO) {
        // A plugin fills in only the kind that was asked for, and kind names
        // differ: ximalaya-web answers "shows" (专辑), 网易云/QQ音乐 answer
        // "albums". Asking only for tracks is why 专辑 never showed up in
        // search results — there was nothing to tap.
        val tracks = searchKind(sourceId, query, "tracks")
        val shows = searchKind(sourceId, query, "shows")
        val albums = if (shows == null || shows.shows.isEmpty()) {
            searchKind(sourceId, query, "albums")
        } else {
            null
        }
        SearchResult(
            tracks = tracks?.tracks ?: emptyList(),
            shows = shows?.shows ?: emptyList(),
            albums = albums?.albums ?: emptyList(),
            more = tracks?.more ?: false
        )
    }

    /**
     * One kind of one search, or null when the source does not do that kind.
     *
     * A source that cannot search 专辑 answers "unsupported", which is not an
     * error worth a toast - but a timeout is, so it goes to logcat rather than
     * vanishing: silently dropping it is what made 专辑 look broken.
     */
    private fun searchKind(sourceId: String, query: String, kind: String): SearchResult? =
        runCatching { Json.search(Facade.searchJSON(sourceId, query, kind, 0)) }
            .onFailure { android.util.Log.w("MyGoSearch", "search $kind \"$query\": ${it.message}") }
            .getOrNull()

    suspend fun state(): PlayerState = withContext(Dispatchers.IO) { Json.queue(Facade.stateJSON()) }

    suspend fun queue(): PlayerState = withContext(Dispatchers.IO) {
        val raw = Facade.queueJSON()
        android.util.Log.d("GoQueue", "queueJSON=$raw")
        Json.queue(raw)
    }

    /** Serialize tracks back into music.Track JSON for the Go queue. */
    suspend fun play(sourceId: String, tracks: List<Track>, start: Int) = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        tracks.forEach { t ->
            val album = JSONObject().put("id", t.album?.id ?: "").put("name", t.album?.name ?: "")
            if (t.imageUrl.isNotBlank() || t.album?.imageUrl?.isNotBlank() == true) {
                album.put("imageUrl", t.imageUrl.ifBlank { t.album?.imageUrl ?: "" })
            }
            val artists = JSONArray()
            t.artists.forEach { artists.put(JSONObject().put("id", it.id).put("name", it.name)) }
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("source", sourceId)
                    .put("name", t.name)
                    .put("album", album)
                    .put("artists", artists)
                    .put("duration", t.durationMs)
            )
        }
        Facade.playQueueJSON(sourceId, arr.toString(), start.toLong())
    }

    /** Start a live station (cnradio station ids are live:<broadcast>). */
    suspend fun playStation(sourceId: String, stationId: String) = withContext(Dispatchers.IO) {
        Facade.playStationJSON(sourceId, stationId)
    }

    /** Jump to a queue row without rebuilding the queue. */
    suspend fun playAt(index: Int) = withContext(Dispatchers.IO) { Facade.playAt(index.toLong()) }

    /**
     * Lyrics for a track. The answer can legitimately have no lines (a radio
     * station, a podcast episode); Go reports that as supported=false plus a
     * reason rather than an error, so this never throws for "no lyrics".
     */
    suspend fun lyrics(pluginId: String, trackId: String): LyricsText = withContext(Dispatchers.IO) {
        Json.lyrics(Facade.lyricsJSON(pluginId, trackId))
    }

    /**
     * Runs a source's sign-in. Blocks for as long as the listener takes — the
     * plugin allows five minutes — so it must never be called on the main
     * thread. The plugin may answer with a WebView page (host.webSignIn) or a
     * QR code, which arrives as a `signInPrompt` event while this is waiting.
     */
    suspend fun signIn(pluginId: String): Account = withContext(Dispatchers.IO) {
        Json.account(Facade.signInJSON(pluginId))
    }

    suspend fun account(pluginId: String): Account = withContext(Dispatchers.IO) {
        Json.account(Facade.accountJSON(pluginId))
    }

    suspend fun signOut(pluginId: String): Account = withContext(Dispatchers.IO) {
        Json.account(Facade.signOutJSON(pluginId))
    }

    suspend fun togglePlay(): Boolean = withContext(Dispatchers.IO) { Facade.togglePlay() }

    suspend fun next() = withContext(Dispatchers.IO) { Facade.next() }
    suspend fun prev() = withContext(Dispatchers.IO) { Facade.prev() }
    suspend fun seek(ms: Long) = withContext(Dispatchers.IO) { Facade.seekMs(ms) }
    suspend fun volume(v: Double) = withContext(Dispatchers.IO) { Facade.setVolume(v) }

    suspend fun appSettings(): AppSettings = withContext(Dispatchers.IO) {
        val o = Json.obj(Facade.appSettingsJSON()) ?: return@withContext AppSettings()
        AppSettings(
            language = o.optString("language", "zh-CN"),
            quality = o.optString("quality", "high"),
            volume = o.optDouble("volume", 1.0),
            appearance = o.optString("appearance", "system"),
            normalize = o.optBoolean("normalize", false),
            lyrics = o.optBoolean("lyrics", true)
        )
    }

    suspend fun setAppSettings(
        language: String? = null,
        quality: String? = null,
        volume: Double? = null,
        appearance: String? = null,
        lyrics: Boolean? = null
    ) = withContext(Dispatchers.IO) {
        val patch = JSONObject()
        language?.let { patch.put("language", it) }
        quality?.let { patch.put("quality", it) }
        volume?.let { patch.put("volume", it) }
        appearance?.let { patch.put("appearance", it) }
        lyrics?.let { patch.put("lyrics", it) }
        Facade.setSettingsJSON(patch.toString())
    }

    suspend fun localTracks(): Browse? = withContext(Dispatchers.IO) { Json.browse(Facade.localTracksJSON()) }
}
