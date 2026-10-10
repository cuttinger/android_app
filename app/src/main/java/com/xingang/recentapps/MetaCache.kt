package com.xingang.recentapps

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Persistent cache of the per-app text metadata (labels, search keys).
 * Computing it means loading every app's resources in several locales,
 * which costs ~2 s for 100 apps; the result only changes when the app is
 * updated, so it is keyed by package + lastUpdateTime + device locale.
 */
class MetaCache(context: Context) {

    data class Meta(val label: String, val labelEn: String, val keys: List<String>)

    private val prefs = context.getSharedPreferences("meta_cache", Context.MODE_PRIVATE)
    private val locale = Locale.getDefault().toLanguageTag()
    private val pending = HashMap<String, String>()

    fun get(pkg: String, lastUpdateTime: Long): Meta? {
        val raw = prefs.getString(key(pkg), null) ?: return null
        return try {
            val o = JSONObject(raw)
            if (o.getLong("u") != lastUpdateTime) return null
            val arr = o.getJSONArray("k")
            Meta(
                label = o.getString("l"),
                labelEn = o.getString("e"),
                keys = List(arr.length()) { arr.getString(it) },
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Queue a write; call [flush] once after a batch. */
    fun put(pkg: String, lastUpdateTime: Long, meta: Meta) {
        val o = JSONObject()
            .put("u", lastUpdateTime)
            .put("l", meta.label)
            .put("e", meta.labelEn)
            .put("k", JSONArray(meta.keys))
        pending[key(pkg)] = o.toString()
    }

    fun flush() {
        if (pending.isEmpty()) return
        val e = prefs.edit()
        for ((k, v) in pending) e.putString(k, v)
        e.apply()
        pending.clear()
    }

    private fun key(pkg: String) = "$pkg|$locale"
}
