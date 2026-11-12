package app.sd

import android.annotation.SuppressLint
import android.app.Activity
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL

data class VjCountry(val slug: String, val name: String)

/** status: available / full / maintenance / unknown. days = "Active N Days" as the site shows it (0 = not shown). */
data class VjSrv(
    val code: String,
    val title: String,
    val location: String,
    val status: String,
    val days: Int,
    val slowdns: Boolean
)

/** Reads the VPNJantit website: country list, and for one country every free SSH server with its live status. */
object VjSite {
    const val BASE = "https://www.vpnjantit.com"
    const val COUNTRIES_URL = "$BASE/free-ssh-account"
    private const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** Shown when the site cannot be read at all. */
    val FALLBACK_COUNTRIES = listOf(VjCountry("india", "India"))

    /** Why the last read failed (shown in the dialog so it is easy to report). */
    @Volatile var lastErr = ""

    /** Built-in list (from the site's "SSH by Location" page) so the country list is never empty. Live list replaces it. */
    private val BUILTIN = listOf(
        "Armenia", "Azerbaijan", "Bahrain", "Bangladesh", "Cambodia", "China", "Hong Kong", "India", "Indonesia", "Israel", "Japan",
        "Kazakhstan", "Malaysia", "Pakistan", "Philippines", "Saudi Arabia", "Singapore", "South Korea", "Thailand", "Turkey",
        "United Arab Emirates", "Vietnam", "Austria", "Belgium", "Bulgaria", "Czech Republic", "Denmark", "Estonia", "Finland",
        "France", "Germany", "Greece", "Hungary", "Ireland", "Italy", "Latvia", "Lithuania", "Luxembourg", "Moldova", "Netherlands",
        "Poland", "Portugal", "Romania", "Russia", "Slovakia", "Spain", "Sweden", "Switzerland", "Ukraine", "United Kingdom",
        "Argentina", "Brazil", "Canada", "Chile", "Mexico", "United States", "South Africa", "Australia"
    )

    private fun indiaFirst(l: List<VjCountry>): List<VjCountry> =
        l.filter { it.slug == "india" } + l.filter { it.slug != "india" }.sortedBy { it.name }

    fun builtinCountries(): List<VjCountry> = indiaFirst(BUILTIN.map { VjCountry(it.lowercase().replace(' ', '-'), it) })

    /** Last country list read live from the site, else the built-in one. */
    fun savedCountries(p: SharedPreferences): List<VjCountry> {
        val raw = p.getString("vj_countries", "") ?: ""
        val l = raw.split(',').filter { it.isNotBlank() }.map { VjCountry(it, nameOf(it)) }
        return if (l.size >= 5) indiaFirst(l) else builtinCountries()
    }

    fun saveCountries(p: SharedPreferences, l: List<VjCountry>) {
        if (l.size >= 5) p.edit().putString("vj_countries", l.joinToString(",") { it.slug }).apply()
    }

    fun saveServers(p: SharedPreferences, slug: String, l: List<VjSrv>) {
        try {
            val a = JSONArray()
            for (s in l) a.put(JSONObject().put("c", s.code).put("t", s.title).put("l", s.location).put("d", s.days))
            p.edit().putString("vj_srv_$slug", a.toString()).apply()
        } catch (e: Throwable) { }
    }

    /** Servers last seen for this country; status is \"unknown\" because it is not live. */
    fun savedServers(p: SharedPreferences, slug: String): List<VjSrv> = try {
        val a = JSONArray(p.getString("vj_srv_$slug", "[]") ?: "[]")
        (0 until a.length()).map { val o = a.getJSONObject(it); VjSrv(o.getString("c"), o.getString("t"), o.optString("l"), "unknown", o.optInt("d"), true) }
    } catch (e: Throwable) { emptyList() }

    private val NOT_COUNTRY = setOf("account", "server-status", "port-443", "port-80", "stunnel", "with-stunnel")
    private val ui = Handler(Looper.getMainLooper())

    fun countryUrl(slug: String) = "$BASE/free-ssh-$slug"

    // ───────────── downloading ─────────────

    /** Plain HTTPS first (fast). If Cloudflare or an error blocks it, the page is loaded in a hidden WebView instead. */
    fun fetch(a: Activity, url: String, marker: String, cb: (String?) -> Unit) {
        Thread {
            val html = httpGet(url, marker)
            ui.post {
                if (html != null) cb(html)
                else if (a.isFinishing) cb(null)
                else webGet(a, url, marker, cb)
            }
        }.start()
    }

