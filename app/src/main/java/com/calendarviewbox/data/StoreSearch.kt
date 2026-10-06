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
        parseProducts(STORE_WOOLWORTHS, readFromPage(searchUrl(STORE_WOOLWORTHS, query), woolworthsScript(query)))

    /**
     * Woolworths' own search API, called from inside its page so it carries the site's cookies.
     * Waits a moment first (the site's bot check sets cookies after load) and retries a few times.
     * Leaves a short list of products in window.__cvbResult for the app to collect.
     */
    private fun woolworthsScript(query: String): String = """
        (function(){
          if (window.__cvb) return "running"; window.__cvb = true;
          var q = ${JSONObject.quote(query)};
          var attempt = 0;
          function finish(list){ window.__cvbResult = JSON.stringify({ ok: true, products: list }); }
          function problem(o){ o.attempt = attempt; window.__cvbProblem = JSON.stringify(o); }
          function compact(j){
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
          }
          var body = { Filters: [], IsSpecial: false, Location: "/shop/search/products?searchTerm=" + encodeURIComponent(q),
            PageNumber: 1, PageSize: 24, SearchTerm: q, SortType: "TraderRelevance",
            IsRegisteredRewardCardPromotion: null, ExcludeSearchTypes: ["UntraceableVendors"],
            GpBoost: 0, GroupEdmVariants: false, EnableAdReRanking: false };
          function viaGet(first){
            fetch("/apis/ui/Search/products?searchTerm=" + encodeURIComponent(q) + "&pageNumber=1&pageSize=24&sortType=TraderRelevance",
                  { credentials: "include", headers: { "Accept": "application/json" } })
              .then(function(r){ if (!r.ok) throw new Error("GET HTTP " + r.status); return r.json(); })
              .then(function(j){ if (j && j.Products) finish(compact(j)); else throw new Error("GET had no Products"); })
              .catch(function(e){
                problem({ error: String(first) + " / " + String(e) });
                if (attempt < 4) setTimeout(viaPost, 3000);
              });
          }
          function viaPost(){
            attempt++;
            fetch("/apis/ui/Search/products", { method: "POST", credentials: "include",
                  headers: { "Content-Type": "application/json", "Accept": "application/json" }, body: JSON.stringify(body) })
              .then(function(r){ if (!r.ok) throw new Error("POST HTTP " + r.status); return r.json(); })
              .then(function(j){ if (j && j.Products) finish(compact(j)); else throw new Error("POST had no Products"); })
              .catch(function(e){ viaGet(e); });
          }
          setTimeout(viaPost, 2500);
          return "started";
        })();
    """.trimIndent()

    // ---------------- Coles ----------------

    private suspend fun coles(query: String): List<StoreProduct> =
        parseProducts(STORE_COLES, readFromPage(searchUrl(STORE_COLES, query), COLES_SCRIPT))

    /** Reads the short product list the page scripts leave behind. */
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
        var started = false
        var loads = 0
        var polls = 0
    }

    /** evaluateJavascript hands back a JSON-encoded value; this turns it into a plain string, or null. */
    private fun decode(value: String?): String? = runCatching {
        val v = JSONTokener(value ?: "null").nextValue()
        if (v == JSONObject.NULL) null else v.toString()
    }.getOrNull()

    /**
     * Opens [url] in a hidden WebView sized like a phone screen and runs [script] once each page
     * finishes loading. The script leaves its result on the page; this checks for it every
     * 700ms. A bot-check page may come first; it keeps waiting for the real one.
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
                    view.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView, loadedUrl: String) {
                            report.loads++
                            report.page = "'" + v.title + "' at " + loadedUrl
                            v.evaluateJavascript(script) { started ->
                                if (decode(started) == "started") report.started = true
                            }
                        }
                    }
                    val poll = object : Runnable {
                        override fun run() {
                            if (!cont.isActive) return
                            report.polls++
                            view.evaluateJavascript(POLL_SCRIPT) { value ->
                                val state = decode(value)?.let { runCatching { JSONObject(it) }.getOrNull() }
                                if (state != null) {
                                    if (state.optBoolean("s")) report.started = true
                                    if (!state.isNull("p")) report.problem = state.optString("p").take(300)
                                    if (!state.isNull("r")) {
                                        val r = state.optString("r")
                                        if (runCatching { JSONObject(r).optBoolean("ok") }.getOrDefault(false)) {
                                            if (cont.isActive) cont.resume(r)
                                            return@evaluateJavascript
                                        }
                                    }
                                }
                                view.postDelayed(this, 700)
                            }
                        }
                    }
                    view.loadUrl(url)
                    view.postDelayed(poll, 1500)
                }
            }
            result ?: throw StoreProblem(
                buildString {
                    append("No results after ${TIMEOUT_MS / 1000}s. Pages loaded: ${report.loads}. ")
                    append(if (report.started) "Script ran. " else "Script didn't start. ")
                    append("Checks: ${report.polls}.")
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

        /** Asks the page what the store script has found so far. */
        private const val POLL_SCRIPT =
            "(function(){ return JSON.stringify({ r: window.__cvbResult || null, p: window.__cvbProblem || null, s: !!window.__cvb }); })()"
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
              if (window.__cvb) return "running"; window.__cvb = true;
              var tries = 0;
              function finish(list){ window.__cvbResult = JSON.stringify({ ok: true, products: list.slice(0, 10) }); }
              function fromData(results){
                return results.filter(function(r){ return r && r._type === "PRODUCT"; }).map(function(r){
                  var pr = r.pricing || {};
                  var brand = r.brand || "", name = r.name || "";
                  return { id: String(r.id), name: (brand && name.indexOf(brand) !== 0 ? brand + " " : "") + name,
                    size: r.size || "", price: pr.now, was: pr.was || null, unit: pr.comparable || "",
                    special: !!(pr.was && pr.was > pr.now) || !!pr.onlineSpecial || !!pr.promotionType };
                });
              }
              function fromTiles(){
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
                    if (sr && sr.results) { finish(fromData(sr.results)); return; }
                  } catch (e) { keys = "unreadable: " + e; }
                }
                if (tries > 6) {
                  var found = fromTiles();
                  if (found.length) { finish(found); return; }
                }
                window.__cvbProblem = JSON.stringify({ error: "no results yet", title: document.title, nextData: !!el,
                  pageProps: keys, tiles: document.querySelectorAll('[data-testid="product-tile"]').length });
                if (tries <= 60) setTimeout(attempt, 500);
              }
              attempt();
              return "started";
            })();
        """.trimIndent()
    }
}
