package com.xingang.novelreader

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.LruCache
import android.util.TypedValue
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import kotlin.math.roundToInt

/**
 * Shows one page at a time: either a chapter list or the text of a chapter,
 * split into screen-sized pages that are turned by horizontal swipes. Pages
 * are fetched through a hidden WebView (see [WebFetcher]) and parsed by
 * [PageParser].
 */
class ReaderActivity : AppCompatActivity() {

    private lateinit var library: Library
    private lateinit var store: PageStore
    private lateinit var fetcher: WebFetcher
    private lateinit var web: WebView
    private lateinit var challengePanel: View
    private lateinit var progress: View
    private lateinit var errorPanel: View
    private lateinit var errorText: TextView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var pager: ViewPager2
    private lateinit var pageAdapter: PageAdapter
    private lateinit var catalogList: RecyclerView
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button
    private lateinit var catalogButton: Button

    private val cache = LruCache<String, Page>(30)
    private val inFlight = HashMap<String, Deferred<Page>>()
    /** "<page url>|<action spec>" -> cache key of the page that action produced. */
    private val actionKeys = HashMap<String, String>()
    private val history = ArrayDeque<String>()
    private var current: Page? = null
    private var bookKey: String? = null
    private var bookTitle: String? = null
    private var catalogUrl: String? = null
    private var barsVisible = true

    /** Index of the first real text page in the adapter (1 when a "previous" sentinel exists). */
    private var firstTextIndex = 0
    private var textPageCount = 0
    /** Set before loading a neighbour so the new chapter opens on its last page. */
    private var landOnLastPage = false
    /**
     * Swiping back from a chapter's first page should land on the *end* of the
     * previous chapter. When the previous chapter is split over several web
     * pages, keep following its 下一页 links until the last one.
     */
    private var seekChapterEnd = false
    /** A sentinel page is currently loading its neighbour; avoid triggering twice. */
    private var sentinelLoading = false
    /** Suppress page-change side effects while we reposition the pager ourselves. */
    private var settingPage = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        library = Library(this)
        store = PageStore(this)

        web = findViewById(R.id.web)
        challengePanel = findViewById(R.id.challengePanel)
        progress = findViewById(R.id.progress)
        errorPanel = findViewById(R.id.errorPanel)
        errorText = findViewById(R.id.errorText)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        titleView = findViewById(R.id.title)
        subtitleView = findViewById(R.id.subtitle)
        pager = findViewById(R.id.pager)
        catalogList = findViewById(R.id.catalogList)
        prevButton = findViewById(R.id.prevButton)
        nextButton = findViewById(R.id.nextButton)
        catalogButton = findViewById(R.id.catalogButton)

        fetcher = WebFetcher(web) { needed -> runOnUiThread { showChallenge(needed) } }
        catalogList.layoutManager = LinearLayoutManager(this)

