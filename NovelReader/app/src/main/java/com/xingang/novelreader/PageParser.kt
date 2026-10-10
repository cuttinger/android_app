package com.xingang.novelreader

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI

/**
 * Site-agnostic extraction of novel pages.
 *
 * Catalog pages are recognised by a dense cluster of chapter-like links;
 * chapter pages by the element holding the most paragraph text. Navigation
 * is found by visible text (上一章 / 下一章 / 目录 and variants). Targets are
 * resolved, in order of preference, from a real href, a `data-href`-style
 * attribute, chapter URLs embedded in inline scripts, `rel=prev/next`, the
 * `bookinfo` JS object used by several CMSes, and finally a click action on
 * the element itself for pure-JavaScript controls.
 */
object PageParser {

    private val PREV_PAGE = Regex("上一[頁页]")
    private val NEXT_PAGE = Regex("下一[頁页]")
    private val PREV_CH = Regex("上一[章節节回]|^上[章節节]$|前一[章節节]")
    private val NEXT_CH = Regex("下一[章節节回]|^下[章節节]$|後一[章節节]|后一[章節节]")
    private val CATALOG = Regex("^(返回)?(目[錄录]|章[節节]列表|章[節节]目[錄录]|全部章[節节]|回目[錄录])$")
    /** Weaker catalog hints (book info page); used only when nothing better exists. */
    private val CATALOG_WEAK = Regex("^(返回)?(書頁|书页|書籤頁|详情|詳情|封面)$")
    private val CHAPTER_LIKE = Regex(
        "第\\s*[0-9零一二三四五六七八九十百千萬万两〇]+\\s*[章節节回卷集話话部篇]|chapter\\s*\\d+|^\\d{1,4}[.、\\s]",
        RegexOption.IGNORE_CASE,
    )
    private val CHAPTER_NUMBER = Regex("第\\s*([0-9]+|[零一二三四五六七八九十百千两〇]+)\\s*[章節节回]")
    private val AD_TOKEN = Regex(
        "^(ad|ads|adv|advert|advertisement|txtad|gg|banner|adsbygoogle|recommend|share|comment|footer|header|nav|menu|breadcrumb)([_\\-]|\\d|$)",
        RegexOption.IGNORE_CASE,
    )
    private val BOILERPLATE = Regex(
        "本章未完|請收藏|请收藏|手機用戶請|手机用户请|最新章[節节]|www\\.|\\.com|\\.net|\\.cc|\\.org|" +
            "記住本站|记住本站|一秒記住|一秒记住|點擊下一頁|点击下一页|加入書籤|加入书签|\\(本章完\\)|" +
            "無彈窗|无弹窗|字數：|字数：|更新時間|更新时间|作者[:：]|正在加[载載]|更新不易|速读谷|速讀谷",
    )
    private val INLINE_TAGS = setOf("span", "font", "b", "strong", "i", "em", "u", "s", "small", "big", "sup", "sub", "mark")
    private val URL_IN_SCRIPT = Regex("[\"'](https?://[^\"'\\s<>]+|/[^\"'\\s<>]*\\d[^\"'\\s<>]*)[\"']")

    fun parse(html: String, url: String): Page {
        val doc = Jsoup.parse(html, url)
        val bookinfo = parseBookinfo(html)
        val scripts = doc.select("script").map { it.data() }.filter { it.isNotBlank() }
        doc.select("script, style, noscript, iframe, ins, svg, canvas, input, select, textarea, form").remove()

        val nav = findNavLinks(doc)
        val catalogCluster = findCatalogCluster(doc)
        val content = findContentBlock(doc)
        val contentLen = content?.let { blockText(it).sumOf { p -> p.length } } ?: 0

        val isCatalog = when {
            catalogCluster == null -> false
            catalogCluster.size >= 30 && contentLen < 3000 -> true
            catalogCluster.size >= 10 && contentLen < 800 -> true
            else -> false
        }

        return simplified(if (isCatalog) {
            Page.Catalog(
                url = url,
                title = bookinfo["articlename"] ?: pageTitle(doc),
                chapters = catalogCluster!!,
            )
        } else {
            val scriptLinks = scriptChapterLinks(scripts, url)

            // Page links (within a multi-page chapter) win over chapter links.
            val prevPage = nav.pick(PREV_PAGE)
            val nextPage = nav.pick(NEXT_PAGE)
            val prevCh = nav.pickUrl(PREV_CH)
                ?: scriptLinks.first?.let { Link("上一章", it) }
                ?: relLink(doc, "prev")
                ?: bookinfo["preview_page"]?.let { Link("上一章", it) }
                ?: nav.pick(PREV_CH)
            val nextCh = nav.pickUrl(NEXT_CH)
                ?: scriptLinks.second?.let { Link("下一章", it) }
                ?: relLink(doc, "next")
                ?: bookinfo["next_page"]?.let { Link("下一章", it) }
                ?: nav.pick(NEXT_CH)
            val catalog = nav.pickUrl(CATALOG)
                ?: bookinfo["index_page"]?.let { Link("目录", it) }
                ?: nav.pickUrl(CATALOG_WEAK)
                ?: nav.pick(CATALOG)

            val title = bookinfo["chaptername"] ?: chapterTitle(doc)
            Page.Chapter(
                url = url,
                title = title,
                bookTitle = bookinfo["articlename"] ?: bookTitle(doc, catalog?.url, title),
                paragraphs = (content?.let { blockText(it) } ?: emptyList()).dropTitle(title),
                prev = (prevPage ?: prevCh)?.takeIf { it.url != url },
                next = (nextPage ?: nextCh)?.takeIf { it.url != url },
                catalog = catalog,
            )
        })
    }

