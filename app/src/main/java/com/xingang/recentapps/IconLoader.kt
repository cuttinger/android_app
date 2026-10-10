package com.xingang.recentapps

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loads app icons off the main thread on demand, so the list can be shown
 * before any icon has been decoded. Rows that scroll away before their icon
 * arrives are skipped via the tag check.
 */
class IconLoader(private val pm: PackageManager, private val scope: CoroutineScope) {

    private val cache = LruCache<String, Drawable>(256)

    fun load(pkg: String, target: ImageView) {
        target.tag = pkg
        val cached = cache.get(pkg)
        if (cached != null) {
            target.setImageDrawable(cached)
            return
        }
        target.setImageDrawable(null)
        scope.launch {
            val icon = withContext(Dispatchers.IO) {
                try {
                    pm.getApplicationIcon(pkg)
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                }
            } ?: return@launch
            cache.put(pkg, icon)
            if (target.tag == pkg) target.setImageDrawable(icon)
        }
    }
}
