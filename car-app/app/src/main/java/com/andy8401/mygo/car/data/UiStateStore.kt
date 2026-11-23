package com.andy8401.mygo.car.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Where the driver was in the UI before leaving the app. Deliberately small:
 * only the fields that make a boot feel continuous. Search text, the detail
 * back-stack and transient messages are moments, not preferences, so they are
 * intentionally not restored.
 *
 * Written with commit() rather than apply(): onStop can be followed almost
 * immediately by the process being killed, and apply() would lose that write.
 */
class UiStateStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cached: Snapshot? = read()

    fun load(): Snapshot? = synchronized(this) { cached }

    fun save(s: Snapshot) = synchronized(this) {
        cached = s
        val json = JSONObject()
            .put("sourceId", s.sourceId)
            .put("page", s.page)
            .put("navKey", s.navKey)
            .put("playerOpen", s.playerOpen)
            .put("playerPane", s.playerPane)
            .put("maximized", s.maximized)
        runCatching { prefs.edit().putString(KEY_SNAPSHOT, json.toString()).commit() }
    }

    private fun read(): Snapshot? {
        val raw = runCatching { prefs.getString(KEY_SNAPSHOT, "") }.getOrNull().orEmpty()
        if (raw.isBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            Snapshot(
                sourceId = o.optString("sourceId", ""),
                page = o.optString("page", ""),
                navKey = o.optString("navKey", ""),
                playerOpen = o.optBoolean("playerOpen", false),
                playerPane = o.optString("playerPane", "LYRICS"),
                maximized = o.optBoolean("maximized", false)
            )
        }.getOrNull()
    }

    data class Snapshot(
        val sourceId: String = "",
        val page: String = "",
        val navKey: String = "",
        val playerOpen: Boolean = false,
        val playerPane: String = "LYRICS",
        val maximized: Boolean = false
    )

    companion object {
        private const val PREFS_NAME = "mygocar_ui_state"
        private const val KEY_SNAPSHOT = "snapshot"
    }
}
