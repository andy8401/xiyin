package com.andy8401.mygo.car.bridge

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridges the synchronous Go interface call (webSignInJSON) to the asynchronous
 * WebView activity. Go invokes us on its own goroutine, so blocking here is safe
 * as long as we never block the main thread: the Activity is posted to main and
 * the caller waits on a latch with a hard timeout.
 */
object SignInCoordinator {

    private val latchRef = AtomicReference<CountDownLatch?>(null)
    private val resultRef = AtomicReference<String?>(null)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Show the sign-in page and wait for cookies. Returns a Cookie header
     * string, or null if the user cancelled / timed out. Must be called off
     * the main thread.
     */
    fun showAndWait(context: Context, requestJSON: String, timeoutMs: Long = 300_000): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException("webSignIn must not block the main thread")
        }

        val latch = CountDownLatch(1)
        latchRef.set(latch)
        resultRef.set(null)

        // A fresh cookie jar for every sign-in so sessions do not leak between
        // plugins or into the browser. Restore the previous state afterwards.
        val manager = CookieManager.getInstance()
        val previousAccept = manager.acceptCookie()
        manager.setAcceptCookie(true)

        mainHandler.post {
            val intent = Intent(context, SignInActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SignInActivity.EXTRA_REQUEST, requestJSON)
            runCatching { context.startActivity(intent) }
                .onFailure {
                    resultRef.set(null)
                    latch.countDown()
                }
        }

        val completed = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        manager.setAcceptCookie(previousAccept)
        latchRef.set(null)
        return if (completed) resultRef.get() else null
    }

    /** Called by SignInActivity with the Cookie header, or null on cancel. */
    fun complete(cookieHeader: String?) {
        resultRef.set(cookieHeader)
        latchRef.get()?.countDown()
    }
}