    /** Both spellings (with and without www) are asked at the same time; the first good page wins. */
    private fun httpGet(url: String, marker: String): String? {
        val alt = if (url.contains("://www.")) url.replace("://www.", "://") else url.replace("://", "://www.")
        val q = java.util.concurrent.LinkedBlockingQueue<String>()
        val left = java.util.concurrent.atomic.AtomicInteger(2)
        for (u in listOf(url, alt)) {
            Thread {
                val r = httpGetOne(u, marker)
                q.offer(r ?: "")
                left.decrementAndGet()
            }.start()
        }
        val end = System.currentTimeMillis() + 14_000L
        var got = 0
        while (got < 2) {
            val left0 = end - System.currentTimeMillis()
            if (left0 <= 0) break
            val r = q.poll(left0, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
            got++
            if (r.isNotEmpty()) return r
        }
        return null
    }

    private fun httpGetOne(url: String, marker: String): String? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 6_000; c.readTimeout = 9_000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            // After the hidden browser passed the Cloudflare check once, its cookie lets the next plain request through
            try { CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Cookie", it) } } catch (e: Throwable) { }
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (c.responseCode != 200) { lastErr = "HTTP ${c.responseCode}"; LogBus.dbg("VPNJantit site: HTTP ${c.responseCode} for $url"); c.disconnect(); return null }
            val s = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            if (s.contains(marker, true)) s else { lastErr = "page blocked (Cloudflare?)"; LogBus.dbg("VPNJantit site: page has no '$marker' (Cloudflare?)"); null }
        } catch (e: Throwable) {
            lastErr = "${e.javaClass.simpleName} ${e.message ?: ""}".trim()
            LogBus.dbg("VPNJantit site: $lastErr")
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun webGet(a: Activity, url: String, marker: String, cb: (String?) -> Unit) {
        var finished = false
        val w = try { WebView(a) } catch (e: Throwable) { lastErr = "WebView missing"; cb(null); return }
        val root = try { a.findViewById<android.view.ViewGroup>(android.R.id.content) } catch (e: Throwable) { null }
        fun done(r: String?) {
            if (finished) return
            finished = true
            try { root?.removeView(w) } catch (e: Throwable) { }
            try { w.stopLoading(); w.destroy() } catch (e: Throwable) { }
            cb(r)
        }
        try {
            w.settings.javaScriptEnabled = true
            w.settings.domStorageEnabled = true      // Cloudflare's check needs this
            w.settings.useWideViewPort = true
            w.settings.userAgentString = UA
            try { CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(w, true) } catch (e: Throwable) { }
            w.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                    val u = r.url.toString().substringBefore('?').lowercase()
                    val host = (r.url.host ?: "").lowercase()
                    val cf = host.endsWith("cloudflare.com") || host.endsWith("vpnjantit.com")
                    val accept = (r.requestHeaders?.get("Accept") ?: "").lowercase()
                    val heavy = u.endsWith(".mp4") || u.endsWith(".webm") || u.endsWith(".woff") || u.endsWith(".woff2") || u.endsWith(".ttf") ||
                        (accept.contains("image/") && !accept.contains("text/html") && !cf) ||
                        (!r.isForMainFrame && !cf && accept.contains("text/html"))
                    return if (heavy) WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0))) else null
                }
                override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                    if (r.isForMainFrame) { lastErr = "WebView: ${e.description}"; LogBus.dbg("VPNJantit site: $lastErr") }
                }
                override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, e: WebResourceResponse) {
                    if (r.isForMainFrame) LogBus.dbg("VPNJantit site: WebView HTTP ${e.statusCode}")
                }
            }
            // A WebView that is not on screen is paused by Android and the Cloudflare check never finishes, so it sits in the layout as a tiny, nearly invisible view.
            w.alpha = 0.01f
            root?.addView(w, FrameLayout.LayoutParams(2, 2))
            w.loadUrl(url)
        } catch (e: Throwable) { lastErr = "WebView ${e.javaClass.simpleName}"; done(null); return }
        val t0 = System.currentTimeMillis()
        val poll = object : Runnable {
            override fun run() {
                if (finished) return
                if (a.isFinishing) { done(null); return }
                if (System.currentTimeMillis() - t0 > 30_000L) { if (lastErr.isBlank()) lastErr = "timeout (Cloudflare check did not finish)"; done(null); return }
                try {
                    w.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                        val s = try { JSONTokener(raw ?: "").nextValue() as? String } catch (e: Throwable) { null }
                        if (s != null && s.contains(marker, true)) done(s) else ui.postDelayed(this, 600L)
                    }
                } catch (e: Throwable) { done(null) }
            }
        }
        ui.postDelayed(poll, 800L)
    }

    // ───────────── reading the pages ─────────────

    private fun strip(h: String): String =
        h.replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?s)<[^>]*>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&#039;", "'").replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ").trim()

    /** "united-states" -> "United States" */
    fun nameOf(slug: String) = slug.split('-').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    fun parseCountries(html: String): List<VjCountry> {
        val re = Regex("href=[\"'](?:https?://www\\.vpnjantit\\.com)?/free-ssh-([a-z][a-z\\-]*)[\"']", RegexOption.IGNORE_CASE)
        val seen = LinkedHashSet<String>()
        for (m in re.findAll(html)) {
            val s = m.groupValues[1].lowercase()
            if (s in NOT_COUNTRY || s.endsWith("-days") || s.startsWith("port") || s.contains("status")) continue
            seen.add(s)
        }
        val list = seen.map { VjCountry(it, nameOf(it)) }.sortedBy { it.name }
        // India first (the servers this app was made for)
        return list.filter { it.slug == "india" } + list.filter { it.slug != "india" }
    }

    /**
     * One block per "Create Username SSH Tunnel" link. The text just before each link holds that server's
     * name, status (Available / Server Full / Maintenance), location and "Active N Days".
     */
    fun parseServers(html: String): List<VjSrv> {
        val re = Regex("create-free-account\\?server=([a-z0-9]+)&(?:amp;)?type=SSH", RegexOption.IGNORE_CASE)
        val head = Regex("FREE\\s+SSH\\s+Tunnel\\s+", RegexOption.IGNORE_CASE)
        val out = ArrayList<VjSrv>()
        val seen = HashSet<String>()
        var prev = 0
        for (m in re.findAll(html)) {
            val code = m.groupValues[1].lowercase()
            val seg = html.substring(prev, m.range.first)
            prev = m.range.last + 1
            if (!seen.add(code)) continue
            val text = strip(seg)
            val hm = head.findAll(text).lastOrNull()
            val block = if (hm != null) text.substring(hm.range.first) else text.takeLast(1200)
            val loc = Regex("Location\\s+(.+?)\\s+[a-z0-9\\-]+\\.vpnj", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)?.trim().orEmpty()
            val top = block.substringBefore("Location").lowercase()
            val status = when {
                top.contains("maintenance") -> "maintenance"
                top.contains("full") -> "full"
                top.contains("available") -> "available"
                else -> "unknown"
            }
            val title = Regex("^FREE\\s+SSH\\s+Tunnel\\s+([A-Za-z .]+?\\s*\\d+)", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)?.trim()
                ?: code.uppercase()
            val days = Regex("Active\\s+(\\d+)\\s*Day", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            // This app talks over SlowDNS, so a server without it cannot be used
            val slow = block.contains("slowdns", true)
            out.add(VjSrv(code, title, loc, status, days, slow))
        }
        return out
    }

    // ───────────── remembering what the site said ─────────────

    /** Fixes the app's own "full" flags with what the site shows right now, and keeps the days per server. */
    fun remember(p: SharedPreferences, list: List<VjSrv>) {
        val ed = p.edit()
        val info = try { JSONObject(p.getString("vj_info", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        for (s in list) {
            when (s.status) {
                "full" -> ed.putLong("vjfull_${s.code}", VjStore.nextReset())
                "maintenance" -> ed.putLong("vjfull_${s.code}", System.currentTimeMillis() + 60 * 60_000L)
                "available" -> ed.putLong("vjfull_${s.code}", 0L)
            }
            if (s.days > 0) info.put(s.code, s.days)
        }
        ed.putString("vj_info", info.toString()).apply()
    }

    /** "Active N days" last seen on the site for this server, or 0. */
    fun daysOf(p: SharedPreferences, code: String): Int =
        try { JSONObject(p.getString("vj_info", "{}") ?: "{}").optInt(code, 0) } catch (e: Exception) { 0 }
}
