package com.calendarviewbox.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import kotlin.coroutines.resume

data class StoreResults(
    val store: String,
    val products: List<StoreProduct>,
    /** Set when the store couldn't be searched, e.g. its site didn't respond. */
    val problem: String? = null,
    /** Technical detail about what went wrong, to help fix it. */
    val detail: String? = null,
    /** The store's own search page for this query, to open in the app. */
    val pageUrl: String? = null,
)

/** A store search that ran but produced no results, with what the page reported. */
class StoreProblem(message: String) : Exception(message)

/**
 * Searches Woolworths and Coles the way a browser would: a hidden WebView opens the store's
 * search page, then reads the results from it. Neither store has a public API, so this is
 * unofficial and may need fixing when their sites change.
 */
class StoreSearch(private val context: Context) {

    suspend fun search(query: String): List<StoreResults> = coroutineScope {
        val woolies = async { safely(STORE_WOOLWORTHS, query) { woolworths(query) } }
        val coles = async { safely(STORE_COLES, query) { coles(query) } }
        listOf(woolies.await(), coles.await())
    }

    private suspend fun safely(store: String, query: String, block: suspend () -> List<StoreProduct>): StoreResults {
        val url = searchUrl(store, query)
        return try {
            StoreResults(store, block(), pageUrl = url)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: StoreProblem) {
            StoreResults(store, emptyList(), "$store search isn't working right now.", e.message, url)
        } catch (e: Exception) {
            StoreResults(store, emptyList(), "$store search isn't working right now.", e.toString().take(300), url)
        }
    }

    // ---------------- Woolworths ----------------

    private suspend fun woolworths(query: String): List<StoreProduct> =
        parseProducts(STORE_WOOLWORTHS, readFromPage(searchUrl(STORE_WOOLWORTHS, query), woolworthsPoll(query)))

    /**
     * Run every check. Sets up a search using Woolworths' own API from inside its page (so it
     * carries the site's cookies), starts it once the page has loaded, retries every few seconds,
     * and reports the result. No JavaScript timers, which Android may pause on hidden pages.
     */
    private fun woolworthsPoll(query: String): String = """
        (function(){
          if (window.__cvbResult) return window.__cvbResult;
          if (!window.__cvbGo) {
            var q = ${JSONObject.quote(query)};
            var compact = function(j){
              var out = [];
              (j.Products || []).forEach(function(g){
                (g.Products || [g]).forEach(function(p){
                  if (p == null || p.Price == null) return;
                  out.push({ id: String(p.Stockcode), name: String(p.DisplayName || p.Name || "").trim(),
                    size: p.PackageSize || "", price: p.Price, was: p.WasPrice || null,
                    unit: p.CupString || "", special: !!p.IsOnSpecial });
                });
              });
              return out.slice(0, 10);
            };
            var keep = function(j){
              if (j && j.Products) { window.__cvbResult = JSON.stringify({ ok: true, products: compact(j) }); return true; }
              return false;
            };
            var body = { Filters: [], IsSpecial: false, Location: "/shop/search/products?searchTerm=" + encodeURIComponent(q),
              PageNumber: 1, PageSize: 24, SearchTerm: q, SortType: "TraderRelevance",
              IsRegisteredRewardCardPromotion: null, ExcludeSearchTypes: ["UntraceableVendors"],
              GpBoost: 0, GroupEdmVariants: false, EnableAdReRanking: false };
            window.__cvbGo = function(){
              window.__cvbBusy = true;
              window.__cvbAttempts = (window.__cvbAttempts || 0) + 1;
              window.__cvbLast = Date.now();
              fetch("/apis/ui/Search/products", { method: "POST", credentials: "include",
                    headers: { "Content-Type": "application/json", "Accept": "application/json" }, body: JSON.stringify(body) })
                .then(function(r){ if (!r.ok) throw new Error("POST HTTP " + r.status); return r.json(); })
                .then(function(j){ if (!keep(j)) throw new Error("POST had no Products"); })
                .catch(function(e){
                  return fetch("/apis/ui/Search/products?searchTerm=" + encodeURIComponent(q) + "&pageNumber=1&pageSize=24&sortType=TraderRelevance",
                               { credentials: "include", headers: { "Accept": "application/json" } })
                    .then(function(r){ if (!r.ok) throw new Error("GET HTTP " + r.status); return r.json(); })
                    .then(function(j){ if (!keep(j)) throw new Error("GET had no Products"); })
                    .catch(function(e2){ window.__cvbProblem = String(e) + " / " + String(e2); });
                })
                .then(function(){ window.__cvbBusy = false; }, function(){ window.__cvbBusy = false; });
            };
          }
          var ready = document.readyState === "complete";
          var waited = Date.now() - (window.__cvbLast || 0);
          if (ready && !window.__cvbBusy && (window.__cvbAttempts || 0) < 8 && waited > 3000) window.__cvbGo();
          return JSON.stringify({ ok: false, error: window.__cvbProblem || "waiting", attempts: window.__cvbAttempts || 0,
            busy: !!window.__cvbBusy, ready: document.readyState, title: document.title });
        })()
    """.trimIndent()

