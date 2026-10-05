package com.calendarviewbox.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.coroutines.resume

data class StoreResults(
    val store: String,
    val products: List<StoreProduct>,
    /** Set when the store couldn't be searched, e.g. its site didn't respond. */
    val problem: String? = null,
)

/**
 * Searches Woolworths and Coles the way a browser would: a hidden WebView opens the store's
 * search page, then reads the results from it. Neither store has a public API, so this is
 * unofficial and may need fixing when their sites change.
 */
class StoreSearch(private val context: Context) {

    suspend fun search(query: String): List<StoreResults> = coroutineScope {
        val woolies = async { safely(STORE_WOOLWORTHS) { woolworths(query) } }
        val coles = async { safely(STORE_COLES) { coles(query) } }
        listOf(woolies.await(), coles.await())
    }

    private suspend fun safely(store: String, block: suspend () -> List<StoreProduct>): StoreResults =
        try {
            StoreResults(store, block())
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                StoreResults(store, emptyList(), "$store didn't respond in time.")
            } else {
                throw e
            }
        } catch (e: Exception) {
            StoreResults(store, emptyList(), "$store search isn't working right now.")
        }

    // ---------------- Woolworths ----------------

    private suspend fun woolworths(query: String): List<StoreProduct> {
        val q = URLEncoder.encode(query, "UTF-8")
        val raw = readFromPage("https://www.woolworths.com.au/shop/search/products?searchTerm=$q", woolworthsScript(query))
        return parseWoolworths(JSONObject(raw).getJSONObject("data"))
    }

    private fun woolworthsScript(query: String): String = """
        (function(){
          if (window.__cvb) return; window.__cvb = true;
          var q = ${JSONObject.quote(query)};
          function done(o){ try { CVB.result(JSON.stringify(o)); } catch (e) {} }
          var body = { SearchTerm: q, PageSize: 24, PageNumber: 1, SortType: "TraderRelevance",
            IsSpecial: false, Filters: [], Location: "/shop/search/products?searchTerm=" + encodeURIComponent(q) };
          function viaGet(firstError){
            fetch("/apis/ui/Search/products?searchTerm=" + encodeURIComponent(q) + "&pageNumber=1&pageSize=24&sortType=TraderRelevance",
                  { credentials: "include", headers: { "Accept": "application/json" } })
              .then(function(r){ if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
              .then(function(j){ done({ ok: true, data: j }); })
              .catch(function(e){ done({ ok: false, error: String(firstError) + " / " + String(e) }); });
          }
          fetch("/apis/ui/Search/products", { method: "POST", credentials: "include",
                headers: { "Content-Type": "application/json", "Accept": "application/json" }, body: JSON.stringify(body) })
            .then(function(r){ if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
            .then(function(j){ if (j && j.Products) done({ ok: true, data: j }); else viaGet("no products"); })
            .catch(function(e){ viaGet(e); });
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
        val q = URLEncoder.encode(query, "UTF-8")
        val raw = readFromPage("https://www.coles.com.au/search/products?q=$q", COLES_SCRIPT)
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

    /**
     * Opens [url] in a hidden WebView, runs [script] once each page finishes loading, and returns
     * the first successful result. A bot-check page may come first; it keeps waiting for the real one.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun readFromPage(url: String, script: String): String = withContext(Dispatchers.Main) {
        withTimeout(TIMEOUT_MS) {
            var webView: WebView? = null
            try {
                suspendCancellableCoroutine { cont ->
                    val view = WebView(context)
                    webView = view
                    view.settings.javaScriptEnabled = true
                    view.settings.domStorageEnabled = true
                    view.settings.blockNetworkImage = true // results only; skip product photos
                    view.addJavascriptInterface(
                        Bridge(
                            onOk = { json -> view.post { if (cont.isActive) cont.resume(json) } },
                            onProblem = { /* keep waiting: a later page load may succeed */ },
                        ),
                        "CVB",
                    )
                    view.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView, loadedUrl: String) {
                            v.evaluateJavascript(script, null)
                        }
                    }
                    view.loadUrl(url)
                }
            } finally {
                webView?.let {
                    it.stopLoading()
                    it.destroy()
                }
            }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 30_000L
        private const val MAX_PER_STORE = 10

        /** Coles renders search results into the page's Next.js data. Poll until it's there. */
        private val COLES_SCRIPT = """
            (function(){
              if (window.__cvb) return; window.__cvb = true;
              var tries = 0;
              function done(o){ try { CVB.result(JSON.stringify(o)); } catch (e) {} }
              function attempt(){
                tries++;
                var el = document.getElementById("__NEXT_DATA__");
                if (el) {
                  try {
                    var nd = JSON.parse(el.textContent);
                    var sr = nd && nd.props && nd.props.pageProps && nd.props.pageProps.searchResults;
                    if (sr && sr.results) { done({ ok: true, data: sr.results }); return; }
                  } catch (e) {}
                }
                if (tries > 50) { done({ ok: false, error: "no results on page", title: document.title }); return; }
                setTimeout(attempt, 400);
              }
              attempt();
            })();
        """.trimIndent()
    }
}
