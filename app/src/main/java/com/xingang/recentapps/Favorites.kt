package com.xingang.recentapps

import android.content.Context
import org.json.JSONArray

/**
 * The user's pinned apps, in the order they arranged them. Only package names
 * are stored; labels and icons come from the live app list, so an app that is
 * uninstalled simply stops appearing.
 */
class Favorites(context: Context) {

    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun packages(): List<String> = try {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        List(arr.length()) { arr.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(packages: List<String>) {
        val arr = JSONArray()
        packages.distinct().forEach { arr.put(it) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun add(pkg: String) {
        if (pkg in packages()) return
        save(packages() + pkg)
    }

    fun remove(pkg: String) = save(packages().filterNot { it == pkg })

    private companion object {
        const val KEY = "packages"
    }
}
