package com.xingang.novelreader

import org.json.JSONObject

/**
 * A navigation target with its visible text.
 *
 * [url] is normally an absolute http(s) URL. For controls that only work
 * through JavaScript (`<a href="javascript:">`, `<button>`), it is an
 * *action spec* (see [ACTION_PREFIX]) describing which element to click in
 * the live page; [WebFetcher.performAction] resolves it.
 */
data class Link(val text: String, val url: String) {
    val isAction: Boolean get() = url.startsWith(ACTION_PREFIX)

    /** True for in-chapter paging links (上一页 / 下一页) as opposed to chapter links. */
    val isPageLink: Boolean get() = Regex("[上下]一[頁页]").containsMatchIn(text)

    companion object {
        const val ACTION_PREFIX = "action:"

        fun action(text: String, tag: String, cls: String, id: String): Link {
            val spec = JSONObject().put("text", text).put("tag", tag).put("cls", cls).put("id", id)
            return Link(text, ACTION_PREFIX + spec)
        }
    }
}

/** Result of parsing one fetched web page. */
sealed class Page {
    abstract val url: String
    abstract val title: String

    /** A chapter list. */
    data class Catalog(
        override val url: String,
        override val title: String,
        val chapters: List<Link>,
    ) : Page()

    /** One chapter (or one page of a multi-page chapter). */
    data class Chapter(
        override val url: String,
        override val title: String,
        val bookTitle: String?,
        val paragraphs: List<String>,
        val prev: Link?,
        val next: Link?,
        val catalog: Link?,
    ) : Page()
}
