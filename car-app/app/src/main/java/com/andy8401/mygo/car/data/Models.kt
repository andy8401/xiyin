package com.andy8401.mygo.car.data

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

data class NavItem(val builtin: String = "", val page: String = "", val name: String = "")

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
                    NavItem(n.optString("builtin", ""), n.optString("page", ""), n.optString("name", ""))
                }
            } ?: emptyList()
            val settings = o.optJSONArray("settings")?.let { sa ->
                (0 until sa.length()).mapNotNull { j -> sa.optJSONObject(j)?.optString("key", "")?.takeIf { it.isNotBlank() } }
            } ?: emptyList()
            SourceInfo(
                o.optString("id", ""), o.optString("name", ""), o.optString("version", ""),
                o.optString("playback", ""), settings, nav,
                o.optBoolean("signIn", false), o.optBoolean("account", false)
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