    // ---------------- Coles ----------------

    private suspend fun coles(query: String): List<StoreProduct> =
        parseProducts(STORE_COLES, readFromPage(searchUrl(STORE_COLES, query), COLES_POLL))

    /** Reads the short product list the page checks return. */
    private fun parseProducts(store: String, raw: String): List<StoreProduct> {
        val list = JSONObject(raw).optJSONArray("products") ?: JSONArray()
        val out = mutableListOf<StoreProduct>()
        for (i in 0 until list.length()) {
            val p = list.optJSONObject(i) ?: continue
            val price = p.optDouble("price", Double.NaN).takeIf { !it.isNaN() && it > 0 } ?: continue
            val name = p.optString("name").trim()
            if (name.isEmpty()) continue
            val was = if (p.isNull("was")) null else p.optDouble("was", Double.NaN).takeIf { !it.isNaN() && it > price + 0.001 }
            val size = p.optString("size").trim().takeIf { it.isNotEmpty() && !name.contains(it, ignoreCase = true) }
            out += StoreProduct(
                store = store,
                productId = p.optString("id").takeIf { it.isNotBlank() && it != "undefined" },
                name = name,
                size = size,
                price = price,
                wasPrice = was,
                unitPrice = p.optString("unit").takeIf { it.isNotBlank() },
                special = p.optBoolean("special") || was != null,
            )
        }
        return out.take(MAX_PER_STORE)
    }

    // ---------------- hidden browser ----------------

    /** What the hidden page last reported, for the error message if nothing works. */
    private class PageReport {
        var page: String? = null
        var problem: String? = null
        var loads = 0
        var checks = 0
        var answered = 0
        var attached = false
    }

    /** evaluateJavascript hands back a JSON-encoded value; this turns it into a plain string, or null. */
    private fun decode(value: String?): String? = runCatching {
        val v = JSONTokener(value ?: "null").nextValue()
        if (v == JSONObject.NULL) null else v.toString()
    }.getOrNull()

