package com.andy8401.mygo.car.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Host-side favourites. Everything lives in the app's own SharedPreferences:
 * no plugin call, no account, no network — the collection stays on this head
 * unit even when the source plugin is uninstalled.
 *
 * The whole list is stored as one JSON array so a write is atomic; a crash
 * while writing cannot leave a half-saved favourite. Reads are served from an
 * in-memory snapshot, which keeps the UI's state flow synchronous.
 */
class FavoriteStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var rows: MutableList<FavoriteItem> = read()

    fun all(): List<FavoriteItem> = synchronized(this) { rows.toList() }

    fun bySource(sourceId: String): List<FavoriteItem> = synchronized(this) {
        rows.filter { it.sourceId == sourceId }.toList()
    }

    /** Adds the row when absent, removes it when present. Returns true if added. */
    fun toggle(item: FavoriteItem): Boolean = synchronized(this) {
        val existed = rows.any { it.key == item.key }
        if (existed) rows.removeAll { it.key == item.key }
        else rows.add(0, item)
        write()
        !existed
    }

    /** Channel rows a folder contributed. Reads snapshot, no plugin contact. */
    fun byFolder(sourceId: String, folder: String): List<FavoriteItem> = synchronized(this) {
        rows.filter { it.sourceId == sourceId && it.folder == folder }.toList()
    }

    /**
     * Add every row that is not already saved. Used when favouriting a folder:
     * its channels must not silently un-favourite a station the driver already
     * saved from somewhere else. Returns how many were actually new.
     */
    fun addIfAbsent(items: List<FavoriteItem>): Int = synchronized(this) {
        val existing = rows.map { it.key }.toSet()
        val fresh = items.filter { it.key !in existing }
        if (fresh.isNotEmpty()) {
            rows.addAll(0, fresh)
            write()
        }
        fresh.size
    }

    /** Remove all rows a folder contributed. Returns how many were removed. */
    fun removeByFolder(sourceId: String, folder: String): Int = synchronized(this) {
        val before = rows.size
        rows.removeAll { it.sourceId == sourceId && it.folder == folder }
        val removed = before - rows.size
        if (removed > 0) write()
        removed
    }

    /** Drop the old, useless "saved folder" row once its channels are saved. */
    fun removeLinkRow(sourceId: String, itemId: String): Unit = synchronized(this) {
        if (rows.removeAll {
                it.sourceId == sourceId && it.itemId == itemId &&
                    it.kind == com.andy8401.mygo.car.data.BrowseItem.Kind.LINK.name
            }) write()
    }

    /** Called when a plugin is uninstalled so its favourites cannot linger. */
    fun removeSource(sourceId: String): Unit = synchronized(this) {
        if (rows.removeAll { it.sourceId == sourceId }) write()
    }

    private fun read(): MutableList<FavoriteItem> {
        val raw = runCatching { prefs.getString(KEY_ROWS, "") }.getOrNull().orEmpty()
        if (raw.isBlank()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { FavoriteItem.fromJson(arr.optJSONObject(it)) }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun write() {
        val arr = JSONArray()
        rows.forEach { arr.put(it.toJson()) }
        runCatching { prefs.edit().putString(KEY_ROWS, arr.toString()).apply() }
    }

    companion object {
        private const val PREFS_NAME = "mygocar_favorites"
        private const val KEY_ROWS = "rows"
    }
}
