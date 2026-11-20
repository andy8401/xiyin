package com.andy8401.mygo.car.bridge

import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.andy8401.mygo.car.player.MediaOutputImpl
import com.andy8401.mygo.car.player.PlaybackService
import facade.Facade
import java.io.File

/**
 * Boots the Go facade with Android platform implementations.
 *
 * The AAR's Init() calls plugin.Discover(<dataDir>/plugins), so bundled plugins
 * must be unpacked from assets **before** Init() runs. No additional LoadPlugin
 * call is needed for them.
 */
object GoBridge {

    @Volatile
    private var initialized = false
    private var pluginCount = 0

    fun init(context: Context): Int {
        if (initialized) return pluginCount
        synchronized(this) {
            if (initialized) return pluginCount
            val app = context.applicationContext
            val dataDir = app.filesDir.absolutePath
            val pluginDir = File(dataDir, "plugins")

            // Offline fallback only: seed the bundled plugins on a truly fresh
            // install (empty plugins dir). The release build installs both
            // defaults from their GitHub repos in MainViewModel, which records
            // .origin.json so 检查更新 works. Rewriting assets on every boot
            // would clobber those repo installs and strip their origins.
            // The local source is synthesized by the ViewModel, not a plugin.
            val hasPlugins = pluginDir.listFiles()?.any { it.isDirectory } == true
            if (!hasPlugins) installBundledPlugins(app.assets, pluginDir)

            Facade.setHostBridge(BridgeHost(app))
            Facade.setMediaOutput(MediaOutputImpl(app))
            Facade.setListener(BridgeListener)

            pluginCount = Facade.init(dataDir).toInt()
            initialized = true
            Log.i(TAG, "Go facade ready: $pluginCount plugins from ${pluginDir.absolutePath}")
        }
        return pluginCount
    }

    fun startPlaybackService(context: Context) {
        ContextCompat.startForegroundService(
            context.applicationContext,
            Intent(context.applicationContext, PlaybackService::class.java)
        )
    }

    private fun installBundledPlugins(assets: AssetManager, target: File) {
        val root = "plugins"
        if (assets.list(root).isNullOrEmpty()) {
            Log.w(TAG, "no bundled plugins in assets/$root")
            return
        }
        copyAssetTree(assets, root, target)
    }

    private fun copyAssetTree(assets: AssetManager, source: String, target: File) {
        val children = assets.list(source) ?: return
        if (!target.exists() && !target.mkdirs()) {
            Log.e(TAG, "cannot create ${target.absolutePath}")
            return
        }
        for (child in children) {
            val sourcePath = "$source/$child"
            val destination = File(target, child)
            val nested = assets.list(sourcePath)
            if (!nested.isNullOrEmpty()) {
                copyAssetTree(assets, sourcePath, destination)
            } else {
                assets.open(sourcePath).use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private const val TAG = "GoBridge"
}
