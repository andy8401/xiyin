package com.andy8401.mygo.car.bridge

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Kotlin-side helper for stage E plugin installation.
 *
 * SAF hands us a content:// Uri that the Go process cannot open directly, so
 * zip files are copied into app-private storage first. Repo installs run Go's
 * network fetch off the main thread.
 */
object PluginInstaller {

    sealed class Result {
        data class Ok(val id: String) : Result()
        data class Failed(val message: String) : Result()
    }

    /** Copy a SAF-provided zip to private storage, then hand it to Go. */
    suspend fun installFromSaf(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        runCatching {
            val name = queryDisplayName(context, uri) ?: "plugin.zip"
            val safeName = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
            val file = File(context.filesDir, "incoming_${System.currentTimeMillis()}_$safeName")
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "cannot open $uri" }
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val id = facade.Facade.installPluginFromPath(file.absolutePath)
            file.delete()
            Result.Ok(id)
        }.getOrElse { Result.Failed(it.message ?: it.toString()) }
    }

    /** Install or update from a GitHub owner/repo (network call inside Go). */
    suspend fun installFromRepo(repo: String): Result = withContext(Dispatchers.IO) {
        runCatching { Result.Ok(facade.Facade.installPluginFromRepo(repo)) }
            .getOrElse { Result.Failed(it.message ?: it.toString()) }
    }

    suspend fun uninstall(id: String): Result = withContext(Dispatchers.IO) {
        runCatching {
            facade.Facade.uninstallPlugin(id)
            Result.Ok(id)
        }.getOrElse { Result.Failed(it.message ?: it.toString()) }
    }

    suspend fun origins(): String = withContext(Dispatchers.IO) {
        facade.Facade.pluginOriginsJSON()
    }

    suspend fun updates(): String = withContext(Dispatchers.IO) {
        facade.Facade.checkPluginUpdatesJSON()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment
    }
}
