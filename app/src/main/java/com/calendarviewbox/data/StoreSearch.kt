package com.calendarviewbox.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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

    private suspend fun woolworths(query: String): List<StoreProduct> {
        val raw = readFromPage(searchUrl(STORE_WOOLWORTHS, query), woolworthsScript(query))
        return parseWoolworths(JSONObject(raw).getJSONObject("data"))
    }

    /**
     * Woolworths' own search API, called from inside its page so it carries the site's cookies.
     * Waits a moment first (the site's bot check sets cookies after load) and retries a few times.
     */
    private fun woolworthsScript(query: String): String = """
        (function(){
          if (window.__cvb) return; window.__cvb = true;
          var q = ${JSONObject.quote(query)};
          var attempt = 0;
          function done(o){ try { CVB.result(JSON.stringify(o)); } catch (e) {} }
          var body = { Filters: [], IsSpecial: false, Location: "/shop/search/products?searchTerm=" + encodeURIComponent(q),
            PageNumber: 1, PageSize: 24, SearchTerm: q, SortType: "TraderRelevance",
            IsRegisteredRewardCardPromotion: null, ExcludeSearchTypes: ["UntraceableVendors"],
            GpBoost: 0, GroupEdmVariants: false, EnableAdReRanking: false };
          function viaGet(first){
            fetch("/apis/ui/Search/products?searchTerm=" + encodeURIComponent(q) + "&pageNumber=1&pageSize=24&sortType=TraderRelevance",
                  { credentials: "include", headers: { "Accept": "application/json" } })
              .then(function(r){ if (!r.ok) throw new Error("GET HTTP " + r.status); return r.json(); })
              .then(function(j){ if (j && j.Products) done({ ok: true, data: j }); else throw new Error("GET had no Products"); })
              .catch(function(e){
                done({ ok: false, error: String(first) + " / " + String(e), attempt: attempt, title: document.title });
                if (attempt < 4) setTimeout(viaPost, 3000);
              });
          }
          function viaPost(){
            attempt++;
            fetch("/apis/ui/Search/products", { method: "POST", credentials: "include",
                  headers: { "Content-Type": "application/json", "Accept": "application/json" }, body: JSON.stringify(body) })
              .then(function(r){ if (!r.ok) throw new Error("POST HTTP " + r.status); return r.json(); })
              .then(function(j){ if (j && j.Products) done({ ok: true, data: j }); else throw new Error("POST had no Products"); })
              .catch(function(e){ viaGet(e); });
          }
          setTimeout(viaPost, 2500);
        })();
    """.trimIndent()

    private fun parseWoolworths(data: JSONObject): List<StoreProduct> {
        val out = mutableListOf<StoreProduct>()
        val groups = data.optJSONArray("Products") ?: JSONArray()
        for (g in 0 until groups.length()) {
            val group = groups.optJSONObject(g) ?: continue
            val items = group.optJSONArray("Products") ?: JSONArray().put(group)
            for (i in 0 until items.length()) {
                val p = items.optJSONObject(i) ?: continue
                if (p.isNull("Price")) continue
                val price = p.optDouble("Price", Double.NaN).takeIf { !it.isNaN() } ?: continue
                val was = p.optDouble("WasPrice", Double.NaN).takeIf { !it.isNaN() && it > price + 0.001 }
                val name = p.optString("DisplayName").ifBlank { p.optString("Name") }.trim()
                if (name.isEmpty()) continue
                out += StoreProduct(
                    store = STORE_WOOLWORTHS,
                    productId = p.opt("Stockcode")?.toString(),
                    name = name,
                    size = p.optString("PackageSize").takeIf { it.isNotBlank() && !name.contains(it, ignoreCase = true) },
                    price = price,
                    wasPrice = was,
                    unitPrice = p.optString("CupString").takeIf { it.isNotBlank() },
                    special = p.optBoolean("IsOnSpecial") || was != null,
                )
            }
        }
        return out.take(MAX_PER_STORE)
    }

    // ---------------- Coles ----------------

    private suspend fun coles(query: String): List<StoreProduct> {
        val raw = readFromPage(searchUrl(STORE_COLES, query), COLES_SCRIPT)
        return parseColes(JSONObject(raw).getJSONArray("data"))
    }

    private fun parseColes(results: JSONArray): List<StoreProduct> {
        val out = mutableListOf<StoreProduct>()
        for (i in 0 until results.length()) {
            val p = results.optJSONObject(i) ?: continue
            if (p.optString("_type") != "PRODUCT") continue
            val pricing = p.optJSONObject("pricing") ?: continue
            val price = pricing.optDouble("now", Double.NaN).takeIf { !it.isNaN() && it > 0 } ?: continue
            val was = pricing.optDouble("was", Double.NaN).takeIf { !it.isNaN() && it > price + 0.001 }
            val brand = p.optString("brand").trim()
            val baseName = p.optString("name").trim()
            val name = if (brand.isNotEmpty() && !baseName.startsWith(brand, ignoreCase = true)) "$brand $baseName" else baseName
            if (name.isEmpty()) continue
            out += StoreProduct(
                store = STORE_COLES,
                productId = p.opt("id")?.toString(),
                name = name,
                size = p.optString("size").takeIf { it.isNotBlank() },
                price = price,
                wasPrice = was,
                unitPrice = pricing.optString("comparable").takeIf { it.isNotBlank() },
                special = was != null || pricing.optBoolean("onlineSpecial") || !pricing.isNull("promotionType"),
            )
        }
        return out.take(MAX_PER_STORE)
    }

    // ---------------- hidden browser ----------------

    private class Bridge(val onOk: (String) -> Unit, val onProblem: (String) -> Unit) {
        @JavascriptInterface
        fun result(json: String) {
            val ok = runCatching { JSONObject(json).optBoolean("ok") }.getOrDefault(false)
            if (ok) onOk(json) else onProblem(json)
        }
    }

    /** What the hidden page last reported, for the error message if nothing works. */
    private class PageReport {
        var page: String? = null
        var problem: String? = null
        var loads = 0
    }

    /**
     * Opens [url] in a hidden WebView sized like a phone screen, runs [script] once each page finishes
     * loading, and returns the first successful result. A bot-check page may come first; it keeps
     * waiting for the real one. On failure, says what the page reported.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun readFromPage(url: String, script: String): String = withContext(Dispatchers.Main) {
        val report = PageReport()
        var webView: WebView? = null
        try {
            val result = withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine<String> { cont ->
                    val view = WebView(context)
                    webView = view
                    configure(view, context)
                    // A real size, so the page lays out like it would on a phone.
                    view.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY),
                    )
                    view.layout(0, 0, 1080, 1920)
                    view.settings.blockNetworkImage = true // results only; skip product photos
                    view.addJavascriptInterface(
                        Bridge(
                            onOk = { json -> view.post { if (cont.isActive) cont.resume(json) } },
                            onProblem = { json -> view.post { report.problem = json.take(400) } },
                        ),
                        "CVB",
                    )
                    view.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView, loadedUrl: String) {
                            report.loads++
                            report.page = "'" + v.title + "' at " + loadedUrl
                            v.evaluateJavascript(script, null)
                        }
                    }
                    view.loadUrl(url)
                }
            }
            result ?: throw StoreProblem(
                buildString {
                    append("No results after ${TIMEOUT_MS / 1000}s. Pages loaded: ${report.loads}.")
                    report.page?.let { append(" Last page: $it.") }
                    report.problem?.let { append(" Page reported: $it") }
                }
            )
        } finally {
            webView?.let {
                it.stopLoading()
                it.destroy()
            }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 35_000L
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
         * Coles renders search results into the page's Next.js data. Poll until it's there; after a
         * few seconds also try reading the product tiles on the page. Reports what it found if neither works.
         */
        private val COLES_SCRIPT = """
            (function(){
              if (window.__cvb) return; window.__cvb = true;
              var tries = 0;
              function done(o){ try { CVB.result(JSON.stringify(o)); } catch (e) {} }
              function fromTiles(){
                var tiles = document.querySelectorAll('[data-testid="product-tile"], section.product__tile, .product__tile');
                var out = [];
                tiles.forEach(function(t){
                  var title = t.querySelector('[data-testid="product-title"], .product__title, h2, h3');
                  var price = t.querySelector('.price__value, [data-testid="product-pricing"] span, [class*="price__value"]');
                  if (!title || !price) return;
                  var p = parseFloat(price.textContent.replace(/[^0-9.]/g, ""));
                  if (!isFinite(p)) return;
                  out.push({ _type: "PRODUCT", name: title.textContent.trim(), brand: "", size: "", pricing: { now: p } });
                });
                return out;
              }
              function attempt(){
                tries++;
                var el = document.getElementById("__NEXT_DATA__");
                var keys = "";
                if (el) {
                  try {
                    var nd = JSON.parse(el.textContent);
                    var pp = nd && nd.props && nd.props.pageProps;
                    keys = pp ? Object.keys(pp).join(",") : "no pageProps";
                    var sr = pp && pp.searchResults;
                    if (sr && sr.results) { done({ ok: true, data: sr.results }); return; }
                  } catch (e) { keys = "unreadable: " + e; }
                }
                if (tries > 10) {
                  var found = fromTiles();
                  if (found.length) { done({ ok: true, data: found }); return; }
                }
                if (tries % 10 === 0 || tries > 60) {
                  done({ ok: false, error: "no results yet", title: document.title,
                         nextData: !!el, pageProps: keys, tiles: document.querySelectorAll('[data-testid="product-tile"]').length });
                }
                if (tries <= 60) setTimeout(attempt, 500);
              }
              attempt();
            })();
        """.trimIndent()
    }
}
