package com.andy8401.mygo.car.bridge

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import org.json.JSONObject

/**
 * A private WebView for moejs host.webSignIn. Each sign-in gets its own cookie
 * jar (spawn a fresh in-memory WebView profile by clearing cookies for the
 * request scope) so the session cannot leak into the listener's browser.
 *
 * The Go side blocks in BridgeHost.webSignInJSON waiting for either all `until`
 * cookies to appear or the user to dismiss the page.
 */
class SignInActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var cookiesOf: String = ""
    private var until: List<String> = emptyList()
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val reqRaw = intent.getStringExtra(EXTRA_REQUEST) ?: ""
        val req = runCatching { JSONObject(reqRaw) }.getOrElse {
            Toast.makeText(this, "Invalid sign-in request", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val url = req.optString("url", "")
        cookiesOf = req.optString("cookiesOf", url)
        // Go marshals WebSignIn.Until as a JSON array of cookie names.
        until = req.optJSONArray("until")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { s -> s.isNotBlank() } }
        } ?: emptyList()
        val userAgent = req.optString("userAgent", "")

        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            if (userAgent.isNotBlank()) {
                settings.userAgentString = userAgent
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    checkCookies()
                }
            }
            webChromeClient = WebChromeClient()
        }
        setContentView(webView)

        if (url.isBlank()) {
            Toast.makeText(this, "Empty sign-in URL", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        webView.loadUrl(url)
    }

    private fun checkCookies() {
        if (finished) return
        val cookieHeader = CookieManager.getInstance().getCookie(cookiesOf) ?: ""
        val present = until.all { name ->
            cookieHeader.split(';').any { it.trim().startsWith("$name=") }
        }
        if (present) {
            finished = true
            SignInCoordinator.complete(cookieHeader)
            finish()
        }
    }

    override fun onBackPressed() {
        if (!finished) {
            finished = true
            SignInCoordinator.complete(null) // cancelled
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (!finished) {
            finished = true
            SignInCoordinator.complete(null)
        }
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REQUEST = "signInRequest"
    }
}
