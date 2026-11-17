package com.andy8401.mygo.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.andy8401.mygo.car.bridge.InstallRequest
import com.andy8401.mygo.car.ui.CarScreen
import com.andy8401.mygo.car.ui.CarTheme
import com.andy8401.mygo.car.ui.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUpSystemBars()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        handleInstallIntent(intent)

        setContent {
            CarTheme {
                val vm: MainViewModel = viewModel()
                vm.init(this@MainActivity)
                CarScreen(vm)
            }
        }
    }

    /**
     * Edge-to-edge with a *visible* but transparent status bar.
     *
     * The car UI draws its own dark background all the way to the top, so the
     * bar shows the app's colour instead of a system grey. Light icons are
     * forced because that background is always dark. Content is inset below the
     * bar in CarScreen (statusBarsPadding), so the clock never sits on the
     * MyGo title.
     *
     * The navigation bar stays hidden: a head unit has no use for
     * back/home/recents, and it is swipe-revealable if a passenger needs it.
     */
    private fun setUpSystemBars() {
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(transparent),
            navigationBarStyle = SystemBarStyle.dark(transparent)
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            show(WindowInsetsCompat.Type.statusBars())
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInstallIntent(intent)
    }

    /** mygocar://install?repo=owner/repo — parked for the UI to consume. */
    private fun handleInstallIntent(intent: Intent) {
        val uri: Uri = intent.data ?: return
        if (!"mygocar".equals(uri.scheme, ignoreCase = true)) return
        val repo = uri.getQueryParameter("repo")
        if (repo.isNullOrBlank()) {
            InstallRequest.report("安装链接缺少 repo 参数")
            Log.w(TAG, "install link had no repo: $uri")
            return
        }
        InstallRequest.submitRepo(repo)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
