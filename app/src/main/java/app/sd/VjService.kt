package app.sd

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Random
import java.util.TimeZone

/**
 * Creates a free SSH account on VPNJantit, completely in the background.
 *
 * - Runs as a foreground service in a SEPARATE PROCESS (:vj): the VPN and the app stay alive if the WebView crashes,
 *   and Android does not freeze the work when you leave the app.
 * - The WebView lives in an invisible full-screen overlay window (needs "Display over other apps"), so the page keeps
 *   running like in a normal browser even when the app is closed.
 * - It waits for the light Cloudflare check by itself and only then presses Create.
 * - When a real captcha (hCaptcha / interactive Cloudflare) appears, ONLY that captcha pops up on top of whatever app
 *   is open. You tap it, it disappears again and the work continues.
 * - Live steps are sent to the main app (status line + Logs) and shown in the notification.
 */
class VjService : Service() {

    private val ui = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var wlp: WindowManager.LayoutParams? = null
    private lateinit var root: FrameLayout
    private lateinit var holder: FrameLayout
    private lateinit var web: WebView
    private lateinit var card: LinearLayout
    private lateinit var statusTv: TextView
    private var built = false

    private var running = false
    private var done = false
    private var server = "in6"
    private var serverLabel: String? = null
    private var user = ""
    private var pass = ""
    private var lastSaid = ""

    private var startedAt = 0L
    private var lastTick = 0L
    private var formSince = 0L
    private var waitSince = 0L
    private var lastGood = 0L
    private var revealedAt = 0L
    private var filledAt = 0L
    private var clickedAt = 0L
    private var clicks = 0
    private var errStreak = 0
    private var reloads = 0
    private var revealed = false
    private var gateOpen = false
    private var busy = false
    private var busyAt = 0L
    private var userHiddenUntil = 0L

