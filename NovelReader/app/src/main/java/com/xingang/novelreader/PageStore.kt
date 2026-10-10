package com.xingang.novelreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Disk cache of parsed pages, so reopening the app (or a chapter read earlier)
 * needs no network. One JSON file per page URL under the app cache dir; the
 * mapping from "page|action" to the page an action produced is kept alongside
 * so JavaScript-only navigation is served from cache as well.
 */
class PageStore(context: Context) {

    private val dir = File(context.cacheDir, "pages_v2").apply { mkdirs() }
    private val actions = context.getSharedPreferences("action_keys", Context.MODE_PRIVATE)

    fun load(url: String): Page? {
        val f = file(url)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            f.setLastModified(System.currentTimeMillis())
            when (o.getString("type")) {
                "catalog" -> Page.Catalog(
                    url = o.getString("url"),
                    title = o.getString("title"),
                    chapters = links(o.getJSONArray("chapters")),
                )
                else -> Page.Chapter(
                    url = o.getString("url"),
                    title = o.getString("title"),
                    bookTitle = o.optString("bookTitle").takeIf { it.isNotEmpty() },
                    paragraphs = strings(o.getJSONArray("paragraphs")),
                    prev = o.optJSONObject("prev")?.let(::link),
                    next = o.optJSONObject("next")?.let(::link),
                    catalog = o.optJSONObject("catalog")?.let(::link),
                )
            }
        } catch (e: Exception) {
            f.delete()
            null
        }
    }

    fun save(key: String, page: Page) {
        val o = JSONObject().put("url", page.url).put("title", page.title)
        when (page) {
            is Page.Catalog -> o.put("type", "catalog").put("chapters", JSONArray(page.chapters.map(::json)))
            is Page.Chapter -> o.put("type", "chapter")
                .put("bookTitle", page.bookTitle ?: "")
                .put("paragraphs", JSONArray(page.paragraphs))
                .put("prev", page.prev?.let(::json))
                .put("next", page.next?.let(::json))
                .put("catalog", page.catalog?.let(::json))
        }
        try {
            file(key).writeText(o.toString())
        } catch (e: Exception) {
            // Cache only; ignore a full or read-only disk.
        }
        prune()
    }

    fun remove(url: String) {
        file(url).delete()
    }

    /** Cache key produced earlier by clicking [actionId] ("<page url>|<action spec>"). */
    fun actionKey(actionId: String): String? = actions.getString(hash(actionId), null)

    fun saveActionKey(actionId: String, key: String) = actions.edit().putString(hash(actionId), key).apply()

    private fun prune() {
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    private fun file(key: String) = File(dir, hash(key) + ".json")

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun json(l: Link) = JSONObject().put("text", l.text).put("url", l.url)
    private fun link(o: JSONObject) = Link(o.getString("text"), o.getString("url"))
    private fun links(a: JSONArray) = List(a.length()) { link(a.getJSONObject(it)) }
    private fun strings(a: JSONArray) = List(a.length()) { a.getString(it) }

    companion object {
        private const val MAX_FILES = 300
    }
}
