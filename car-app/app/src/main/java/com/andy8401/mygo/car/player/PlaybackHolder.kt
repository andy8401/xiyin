@file:OptIn(UnstableApi::class)

package com.andy8401.mygo.car.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * One ExoPlayer instance shared by the Go bridge (MediaOutput) and the
 * MediaSessionService. Keeping a single owner avoids two players fighting over
 * the audio focus in a car head unit.
 *
 * Media3 has no per-MediaItem header builder in 1.4.1, so request headers are
 * injected at the DataSource layer. Each Go load() replaces the headers before
 * the new item is prepared.
 */
object PlaybackHolder {

    private val requestHeaders = AtomicReference<Map<String, String>>(emptyMap())

    /** Last known playback position, used to resume an on-demand track after a
     *  network drop. Updated on error/pause; cleared when a new item is loaded. */
    private val lastPositionMs = AtomicLong(0)

    /** Max load retries before ExoPlayer surfaces the error to onPlayerError.
     *  Default policy gives up fast; weak car networks need a longer window. */
    private const val MAX_LOAD_RETRIES = 8

    @Volatile
    private var instance: ExoPlayer? = null

    /** Exponential backoff for load errors, capped at 10s. Combined with
     *  MAX_LOAD_RETRIES this keeps ExoPlayer retrying through short car-network
     *  dropouts (tunnels, Flyme Link handover) without surfacing onPlayerError. */
    private class PatientErrorPolicy : DefaultLoadErrorHandlingPolicy(MAX_LOAD_RETRIES) {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            // exception is always IOException here. Exponential backoff with a
            // 10s cap keeps retrying through short dropouts (tunnels, Flyme
            // Link handover) before the error ever reaches onPlayerError.
            val delay = 500L shl minOf(info.errorCount - 1, 4) // 500ms,1s,2s,4s,8s
            return minOf(delay, 10_000L)
        }
    }

    @OptIn(UnstableApi::class)
    fun get(context: Context): ExoPlayer {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: buildPlayer(context).also { instance = it }
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildPlayer(context: Context): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        // A plugin may resolve a track to file:// (demo ships tone.mp3 in its
        // own directory, and the local library scans the head unit's storage),
        // so the HTTP source cannot be the outer layer: DefaultDataSource picks
        // FileDataSource/ContentDataSource for those schemes and delegates
        // http(s) to the factory below.
        val baseFactory = DefaultDataSource.Factory(context.applicationContext, httpFactory)
        val dataSourceFactory = ResolvingDataSource.Factory(baseFactory) { dataSpec ->
            dataSpec.withAdditionalHeaders(requestHeaders.get())
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(context.applicationContext)
            .setDataSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(PatientErrorPolicy())
        // Media audio identity + focus handling. handleAudioFocus=true makes
        // ExoPlayer request AUDIOFOCUS_GAIN before playing (interrupting other
        // media), pause on LOSS/LOSS_TRANSIENT, resume on GAIN, and abandon the
        // focus when released. USAGE_MEDIA + CONTENT_TYPE_MUSIC tell the system
        // (and car head units) that this stream is media playback.
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        return ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    /** Snapshot the current position so an on-demand track can resume after a
     *  network drop. Called from the player listener before the error state. */
    fun notePosition(ms: Long) {
        if (ms >= 0) lastPositionMs.set(ms)
    }

    fun noteCurrentPosition() {
        instance?.let { notePosition(it.currentPosition) }
    }

    /** Set when load retries are exhausted, i.e. the drop outlived ExoPlayer's
     *  own retry window. The connectivity hook acts only on this flag. */
    private val needReconnect = java.util.concurrent.atomic.AtomicBoolean(false)

    fun markNeedReconnect() { needReconnect.set(true) }

    /** Returns the resume position and clears the flag, or null when no
     *  reconnect is pending. One-shot: safe even if connectivity fires twice. */
    fun pendingResumeMs(): Long? =
        if (needReconnect.compareAndSet(true, false)) lastPositionMs.get() else null

    fun clearResume() { needReconnect.set(false); lastPositionMs.set(0) }

    /** Called by MediaOutputImpl.load with music.Stream.Headers from Go. */
    fun setHeaders(headers: Map<String, String>) {
        requestHeaders.set(headers)
    }

    fun current(): ExoPlayer? = instance
}