        // Tap zones: left quarter = previous page, right quarter = next page, middle = toggle bars.
        pageAdapter = PageAdapter { x ->
            when {
                x < 0.25f -> turnPage(-1)
                x > 0.75f -> turnPage(+1)
                else -> toggleBars()
            }
        }
        pageAdapter.fontSp = library.fontSp
        pager.adapter = pageAdapter
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (!settingPage) savePosition()
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_IDLE) onPagerSettled()
            }
        })

        findViewById<ImageButton>(R.id.back).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        findViewById<ImageButton>(R.id.fontSmaller).setOnClickListener { changeFont(-1f) }
        findViewById<ImageButton>(R.id.fontLarger).setOnClickListener { changeFont(+1f) }
        findViewById<ImageButton>(R.id.home).setOnClickListener { goHome() }
        findViewById<ImageButton>(R.id.more).setOnClickListener { anchor ->
            PopupMenu(this, anchor).apply {
                menuInflater.inflate(R.menu.reader_more, menu)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.menuHistory -> startActivity(Intent(this@ReaderActivity, HistoryActivity::class.java))
                        R.id.menuRefresh -> current?.let { cache.remove(it.url); store.remove(it.url); open(it.url, push = false) }
                        R.id.menuBrowser -> current?.let {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.url.substringBefore('#'))))
                        }
                    }
                    true
                }
                show()
            }
        }
        findViewById<Button>(R.id.retryButton).setOnClickListener {
            history.peekLast()?.let { open(it, push = false) }
        }
        prevButton.setOnClickListener { (current as? Page.Chapter)?.prev?.let { follow(it) } }
        nextButton.setOnClickListener { (current as? Page.Chapter)?.next?.let { follow(it) } }
        catalogButton.setOnClickListener {
            val link = (current as? Page.Chapter)?.catalog ?: catalogUrl?.let { Link("目录", it) }
            if (link != null) follow(link) else Toast.makeText(this, R.string.no_catalog, Toast.LENGTH_SHORT).show()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (challengePanel.visibility == View.VISIBLE) {
                    showChallenge(false)
                    return
                }
                if (history.size > 1) {
                    history.pollLast()
                    val target = history.peekLast()!!
                    open(if (cache.get(target) == null && store.load(target) == null) target.substringBefore('#') else target, push = false)
                } else {
                    finish()
                }
            }
        })

        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        bookKey = intent.getStringExtra(EXTRA_BOOK_KEY)
        library.find(bookKey ?: "")?.let {
            bookTitle = it.title
            catalogUrl = it.catalogUrl
        }
        open(url)
    }

    override fun onPause() {
        super.onPause()
        savePosition()
    }

    // ---- loading ---------------------------------------------------------

    private fun open(url: String, push: Boolean = true) {
        android.util.Log.d(TAG, "open $url push=$push history=${history.size}")
        savePosition()
        if (push && history.peekLast() != url) history.addLast(url)
        progress.visibility = View.VISIBLE
        errorPanel.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val page = load(url)
                render(page)
                afterRender(page)
            } catch (e: Exception) {
                onLoadFailed(e)
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    /** Fetch + parse with an in-memory cache and de-duplicated in-flight requests. */
    private suspend fun load(url: String): Page {
        cached(url)?.let { return it }
        inFlight[url]?.let { return it.await() }
        val d = lifecycleScope.async {
            val result = fetcher.fetch(url)
            val page = withContext(Dispatchers.Default) { PageParser.parse(result.html, result.finalUrl) }
            remember(url, page)
            if (result.finalUrl != url) remember(result.finalUrl, page)
            android.util.Log.d(TAG, "fetched $url")
            page
        }
        inFlight[url] = d
        try {
            return d.await()
        } finally {
            inFlight.remove(url)
        }
    }

    /** Follow a navigation link; JavaScript-only controls are clicked in the live page. */
    private fun follow(link: Link) {
        android.util.Log.d(TAG, "follow ${link.text} -> ${link.url.take(80)}")
        if (!link.isAction) { open(link.url); return }
        val base = current?.url ?: return
        savePosition()
        progress.visibility = View.VISIBLE
        errorPanel.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val page = loadAction(base, link)
                if (history.peekLast() != page.url) history.addLast(page.url)
                render(page)
                afterRender(page)
            } catch (e: Exception) {
                onLoadFailed(e)
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    /** Resolve a click action, served from cache when it was already performed (e.g. by prefetch). */
    private suspend fun loadAction(base: String, link: Link): Page {
        val id = "$base|${link.url}"
        (actionKeys[id] ?: store.actionKey(id))?.let { key -> cached(key)?.let { return it } }
        inFlight[id]?.let { return it.await() }
        val d = lifecycleScope.async {
            val result = fetcher.performAction(base, link.url)
            val key = if (result.finalUrl != base) result.finalUrl else "$base#${link.text}"
            val page = withContext(Dispatchers.Default) { PageParser.parse(result.html, result.finalUrl) }
                .let { if (it.url != key) rekey(it, key) else it }
            remember(key, page)
            actionKeys[id] = key
            store.saveActionKey(id, key)
            android.util.Log.d(TAG, "action done -> $key")
            page
        }
        inFlight[id] = d
        try {
            return d.await()
        } finally {
            inFlight.remove(id)
        }
    }

    /** Memory cache first, then the disk cache (which survives restarts). */
    private suspend fun cached(key: String): Page? {
        cache.get(key)?.let { return it }
        val fromDisk = withContext(Dispatchers.IO) { store.load(key) } ?: return null
        cache.put(key, fromDisk)
        return fromDisk
    }

    private suspend fun remember(key: String, page: Page) {
        cache.put(key, page)
        withContext(Dispatchers.IO) { store.save(key, page) }
    }

    /** Load whatever [link] points at: a plain URL directly, a JavaScript control by clicking it. */
    private suspend fun fetchLink(base: String, link: Link): Page =
        if (link.isAction) loadAction(base, link) else load(link.url)

    /**
     * Warm the cache around the current page so swipes in either direction are
     * instant: the next page first, then the previous one. When the previous
     * chapter is split across several web pages, follow its 下一页 links (a few
     * hops at most) so a back-swipe can land on the chapter's end right away.
     * Fetches share one WebView and run one at a time; clicks on JavaScript
     * controls move that WebView, which [WebFetcher.performAction] handles by
     * reloading the origin page when needed.
     */
    private fun prefetchNeighbours(page: Page) {
        val chapter = page as? Page.Chapter ?: return
        lifecycleScope.launch {
            chapter.next?.let { next ->
                android.util.Log.d(TAG, "prefetch next ${next.text}")
                runCatching { fetchLink(chapter.url, next) }
            }
            var base = chapter.url
            var link = chapter.prev
            var hops = 0
            while (link != null && hops < PREV_CHAIN_HOPS) {
                val l: Link = link
                android.util.Log.d(TAG, "prefetch prev ${l.text} from $base")
                val p = runCatching { fetchLink(base, l) }.getOrNull() as? Page.Chapter ?: break
                base = p.url
                link = p.next?.takeIf { it.isPageLink }
                hops++
            }
        }
    }

    /** Continue to the chapter's last web page when seeking its end; otherwise prefetch. */
    private fun afterRender(page: Page) {
        val next = (page as? Page.Chapter)?.next
        if (seekChapterEnd && next != null && next.isPageLink) {
            landOnLastPage = true
            follow(next)
        } else {
            seekChapterEnd = false
            prefetchNeighbours(page)
        }
    }

    private fun rekey(page: Page, key: String): Page = when (page) {
        is Page.Catalog -> page.copy(url = key)
        is Page.Chapter -> page.copy(url = key)
    }

    private fun onLoadFailed(e: Exception) {
        val msg = e.message ?: e.toString()
        seekChapterEnd = false
        if (sentinelLoading) {
            // Triggered from a sentinel page: report there and let the reader swipe back.
            sentinelLoading = false
            pageAdapter.setSentinelMessage(!landOnLastPage, getString(R.string.load_failed_swipe_back, msg))
            landOnLastPage = false
        } else {
            showError(msg)
        }
    }

    // ---- rendering -------------------------------------------------------

    private fun render(page: Page) {
        android.util.Log.d(TAG, "render ${page::class.simpleName} ${page.url} title=${page.title}")
        current = page
        errorPanel.visibility = View.GONE
        sentinelLoading = false
        when (page) {
            is Page.Catalog -> renderCatalog(page)
            is Page.Chapter -> renderChapter(page)
        }
        recordHistory(page)
    }

    /** Browsing history: real URLs only (synthetic in-place action keys carry a '#'). */
    private fun recordHistory(page: Page) {
        if (page.url.contains('#')) return
        val title = when (page) {
            is Page.Catalog -> getString(R.string.history_catalog_title, page.title)
            is Page.Chapter -> listOfNotNull(bookTitle?.takeIf { it != page.title }, page.title).joinToString(" · ")
        }
        library.addHistory(page.url, title)
    }

    private fun renderCatalog(page: Page.Catalog) {
        bookTitle = page.title
        catalogUrl = page.url
        bookKey = bookKey ?: page.url
        titleView.text = page.title
        subtitleView.text = getString(R.string.chapter_count, page.chapters.size)
        pager.visibility = View.GONE
        catalogList.visibility = View.VISIBLE
        prevButton.visibility = View.INVISIBLE
        nextButton.visibility = View.INVISIBLE
        catalogButton.visibility = View.INVISIBLE
        landOnLastPage = false
        setBars(true)

        // The last-read URL may point at page 2 of a chapter (…_2.html, ?page=2);
        // match it to the chapter entry by stripping the page suffix.
        val lastUrl = library.find(bookKey!!)?.lastUrl
        val lastChapter = lastUrl?.let { last -> page.chapters.firstOrNull { chapterKey(it.url) == chapterKey(last) }?.url }
        catalogList.adapter = ChapterAdapter(page.chapters, lastChapter) { open(it.url) }
        val idx = page.chapters.indexOfFirst { it.url == lastChapter }
        if (idx >= 0) (catalogList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, 200)

        library.save(
            Book(
                key = bookKey!!, title = page.title, catalogUrl = page.url,
                lastUrl = lastUrl ?: page.url,
                lastChapter = library.find(bookKey!!)?.lastChapter ?: "",
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** URL with in-chapter page markers removed, for comparing against catalog entries. */
    private fun chapterKey(url: String): String = url
        .substringBefore('#')
        .replace(Regex("[?&]page=\\d+"), "")
        .replace(Regex("_\\d+(\\.html?)?$"), "")
        .replace(Regex("\\.html?$"), "")
        .removeSuffix("/")

    private fun renderChapter(page: Page.Chapter) {
        page.catalog?.takeIf { !it.isAction }?.let { catalogUrl = it.url }
        page.bookTitle?.let { bookTitle = it }
        bookKey = bookKey ?: catalogUrl ?: page.url
        titleView.text = bookTitle ?: page.title
        subtitleView.text = if (bookTitle != null) page.title else ""
        catalogList.visibility = View.GONE
        pager.visibility = View.VISIBLE
        prevButton.visibility = View.VISIBLE
        nextButton.visibility = View.VISIBLE
        catalogButton.visibility = View.VISIBLE
        prevButton.isEnabled = page.prev != null
        nextButton.isEnabled = page.next != null
        prevButton.text = page.prev?.text?.takeIf { it.length <= 4 } ?: getString(R.string.prev)
        nextButton.text = page.next?.text?.takeIf { it.length <= 4 } ?: getString(R.string.next)
        catalogButton.isEnabled = page.catalog != null || catalogUrl != null
        setBars(false)

        val fraction = if (landOnLastPage) 1f else library.position(page.url)
        landOnLastPage = false
        pager.doOnLayout { paginate(page, fraction) }

        library.save(
            Book(
                key = bookKey!!, title = bookTitle ?: page.title, catalogUrl = catalogUrl,
                lastUrl = page.url, lastChapter = page.title, updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** Lay the chapter out as pages and show the one at [fraction] of the way through. */
    private fun paginate(page: Page.Chapter, fraction: Float) {
        val text = formatChapter(page)
        // Measure with a real page view so line heights match the rendered pages exactly.
        val probe = layoutInflater.inflate(R.layout.item_page, pager, false).findViewById<TextView>(R.id.pageText)
        probe.setTextSize(TypedValue.COMPLEX_UNIT_SP, library.fontSp)
        val width = pager.width - pager.paddingLeft - pager.paddingRight
        val height = pager.height - pager.paddingTop - pager.paddingBottom
        val pages = Paginator.paginate(text, probe, width, height)

        val items = ArrayList<PageAdapter.Item>()
        if (page.prev != null) items += PageAdapter.Item.Sentinel(forward = false, message = getString(R.string.loading_prev))
        firstTextIndex = items.size
        textPageCount = pages.size
        pages.forEachIndexed { i, p ->
            items += PageAdapter.Item.Text(p, getString(R.string.page_footer, page.title, i + 1, pages.size))
        }
        if (page.next != null) items += PageAdapter.Item.Sentinel(forward = true, message = getString(R.string.loading_next))

        android.util.Log.d(TAG, "paginate: pager=${pager.width}x${pager.height} pad=${pager.paddingTop} pages=${pages.size} items=${items.size} first=$firstTextIndex")
        pageAdapter.fontSp = library.fontSp
        pageAdapter.submit(items)
        val target = firstTextIndex + (fraction * (pages.size - 1)).roundToInt().coerceIn(0, pages.size - 1)
        settingPage = true
        pager.setCurrentItem(target, false)
        settingPage = false
    }

    /** Chapter heading (larger, bold) followed by indented paragraphs with a small gap between them. */
    private fun formatChapter(page: Page.Chapter): CharSequence {
        val sb = SpannableStringBuilder()
        sb.append(page.title)
        sb.setSpan(RelativeSizeSpan(1.25f), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val paragraphs = page.paragraphs.ifEmpty { listOf(getString(R.string.no_content)) }
        for (p in paragraphs) {
            val start = sb.length
            sb.append("\n​\n")
            // Cover the spacer's trailing newline too: its glyph metrics also set the line height.
            sb.setSpan(RelativeSizeSpan(PARAGRAPH_GAP), start + 1, start + 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("　　").append(p)
        }
        return sb
    }

    /** Called when a swipe settles; a sentinel page means "load the neighbouring chapter". */
    private fun onPagerSettled() {
        if (settingPage) return
        val item = pageAdapter.items.getOrNull(pager.currentItem) ?: return
        android.util.Log.d(TAG, "settled at ${pager.currentItem}/${pageAdapter.itemCount} ${item::class.simpleName}")
        if (item !is PageAdapter.Item.Sentinel || sentinelLoading) return
        val chapter = current as? Page.Chapter ?: return
        if (!item.forward) {
            // Prefer the page we arrived from: it is already cached and is the
            // real "previous page" even when the site's 上一章 link points elsewhere.
            val came = history.elementAtOrNull(history.size - 2)
            if (came != null && (cache.get(came) ?: store.load(came)) is Page.Chapter) {
                sentinelLoading = true
                landOnLastPage = true
                history.pollLast()
                open(came, push = false)
                return
            }
        }
        val link = (if (item.forward) chapter.next else chapter.prev) ?: return
        sentinelLoading = true
        landOnLastPage = !item.forward
        seekChapterEnd = !item.forward
        follow(link)
    }

    /** Turn one page; past either end this moves onto the sentinel, which loads the neighbour. */
    private fun turnPage(delta: Int) {
        val target = pager.currentItem + delta
        if (target in 0 until pageAdapter.itemCount) pager.setCurrentItem(target, true)
    }

    private fun savePosition() {
        val page = current as? Page.Chapter ?: return
        if (textPageCount <= 0) return
        val idx = (pager.currentItem - firstTextIndex).coerceIn(0, textPageCount - 1)
        library.savePosition(page.url, if (textPageCount > 1) idx.toFloat() / (textPageCount - 1) else 0f)
    }

    /** Back to the bookshelf: reuse the MainActivity beneath us and drop this reader. */
    private fun goHome() {
        savePosition()
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private fun showError(msg: String) {
        errorText.text = msg
        errorPanel.visibility = View.VISIBLE
    }

    /**
     * The WebView normally sits invisible behind the reader. For a human
     * check it is raised above the reader with a hint bar on top; the hint
     * panel is transparent below the bar so touches reach the WebView.
     */
    private fun showChallenge(show: Boolean) {
        if (show) {
            web.visibility = View.VISIBLE
            web.bringToFront()
            challengePanel.bringToFront()
            challengePanel.visibility = View.VISIBLE
        } else {
            challengePanel.visibility = View.GONE
            web.visibility = View.INVISIBLE
        }
    }

    private fun toggleBars() = setBars(!barsVisible)

    /** Show or hide the app bars together with the system status / navigation bars. */
    private fun setBars(visible: Boolean) {
        barsVisible = visible
        topBar.visibility = if (visible) View.VISIBLE else View.GONE
        bottomBar.visibility = if (visible) View.VISIBLE else View.GONE
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        // In fullscreen the window extends under the camera cutout; keep text below it.
        val cutoutTop = if (visible) 0 else
            ViewCompat.getRootWindowInsets(window.decorView)?.getInsets(WindowInsetsCompat.Type.displayCutout())?.top ?: 0
        pager.setPadding(0, cutoutTop, 0, 0)
    }

    private fun changeFont(delta: Float) {
        library.fontSp = (library.fontSp + delta).coerceIn(12f, 32f)
        val page = current as? Page.Chapter ?: return
        savePosition()
        pager.doOnLayout { paginate(page, library.position(page.url)) }
    }

    companion object {
        private const val TAG = "NovelReader"
        /** Previous chapter + up to this many of its 下一页 pages are prefetched. */
        private const val PREV_CHAIN_HOPS = 4
        /** Height of the gap between paragraphs, as a fraction of one text line. */
        private const val PARAGRAPH_GAP = 0.4f
        const val EXTRA_URL = "url"
        const val EXTRA_BOOK_KEY = "bookKey"

        fun start(context: Context, url: String, bookKey: String? = null) {
            context.startActivity(
                Intent(context, ReaderActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_BOOK_KEY, bookKey)
            )
        }
    }
}
