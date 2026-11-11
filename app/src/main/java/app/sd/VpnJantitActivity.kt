package app.sd

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.ViewOutlineProvider
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Random
import java.util.TimeZone

/**
 * Creates a free SSH account on VPNJantit.
 *
 * - Runs in a SEPARATE PROCESS (:vj), so if the WebView/ads crash, the VPN and the main app stay alive.
 * - The WebView loads the site exactly like a normal browser (ads load too, nothing is blocked).
 * - It waits for the light Cloudflare check to show "Success" on its own, and only then presses Create.
 * - If a real captcha (hCaptcha / interactive Cloudflare) appears, only that part is shown on screen and the user taps it.
 * - There is no trick to break the captcha.
 */
class VpnJantitActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var holder: FrameLayout
    private lateinit var status: TextView
    private lateinit var root: FrameLayout
    private lateinit var card: LinearLayout
    private val ui = Handler(Looper.getMainLooper())

    private var server = "in6"
    private var user = ""
    private var pass = ""

    private var startedAt = 0L
    private var filledAt = 0L
    private var clickedAt = 0L
    private var clicks = 0
    private var revealed = false
    private var done = false
    private var busy = false
    private var gateOpen = false
    private var userHiddenUntil = 0L

    private val poll = object : Runnable {
        override fun run() {
            if (done) return
            tick()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Required for a WebView in a separate process (Android 9+), otherwise it crashes
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) WebView.setDataDirectorySuffix("vj")
        } catch (e: Exception) { }
        if (intent.getBooleanExtra(VpnJantit.EXTRA_ONLY_CLEAR, false)) {
            // Only clear the cookies (the WebView data lives in the :vj process, so it is cleared from here)
            done = true
            try {
                val cm = CookieManager.getInstance()
                cm.removeAllCookies(null); cm.flush()
                android.webkit.WebStorage.getInstance().deleteAllData()
            } catch (e: Exception) { }
            setResult(RESULT_CANCELED)
            ui.postDelayed({ finish() }, 800)
            return
        }
        server = intent.getStringExtra(VpnJantit.EXTRA_SERVER)?.takeIf { it.isNotBlank() } ?: "in6"
        // Background mode: no visible window and touches pass through, until a captcha needs the user
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        overridePendingTransition(0, 0)
        user = randomName()
        pass = randomPass()
        startedAt = SystemClock.elapsedRealtime()

        buildUi()
        setupWebView()

        if (intent.getBooleanExtra(VpnJantit.EXTRA_CLEAR_COOKIES, false)) {
            CookieManager.getInstance().removeAllCookies(null)
        }
        web.loadUrl(String.format(VpnJantit.CREATE_URL, server))
        ui.postDelayed(poll, 1500)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rr(c: Int, r: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(c); cornerRadius = dp(r).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(0)

        // The WebView sits in its own rounded box: it is hidden (alpha 0) and only shown when a captcha appears.
        holder = FrameLayout(this)
        holder.background = rr(Color.WHITE, 16)
        holder.clipToOutline = true
        holder.outlineProvider = ViewOutlineProvider.BACKGROUND
        holder.alpha = 0f
        web = WebView(this)
        holder.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val hp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420), Gravity.BOTTOM)
        hp.setMargins(dp(16), 0, dp(16), dp(24))
        root.addView(holder, hp)

        card = LinearLayout(this)
        card.visibility = View.GONE
        card.orientation = LinearLayout.VERTICAL
        card.background = rr(0xFF121821.toInt(), 20, 0xFF222C3A.toInt())
        card.setPadding(dp(18), dp(16), dp(18), dp(16))

        val serverName = VpnJantit.SERVERS.firstOrNull { it.code == server }?.label ?: server
        val title = TextView(this)
        title.text = "VPNJantit account"
        title.textSize = 18f
        title.setTextColor(0xFFF1F5F9.toInt())
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        card.addView(title)
        val srv = TextView(this)
        srv.text = serverName
        srv.textSize = 12f
        srv.setTextColor(0xFF8B97A6.toInt())
        card.addView(srv)

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.isIndeterminate = true
        bar.indeterminateTintList = ColorStateList.valueOf(0xFF2DD4A7.toInt())
        card.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(14) })

        status = TextView(this)
        status.textSize = 15f
        status.setTextColor(0xFFE5E7EB.toInt())
        status.text = "Creating your VPNJantit account..."
        card.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        val hideB = Button(this)
        hideB.text = "Continue in background"
        hideB.isAllCaps = false
        hideB.stateListAnimator = null
        hideB.setTextColor(Color.WHITE)
        hideB.background = rr(0xFF2563EB.toInt(), 12)
        hideB.setOnClickListener { hideByUser() }
        card.addView(hideB, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        val cancel = Button(this)
        cancel.text = "Cancel"
        cancel.isAllCaps = false
        cancel.stateListAnimator = null
        cancel.setTextColor(Color.WHITE)
        cancel.background = rr(0xFF1B2330.toInt(), 12)
        cancel.setOnClickListener { finishCancelled() }
        card.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        val cp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        cp.setMargins(dp(16), dp(56), dp(16), 0)
        root.addView(card, cp)

        setContentView(root)
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

        // Block touches while hidden (so an ad is not opened by accident)
        web.setOnTouchListener { _, _ -> !revealed }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Avoid ad redirects: the main page must stay on vpnjantit.com
                if (!request.isForMainFrame) return false
                val host = request.url.host ?: return true
                return !(host == "vpnjantit.com" || host.endsWith(".vpnjantit.com"))
            }

            // Videos (ads) are not downloaded: saves data and removes the sound. Scripts and images stay untouched,
            // because the site refuses to work when it thinks an ad-blocker is running.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                val u = request.url.toString().substringBefore('?').lowercase()
                if (u.endsWith(".mp4") || u.endsWith(".webm") || u.endsWith(".m3u8") || u.endsWith(".mpd") || u.endsWith(".mov")) {
                    return android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
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
        if (now - startedAt > 240_000L) {
            finishFail("Timed out: the account was not created", 0L)
            return
        }
        run(MUTE_JS) { }
        run(DISMISS_JS) { }
        if (revealed && !gateOpen) run(RECENTER_JS) { }
        if (busy) return
        busy = true
        web.evaluateJavascript(JS_PROBE) { raw ->
            busy = false
            onProbe(raw)
        }
    }

    private fun onProbe(raw: String?) {
        if (done || raw.isNullOrEmpty() || raw == "null") return
        val o = try { JSONObject(raw) } catch (e: Exception) { return }
        when (o.optString("stage")) {
            "result" -> finishFromResult(o)
            "adblock" -> finishFail("The site detected an ad-blocker. Turn off DNS/ad-blocking and try again.", 0L)
            "form" -> handleForm(o)
            else -> {}
        }
    }

    private fun handleForm(o: JSONObject) {
        val now = SystemClock.elapsedRealtime()
        val hasInputs = o.optBoolean("hasInputs")
        val ts = o.optString("ts")
        val hc = o.optBoolean("hc")
        val hcDone = o.optBoolean("hcDone")
        val err = o.optString("err")

        if (err.isNotBlank() && (clicks > 0 || !hasInputs)) {
            val cooldown = Regex("minute|wait|too (many|fast|soon)|limit", RegexOption.IGNORE_CASE).containsMatchIn(err)
            finishFail(err, if (cooldown) VpnJantit.COOLDOWN_MS else 0L)
            return
        }
        if (o.optBoolean("gate")) { revealGate(); return }
        if (gateOpen) { gateOpen = false; hide() }
        if (!hasInputs) return

        if (filledAt == 0L) {
            run(fillJs(user, pass)) { }
            filledAt = now
            setStatus("Form filled in, waiting for the Cloudflare check...")
            return
        }

        val canClick = ts == "ok" || hcDone || (ts == "none" && !hc && now - filledAt > 5000L)
        if (canClick) {
            if (clicks == 0 || (hcDone && clicks < 2)) {
                clicks++
                clickedAt = now
                run(CLICK_JS) { }
                hide()
                setStatus("Creating the account...")
            } else if (clicks > 0 && now - clickedAt > 40_000L) {
                finishFail("The account was not created (no response from the site)", 0L)
            }
            return
        }

        // Cloudflare did not reach "Success" or a real captcha appeared -> show only that part to the user
        if (hc || now - filledAt > 12_000L) reveal()
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
        val exp = parseExpiry(o.optString("exp"))
        val data = Intent()
            .putExtra(VpnJantit.EXTRA_SERVER, server)
            .putExtra(VpnJantit.R_HOST, host)
            .putExtra(VpnJantit.R_NS, ns)
            .putExtra(VpnJantit.R_PUBKEY, pub)
            .putExtra(VpnJantit.R_USER, u)
            .putExtra(VpnJantit.R_PASS, p)
            .putExtra(VpnJantit.R_EXPIRES, exp)
        done = true
        setResult(RESULT_OK, data)
        finish()
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

    // ───────────────────────── UI helpers ─────────────────────────

    private fun setStatus(t: String) { status.text = t }

    private fun showUi(msg: String) {
        revealed = true
        root.setBackgroundColor(0xE60A0E14.toInt()); card.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        holder.alpha = 1f
        setStatus(msg)
    }

    /** Only the captcha is shown: cookie banner, ads and the rest of the page are hidden. */
    private fun reveal() {
        if (revealed || SystemClock.elapsedRealtime() < userHiddenUntil) return
        showUi("Tap \"I am human\" below")
        run(CROP_JS) { }
        // The captcha widget may load later, so crop twice
        ui.postDelayed({ if (!done && revealed && !gateOpen) run(CROP_JS) { } }, 1500)
    }

    /** The site asks for a short ad before it continues: show the full page so the user can do it. */
    private fun revealGate() {
        if (revealed || SystemClock.elapsedRealtime() < userHiddenUntil) return
        gateOpen = true
        run(UNCROP_JS) { }
        showUi("The site asks you to view a short ad. Finish it and this continues by itself.")
    }

    private fun hide() {
        if (!revealed) return
        revealed = false
        run(UNCROP_JS) { }
        root.setBackgroundColor(0); card.visibility = View.GONE
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        holder.alpha = 0f
    }

    /** Back / "Continue in background": the window closes but the account keeps being created. */
    private fun hideByUser() {
        userHiddenUntil = SystemClock.elapsedRealtime() + 60_000L
        gateOpen = false
        hide()
        android.widget.Toast.makeText(this, "Continuing in the background", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun run(js: String, cb: (String?) -> Unit) {
        try { web.evaluateJavascript(js) { cb(it) } } catch (e: Exception) { }
    }

    private fun finishFail(msg: String, retryMs: Long) {
        if (done) return
        done = true
        val data = Intent().putExtra(VpnJantit.R_ERROR, msg).putExtra(VpnJantit.R_RETRY_MS, retryMs)
        setResult(RESULT_CANCELED, data)
        finish()
    }

    private fun finishCancelled() {
        if (done) return
        done = true
        setResult(RESULT_CANCELED)
        finish()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (revealed) hideByUser() else finishCancelled()
    }

    override fun onDestroy() {
        done = true
        ui.removeCallbacksAndMessages(null)
        try {
            holder.removeAllViews()
            web.stopLoading()
            web.destroy()
        } catch (e: Exception) { }
        super.onDestroy()
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

        private const val REVEAL_JS = """
(function(){
  var e=document.querySelector('iframe[src*="hcaptcha.com"],.h-captcha,.cf-turnstile,iframe[src*="challenges.cloudflare.com"]');
  if(e){ e.scrollIntoView({block:'center'}); }
  return 'ok';
})()
"""

        /** Hides everything except the captcha widgets (cookie banner, ads, pop-ups). */
        private const val CROP_JS = """
(function(){
  var st=document.getElementById('vj-crop');
  if(!st){ st=document.createElement('style'); st.id='vj-crop'; document.head.appendChild(st); }
  st.textContent='html,body{background:#fff !important;overflow:auto !important}'+
    'body *{visibility:hidden !important}'+
    '.h-captcha,.h-captcha *,.cf-turnstile,.cf-turnstile *,iframe[src*="hcaptcha.com"],iframe[src*="challenges.cloudflare.com"]{visibility:visible !important}'+
    '.h-captcha,.cf-turnstile{position:fixed !important;top:12px !important;left:50% !important;transform:translateX(-50%) !important;z-index:2147483647 !important;background:#fff !important}';
  var e=document.querySelector('iframe[src*="hcaptcha.com"],.h-captcha,.cf-turnstile,iframe[src*="challenges.cloudflare.com"]');
  if(e){ e.scrollIntoView({block:'center'}); }
  return 'ok';
})()
"""

        /** If the captcha moved completely out of view (page grew or shrank), bring it back to the middle. */
        private const val RECENTER_JS = """
(function(){
  var e=document.querySelector('iframe[src*="hcaptcha.com"],.h-captcha,.cf-turnstile,iframe[src*="challenges.cloudflare.com"]');
  if(!e) return 'none';
  var r=e.getBoundingClientRect();
  if(r.height>0 && (r.bottom<0 || r.top>window.innerHeight)) e.scrollIntoView({block:'center'});
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
    else if(e.children.length===0 && /^(\u00d7|\u2715|\u2716|x)$/i.test(t)) ok=true;
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
    if(L.length>3 && L.length<200 && /(server (is )?full|please wait|try again|too many|already (exist|taken|used)|not available|failed|limit reached|wait \d+ minute)/i.test(L)){ err=L; break; }
  }
  o.err=err;
  o.gate=/view a short ad/i.test(body);
  return o;
})()
"""
    }
}
