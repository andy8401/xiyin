package com.andy8401.mygo.car.bridge

import android.util.Log
import facade.Listener
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject

/** Go -> Kotlin event channel. State JSON stays opaque; the UI parses what it needs. */
object BridgeListener : Listener {

    private val _events = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 32)
    val events: SharedFlow<String> = _events.asSharedFlow()

    override fun onChangedJSON(stateJSON: String?) {
        _events.tryEmit(stateJSON ?: "{}")
    }

    override fun onError(source: String?, msg: String?) {
        Log.e(TAG, "[$source] $msg")
        val payload = JSONObject()
            .put("event", "error")
            .put("source", source ?: "")
            .put("message", msg ?: "")
        _events.tryEmit(payload.toString())
    }

    private const val TAG = "GoListener"
}