    /** Show everything in Simplified Chinese regardless of the site's script. */
    private fun simplified(page: Page): Page {
        fun s(t: String) = Chinese.toSimplified(t)
        fun l(link: Link?) = link?.let { Link(s(it.text), it.url) }
        return when (page) {
            is Page.Catalog -> page.copy(title = s(page.title), chapters = page.chapters.map { l(it)!! })
            is Page.Chapter -> page.copy(
                title = s(page.title),
                bookTitle = page.bookTitle?.let(::s),
                paragraphs = page.paragraphs.map(::s),
                prev = l(page.prev), next = l(page.next), catalog = l(page.catalog),
            )
        }
    }

    // ---- navigation -----------------------------------------------------

    private fun List<Link>.pick(re: Regex): Link? = firstOrNull { re.containsMatchIn(it.text) }
    private fun List<Link>.pickUrl(re: Regex): Link? = firstOrNull { re.containsMatchIn(it.text) && !it.isAction }

    private fun isNavText(text: String) =
        PREV_PAGE.containsMatchIn(text) || NEXT_PAGE.containsMatchIn(text) ||
            PREV_CH.containsMatchIn(text) || NEXT_CH.containsMatchIn(text) ||
            CATALOG.matches(text) || CATALOG_WEAK.matches(text)

    /** Clickable elements whose text is a navigation phrase, as links or click actions. */
    private fun findNavLinks(doc: Document): List<Link> =
        doc.select("a, button, [onclick], [data-action], [data-href], [data-url]").mapNotNull { el ->
            val text = el.text().replace(Regex("\\s+"), "")
            if (text.isEmpty() || text.length > 8 || !isNavText(text)) return@mapNotNull null
            val href = el.absUrl("href")
            if (href.startsWith("http")) return@mapNotNull Link(text, href)
            for (attr in listOf("data-href", "data-url", "data-link")) {
                val v = el.absUrl(attr)
                if (v.startsWith("http")) return@mapNotNull Link(text, v)
            }
            Regex("https?://[^'\"\\s()]+|'(/[^'\\s]+)'").find(el.attr("onclick"))?.let { m ->
                val raw = m.groupValues[1].ifEmpty { m.value }
                val abs = resolve(doc.baseUri(), raw)
                if (abs != null) return@mapNotNull Link(text, abs)
            }
            Link.action(text, el.tagName(), el.className(), el.id())
        }

    private fun relLink(doc: Document, rel: String): Link? =
        doc.selectFirst("link[rel=$rel][href], a[rel=$rel][href]")
            ?.absUrl("href")?.takeIf { it.isNotEmpty() }?.let { Link(rel, it) }

    /**
     * Chapter URLs embedded in inline scripts (e.g. `loadChapter('prev')`
     * implementations): candidates share the current URL's shape with
     * different digits. Returns (prev, next).
     */
    private fun scriptChapterLinks(scripts: List<String>, url: String): Pair<String?, String?> {
        val base = url.substringBefore('?').substringBefore('#')
        val shape = Regex(Regex.escape(base.replace(Regex("\\d+"), " ")).replace(" ", "\\E\\d+\\Q"))
        if (!shape.matches(base)) return null to null
        var prev: String? = null
        var next: String? = null
        val untagged = ArrayList<String>()
        for (script in scripts) {
            for (m in URL_IN_SCRIPT.findAll(script)) {
                val abs = resolve(url, m.groupValues[1]) ?: continue
                if (abs.substringBefore('?') == base || !shape.matches(abs.substringBefore('?'))) continue
                val before = script.substring(maxOf(0, m.range.first - 120), m.range.first).lowercase()
                when {
                    Regex("prev|上一|pre[^v]|last").containsMatchIn(before) -> if (prev == null) prev = abs
                    Regex("next|下一").containsMatchIn(before) -> if (next == null) next = abs
                    else -> untagged += abs
                }
            }
        }
        val rest = untagged.filter { it != prev && it != next }.distinct()
        if (prev == null && next != null && rest.size == 1) prev = rest[0]
        if (next == null && prev != null && rest.size == 1) next = rest[0]
        if (prev == null && next == null && rest.size == 2) { prev = rest[0]; next = rest[1] }
        return prev to next
    }

