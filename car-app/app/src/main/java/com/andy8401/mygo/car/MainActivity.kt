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
import androidx.lifecycle.ViewModelProvider
import com.andy8401.mygo.car.bridge.InstallRequest
import com.andy8401.mygo.car.ui.CarScreen
import com.andy8401.mygo.car.ui.CarTheme
import com.andy8401.mygo.car.ui.MainViewModel

class MainActivity : ComponentActivity() {

    /** Same instance Compose obtains via viewModel(): both use the activity's
     *  ViewModelStore, so onStop can flush what the UI has been holding. */
    private lateinit var vm: MainViewModel

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
        vm = ViewModelProvider(this)[MainViewModel::class.java]

        setContent {
            CarTheme {
                vm.init(this@MainActivity)
                CarScreen(vm)
            }
        }
    }

    override fun onStop() {
        vm.persistNow()
        super.onStop()
    }

    /**
     * Edge-to-edge, with BOTH system bars visible and transparent.
     *
     * The car UI draws its own dark background all the way to the edges, so the
     * bars show the app's colour instead of a system grey. Light icons are
     * forced because that background is always dark.
     *
     * Lynk & Co 900 reserves the bottom strip of the panel for the vehicle's own
     * navigation / climate bar. Hiding the Android navigation bar let our
     * content cover that strip, so the car bar never appeared. We now show the
     * system bars and inset the Composable content with statusBarsPadding() +
     * navigationBarsPadding() in CarScreen, which keeps the car's bar visible.
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
            show(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
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
