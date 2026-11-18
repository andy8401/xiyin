package com.andy8401.mygo.car.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.andy8401.mygo.car.MainActivity
import com.andy8401.mygo.car.bridge.GoBridge
import facade.Facade

/**
 * Exposes the single ExoPlayer instance to car steering-wheel controls, Bluetooth
 * head units and the system media notification.
 *
 * Stage E: transport commands are forwarded to the Go facade first, so the Go
 * queue / playing state stays the single source of truth. MediaOutputImpl then
 * drives ExoPlayer from the Go side; we do not call player.play()/next() here.
 */
class PlaybackService : MediaSessionService() {

    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        // Make sure the Go bridge (and therefore MediaOutput) is installed even
        // if a controller connects before the user touches the smoke UI.
        GoBridge.init(this)
        enterForeground()
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = MediaSession.Builder(this, PlaybackHolder.get(this))
            .setId("mygo_car")
            .setSessionActivity(contentIntent)
            .setCallback(GoTransportCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (::mediaSession.isInitialized) mediaSession else null

    override fun onDestroy() {
        if (::mediaSession.isInitialized) mediaSession.release()
        super.onDestroy()
    }

    /**
     * Intercepts the transport keys a car head unit sends. Returning
     * RESULT_SUCCESS stops Media3 from applying the command to ExoPlayer as
     * well; the Go facade's MediaOutput call is the only thing that moves it.
     */
    private class GoTransportCallback : MediaSession.Callback {
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int
        ): Int {
            return try {
                when (playerCommand) {
                    Player.COMMAND_PLAY_PAUSE -> Facade.togglePlay()
                    Player.COMMAND_SEEK_TO_NEXT -> Facade.next()
                    Player.COMMAND_SEEK_TO_PREVIOUS -> Facade.prev()
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> {
                        // A head unit's "seek" with no target is not meaningful
                        // for our queue; explicit seeks come from the UI.
                        Unit
                    }
                    else -> return SessionError.ERROR_NOT_SUPPORTED
                }
                SessionResult.RESULT_SUCCESS
            } catch (t: Throwable) {
                Log.e(TAG, "Go transport command $playerCommand failed", t)
                SessionError.ERROR_UNKNOWN
            }
        }

        companion object {
            private const val TAG = "GoTransport"
        }
    }

    private fun enterForeground() {
        val channelId = "mygo_playback"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(com.andy8401.mygo.car.R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(com.andy8401.mygo.car.R.string.app_name))
            .setContentText("Ready")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val NOTIFICATION_ID = 1001
    }
}