    /**
     * Opens [url] in a WebView kept behind the board (so Android treats it as a visible page),
     * then runs [poll] every 800ms until it returns { ok: true, products: [...] }. A bot-check
     * page may come first; it keeps checking until the real page answers or time runs out.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun readFromPage(url: String, poll: String): String = withContext(Dispatchers.Main) {
        val report = PageReport()
        var webView: WebView? = null
        val host = WebHost.container
        try {
            val result = withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine<String> { cont ->
                    val view = WebView(host?.context ?: context)
                    webView = view
                    configure(view, view.context)
                    view.settings.blockNetworkImage = true // results only; skip product photos
                    if (host != null) {
                        host.addView(view, android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
                        report.attached = true
                    } else {
                        view.measure(
                            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                            android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY),
                        )
                        view.layout(0, 0, 1080, 1920)
                    }
                    view.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView, loadedUrl: String) {
                            report.loads++
                            report.page = "'" + v.title + "' at " + loadedUrl
                        }
                    }
                    val check = object : Runnable {
                        override fun run() {
                            if (!cont.isActive) return
                            report.checks++
                            view.evaluateJavascript(poll) { value ->
                                report.answered++
                                val text = decode(value)
                                val ok = text != null && runCatching { JSONObject(text).optBoolean("ok") }.getOrDefault(false)
                                if (ok) {
                                    if (cont.isActive) cont.resume(text!!)
                                    return@evaluateJavascript
                                }
                                if (text != null) report.problem = text.take(300)
                                view.postDelayed(this, 800)
                            }
                        }
                    }
                    view.loadUrl(url)
                    view.postDelayed(check, 1200)
                }
            }
            result ?: throw StoreProblem(
                buildString {
                    append("No results after ${TIMEOUT_MS / 1000}s. Pages loaded: ${report.loads}. ")
                    append("Checks: ${report.checks}, answered: ${report.answered}. ")
                    append(if (report.attached) "On screen. " else "Off screen. ")
                    report.page?.let { append("Last page: $it. ") }
                    report.problem?.let { append("Page said: $it") }
                }
            )
        } finally {
            webView?.let {
                it.stopLoading()
                host?.removeView(it)
                it.destroy()
            }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 40_000L

        private const val MAX_PER_STORE = 10

        fun searchUrl(store: String, query: String): String {
            val q = URLEncoder.encode(query, "UTF-8")
            return when (store) {
                STORE_COLES -> "https://www.coles.com.au/search/products?q=$q"
                else -> "https://www.woolworths.com.au/shop/search/products?searchTerm=$q"
            }
        }

        private val webViewMarker = Regex("Version/\\d+(\\.\\d+)* ")

        /**
         * The phone's normal Chrome identity, without the "this is an in-app WebView" marker,
         * which bot checks often refuse. Used for both the hidden and the visible store pages,
         * so cookies from one work in the other.
         */
        fun userAgent(context: Context): String = runCatching {
            WebSettings.getDefaultUserAgent(context).replace("; wv", "").replace(webViewMarker, "")
        }.getOrDefault("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

        @SuppressLint("SetJavaScriptEnabled")
        fun configure(view: WebView, context: Context) {
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.userAgentString = userAgent(context)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        }

        /**
         * Run every check: looks for Coles' search results in the page's Next.js data, then in the
         * product tiles on the page, and reports what it found if neither has results yet.
         */
        private val COLES_POLL = """
            (function(){
              var fromData = function(results){
                return results.filter(function(r){ return r && r._type === "PRODUCT"; }).map(function(r){
                  var pr = r.pricing || {};
                  var brand = r.brand || "", name = r.name || "";
                  return { id: String(r.id), name: (brand && name.indexOf(brand) !== 0 ? brand + " " : "") + name,
                    size: r.size || "", price: pr.now, was: pr.was || null, unit: pr.comparable || "",
                    special: !!(pr.was && pr.was > pr.now) || !!pr.onlineSpecial || !!pr.promotionType };
                });
              };
              var fromTiles = function(){
                var out = [];
                document.querySelectorAll('[data-testid="product-tile"], section.product__tile, .product__tile').forEach(function(t){
                  var title = t.querySelector('[data-testid="product-title"], .product__title, h2, h3');
                  var price = t.querySelector('.price__value, [data-testid="product-pricing"] span, [class*="price__value"]');
                  if (!title || !price) return;
                  var p = parseFloat(price.textContent.replace(/[^0-9.]/g, ""));
                  if (!isFinite(p)) return;
                  out.push({ id: "", name: title.textContent.trim(), size: "", price: p, was: null, unit: "", special: false });
                });
                return out;
              };
              var el = document.getElementById("__NEXT_DATA__");
              var keys = "";
              if (el) {
                try {
                  var nd = JSON.parse(el.textContent);
                  var pp = nd && nd.props && nd.props.pageProps;
                  keys = pp ? Object.keys(pp).join(",") : "no pageProps";
                  var sr = pp && pp.searchResults;
                  if (sr && sr.results) {
                    var list = fromData(sr.results);
                    if (list.length) return JSON.stringify({ ok: true, products: list.slice(0, 10) });
                    keys += " (results empty)";
                  }
                } catch (e) { keys = "unreadable: " + e; }
              }
              var tiles = fromTiles();
              if (tiles.length) return JSON.stringify({ ok: true, products: tiles.slice(0, 10) });
              return JSON.stringify({ ok: false, error: "no results yet", title: document.title, ready: document.readyState,
                nextData: !!el, pageProps: keys, tiles: document.querySelectorAll('[data-testid="product-tile"]').length });
            })()
        """.trimIndent()
    }
}


/** A place behind the board where store pages load, so Android treats them as visible. Set by MainActivity. */
object WebHost {
    var container: android.view.ViewGroup? = null
}
