package com.andy8401.mygo.car.data

import org.json.JSONArray
import org.json.JSONObject

/** Kotlin mirrors of the JSON the Go facade emits (internal/music structs).
 *  Parsing is defensive: a plugin may omit optional fields. */

data class Artist(val id: String, val name: String)
data class Album(val id: String, val name: String, val imageUrl: String = "")

data class Track(
    val id: String,
    val name: String,
    val artists: List<Artist> = emptyList(),
    val album: Album? = null,
    val durationMs: Long = 0,
    val imageUrl: String = "",
    val source: String = ""
) {
    val artistText: String get() = artists.joinToString(", ") { it.name }
}

data class Station(val id: String, val name: String, val description: String = "", val imageUrl: String = "")
data class Playlist(val id: String, val name: String, val imageUrl: String = "", val trackCount: Int = 0)

/** One row in a Browse section. Exactly one of these is populated. */
data class BrowseItem(
    val kind: Kind,
    val id: String,
    val title: String,
    val subtitle: String = "",
    val imageUrl: String = "",
    val track: Track? = null,
    val morePage: String = ""
) {
    enum class Kind { TRACK, ALBUM, ARTIST, PLAYLIST, SHOW, STATION, LINK }
}

data class Section(val title: String, val items: List<BrowseItem>, val morePage: String = "")

data class Browse(val title: String, val subtitle: String = "", val sections: List<Section>)

/**
 * One locally saved favourite. Favourites are a host concept: they are written
 * only to the app's own SharedPreferences, never through a plugin's API or the
 * network, so they survive source updates and work offline.
 *
 * [kind] reuses [BrowseItem.Kind]'s name, which lets a stored row be turned
 * back into a tappable tile without asking the plugin to re-browse. Songs keep
 * the full Track JSON in [payload], so a saved song plays even if the source
 * is unreachable at that moment.
 */
data class FavoriteItem(
    val sourceId: String,
    val kind: String,
    val itemId: String,
    val title: String,
    val subtitle: String = "",
    val imageUrl: String = "",
    val payload: String = "",
    /** Page id of the folder whose expansion added this row; "" when the row
     *  was favourited directly. Lets a folder's heart reflect/unfavourite the
     *  channels it contributed without storing the folder itself as a row. */
    val folder: String = ""
) {
    /** Stable identity: one copy of a given item per source. */
    val key: String get() = "$sourceId|$kind|$itemId"

    fun toBrowseItem(): BrowseItem = BrowseItem(
        kind = runCatching { BrowseItem.Kind.valueOf(kind) }.getOrDefault(BrowseItem.Kind.LINK),
        id = itemId,
        title = title,
        subtitle = subtitle,
        imageUrl = imageUrl,
        track = if (kind == BrowseItem.Kind.TRACK.name && payload.isNotBlank()) {
            Json.track(Json.obj(payload))
        } else null
    )

    fun toJson(): JSONObject = JSONObject()
        .put("sourceId", sourceId)
        .put("kind", kind)
        .put("itemId", itemId)
        .put("title", title)
        .put("subtitle", subtitle)
        .put("imageUrl", imageUrl)
        .put("payload", payload)
        .put("folder", folder)

    companion object {
        fun fromJson(o: JSONObject?): FavoriteItem? {
            if (o == null) return null
            val sourceId = o.optString("sourceId", "")
            val kind = o.optString("kind", "")
            val itemId = o.optString("itemId", "")
            if (sourceId.isBlank() || kind.isBlank() || itemId.isBlank()) return null
            return FavoriteItem(
                sourceId = sourceId,
                kind = kind,
                itemId = itemId,
                title = o.optString("title", ""),
                subtitle = o.optString("subtitle", ""),
                imageUrl = o.optString("imageUrl", ""),
                payload = o.optString("payload", ""),
                folder = o.optString("folder", "")
            )
        }
    }
}