    private fun resolve(base: String, raw: String): String? = try {
        URI(base).resolve(raw.trim()).toString().takeIf { it.startsWith("http") }
    } catch (e: Exception) {
        null
    }

    /** `var bookinfo = { articleid: '1', next_page: "..." }` as used by several CMSes. */
    private fun parseBookinfo(html: String): Map<String, String> {
        val start = html.indexOf("bookinfo")
        if (start < 0) return emptyMap()
        val brace = html.indexOf('{', start)
        val end = if (brace >= 0) html.indexOf('}', brace) else -1
        if (brace < 0 || end < 0 || end - brace > 4000) return emptyMap()
        val body = html.substring(brace + 1, end)
        val re = Regex("[\"']?(\\w+)[\"']?\\s*:\\s*[\"']([^\"']*)[\"']")
        return re.findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
            .filterValues { it.isNotBlank() }
    }

    // ---- catalog --------------------------------------------------------

    /**
     * Find containers dense with chapter-like links. The largest one defines
     * the chapter-URL shape; containers are emitted in order of their lowest
     * chapter number so a "latest chapters" teaser lands after the main list.
     */
    private fun findCatalogCluster(doc: Document): List<Link>? {
        val anchors = doc.select("a[href]").toList().filter {
            val h = it.absUrl("href"); h.startsWith("http") && it.text().isNotBlank()
        }
        if (anchors.size < 10) return null

        val containerTags = setOf("ul", "ol", "dl", "table", "div", "section")
        val byContainer = LinkedHashMap<Element, MutableList<Element>>()
        for (a in anchors) {
            val c = a.parents().firstOrNull { it.tagName() in containerTags } ?: continue
            byContainer.getOrPut(c) { mutableListOf() }.add(a)
        }
        val qualifying = byContainer.values
            .filter { list -> list.size >= 10 && list.count { CHAPTER_LIKE.containsMatchIn(it.text()) } * 2 >= list.size }
        val best = qualifying.maxByOrNull { it.size } ?: return null
        val pattern = urlPattern(best.map { it.absUrl("href") }) ?: return null

        val ordered = qualifying.sortedWith(
            compareBy<List<Element>> { list -> list.mapNotNull { chapterNumber(it.text()) }.minOrNull() ?: Int.MAX_VALUE }
                .thenByDescending { it.size }
        )
        val seen = LinkedHashMap<String, Link>()
        for (a in ordered.flatten() + anchors) {
            val href = a.absUrl("href")
            if (!pattern.matches(href)) continue
            val text = a.text().trim()
            if (text.isEmpty()) continue
            seen.putIfAbsent(href, Link(text, href))
        }
        return seen.values.toList().takeIf { it.size >= 10 }
    }

    /** Most common shape of the URLs with digit runs generalised, e.g. `https://x/txt/111560/\d+`. */
    private fun urlPattern(urls: List<String>): Regex? {
        val shapes = urls.groupingBy { it.replace(Regex("\\d+"), " ") }.eachCount()
        val top = shapes.maxByOrNull { it.value } ?: return null
        if (top.value * 2 < urls.size) return null
        return Regex(Regex.escape(top.key).replace(" ", "\\E\\d+\\Q"))
    }

    /** Chapter number from a title such as 第十二章 or 第385章; null when absent. */
    fun chapterNumber(title: String): Int? {
        val m = CHAPTER_NUMBER.find(title) ?: return null
        val s = m.groupValues[1]
        return s.toIntOrNull() ?: chineseNumber(s)
    }

    private fun chineseNumber(s: String): Int? {
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        val units = mapOf('十' to 10, '百' to 100, '千' to 1000)
        var total = 0
        var num = 0
        for (c in s) {
            when {
                c in digits -> num = digits[c]!!
                c in units -> { total += (if (num == 0) 1 else num) * units[c]!!; num = 0 }
                else -> return null
            }
        }
        return total + num
    }

    // ---- chapter content --------------------------------------------------

