package com.xingang.novelreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A book the user has opened, with where they left off. */
data class Book(
    val key: String,
    val title: String,
    val catalogUrl: String?,
    val lastUrl: String,
    val lastChapter: String,
    val updatedAt: Long,
)

/** One visited page. */
data class HistoryEntry(val url: String, val title: String, val visitedAt: Long)

/** Persists recent books, browsing history, per-page scroll positions and reader preferences. */
class Library(context: Context) {

    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val positions = context.getSharedPreferences("positions", Context.MODE_PRIVATE)
    private val historyPrefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)

    fun books(): List<Book> {
        val arr = JSONArray(prefs.getString("books", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Book(
                key = it.getString("key"),
                title = it.getString("title"),
                catalogUrl = it.optString("catalogUrl").takeIf { s -> s.isNotEmpty() },
                lastUrl = it.getString("lastUrl"),
                lastChapter = it.optString("lastChapter"),
                updatedAt = it.getLong("updatedAt"),
            )
        }.sortedByDescending { it.updatedAt }
    }

    fun save(book: Book) = write(books().filter { it.key != book.key } + book)

    fun remove(key: String) = write(books().filter { it.key != key })

    private fun write(list: List<Book>) {
        val arr = JSONArray()
        for (b in list.sortedByDescending { it.updatedAt }.take(100)) {
            arr.put(
                JSONObject()
                    .put("key", b.key).put("title", b.title).put("catalogUrl", b.catalogUrl ?: "")
                    .put("lastUrl", b.lastUrl).put("lastChapter", b.lastChapter).put("updatedAt", b.updatedAt)
            )
        }
        prefs.edit().putString("books", arr.toString()).apply()
    }

    fun find(key: String): Book? = books().firstOrNull { it.key == key }

    // ---- browsing history ------------------------------------------------

    /** Most recent first; one entry per URL. */
    fun history(): List<HistoryEntry> {
        val arr = JSONArray(historyPrefs.getString("entries", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            HistoryEntry(it.getString("url"), it.optString("title"), it.getLong("t"))
        }
    }

    /** Record a visit; a URL seen before moves to the top. Keeps [HISTORY_LIMIT] entries. */
    fun addHistory(url: String, title: String) {
        val list = listOf(HistoryEntry(url, title, System.currentTimeMillis())) +
            history().filter { it.url != url }
        writeHistory(list.take(HISTORY_LIMIT))
    }

    fun removeHistory(url: String) = writeHistory(history().filter { it.url != url })

    fun clearHistory() = writeHistory(emptyList())

    private fun writeHistory(list: List<HistoryEntry>) {
        val arr = JSONArray()
        for (e in list) arr.put(JSONObject().put("url", e.url).put("title", e.title).put("t", e.visitedAt))
        historyPrefs.edit().putString("entries", arr.toString()).apply()
    }

    /** Scroll position as a fraction of the content height. */
    fun position(url: String): Float = positions.getFloat(url, 0f)
    fun savePosition(url: String, fraction: Float) = positions.edit().putFloat(url, fraction).apply()

    companion object {
        const val HISTORY_LIMIT = 100
    }

    var fontSp: Float
        get() = prefs.getFloat("fontSp", 18f)
        set(v) = prefs.edit().putFloat("fontSp", v).apply()
}