/** Turn a browse tile into a storable favourite of the given source. */
fun BrowseItem.toFavorite(sourceId: String): FavoriteItem = FavoriteItem(
    sourceId = sourceId,
    kind = kind.name,
    itemId = id,
    title = title,
    subtitle = subtitle,
    imageUrl = imageUrl,
    payload = track?.let(::trackPayload) ?: ""
)

/** Turn the now-playing track into a storable favourite. */
fun Track.toFavorite(sourceId: String): FavoriteItem = FavoriteItem(
    sourceId = sourceId,
    kind = BrowseItem.Kind.TRACK.name,
    itemId = id,
    title = name,
    subtitle = artistText,
    imageUrl = imageUrl.ifBlank { album?.imageUrl ?: "" },
    payload = trackPayload(this)
)

/** The same Track shape MusicRepository.play() sends back to Go. */
internal fun trackPayload(t: Track): String {
    val album = JSONObject()
        .put("id", t.album?.id ?: "")
        .put("name", t.album?.name ?: "")
        .put("imageUrl", t.album?.imageUrl ?: "")
    val artists = JSONArray()
    t.artists.forEach { artists.put(JSONObject().put("id", it.id).put("name", it.name)) }
    return JSONObject()
        .put("id", t.id)
        .put("source", t.source)
        .put("name", t.name)
        .put("album", album)
        .put("artists", artists)
        .put("duration", t.durationMs)
        .put("imageUrl", t.imageUrl)
        .toString()
}

/** One declared setting of a plugin, with its current value (music.Setting + value). */
data class PluginSetting(
    val key: String,
    val label: String,
    val help: String = "",
    /** text (default), password, boolean or select. */
    val type: String = "text",
    val value: String = "",
    val choices: List<SettingChoice> = emptyList(),
    /** Where to get the value (music.Setting.Link), e.g. a sign-in page. */
    val link: String = ""
)

data class SettingChoice(val value: String, val label: String)

/** Where an installed plugin came from (flush of pluginOriginsJSON). */
data class PluginOrigin(
    val id: String,
    /** Empty for a plugin installed from a local zip; set for repo installs. */
    val repo: String = "",
    val version: String = "",
    val dir: String = ""
)

/** One row of checkPluginUpdatesJSON. */
data class PluginUpdate(
    val id: String,
    val repo: String = "",
    val current: String = "",
    val latest: String = "",
    val available: Boolean = false,
    /** Set when the GitHub check failed for this plugin. */
    val error: String = ""
)

data class SearchResult(
    val tracks: List<Track> = emptyList(),
    val albums: List<BrowseItem> = emptyList(),
    val artists: List<BrowseItem> = emptyList(),
    val playlists: List<BrowseItem> = emptyList(),
    val shows: List<BrowseItem> = emptyList(),
    val more: Boolean = false
)

data class SourceInfo(
    val id: String,
    val name: String,
    val version: String = "",
    val playback: String = "",
    /** Optional favicon/site icon URL declared by the plugin manifest. */
    val iconUrl: String = "",
    val settings: List<String> = emptyList(),
    val navigation: List<NavItem> = emptyList(),
    /** The plugin exports signIn(): only then may the UI offer 登录. */
    val canSignIn: Boolean = false,
    /** The plugin exports account(): only then can the UI say who signed in. */
    val canAccount: Boolean = false
)

/** Who is signed in to a source, or nobody. */
data class Account(
    val signedIn: Boolean = false,
    val name: String = "",
    val imageUrl: String = ""
)

/**
 * What a plugin wants shown while it signs in: a QR for the phone to scan
 * (matrix is rows of "0"/"1", encoded in Go) or a line of text.
 */
data class SignInPrompt(
    val pluginId: String = "",
    val text: String = "",
    val qr: String = "",
    val matrix: List<String> = emptyList()
) {
    val hasQR: Boolean get() = matrix.isNotEmpty()
}

/**
 * One category declared by a plugin's `navigation` array.
 *
 * Go emits `title` (with optional `name` kept for older manifests) and an
 * optional `icon` hint. [key] is derived, never stored, so the UI can keep a
 * stable selection across refreshes without duplicating comparison logic.
 */
