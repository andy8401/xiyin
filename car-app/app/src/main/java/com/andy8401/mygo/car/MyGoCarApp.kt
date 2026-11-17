package com.andy8401.mygo.car

import android.app.Application
import com.andy8401.mygo.car.player.PlaybackHolder

class MyGoCarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // ExoPlayer must be constructed on the main thread; Application.onCreate is.
        PlaybackHolder.get(this)
    }
}
