package com.andy8401.mygo.car.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import facade.Facade
import facade.MediaOutput
import org.json.JSONObject

/**
 * Kotlin side of the Go MediaOutput interface (stage D, audio plan B).
 *
 * Go resolves a track only as far as music.Stream{URL, Headers} and hands it
 * here; all downloading, demuxing and decoding happens inside Media3. In
 * particular cnradio's HLS stream is consumed natively by ExoPlayer's HLS
 * datasource, so the desktop Python/ffmpeg `host.spawn` bridge is not needed.
 */
class MediaOutputImpl(context: Context) : MediaOutput {

    private val player: ExoPlayer = PlaybackHolder.get(context)
    private val main = Handler(Looper.getMainLooper())

    init {
        // A single listener is enough: Go installs exactly one MediaOutput.
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    // Let Go advance the queue (player.go Ended()).
                    runCatching { Facade.ended() }
                        .onFailure { Log.e(TAG, "Facade.ended failed", it) }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "ExoPlayer error", error)
            }
        })
    }

    override fun load(url: String?, headersJSON: String?) {
        require(!url.isNullOrBlank()) { "empty stream URL from Go" }
        // Headers are injected by PlaybackHolder's ResolvingDataSource, which
        // is the only Media3 1.4.x mechanism that supports per-track headers.
        // Setting them must happen before the item is prepared, so they are
        // written here rather than inside the posted block; the holder is an
        // AtomicReference and therefore safe off the player thread.
        PlaybackHolder.setHeaders(parseHeaders(headersJSON))
        val builder = MediaItem.Builder().setUri(url)
        // cnradio's live streams arrive as HLS with a signed query string
        // (?k=...&t=...). DefaultMediaSourceFactory infers the container from
        // the last path segment, which then ends in the query, so the type must
        // be stated outright or the playlist is parsed as progressive audio.
        hlsMimeType(url)?.let { builder.setMimeType(it) }
        // Log the shape only: cnradio stream URLs carry a short-lived key in
        // the query string, which should not end up in logcat.
        Log.i(TAG, "load " + url.substringBefore('?') + " mime=" + (hlsMimeType(url) ?: "auto"))
        val item = builder.build()
        onPlayerThread {
            player.setMediaItem(item)
            player.prepare()
        }
    }

    override fun play() {
        onPlayerThread { player.play() }
    }

    override fun pause() {
        onPlayerThread { player.pause() }
    }

    override fun seekMs(ms: Long) {
        onPlayerThread { player.seekTo(ms) }
    }

    override fun setVolume(v: Double) {
        val volume = v.coerceIn(0.0, 1.0).toFloat()
        onPlayerThread { player.volume = volume }
    }

    /**
     * ExoPlayer only accepts commands on the thread that built it, which is the
     * main thread (GoBridge.init runs there). Go calls MediaOutput from whatever
     * goroutine is playing, so every command hops over. A single Handler keeps
     * load→play ordering intact.
     */
    private fun onPlayerThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            main.post(block)
        }
    }

    companion object {
        private const val TAG = "GoMediaOutput"

        /** MimeTypes.APPLICATION_M3U8 when the URL points at an HLS playlist. */
        fun hlsMimeType(url: String): String? {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return if (path.endsWith(".m3u8")) MimeTypes.APPLICATION_M3U8 else null
        }

        /** Go marshals music.Stream.Headers as a JSON object (may be "null"). */
        fun parseHeaders(headersJSON: String?): Map<String, String> {
            if (headersJSON.isNullOrBlank() || headersJSON == "null") return emptyMap()
            val obj = JSONObject(headersJSON)
            val result = HashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = obj.optString(key, "")
            }
            return result
        }
    }
}