    private val poll = object : Runnable {
        override fun run() {
            if (done) return
            try { tick() } catch (e: Throwable) { }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        Notif.channels(this)
        when (i?.action) {
            ACTION_CLEAR -> {
                clearCookies()
                if (!running) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                if (running) finishCancelled() else stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> { }
            else -> {
                if (!running) stopSelf()
                return START_NOT_STICKY
            }
        }
        try { startForeground(NID, workNotif("Starting...")) } catch (e: Throwable) { }
        if (running) return START_NOT_STICKY
        server = i?.getStringExtra(VpnJantit.EXTRA_SERVER)?.takeIf { it.isNotBlank() } ?: "in6"
        serverLabel = i?.getStringExtra(VpnJantit.EXTRA_LABEL)?.takeIf { it.isNotBlank() }
        running = true
        if (!Settings.canDrawOverlays(this)) {
            finishFail("Allow \"Display over other apps\" for Mollad DNS, then try again", 0L)
            return START_NOT_STICKY
        }
        try {
            begin()
        } catch (e: Throwable) {
            finishFail("Could not start the browser: " + (e.message ?: e.toString()), 0L)
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        initWebViewProcess()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        user = randomName()
        pass = randomPass()
        startedAt = SystemClock.elapsedRealtime()
        t0 = startedAt
        lastTick = startedAt
        lastGood = startedAt
        buildUi()
        setupWebView()
        say("Opening the VPNJantit page...")
        web.loadUrl(String.format(VpnJantit.CREATE_URL, server))
        ui.postDelayed(poll, 1500)
    }

    // ───────────────────────── overlay UI ─────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rr(c: Int, r: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(c); cornerRadius = dp(r).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun pillBtn(ctx: Context, t: String, bg: Int, fg: Int, f: () -> Unit) = TextView(ctx).apply {
        text = t; textSize = 13.5f; gravity = Gravity.CENTER; setTextColor(fg); setTypeface(typeface, Typeface.BOLD)
        setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(6), 0, dp(6), 0)
        background = rr(bg, 14)
        layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
        setOnClickListener { f() }
    }

    // colours of the overlay (they follow the app's Dark / Light setting)
    private var cAcc = 0
    private var cField = 0
    private var cSub = 0
    private var cFg = 0
    private val stepTv = ArrayList<TextView>()
    private var stepNow = -1
    private var t0 = 0L
    private var lastInfo = ""
    private lateinit var timerTv: TextView
    private lateinit var hintTv: TextView

    private fun fmtDur(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return (s / 60L).toString() + ":" + (s % 60L).toString().padStart(2, '0')
    }

    private fun setStep(i: Int) {
        if (!built || i == stepNow) return
        stepNow = i
        stepTv.forEachIndexed { k, t ->
            when {
                k == i -> { t.background = rr(cAcc, 12); t.setTextColor(Color.WHITE) }
                k < i -> { t.background = rr((cAcc and 0x00FFFFFF) or 0x26000000, 12); t.setTextColor(cAcc) }
                else -> { t.background = rr(cField, 12); t.setTextColor(cSub) }
            }
        }
    }

    private fun waitHint(): String {
        val took = getSharedPreferences("a", 0).getLong("vj_took", 0L)
        return if (took > 0L) "Last time this took about " + fmtDur(took) + " (minutes:seconds)." else "This usually takes 1 to 3 minutes."
    }

    private fun buildUi() {
        val pr = getSharedPreferences("a", 0)
        val dark = pr.getBoolean("dark", false)
        val cardC = if (dark) 0xFF141B30.toInt() else Color.WHITE
        cFg = if (dark) 0xFFEAF0FF.toInt() else 0xFF0F1630.toInt()
        cSub = if (dark) 0xFF8E9ABB.toInt() else 0xFF6A7490.toInt()
        cField = if (dark) 0xFF1C2542.toInt() else 0xFFEEF2FC.toInt()
        cAcc = if (dark) 0xFF3B82F6.toInt() else 0xFF2563EB.toInt()
        val ctx = ContextThemeWrapper(this, if (dark) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        root = FrameLayout(ctx)
        root.alpha = 0f

        // The WebView sits in its own rounded box. It is invisible until a captcha needs the user.
        holder = FrameLayout(ctx)
        holder.background = rr(Color.WHITE, 20)
        holder.clipToOutline = true
        holder.outlineProvider = ViewOutlineProvider.BACKGROUND
        web = WebView(ctx)
        holder.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.background = rr(cardC, 24, if (dark) 0xFF263052.toInt() else 0xFFE3E9F6.toInt())
        card.setPadding(dp(18), dp(16), dp(18), dp(14))
        card.elevation = dp(8).toFloat()

        // header: logo, title + server, running time
        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val logo = ImageView(ctx)
        logo.setImageResource(if (dark) R.drawable.logo_dark else R.drawable.logo_light)
        logo.clipToOutline = true
        logo.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(11).toFloat()) }
        }
        head.addView(logo, LinearLayout.LayoutParams(dp(42), dp(42)))
        val names = LinearLayout(ctx)
        names.orientation = LinearLayout.VERTICAL
        val title = TextView(ctx)
        title.text = "VPNJantit account"
        title.textSize = 17f
        title.setTextColor(cFg)
        title.setTypeface(title.typeface, Typeface.BOLD)
        names.addView(title)
        val serverName = serverLabel ?: VpnJantit.SERVERS.firstOrNull { it.code == server }?.label ?: server
        val srv = TextView(ctx)
        srv.text = serverName
        srv.textSize = 12f
        srv.setTextColor(cSub)
        srv.setSingleLine()
        srv.ellipsize = android.text.TextUtils.TruncateAt.END
        names.addView(srv)
        head.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12); rightMargin = dp(8) })
        timerTv = TextView(ctx)
        timerTv.text = "0:00"
        timerTv.textSize = 15f
        timerTv.setTextColor(cAcc)
        timerTv.setTypeface(timerTv.typeface, Typeface.BOLD)
        timerTv.background = rr((cAcc and 0x00FFFFFF) or 0x26000000, 12)
        timerTv.setPadding(dp(10), dp(5), dp(10), dp(5))
        head.addView(timerTv)
        card.addView(head)

        // three steps: Check, Captcha, Create
        stepTv.clear(); stepNow = -1
        val steps = LinearLayout(ctx)
        steps.orientation = LinearLayout.HORIZONTAL
        for (n in listOf("1  Check", "2  Captcha", "3  Create")) {
            val t = TextView(ctx)
            t.text = n
            t.textSize = 11.5f
            t.gravity = Gravity.CENTER
            t.setSingleLine()
            t.setTypeface(t.typeface, Typeface.BOLD)
            t.setPadding(dp(4), dp(7), dp(4), dp(7))
            steps.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            stepTv.add(t)
        }
        card.addView(steps, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal)
        bar.isIndeterminate = true
        bar.indeterminateTintList = ColorStateList.valueOf(cAcc)
        card.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(12) })

        statusTv = TextView(ctx)
        statusTv.textSize = 15f
        statusTv.setTextColor(cFg)
        statusTv.text = "Working..."
        card.addView(statusTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        hintTv = TextView(ctx)
        hintTv.textSize = 12f
        hintTv.setTextColor(cSub)
        hintTv.text = waitHint()
        card.addView(hintTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })

        val btns = LinearLayout(ctx)
        btns.orientation = LinearLayout.HORIZONTAL
        btns.addView(pillBtn(ctx, "Run in background", cField, cAcc) { hideByUser() })
        btns.addView(pillBtn(ctx, "Cancel", if (dark) 0xFF3A1D24.toInt() else 0xFFFDECEC.toInt(), 0xFFEF4444.toInt()) { finishCancelled() })
        card.addView(btns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        // the card on top, the captcha box under it (it takes the rest of the screen)
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(dp(14), dp(40), dp(14), dp(20)) })

        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        wlp = lp
        wm?.addView(root, lp)
        built = true
        setStep(0)
    }

    private fun setTouchable(on: Boolean) {
        val l = wlp ?: return
        l.flags = if (on) l.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else l.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm?.updateViewLayout(root, l) } catch (e: Exception) { }
    }

    private fun setupWebView() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = true
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)
        try { web.onResume(); web.resumeTimers() } catch (e: Throwable) { }

        // While hidden the window does not receive touches at all (they go to the app below)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Avoid ad redirects: the main page must stay on vpnjantit.com
                if (!request.isForMainFrame) return false
                val host = request.url.host ?: return true
                return !(host == "vpnjantit.com" || host.endsWith(".vpnjantit.com"))
            }

            // Lite mode: the account page loads much faster and uses far less data.
            // Dropped: videos, ALL images (except the captcha's own), fonts, and ad pop-up frames from other sites.
            // Kept untouched: every script, because the site refuses to work when it thinks an ad-blocker is running.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                val u = url.toString().substringBefore('?').lowercase()
                val host = (url.host ?: "").lowercase()
                val keep = host == "vpnjantit.com" || host.endsWith(".vpnjantit.com") ||
                    host == "hcaptcha.com" || host.endsWith(".hcaptcha.com") ||
                    host == "cloudflare.com" || host.endsWith(".cloudflare.com")
                val accept = (request.requestHeaders?.get("Accept") ?: "").lowercase()
                val video = u.endsWith(".mp4") || u.endsWith(".webm") || u.endsWith(".m3u8") || u.endsWith(".mpd") || u.endsWith(".mov")
                val font = u.endsWith(".woff") || u.endsWith(".woff2") || u.endsWith(".ttf") || u.endsWith(".otf")
                val image = accept.contains("image/") && !accept.contains("text/html") &&
                    !(host == "hcaptcha.com" || host.endsWith(".hcaptcha.com") || host == "cloudflare.com" || host.endsWith(".cloudflare.com"))
                val adFrame = !request.isForMainFrame && !keep && accept.contains("text/html")
                if (video || font || image || adFrame) {
                    return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                finishFail("The WebView was closed (low memory). Please try again.", 0L)
                return true // the app will not crash
            }
        }
    }

    // ───────────────────────── state machine ─────────────────────────

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        // The clock stops while the user is solving a captcha
        if (revealed) startedAt += now - lastTick
        lastTick = now
        if (now - startedAt > 240_000L) {
            finishFail("Timed out: the account was not created in 4 minutes", 0L)
            return
        }
        if (revealed && now - revealedAt > 300_000L) {
            finishFail("The captcha was not solved in time", 0L)
            return
        }
        try { if (built) { timerTv.text = fmtDur(now - t0); setStep(if (revealed) 1 else if (clicks > 0) 2 else 0) } } catch (e: Exception) { }
        evalJs(MUTE_JS)
        if (revealed && !gateOpen) evalJs(CROP_JS) else evalJs(DISMISS_JS)
        // A probe that never answered (WebView frozen) must not block the machine forever
        if (busy && now - busyAt > 8000L) busy = false
        if (busy) return
        busy = true; busyAt = now
        try {
            web.evaluateJavascript(JS_PROBE) { raw ->
                busy = false
                onProbe(raw)
            }
        } catch (e: Exception) { busy = false }

        // The page never got to a form: reload it (at most twice)
        if (clicks == 0 && !revealed && reloads < 2 && now - lastGood > 45_000L) {
            reloads++
            filledAt = 0L; formSince = 0L; waitSince = 0L; lastGood = now
            say("The page is slow, reloading it...")
            web.loadUrl(String.format(VpnJantit.CREATE_URL, server))
        }
    }

    private fun onProbe(raw: String?) {
        if (done || raw.isNullOrEmpty() || raw == "null") return
        val o = try { JSONObject(raw) } catch (e: Exception) { return }
        when (o.optString("stage")) {
            "result" -> finishFromResult(o)
            "adblock" -> finishFail("The site detected an ad-blocker. Turn off DNS/ad-blocking and try again.", 0L)
            "form" -> handleForm(o)
            else -> { }
        }
    }

    private fun handleForm(o: JSONObject) {
        val now = SystemClock.elapsedRealtime()
        val hasInputs = o.optBoolean("hasInputs")
        val ts = o.optString("ts")
        val hc = o.optBoolean("hc")
        val hcDone = o.optBoolean("hcDone")
        val err = o.optString("err")
        if (formSince == 0L) formSince = now
        lastGood = now
        val alert = o.optString("alert")
        lastInfo = "cloudflare=" + (if (ts.isBlank()) "none" else ts) + ", captcha=" + (if (hc) "open" else if (hcDone) "done" else "none") +
            ", fields=" + hasInputs + ", clicks=" + clicks + (if (alert.isNotBlank()) ", site says: " + alert else "")

        // 1) Error text from the site. It must stay for 3 checks in a row, so a flash of text does not fail the job.
        if (err.isNotBlank() && (clicks > 0 || !hasInputs) && !hc) errStreak++ else errStreak = 0
        if (errStreak >= 3) {
            val cooldown = Regex("minute|wait|too (many|fast|soon)|limit", RegexOption.IGNORE_CASE).containsMatchIn(err)
            finishFail(err, if (cooldown) VpnJantit.COOLDOWN_MS else 0L)
            return
        }

        // 2) The site asks to watch a short ad: show the whole page so the user can do it
        if (o.optBoolean("gate")) { revealGate(); return }
        if (gateOpen) { gateOpen = false; hide() }

        // 3) A real captcha needs the user (hCaptcha, or a Cloudflare box that does not finish by itself)
        if (ts == "wait") { if (waitSince == 0L) waitSince = now } else waitSince = 0L
        val needUser = hc || (ts == "wait" && now - waitSince > 12_000L)
        if (needUser) { reveal(); return }
        if (revealed && !gateOpen) hide()

        if (!hasInputs) { say("Waiting for the page..."); return }

        if (filledAt == 0L) {
            evalJs(fillJs(user, pass))
            filledAt = now
            say("Form filled in, waiting for the Cloudflare check...")
            return
        }

        val canClick = ts == "ok" || hcDone || (ts == "none" && now - filledAt > 5000L)
        if (canClick) {
            if (clicks == 0 || (hcDone && clicks < 2 && now - clickedAt > 2000L)) {
                clicks++
                clickedAt = now
                evalJs(CLICK_JS)
                say("Creating the account...")
            } else if (clicks > 0 && now - clickedAt > 45_000L) {
                finishFail("The account was not created (no response from the site)", 0L)
            }
            return
        }
        say("Waiting for the Cloudflare check...")
    }

    private fun finishFromResult(o: JSONObject) {
        val host = o.optString("host").ifBlank { "$server.vpnjantit.com" }
        val ns = o.optString("ns").ifBlank { "ns-$server.vpnjantit.com" }
        val pub = o.optString("pub").trim()
        val u = o.optString("user").trim()
        val p = o.optString("pass")
        if (u.isBlank() || p.isBlank()) return
        if (pub.isBlank()) {
            finishFail("The account was created ($u) but no public key was found", 0L)
            return
        }
        say("Account created, saving it...")
        val exp = parseExpiry(o.optString("exp"))
        val data = Intent(VpnJantit.ACTION_RESULT).setClassName(packageName, "app.sd.VjBridge")
            .putExtra(VpnJantit.R_OK, true)
            .putExtra(VpnJantit.EXTRA_SERVER, server)
            .putExtra(VpnJantit.R_HOST, host)
            .putExtra(VpnJantit.R_NS, ns)
            .putExtra(VpnJantit.R_PUBKEY, pub)
            .putExtra(VpnJantit.R_USER, u)
            .putExtra(VpnJantit.R_PASS, p)
            .putExtra(VpnJantit.R_EXPIRES, exp)
            .putExtra(VpnJantit.R_TOOK, SystemClock.elapsedRealtime() - t0)
        shutdown(data)
    }

    private fun parseExpiry(s: String): Long {
        if (s.isBlank()) return 0L
        return try {
            // The site's time is GMT+7
            val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            f.timeZone = TimeZone.getTimeZone("GMT+07:00")
            f.parse(s.trim())?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // ───────────────────────── show / hide the captcha ─────────────────────────

    private fun say(t: String) {
        if (t == lastSaid) return
        lastSaid = t
        try { if (built) statusTv.text = t } catch (e: Exception) { }
        try {
            getSystemService(android.app.NotificationManager::class.java)?.notify(NID, workNotif(t))
        } catch (e: Throwable) { }
        try { sendBroadcast(Intent(VpnJantit.ACTION_STATUS).setClassName(packageName, "app.sd.VjBridge").putExtra(VpnJantit.X_TEXT, t)) } catch (e: Throwable) { }
    }

    private fun showUi(msg: String) {
        revealed = true
        revealedAt = SystemClock.elapsedRealtime()
        root.setBackgroundColor(0xB3000000.toInt())
        setTouchable(true)
        root.animate().cancel()
        root.animate().alpha(1f).setDuration(180).start()
        say(msg)
        try { hintTv.text = "Only this check needs you. The rest continues by itself." } catch (e: Exception) { }
        Notif.alert(this, NID_CAPTCHA, "Captcha needs you", msg)
    }

    /** Only the captcha is shown: cookie banner, ads and the rest of the page are hidden. */
    private fun reveal() {
        if (revealed || SystemClock.elapsedRealtime() < userHiddenUntil) return
        showUi("Tap \"I am human\" in the box below")
        evalJs(CROP_JS)
        // The captcha widget may load later, so crop again
        ui.postDelayed({ if (!done && revealed && !gateOpen) evalJs(CROP_JS) }, 1500)
        ui.postDelayed({ if (!done && revealed && !gateOpen) evalJs(CROP_JS) }, 4000)
    }

    /** The site asks for a short ad before it continues: show the full page so the user can do it. */
    private fun revealGate() {
        if (revealed || SystemClock.elapsedRealtime() < userHiddenUntil) return
        gateOpen = true
        evalJs(UNCROP_JS)
        showUi("The site asks you to view a short ad. Finish it and this continues by itself.")
    }

    private fun hide() {
        if (!revealed) return
        revealed = false
        evalJs(UNCROP_JS)
        setTouchable(false)
        root.animate().cancel()
        root.animate().alpha(0f).setDuration(150).withEndAction { if (!revealed) root.setBackgroundColor(0) }.start()
        Notif.cancel(this, NID_CAPTCHA)
        try { hintTv.text = waitHint() } catch (e: Exception) { }
        say("Continuing in the background...")
    }

    /** "Continue in background": the window goes away but the account keeps being created. */
    private fun hideByUser() {
        userHiddenUntil = SystemClock.elapsedRealtime() + 60_000L
        gateOpen = false
        hide()
    }

    private fun evalJs(js: String) {
        try { if (built && !done) web.evaluateJavascript(js, null) } catch (e: Exception) { }
    }

    // ───────────────────────── finishing ─────────────────────────

    private fun finishFail(msg: String, retryMs: Long) {
        if (done) return
        shutdown(Intent(VpnJantit.ACTION_RESULT).setClassName(packageName, "app.sd.VjBridge")
            .putExtra(VpnJantit.R_OK, false)
            .putExtra(VpnJantit.EXTRA_SERVER, server)
            .putExtra(VpnJantit.R_ERROR, msg)
            .putExtra(VpnJantit.R_DETAIL, failDetail())
            .putExtra(VpnJantit.R_RETRY_MS, retryMs))
    }

    /** Where it stopped and what the page looked like, so a failure is never just "failed". */
    private fun failDetail(): String {
        val took = if (t0 > 0L) fmtDur(SystemClock.elapsedRealtime() - t0) else "0:00"
        return "after " + took + ", last step: " + lastSaid.ifBlank { "start" } + (if (lastInfo.isNotBlank()) " | " + lastInfo else "")
    }

    private fun finishCancelled() {
        if (done) return
        shutdown(Intent(VpnJantit.ACTION_RESULT).setClassName(packageName, "app.sd.VjBridge")
            .putExtra(VpnJantit.R_OK, false)
            .putExtra(VpnJantit.EXTRA_SERVER, server)
            .putExtra(VpnJantit.R_ERROR, "Cancelled"))
    }

    private fun shutdown(result: Intent) {
        if (done) return
        done = true
        ui.removeCallbacksAndMessages(null)
        try { sendBroadcast(result) } catch (e: Throwable) { }
        Notif.cancel(this, NID_CAPTCHA)
        if (built) {
            try { wm?.removeView(root) } catch (e: Exception) { }
            try {
                holder.removeAllViews()
                web.stopLoading()
                web.destroy()
            } catch (e: Exception) { }
        }
        built = false
        running = false
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Throwable) { }
        stopSelf()
    }

    override fun onDestroy() {
        if (!done && running) {
            done = true
            ui.removeCallbacksAndMessages(null)
            try { if (built) wm?.removeView(root) } catch (e: Exception) { }
            try { if (built) web.destroy() } catch (e: Exception) { }
        }
        super.onDestroy()
    }

    // ───────────────────────── notification ─────────────────────────

    private fun workNotif(text: String): Notification {
        val cancelPi = PendingIntent.getService(
            this, 5, Intent(this, VjService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val act = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Cancel", cancelPi
        ).build()
        val since = if (t0 > 0L) System.currentTimeMillis() - (SystemClock.elapsedRealtime() - t0) else System.currentTimeMillis()
        return Notification.Builder(this, Notif.CH_WORK)
            .setWhen(since).setShowWhen(true).setUsesChronometer(true)
            .setSmallIcon(R.drawable.ic_stat).setLargeIcon(Notif.logo(this))
            .setContentTitle("VPNJantit account")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(Notif.open(this))
            .addAction(act)
            .build()
    }

    // ───────────────────────── cookies ─────────────────────────

    private fun clearCookies() {
        try {
            initWebViewProcess()
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null); cm.flush()
            WebStorage.getInstance().deleteAllData()
        } catch (e: Throwable) { }
        try { sendBroadcast(Intent(VpnJantit.ACTION_STATUS).setClassName(packageName, "app.sd.VjBridge").putExtra(VpnJantit.X_TEXT, "Site cookies cleared")) } catch (e: Throwable) { }
    }

    // ───────────────────────── random user/pass ─────────────────────────

    private fun randomName(): String {
        val r = Random()
        val abc = "abcdefghijklmnopqrstuvwxyz"
        val sb = StringBuilder("jt")
        repeat(4) { sb.append(abc[r.nextInt(abc.length)]) }
        repeat(3) { sb.append(r.nextInt(10)) }
        return sb.toString()
    }

    private fun randomPass(): String {
        val r = Random()
        val chars = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val sb = StringBuilder()
        repeat(10) { sb.append(chars[r.nextInt(chars.length)]) }
        return sb.toString()
    }

    private fun fillJs(u: String, p: String): String =
        "(function(u,p){" +
            "var ins=[].slice.call(document.querySelectorAll('input')).filter(function(e){" +
            "var t=(e.type||'text').toLowerCase();" +
            "return (t==='text'||t==='password')&&!e.readOnly&&e.getBoundingClientRect().width>0;});" +
            "if(ins.length<2)return 'no';" +
            "function set(e,v){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');" +
            "d.set.call(e,v);e.dispatchEvent(new Event('input',{bubbles:true}));" +
            "e.dispatchEvent(new Event('change',{bubbles:true}));}" +
            "set(ins[0],u);set(ins[1],p);return 'ok';})(" +
            JSONObject.quote(u) + "," + JSONObject.quote(p) + ")"

    companion object {
        const val ACTION_START = "app.sd.vj_start"
        const val ACTION_CLEAR = "app.sd.vj_clear"
        const val ACTION_CANCEL = "app.sd.vj_cancel"
        private const val NID = 41
        private const val NID_CAPTCHA = 42
        @Volatile private var webReady = false

        /** Must run before the first WebView/CookieManager use in this process. */
        private fun initWebViewProcess() {
            if (webReady) return
            webReady = true
            try {
                if (android.os.Build.VERSION.SDK_INT >= 28) WebView.setDataDirectorySuffix("vj")
            } catch (e: Exception) { }
        }

        fun start(c: Context, server: String, label: String = "") {
            c.startForegroundService(Intent(c, VjService::class.java).setAction(ACTION_START).putExtra(VpnJantit.EXTRA_SERVER, server).putExtra(VpnJantit.EXTRA_LABEL, label))
        }

        fun clear(c: Context) {
            c.startService(Intent(c, VjService::class.java).setAction(ACTION_CLEAR))
        }

        fun cancel(c: Context) {
            try { c.startService(Intent(c, VjService::class.java).setAction(ACTION_CANCEL)) } catch (e: Throwable) { }
        }

        /** The "Create Username SSH Tunnel" button (not the premium "360 Days" one). */
        private const val CLICK_JS = """
(function(){
  var c=[].slice.call(document.querySelectorAll('button,input[type=submit],input[type=button],a'));
  for(var i=0;i<c.length;i++){
    var t=((c[i].innerText||c[i].value||'')+'').replace(/\s+/g,' ').trim();
    if(/create username ssh tunnel/i.test(t)){ c[i].click(); return 'ok'; }
  }
  return 'no';
})()
"""

        /** Hides everything except the captcha widgets (cookie banner, ads, pop-ups). */
        private const val CROP_JS = """
(function(){
  var st=document.getElementById('vj-crop');
  if(!st){ st=document.createElement('style'); st.id='vj-crop'; document.head.appendChild(st); }
  st.textContent='html,body{background:#fff !important;overflow:auto !important}'+
    'ins,.adsbygoogle,[id*="google_ads"],[class*="adsbygoogle"],[id*="aswift"],[id^="div-gpt"],[data-anchor-status],[data-vignette-status],[data-ad-status],[class*="ad-container"],[id*="cookie"],[class*="cookie"],[class*="consent"],iframe:not([src*="hcaptcha.com"]):not([src*="challenges.cloudflare.com"]){display:none !important}'+
    'html>*:not(head):not(body){display:none !important}'+
    'body div[style*="position: fixed"]:not(:has(iframe[src*="hcaptcha.com"])):not(:has(iframe[src*="challenges.cloudflare.com"])):not(.h-captcha):not(.cf-turnstile){display:none !important}'+
    'body *{visibility:hidden !important}'+
    '.h-captcha,.h-captcha *,.cf-turnstile,.cf-turnstile *,iframe[src*="hcaptcha.com"],iframe[src*="challenges.cloudflare.com"]{visibility:visible !important}'+
    '.h-captcha,.cf-turnstile{position:fixed !important;top:12px !important;left:50% !important;transform:translateX(-50%) !important;z-index:2147483647 !important;background:#fff !important}';
  /* The site's own pop-up ad ("Free VPN access" with an X) sits on top of the captcha. It is a fixed element added by the ad script,
     so every fixed / sticky element that does not hold the captcha is hidden, plus ad hosts that live in a shadow root. */
  try{
    var CAPQ='.h-captcha,.cf-turnstile,iframe[src*="hcaptcha.com"],iframe[src*="challenges.cloudflare.com"]';
    var els=document.body?document.body.querySelectorAll('*'):[];
    for(var k=0;k<els.length;k++){
      var x=els[k];
      if(x.getAttribute('data-vjk')) continue;
      var cs=getComputedStyle(x);
      var hostAd=false;
      if(x.shadowRoot && x.parentNode===document.body) hostAd=true;
      if(cs.position!=='fixed' && cs.position!=='sticky' && !hostAd) continue;
      if((x.matches&&x.matches(CAPQ)) || x.querySelector(CAPQ) || (x.closest&&x.closest('.h-captcha,.cf-turnstile'))) continue;
      x.setAttribute('data-vjk','1');
      x.style.setProperty('display','none','important');
    }
  }catch(err){}
  var e=document.querySelector('iframe[src*="hcaptcha.com"],.h-captcha,.cf-turnstile,iframe[src*="challenges.cloudflare.com"]');
  if(e){ e.scrollIntoView({block:'center'}); }
  return 'ok';
})()
"""

        private const val UNCROP_JS = """
(function(){ var s=document.getElementById('vj-crop'); if(s&&s.parentNode) s.parentNode.removeChild(s); return 'ok'; })()
"""

        /** Accepts the cookie banner and closes ad pop-ups. It never presses "view ad" buttons. */
        private const val DISMISS_JS = """
(function(){
  function vis(e){ var r=e.getBoundingClientRect(); var s=getComputedStyle(e);
    return r.width>0 && r.height>0 && s.visibility!=='hidden' && s.display!=='none'; }
  var n=0;
  var all=[].slice.call(document.querySelectorAll('button,a,div,span,input[type=button]'));
  for(var i=0;i<all.length;i++){
    var e=all[i];
    if(e.getAttribute('data-vjc')) continue;
    if(e.closest && e.closest('form,.h-captcha,.cf-turnstile')) continue;
    if(!vis(e)) continue;
    var t=((e.innerText||e.value||'')+'').replace(/\s+/g,' ').trim();
    var lab=((e.getAttribute('aria-label')||'')+'').toLowerCase().trim();
    var ok=false;
    if(t.length<20 && e.children.length<=1 && /^(i agree|agree|accept|accept all|accept cookies|allow all|got it|i understand|ok|okay)$/i.test(t)) ok=true;
    else if(lab==='close' || lab==='dismiss' || lab==='close ad') ok=true;
    else if(e.children.length===0 && /^(×|✕|✖|x)$/i.test(t)) ok=true;
    if(ok){ e.setAttribute('data-vjc','1'); try{ e.click(); n++; }catch(x){} }
  }
  return n;
})()
"""

        /** Keeps any video or audio on the page silent. */
        private const val MUTE_JS = """
(function(){
  try{
    if(!window.__vjm){ window.__vjm=1; var p=HTMLMediaElement.prototype.play;
      HTMLMediaElement.prototype.play=function(){ this.muted=true; this.volume=0; return p.apply(this,arguments); }; }
    [].forEach.call(document.querySelectorAll('video,audio'),function(m){ m.muted=true; m.volume=0; });
  }catch(e){}
  return 'ok';
})()
"""

        /** Reads the page and reports: result / adblock / form + captcha state. */
        private const val JS_PROBE = """
(function(){
  function vis(e){ if(!e) return false; var r=e.getBoundingClientRect(); var s=getComputedStyle(e);
    return r.width>0 && r.height>0 && s.visibility!=='hidden' && s.display!=='none'; }
  var body=(document.body&&document.body.innerText)||'';
  var toks=[];
  if(document.body){
    var w=document.createTreeWalker(document.body,NodeFilter.SHOW_ELEMENT|NodeFilter.SHOW_TEXT,null,false);
    var n;
    while((n=w.nextNode())){
      if(n.nodeType===3){
        var pt=n.parentNode&&n.parentNode.tagName;
        if(pt==='SCRIPT'||pt==='STYLE'||pt==='NOSCRIPT') continue;
        var s=(n.nodeValue||'').replace(/\s+/g,' ').trim();
        if(s) toks.push(['t',s]);
      } else if(n.tagName==='INPUT'){
        var ty=(n.type||'text').toLowerCase();
        if(ty==='text'||ty==='password'||ty==='email') toks.push(['i',n.value||'']);
      }
    }
  }
  function inputAfter(label){
    for(var i=0;i<toks.length;i++){
      if(toks[i][0]==='t' && toks[i][1].toLowerCase().indexOf(label)===0){
        for(var j=i+1;j<toks.length && j<i+4;j++){ if(toks[j][0]==='i') return toks[j][1]; }
      }
    }
    return '';
  }
  var o={stage:'other'};
  var user=inputAfter('username ssh'), pass=inputAfter('password ssh');
  if(user && pass){
    o.stage='result'; o.user=user; o.pass=pass;
    o.host=inputAfter('server host ssh'); o.pub=inputAfter('public key');
    var m=body.match(/Name Server\s*\(NS\)\s*:\s*([A-Za-z0-9.\-]+)/i); o.ns=m?m[1]:'';
    var e=body.match(/Expiration Date\s*:\s*(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})/i); o.exp=e?e[1]:'';
    return o;
  }
  if(/hold up/i.test(body) && /ad ?block|adguard/i.test(body)){ o.stage='adblock'; return o; }
  var ins=[].slice.call(document.querySelectorAll('input')).filter(function(e){
    var t=(e.type||'text').toLowerCase();
    return (t==='text'||t==='password') && !e.readOnly && vis(e); });
  o.stage='form'; o.hasInputs=ins.length>=2;
  var ts=document.querySelector('input[name="cf-turnstile-response"]');
  var tsEl=document.querySelector('.cf-turnstile,iframe[src*="challenges.cloudflare.com"]');
  o.ts = ts ? (ts.value ? 'ok' : 'wait') : (tsEl ? 'wait' : 'none');
  var hcr=document.querySelector('textarea[name="h-captcha-response"],input[name="h-captcha-response"]');
  var hcf=[].slice.call(document.querySelectorAll('iframe[src*="hcaptcha.com"]')).filter(vis);
  o.hcDone=!!(hcr && hcr.value);
  o.hc=hcf.length>0 && !o.hcDone;
  var err='';
  var lines=body.split('\n');
  for(var k=0;k<lines.length;k++){
    var L=lines[k].trim();
    if(L.length>3 && L.length<200 && /(server (is )?full|too many|already (exist|taken|used)|not available|limit reached|wait \d+ minute|please wait \d+|try again (in|after) \d+|failed to create)/i.test(L)){ err=L; break; }
  }
  o.err=err;
  var al='';
  var ael=[].slice.call(document.querySelectorAll('.alert-danger,.alert-warning,.text-danger,[role="alert"],.invalid-feedback')).filter(vis);
  for(var q=0;q<ael.length;q++){ var at=(ael[q].innerText||'').replace(/\s+/g,' ').trim(); if(at.length>2&&at.length<200){ al=at; break; } }
  o.alert=al;
  o.gate=/view a short ad/i.test(body);
  return o;
})()
"""
    }
}