data class NavItem(
    val builtin: String = "",
    val page: String = "",
    val name: String = "",
    val icon: String = ""
) {
    val key: String get() = listOf(builtin, page, name).joinToString("|")

    /** Builtin-only nav entries may omit `title`; fall back to a Chinese label. */
    val label: String get() = name.ifBlank {
        when (builtin) {
            "home" -> "首页"
            "liked" -> "我喜欢"
            "savedAlbums" -> "收藏专辑"
            "stations" -> "电台"
            "shows" -> "节目"
            else -> page.ifBlank { "分类" }
        }
    }
}

data class PlayerState(
    val playing: Boolean = false,
    val volume: Double = 1.0,
    val index: Int = 0,
    val length: Int = 0,
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    /** Owning plugin of the queue (music.Track.source is per track). */
    val pluginId: String = "",
    /** Non-empty while a live station feeds the queue; its rows are a moving window. */
    val stationId: String = ""
)

/** One timed line of lyrics. Times are milliseconds from the track's start. */
data class LyricLine(
    val start: Long,
    val end: Long,
    val text: String,
    val translation: String = ""
)

/** What a source answered for a track's lyrics. */
data class LyricsText(
    val lines: List<LyricLine> = emptyList(),
    val lang: String = "",
    /** False when the source has no lyrics export at all (a radio station). */
    val supported: Boolean = false,
    /** Why there are no lines; shown where the words would be. */
    val reason: String = ""
) {
    val hasLines: Boolean get() = lines.isNotEmpty()
}

data class AppSettings(
    val language: String = "zh-CN",
    val quality: String = "high",
    val volume: Double = 1.0,
    val appearance: String = "system",
    val normalize: Boolean = false,
    /** Show the lyrics pane in the now-playing screen. */
    val lyrics: Boolean = true
)

object Json {
    fun obj(s: String?): JSONObject? = if (s.isNullOrBlank() || s == "—" || s == "null") null else runCatching { JSONObject(s) }.getOrNull()