    private fun findContentBlock(doc: Document): Element? {
        var best: Element? = null
        var bestScore = 0
        for (el in doc.select("div, article, section, td, main, p")) {
            if (isJunk(el)) continue
            val score = ownText(el).length +
                el.children().toList().filter { it.tagName() == "p" }.sumOf { ownText(it).length }
            if (score > bestScore) { bestScore = score; best = el }
        }
        // Prefer the parent when it holds several similar text blocks
        // (content split into lazily loaded chunks).
        best?.parent()?.let { parent ->
            val siblings = parent.children().toList().filter { it.tagName() == best!!.tagName() && ownText(it).length >= 200 }
            if (siblings.size >= 2 && !isJunk(parent)) return parent
        }
        return best?.takeIf { bestScore >= 200 }
    }

    private fun isJunk(el: Element): Boolean {
        val tokens = el.classNames() + listOfNotNull(el.id().takeIf { it.isNotEmpty() })
        return tokens.any { AD_TOKEN.containsMatchIn(it) } || el.hasAttr("hidden") ||
            el.attr("style").replace(" ", "").contains("display:none")
    }

    /** Text of an element counting its own text nodes and inline children only. */
    private fun ownText(el: Element): String {
        val sb = StringBuilder()
        for (n in el.childNodes()) {
            when {
                n is TextNode -> sb.append(n.text())
                n is Element && n.tagName() in INLINE_TAGS -> sb.append(n.text())
            }
        }
        return sb.trim().toString()
    }

    /** Paragraphs of the block: split on <br>, <p> and block-level children. */
    private fun blockText(block: Element): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() {
            val t = clean(cur.toString())
            if (t.isNotEmpty() && !BOILERPLATE.containsMatchIn(t)) out += t
            cur.setLength(0)
        }
        fun walk(n: Node) {
            when {
                n is TextNode -> cur.append(n.text())
                n is Element -> when {
                    n.tagName() == "br" -> flush()
                    isJunk(n) -> Unit
                    n.tagName() in INLINE_TAGS || n.tagName() == "a" -> n.childNodes().forEach(::walk)
                    else -> { flush(); n.childNodes().forEach(::walk); flush() }
                }
            }
        }
        block.childNodes().forEach(::walk)
        flush()
        return out
    }

    /** Some sites repeat the chapter heading as the first line of the body; drop it. */
    private fun List<String>.dropTitle(title: String): List<String> {
        // Strip a trailing page marker such as （1 / 2） or (第1/5页), then all whitespace.
        val t = title.replace(Regex("[（(][^（()）]*[/／][^（()）]*[)）]\\s*$"), "").replace(Regex("\\s+"), "")
        if (t.length < 2) return this
        return dropWhile { it.replace(Regex("\\s+"), "").let { p -> p == t || (p.length <= t.length + 4 && p.startsWith(t)) } }
    }

    /** Collapse whitespace including NBSP (U+00A0) and ideographic space (U+3000). */
    private fun clean(s: String): String =
        s.replace('\u00A0', ' ').replace('\u3000', ' ').replace(Regex("[ \\t\\r\\n]+"), " ").trim()

    // ---- titles -----------------------------------------------------------

    private fun pageTitle(doc: Document): String {
        val h1 = doc.selectFirst("h1")?.text()?.trim()
        if (!h1.isNullOrEmpty()) {
            return h1.replace(Regex("(章[節节]列表|目[錄录]|最新章[節节])$"), "").trim()
        }
        return doc.title().split(Regex("[-_|,，–—]"))[0].trim()
    }

    /**
     * Book title on a chapter page: a breadcrumb link pointing at the catalog,
     * else the `<title>` segment that is neither the chapter title nor the
     * (usually last) site name.
     */
    private fun bookTitle(doc: Document, catalogUrl: String?, chapterTitle: String): String? {
        if (catalogUrl != null) {
            val crumb = doc.select("a[href]").toList().firstOrNull {
                it.absUrl("href").removeSuffix("/") == catalogUrl.removeSuffix("/") &&
                    it.text().trim().length in 2..30 && !isNavText(it.text().replace(Regex("\\s+"), ""))
            }
            crumb?.let { return it.text().trim() }
        }
        val ct = chapterTitle.replace(Regex("\\s+"), "")
        val parts = doc.title().split(Regex("[-_|–—]")).map { it.trim() }.filter { it.isNotEmpty() }
        val rest = parts.filter { seg ->
            val n = seg.replace(Regex("\\s+"), "")
            n != ct && !ct.startsWith(n) && !n.startsWith(ct)
        }
        return when {
            rest.size >= 2 -> rest.first()
            else -> null
        }?.takeIf { it.length in 2..40 }
    }

    private fun chapterTitle(doc: Document): String {
        val h = doc.selectFirst("h1, .title, #title, h2")?.text()?.trim()
        if (!h.isNullOrEmpty()) return h
        return doc.title().split(Regex("[-_|,，–—]"))[0].trim()
    }
}
