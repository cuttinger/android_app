package com.xingang.novelreader

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.net.URI

/**
 * Fetches pages through a WebView so that JavaScript challenges (Cloudflare),
 * cookies and client-side rendering behave exactly as in a browser.
 * Third-party resources and images are blocked, which removes most ads and
 * makes loads fast.
 *
 * When a challenge needs user interaction, [onChallenge] is called with
 * `true` so the host can reveal the WebView, and with `false` once it passes.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebFetcher(
    private val web: WebView,
    private val onChallenge: (Boolean) -> Unit,
) {
    class FetchException(msg: String) : Exception(msg)

    data class Result(val html: String, val finalUrl: String)

    private val mutex = Mutex()
    private var pageFinished = CompletableDeferred<Unit>()
    private var mainFrameError: String? = null
    private var allowedHost: String = ""
    /** URL whose untouched DOM the WebView currently shows; null after an in-place content swap. */
    private var liveUrl: String? = null

    init {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            blockNetworkImage = true
            loadsImagesAutomatically = false
            // Present as a normal mobile Chrome, not an embedded WebView.
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (!pageFinished.isCompleted) pageFinished.complete(Unit)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) mainFrameError = error.description?.toString() ?: "error"
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val host = request.url.host ?: return null
                val ok = host.endsWith(allowedHost) || host.contains("cloudflare") || host.contains("challenges.") ||
                    request.url.path?.contains("/cdn-cgi/") == true || isCdnLibrary(request.url.toString())
                return if (ok) null else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
        }
    }

    /** Load [url] and return the rendered document. */
    suspend fun fetch(url: String): Result = mutex.withLock {
        navigate(url)
        settle()
        extract(url).also { liveUrl = it.finalUrl }
    }

    /**
     * Click the element described by [spec] (see [Link.action]) on [pageUrl]
     * and return whatever the page shows afterwards, whether it navigated or
     * swapped content in place.
     */
    suspend fun performAction(pageUrl: String, spec: String): Result = mutex.withLock {
        if (liveUrl == null || normalize(liveUrl) != normalize(pageUrl)) {
            navigate(pageUrl)
            settle()
            liveUrl = web.url ?: pageUrl
        }
        val json = JSONObject(spec.removePrefix(Link.ACTION_PREFIX))
        val before = eval("String(document.body ? document.body.innerText.length : 0)").toIntOrNull() ?: 0
        val beforeUrl = web.url
        pageFinished = CompletableDeferred()
        mainFrameError = null
        val clicked = eval(clickJs(json))
        if (clicked != "1") throw FetchException("没有找到「${json.optString("text")}」按钮")

        val start = System.currentTimeMillis()
        var lastLen = before
        var stable = 0
        while (System.currentTimeMillis() - start < 15_000) {
            delay(400)
            if (pageFinished.isCompleted || web.url != beforeUrl) {
                withTimeoutOrNull(20_000) { pageFinished.await() }
                settle()
                return extract(web.url ?: pageUrl).also { liveUrl = it.finalUrl }
            }
            val len = eval("String(document.body ? document.body.innerText.length : 0)").toIntOrNull() ?: 0
            if (len != before && len == lastLen) {
                if (++stable >= 2) {
                    lazyScroll()
                    // Content changed without navigation: the DOM no longer matches any URL.
                    liveUrl = null
                    return extract(web.url ?: pageUrl)
                }
            } else {
                stable = 0
            }
            lastLen = len
        }
        throw FetchException("页面没有响应「${json.optString("text")}」")
    }

    /** Clears the WebView so a lingering challenge page is not shown later. */
    fun reset() {
        web.stopLoading()
        web.loadUrl("about:blank")
        liveUrl = null
    }

    // ---- internals -------------------------------------------------------

    private suspend fun navigate(url: String) {
        allowedHost = registrableDomain(url)
        pageFinished = CompletableDeferred()
        mainFrameError = null
        web.stopLoading()
        web.loadUrl(url)
        withTimeoutOrNull(25_000) { pageFinished.await() } ?: throw FetchException("页面加载超时")
        mainFrameError?.let { throw FetchException("加载失败：$it") }
    }

    /** Wait out challenges, expand collapsed chapter lists, trigger lazy loading. */
    private suspend fun settle() {
        var challengeShown = false
        val start = System.currentTimeMillis()
        while (true) {
            val st = state()
            if (!st.challenge && st.ready) break
            val elapsed = System.currentTimeMillis() - start
            if (st.challenge && !challengeShown && elapsed > 2_500) {
                challengeShown = true
                onChallenge(true)
            }
            if (elapsed > 180_000) throw FetchException("人机验证未完成")
            delay(500)
        }
        if (challengeShown) onChallenge(false)
        // Client-rendered pages need a moment after 'complete' to fill in content.
        waitForText()
        expandChapterList()
        lazyScroll()
    }

    private suspend fun extract(url: String): Result {
        val html = eval("document.documentElement.outerHTML")
        if (html.length < 200) throw FetchException("页面内容为空")
        return Result(html, web.url ?: url)
    }

    private data class State(val challenge: Boolean, val ready: Boolean)

    private suspend fun state(): State {
        val raw = eval(STATE_JS)
        return try {
            val o = JSONObject(raw)
            State(o.optBoolean("cf"), o.optBoolean("ready"))
        } catch (e: Exception) {
            State(challenge = false, ready = true)
        }
    }

    /** Give scripts up to ~3 s to render text if the body is still nearly empty. */
    private suspend fun waitForText() {
        var last = -1
        for (i in 0 until 8) {
            val len = eval("String(document.body ? document.body.innerText.length : 0)").toIntOrNull() ?: 0
            if (len > 800 && len == last) return
            last = len
            delay(400)
        }
    }

    /** Click a "show all chapters" control if the page has one, then wait for links to appear. */
    private suspend fun expandChapterList() {
        val before = eval(EXPAND_JS).toIntOrNull() ?: -1
        if (before < 0) return
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 8_000) {
            delay(300)
            val now = eval("String(document.querySelectorAll('a[href]').length)").toIntOrNull() ?: 0
            if (now > before + 5) {
                delay(200)
                return
            }
        }
    }

    /** Scroll to the bottom a few times so infinite-scroll content loads, then return to top. */
    private suspend fun lazyScroll() {
        var last = -1
        var unchanged = 0
        for (i in 0 until 8) {
            val len = eval(SCROLL_JS).toIntOrNull() ?: 0
            if (len == last) { if (++unchanged >= 2) break } else unchanged = 0
            last = len
            delay(350)
        }
        eval("window.scrollTo(0, 0); '0'")
    }

    private suspend fun eval(js: String): String {
        val d = CompletableDeferred<String>()
        web.evaluateJavascript(js) { d.complete(it ?: "null") }
        val raw = d.await()
        return try {
            (JSONTokener(raw).nextValue() as? String) ?: raw
        } catch (e: Exception) {
            raw
        }
    }

    private fun normalize(u: String?): String = (u ?: "").removeSuffix("/").removeSuffix("#")

    private fun registrableDomain(url: String): String {
        val host = try { URI(url).host ?: "" } catch (e: Exception) { "" }
        val parts = host.split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    }

    /** Public JS CDNs that sites load jQuery and friends from; needed for their own scripts to run. */
    private fun isCdnLibrary(url: String): Boolean =
        Regex("(staticfile|bootcdn|jsdelivr|cdnjs|unpkg|googleapis|jquery)\\.(org|net|com)", RegexOption.IGNORE_CASE).containsMatchIn(url) &&
            url.substringBefore('?').endsWith(".js")

    private fun clickJs(spec: JSONObject): String = """
        (function(){
          var want = ${JSONObject.quote(spec.optString("text"))};
          var id = ${JSONObject.quote(spec.optString("id"))};
          var cls = ${JSONObject.quote(spec.optString("cls"))};
          var tag = ${JSONObject.quote(spec.optString("tag"))}.toUpperCase();
          var el = id ? document.getElementById(id) : null;
          if (!el) {
            var cands = Array.prototype.slice.call(document.querySelectorAll(tag || 'a'));
            var norm = function(s){ return (s || '').replace(/\s+/g, ''); };
            el = cands.find(function(e){ return norm(e.textContent) === want && e.className === cls; })
              || cands.find(function(e){ return norm(e.textContent) === want; });
          }
          if (!el) return '0';
          el.click();
          return '1';
        })()
    """

    companion object {
        private const val STATE_JS = """
            (function(){
              var t = document.title || '';
              var cf = /Just a moment|请稍候|請稍候|Attention Required|Checking your browser|Verify you are human/i.test(t)
                    || !!document.querySelector('#challenge-form, #challenge-running, #challenge-stage, .cf-browser-verification, #cf-chl-widget, #turnstile-wrapper');
              return JSON.stringify({cf: cf, ready: document.readyState === 'complete' || document.readyState === 'interactive'});
            })()
        """

        /** Finds the innermost visible element whose text asks to expand the chapter list, clicks it. */
        private const val EXPAND_JS = """
            (function(){
              var re = /展開全部|展开全部|全部章節|全部章节|查看全部|顯示全部|显示全部|加載全部|加载全部|更多章節|更多章节|展開目錄|展开目录/;
              var els = document.querySelectorAll('a, button, span, div, li, p');
              var best = null;
              for (var i = 0; i < els.length; i++) {
                var e = els[i];
                if (!re.test(e.textContent || '')) continue;
                if ((e.textContent || '').length > 40) continue;
                if (e.offsetParent === null) continue;
                var deeper = false;
                var kids = e.querySelectorAll('*');
                for (var j = 0; j < kids.length; j++) { if (re.test(kids[j].textContent || '')) { deeper = true; break; } }
                if (deeper) continue;
                best = e; break;
              }
              if (!best) return '-1';
              var before = document.querySelectorAll('a[href]').length;
              best.click();
              return String(before);
            })()
        """

        private const val SCROLL_JS = """
            (function(){
              window.scrollTo(0, document.body.scrollHeight);
              window.dispatchEvent(new Event('scroll'));
              return String(document.body ? document.body.innerText.length : 0);
            })()
        """
    }
}