    fun track(o: JSONObject?): Track? {
        if (o == null) return null
        val artists = o.optJSONArray("artists")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val a = arr.optJSONObject(i) ?: return@mapNotNull null
                Artist(a.optString("id", ""), a.optString("name", ""))
            }
        } ?: emptyList()
        val album = o.optJSONObject("album")?.let {
            Album(it.optString("id", ""), it.optString("name", ""), it.optString("imageUrl", ""))
        }
        return Track(
            id = o.optString("id", ""),
            name = o.optString("name", ""),
            artists = artists,
            album = album,
            durationMs = o.optLong("duration", 0),
            imageUrl = o.optString("imageUrl", album?.imageUrl ?: ""),
            source = o.optString("source", "")
        )
    }

    fun browse(s: String?): Browse? {
        val o = obj(s) ?: return null
        val sections = o.optJSONArray("sections")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> section(arr.optJSONObject(i)) }
        } ?: emptyList()
        return Browse(o.optString("title", ""), o.optString("subtitle", ""), sections)
    }

    /**
     * The items of one bucket: a section, or one kind of search answer. Both
     * carry the same keys (tracks / albums / artists / playlists / shows /
     * stations / links), so a search result renders like a section does.
     */
    internal fun items(o: JSONObject?): List<BrowseItem> {
        if (o == null) return emptyList()
        val items = mutableListOf<BrowseItem>()
        o.optJSONArray("tracks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = track(arr.optJSONObject(i)) ?: continue
                items.add(BrowseItem(BrowseItem.Kind.TRACK, t.id, t.name, t.artistText, t.imageUrl, t))
            }
        }
        o.optJSONArray("albums")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                items.add(BrowseItem(BrowseItem.Kind.ALBUM, a.optString("id", ""), a.optString("name", ""),
                    imageUrl = a.optString("imageUrl", "")))
            }
        }
        o.optJSONArray("artists")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                items.add(BrowseItem(BrowseItem.Kind.ARTIST, a.optString("id", ""), a.optString("name", ""),
                    imageUrl = a.optString("imageUrl", "")))
            }
        }
        o.optJSONArray("playlists")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                items.add(BrowseItem(BrowseItem.Kind.PLAYLIST, p.optString("id", ""), p.optString("name", ""),
                    imageUrl = p.optString("imageUrl", "")))
            }
        }
        o.optJSONArray("shows")?.let { arr ->
            for (i in 0 until arr.length()) {
                val sh = arr.optJSONObject(i) ?: continue
                items.add(BrowseItem(BrowseItem.Kind.SHOW, sh.optString("id", ""), sh.optString("name", ""),
                    imageUrl = sh.optString("imageUrl", "")))
            }
        }
        o.optJSONArray("stations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val st = arr.optJSONObject(i) ?: continue
                items.add(BrowseItem(BrowseItem.Kind.STATION, st.optString("id", ""), st.optString("name", ""),
                    st.optString("description", ""), st.optString("imageUrl", "")))
            }
        }
        // Links are pages of the source's own ("rank", "categories", "search:kw").
        // Dropping them hides most of a plugin's navigation: ximalaya-web puts
        // its category browser and hot-search words in links, so without this
        // they simply are not there.
        o.optJSONArray("links")?.let { arr ->
            for (i in 0 until arr.length()) {
                val l = arr.optJSONObject(i) ?: continue
                val page = l.optString("page", "")
                items.add(BrowseItem(BrowseItem.Kind.LINK, page, l.optString("title", ""),
                    l.optString("subtitle", ""), l.optString("imageUrl", ""), null, page))
            }
        }
        return items
    }

    private fun section(o: JSONObject?): Section? {
        if (o == null) return null
        return Section(o.optString("title", ""), items(o), o.optString("morePage", ""))
    }

    /**
     * A search answer. Albums and shows are what a listener searches for as
     * often as songs: dropping them made 专辑 look like it does not exist —
     * there was nothing on screen to tap.
     */
    fun search(s: String?): SearchResult {
        val o = obj(s) ?: return SearchResult()
        val all = items(o)
        return SearchResult(
            tracks = all.mapNotNull { it.track },
            albums = all.filter { it.kind == BrowseItem.Kind.ALBUM },
            artists = all.filter { it.kind == BrowseItem.Kind.ARTIST },
            playlists = all.filter { it.kind == BrowseItem.Kind.PLAYLIST },
            shows = all.filter { it.kind == BrowseItem.Kind.SHOW },
            more = o.optBoolean("more", false)
        )
    }

    fun sources(s: String?): List<SourceInfo> {
        val arr = obj("{\"a\":$s}")?.optJSONArray("a") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val nav = o.optJSONArray("navigation")?.let { na ->
                (0 until na.length()).mapNotNull { j ->
                    val n = na.optJSONObject(j) ?: return@mapNotNull null
                    NavItem(
                        builtin = n.optString("builtin", ""),
                        page = n.optString("page", ""),
                        // music.NavItem serializes the label as "title"; "name"
                        // is only a fallback for old manifests.
                        name = n.optString("title", "").ifBlank { n.optString("name", "") },
                        icon = n.optString("icon", "")
                    )
                }
            } ?: emptyList()
            val settings = o.optJSONArray("settings")?.let { sa ->
                (0 until sa.length()).mapNotNull { j -> sa.optJSONObject(j)?.optString("key", "")?.takeIf { it.isNotBlank() } }
            } ?: emptyList()
            SourceInfo(
                id = o.optString("id", ""),
                name = o.optString("name", ""),
                version = o.optString("version", ""),
                playback = o.optString("playback", ""),
                iconUrl = o.optString("icon", "").ifBlank { o.optString("iconUrl", "") },
                settings = settings,
                navigation = nav,
                canSignIn = o.optBoolean("signIn", false),
                canAccount = o.optBoolean("account", false)
            )
        }
    }

    fun account(s: String?): Account {
        val o = obj(s) ?: return Account()
        return Account(
            signedIn = o.optBoolean("signedIn", false),
            name = o.optString("name", ""),
            imageUrl = o.optString("imageUrl", "")
        )
    }

    /** A plugin's sign-in prompt event, or null when it cleared the card. */
    fun signInPrompt(s: String?): SignInPrompt? {
        val o = obj(s) ?: return null
        val plugin = o.optString("plugin", "")
        if (plugin.isBlank()) return null
        val matrix = o.optJSONArray("matrix")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optString(i, "").takeIf { it.isNotBlank() } }
        } ?: emptyList()
        return SignInPrompt(plugin, o.optString("text", ""), o.optString("qr", ""), matrix)
    }

    fun pluginSettings(s: String?): List<PluginSetting> {
        val arr = obj("{\"a\":$s}")?.optJSONArray("a") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val key = o.optString("key", "")
            if (key.isBlank()) return@mapNotNull null
            val choices = o.optJSONArray("options")?.let { oa ->
                (0 until oa.length()).mapNotNull { j ->
                    oa.optJSONObject(j)?.let { c ->
                        SettingChoice(c.optString("value", ""), c.optString("label", ""))
                    }
                }
            } ?: emptyList()
            PluginSetting(
                key = key,
                label = o.optString("label", key),
                help = o.optString("help", ""),
                type = o.optString("type", "text"),
                value = o.optString("value", ""),
                choices = choices,
                link = o.optString("link", "")
            )
        }
    }

    fun pluginOrigins(s: String?): List<PluginOrigin> {
        val arr = obj("{\"a\":$s}")?.optJSONArray("a") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id", "")
            if (id.isBlank()) return@mapNotNull null
            PluginOrigin(
                id = id,
                repo = o.optString("repo", ""),
                version = o.optString("version", ""),
                dir = o.optString("dir", "")
            )
        }
    }

    fun pluginUpdates(s: String?): List<PluginUpdate> {
        val arr = obj("{\"a\":$s}")?.optJSONArray("a") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id", "")
            if (id.isBlank()) return@mapNotNull null
            PluginUpdate(
                id = id,
                repo = o.optString("repo", ""),
                current = o.optString("current", ""),
                latest = o.optString("latest", ""),
                available = o.optBoolean("available", false),
                error = o.optString("error", "")
            )
        }
    }

    fun queue(s: String?): PlayerState {
        val o = obj(s) ?: return PlayerState()
        val tracks = o.optJSONArray("tracks")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> track(arr.optJSONObject(i)) }
        } ?: emptyList()
        val index = o.optInt("index", 0)
        return PlayerState(
            playing = o.optBoolean("playing", false),
            index = index,
            length = tracks.size,
            currentTrack = tracks.getOrNull(index),
            queue = tracks,
            pluginId = o.optString("pluginId", ""),
            stationId = o.optString("stationId", "")
        )
    }

    /**
     * Go normalizes a plugin's LRC or timed lines into one array, so the UI
     * never parses LRC. A line may still arrive without an end (LRC has none);
     * it then lasts until the next line, which is what the highlight needs.
     */
    fun lyrics(s: String?): LyricsText {
        val o = obj(s) ?: return LyricsText(reason = "无法读取歌词")
        val raw = o.optJSONArray("lines")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val l = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = l.optString("text", "")
                if (text.isBlank()) return@mapNotNull null
                LyricLine(
                    start = l.optLong("start", 0),
                    end = l.optLong("end", 0),
                    text = text,
                    translation = l.optString("translation", "")
                )
            }
        } ?: emptyList()
        val lines = raw.sortedBy { it.start }.mapIndexed { i, line ->
            if (line.end > line.start) line else {
                val next = raw.getOrNull(i + 1)?.start ?: (line.start + 6_000)
                line.copy(end = maxOf(line.start + 500, minOf(next, line.start + 6_000)))
            }
        }
        return LyricsText(
            lines = lines,
            lang = o.optString("lang", ""),
            supported = o.optBoolean("supported", false),
            reason = o.optString("reason", "")
        )
    }
}
