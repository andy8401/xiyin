package com.andy8401.mygo.car.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import facade.HostBridge

/**
 * Platform capabilities requested by moejs plugins.
 *
 * The Go method is named WebSignInJSON for historical reasons, but its return
 * value is a **Cookie header string** (see facade/plugins.go). Stage D returned
 * an empty string (honest "not signed in"); stage E shows a private WebView and
 * waits for the cookies the plugin asked for via `until`.
 */
class BridgeHost(private val context: Context) : HostBridge {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun openURL(url: String?) {
        if (url.isNullOrBlank()) return
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure { Log.e(TAG, "openURL failed: $url", it) }
    }

    override fun toast(msg: String?) {
        if (msg.isNullOrBlank()) return
        mainHandler.post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun webSignInJSON(reqJSON: String?): String {
        if (reqJSON.isNullOrBlank()) return ""
        return try {
            SignInCoordinator.showAndWait(context, reqJSON) ?: ""
        } catch (t: Throwable) {
            Log.e(TAG, "webSignIn failed", t)
            ""
        }
    }

    companion object {
        private const val TAG = "GoHostBridge"
    }
}
