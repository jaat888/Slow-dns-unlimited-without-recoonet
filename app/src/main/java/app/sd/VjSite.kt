package app.sd

import android.annotation.SuppressLint
import android.app.Activity
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
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

    private fun httpGet(url: String, marker: String): String? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 12_000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (c.responseCode != 200) { LogBus.dbg("VPNJantit site: HTTP ${c.responseCode} for $url"); c.disconnect(); return null }
            val s = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            if (s.contains(marker, true)) s else { LogBus.dbg("VPNJantit site: page has no '$marker' (Cloudflare?)"); null }
        } catch (e: Throwable) {
            LogBus.dbg("VPNJantit site: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun webGet(a: Activity, url: String, marker: String, cb: (String?) -> Unit) {
        var finished = false
        val w = try { WebView(a) } catch (e: Throwable) { cb(null); return }
        fun done(r: String?) {
            if (finished) return
            finished = true
            try { w.stopLoading(); w.destroy() } catch (e: Throwable) { }
            cb(r)
        }
        try {
            w.settings.javaScriptEnabled = true
            w.settings.userAgentString = UA
            w.webViewClient = WebViewClient()
            w.loadUrl(url)
        } catch (e: Throwable) { done(null); return }
        val t0 = System.currentTimeMillis()
        val poll = object : Runnable {
            override fun run() {
                if (finished) return
                if (a.isFinishing || System.currentTimeMillis() - t0 > 25_000L) { done(null); return }
                try {
                    w.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                        val s = try { JSONTokener(raw ?: "").nextValue() as? String } catch (e: Throwable) { null }
                        if (s != null && s.contains(marker, true)) done(s) else ui.postDelayed(this, 1000L)
                    }
                } catch (e: Throwable) { done(null) }
            }
        }
        ui.postDelayed(poll, 1500L)
    }

    // ───────────── reading the pages ─────────────

    private fun strip(h: String): String =
        h.replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?s)<[^>]*>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&#039;", "'").replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ").trim()

    /** "united-states" -> "United States" */
    private fun nameOf(slug: String) = slug.split('-').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

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
