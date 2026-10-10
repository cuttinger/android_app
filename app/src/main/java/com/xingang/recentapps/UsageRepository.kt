package com.xingang.recentapps

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Reads usage history and joins it with the list of launchable apps. */
class UsageRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager
    private val usm: UsageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val metaCache = MetaCache(context)

    /** Whether the user has granted "Usage access" to this app in Settings. */
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * All launchable apps, most recently used first. Apps with no usage record
     * are appended at the end sorted by name.
     *
     * Usage-stats queries and package resolution are independent and each
     * take about a second, so they run concurrently.
     */
    suspend fun loadApps(): List<AppUsage> = coroutineScope {
        val t0 = SystemClock.elapsedRealtime()
        val usage = async(Dispatchers.IO) { collectLastUsed() }
        val apps = async(Dispatchers.IO) { resolveApps() }
        val lastUsed = usage.await()
        val result = apps.await()
            .map { (pkg, meta) ->
                AppUsage(
                    packageName = pkg,
                    label = meta.label,
                    labelEn = meta.labelEn,
                    lastUsed = lastUsed[pkg] ?: 0L,
                    searchKeys = meta.keys,
                )
            }
            .sortedWith(
                compareByDescending<AppUsage> { it.lastUsed }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
            )
        Log.d(TAG, "loadApps: ${result.size} apps in ${SystemClock.elapsedRealtime() - t0} ms")
        result
    }

    /** Launchable packages with their (cached where possible) text metadata. */
    private fun resolveApps(): List<Pair<String, MetaCache.Meta>> {
        val t0 = SystemClock.elapsedRealtime()
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
        }
        var misses = 0
        val out = resolved
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { ai ->
                val updated = lastUpdateTime(ai.packageName)
                val meta = metaCache.get(ai.packageName, updated) ?: run {
                    misses++
                    buildMeta(ai).also { metaCache.put(ai.packageName, updated, it) }
                }
                ai.packageName to meta
            }
            .toList()
        metaCache.flush()
        Log.d(TAG, "resolveApps: ${out.size} apps, $misses cache misses, ${SystemClock.elapsedRealtime() - t0} ms")
        return out
    }

    private fun lastUpdateTime(pkg: String): Long = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L)).lastUpdateTime
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0).lastUpdateTime
        }
    } catch (e: PackageManager.NameNotFoundException) {
        0L
    }

    /** The expensive part: labels in several locales, description, pinyin keys. */
    private fun buildMeta(ai: ApplicationInfo): MetaCache.Meta {
        val label = pm.getApplicationLabel(ai).toString()
        val labelEn = localizedString(ai, ai.labelRes, Locale.ENGLISH) ?: label
        val labelZh = localizedString(ai, ai.labelRes, Locale.SIMPLIFIED_CHINESE)
        val description = localizedString(ai, ai.descriptionRes, null)
        val category = ApplicationInfo.getCategoryTitle(context, ai.category)?.toString()
        return MetaCache.Meta(
            label = label,
            labelEn = labelEn,
            keys = SearchIndex.buildKeys(
                label, labelEn, labelZh, ai.packageName, description, category
            ),
        )
    }

    /**
     * package -> last foreground timestamp.
     *
     * Event history is precise but the system only keeps it for a few days,
     * so it is merged with aggregated stats over a longer window whose
     * lastTimeUsed field fills in older apps.
     */
    private fun collectLastUsed(): Map<String, Long> {
        val t0 = SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        val result = HashMap<String, Long>()

        val aggregated = usm.queryAndAggregateUsageStats(
            now - TimeUnit.DAYS.toMillis(AGGREGATE_DAYS), now
        )
        for ((pkg, stats) in aggregated) {
            if (stats.lastTimeUsed > 0) result[pkg] = stats.lastTimeUsed
        }
        val t1 = SystemClock.elapsedRealtime()

        val events = usm.queryEvents(now - TimeUnit.DAYS.toMillis(EVENT_DAYS), now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (isForegroundEvent(event.eventType)) {
                val prev = result[event.packageName] ?: 0L
                if (event.timeStamp > prev) result[event.packageName] = event.timeStamp
            }
        }
        Log.d(TAG, "usage: aggregate ${t1 - t0} ms, events ${SystemClock.elapsedRealtime() - t1} ms")
        return result
    }

    private fun isForegroundEvent(type: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (type == UsageEvents.Event.ACTIVITY_RESUMED) return true
        }
        @Suppress("DEPRECATION")
        return type == UsageEvents.Event.MOVE_TO_FOREGROUND
    }

    /**
     * Resolve a string resource of another app in a specific locale
     * (null locale = device locale). Returns null when the resource is
     * missing or the app's resources cannot be loaded.
     */
    private fun localizedString(ai: ApplicationInfo, resId: Int, locale: Locale?): String? {
        if (resId == 0) return null
        return try {
            val res = pm.getResourcesForApplication(ai)
            if (locale != null) {
                val config = Configuration(res.configuration)
                config.setLocale(locale)
                @Suppress("DEPRECATION")
                res.updateConfiguration(config, res.displayMetrics)
            }
            res.getString(resId).takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** Launch intent for a package, or null when it has none. */
    fun launchIntent(packageName: String): Intent? = pm.getLaunchIntentForPackage(packageName)

    companion object {
        private const val TAG = "RecentApps"
        private const val AGGREGATE_DAYS = 365L
        private const val EVENT_DAYS = 14L
    }
}
