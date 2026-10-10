package com.xingang.recentapps

/** One launchable app together with the last time it was brought to the foreground. */
data class AppUsage(
    val packageName: String,
    /** Label in the device locale (Chinese on a zh device). */
    val label: String,
    /** Label resolved with an English locale; may equal [label]. */
    val labelEn: String,
    /** Epoch millis of last foreground use, or 0 when no usage record exists. */
    val lastUsed: Long,
    /**
     * Lower-cased, whitespace-free strings this app can be found by:
     * labels, full pinyin, pinyin initials, package name, description, category.
     */
    val searchKeys: List<String>,
) {
    /** True when [query] (already normalised by [SearchIndex.normalize]) hits any key. */
    fun matches(query: String): Boolean =
        query.isEmpty() || searchKeys.any { it.contains(query) }
}
