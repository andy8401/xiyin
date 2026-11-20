package com.andy8401.mygo.car.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * App self-update helper.
 *
 * The car build is sideloaded, so there is no store API. This checks the
 * fork's GitHub *release* (not its source archive), compares the tag with the
 * app's own versionName, downloads the arm64 APK asset and hands it to the
 * system installer. No feature is gated and nothing is installed without the
 * user's explicit "download and install" tap.
 */
object AppUpdater {

    data class Release(
        /** Tag with a leading v/V removed, e.g. 0.1.3. */
        val version: String,
        val notes: String,
        /** Direct APK asset URL; blank when the release has no APK attached. */
        val apkUrl: String,
        val apkName: String,
        val pageUrl: String
    )

    /** 404 means the repository has not published a release yet — not an error. */
    suspend fun check(repo: String): Result<Release?> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("https://api.github.com/repos/$repo/releases/latest")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "mygocar-update-check")
            try {
                when (connection.responseCode) {
                    404 -> null
                    200 -> {
                        val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val release = JSONObject(body)
                        val version = release.optString("tag_name", "").trim()
                            .removePrefix("v").removePrefix("V")
                        val assets = release.optJSONArray("assets")
                        var apkUrl = ""
                        var apkName = ""
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val asset = assets.optJSONObject(i) ?: continue
                                val name = asset.optString("name", "")
                                if (!name.endsWith(".apk", ignoreCase = true)) continue
                                val url = asset.optString("browser_download_url", "")
                                if (url.isBlank()) continue
                                // Prefer the head-unit build; never the emulator
                                // build, which cannot run on arm64 hardware.
                                val preferred = name.contains("arm64", ignoreCase = true) &&
                                    !name.contains("emulator", ignoreCase = true)
                                if (apkUrl.isBlank() ||
                                    (preferred && !apkName.contains("arm64", ignoreCase = true))
                                ) {
                                    apkUrl = url
                                    apkName = name
                                }
                            }
                        }
                        if (version.isBlank()) null else Release(
                            version = version,
                            notes = release.optString("body", ""),
                            apkUrl = apkUrl,
                            apkName = apkName,
                            pageUrl = release.optString("html_url", "")
                        )
                    }
                    else -> throw IllegalStateException("GitHub 返回 ${connection.responseCode}")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Compare dotted numeric versions; non-numeric parts count as zero. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.trim().removePrefix("v").removePrefix("V").split('.')
        val b = current.trim().removePrefix("v").removePrefix("V").split('.')
        for (i in 0 until maxOf(a.size, b.size)) {
            val av = a.getOrNull(i)?.toIntOrNull() ?: 0
            val bv = b.getOrNull(i)?.toIntOrNull() ?: 0
            if (av != bv) return av > bv
        }
        return false
    }

    suspend fun download(context: Context, url: String): File = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "更新地址必须使用 HTTPS" }
        val dir = File(context.applicationContext.filesDir, "updates")
        if (!dir.exists() && !dir.mkdirs()) error("无法创建更新目录")
        val file = File(dir, "mygocar-update.apk")
        if (file.exists() && !file.delete()) error("无法覆盖旧的更新包")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "mygocar-update-check")
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("下载失败：HTTP $code")
            connection.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        if (file.length() <= 0L) error("下载的文件为空")
        file
    }

    /**
     * True when the install intent was handed to the system. False means the
     * user must first allow unknown sources; the settings screen is opened.
     */
    fun install(context: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return false
        }
        val uri = FileProvider.getUriForFile(
            context.applicationContext,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }
}
