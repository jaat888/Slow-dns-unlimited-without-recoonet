package app.sd
import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
  private val h = Handler(Looper.getMainLooper())
  private lateinit var P: SharedPreferences
  private var dark = true

  private val bg get() = if (dark) 0xFF000000.toInt() else 0xFFF2F2F2.toInt()
  private val cardC get() = if (dark) 0xFF141414.toInt() else 0xFFFFFFFF.toInt()
  private val fieldC get() = if (dark) 0xFF222222.toInt() else 0xFFE8E8E8.toInt()
  private val fg get() = if (dark) 0xFFEDEDED.toInt() else 0xFF111111.toInt()
  private val sub get() = if (dark) 0xFF8A8A8A.toInt() else 0xFF666666.toInt()
  private val acc = 0xFF00C853.toInt()
  private val red = 0xFFD32F2F.toInt()

  private class Acc(val v: LinearLayout, val title: TextView, val ns: EditText, val pk: EditText, val us: EditText,
                    val pw: EditText, val dns: EditText, val n: Spinner, val sw: Switch, val ctl: List<View>)

  private val accs = mutableListOf<Acc>()
  private lateinit var accBox: LinearLayout
  private lateinit var statusTv: TextView
  private lateinit var hintTv: TextView
  private lateinit var startB: Button
  private lateinit var stopB: Button
  private lateinit var addB: Button
  private lateinit var logTv: TextView
  private lateinit var logSv: ScrollView
  private lateinit var pages: List<View>
  private lateinit var navs: List<Button>
  private var cur = 0
  private var rev = false
  private var lastLog = ""
  private lateinit var upBig: TextView; private lateinit var upSm: TextView
  private lateinit var dnBig: TextView; private lateinit var dnSm: TextView
  private lateinit var coBig: TextView; private lateinit var coSm: TextView
  private lateinit var tmBig: TextView; private lateinit var tmSm: TextView
  private var lastT = 0L; private var lastU = 0L; private var lastD = 0L

  private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
  private fun rr(c: Int, r: Int = 12) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
  private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
  private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

  private fun tv(t: String, s: Float = 14f, c: Int = fg, bold: Boolean = false) = TextView(this).apply {
    text = t; textSize = s; setTextColor(c); if (bold) setTypeface(typeface, Typeface.BOLD)
  }

  private fun et(hint: String, v: String = "") = EditText(this).apply {
    this.hint = hint; setText(v); setSingleLine(); setTextColor(fg); setHintTextColor(sub); textSize = 14f
    background = rr(fieldC, 8); setPadding(dp(12), dp(10), dp(12), dp(10)); layoutParams = lp(8)
  }

  private fun btn(t: String, c: Int = acc, f: () -> Unit) = Button(this).apply {
    text = t; isAllCaps = false; setTextColor(if (c == acc) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    background = rr(c, 10); setOnClickListener { f() }
    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(6), dp(3), 0) }
  }

  private fun row(vararg v: View) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; v.forEach { addView(it) } }

  // ---------- Home ----------
  private fun addCard(a: JSONObject?, at: Int = accs.size) {
    val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rr(cardC); setPadding(dp(14), dp(12), dp(14), dp(12)); layoutParams = lp(12) }
    val title = tv("", 16f, fg, true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
    val sw = Switch(this).apply { isChecked = a?.optBoolean("on", true) ?: true }
    val ns = et("NS domain", a?.optString("ns") ?: "")
    val pk = et("Public key", a?.optString("pk") ?: "")
    val us = et("User", a?.optString("u") ?: "")
    val pw = et("Password", a?.optString("p") ?: "")
    val dn = et("UDP DNS (jaise 8.8.8.8 ya 1.1.1.1:53)", a?.optString("d") ?: "")
    val list = (1..20).toList()
    val n = Spinner(this).apply {
      adapter = object : ArrayAdapter<Int>(this@MainActivity, android.R.layout.simple_spinner_item, list) {
        override fun getView(p: Int, v: View?, g: ViewGroup): View = (super.getView(p, v, g) as TextView).also { it.setTextColor(fg) }
        override fun getDropDownView(p: Int, v: View?, g: ViewGroup): View =
          (super.getDropDownView(p, v, g) as TextView).also { it.setTextColor(fg); it.setBackgroundColor(cardC); it.setPadding(dp(16), dp(10), dp(16), dp(10)) }
      }
      setSelection(((a?.optInt("n", 1) ?: 1).coerceIn(1, 20)) - 1)
    }
    lateinit var self: Acc
    val clone = btn("Clone", 0xFF2962FF.toInt()) {
      val j = js(self); addCard(j, accs.indexOf(self) + 1); renumber(); save()
      toast("Clone ban gaya - ab uska DNS badlo")
    }
    val del = btn("Delete", red) { accBox.removeView(c); accs.remove(self); renumber(); save() }
    c.addView(row(title, sw))
    listOf(ns, pk, us, pw, dn).forEach { c.addView(it) }
    c.addView(row(tv("Tunnels (1-20):", 14f, sub).apply { setPadding(0, 0, dp(12), 0) }, n).apply {
      gravity = android.view.Gravity.CENTER_VERTICAL; layoutParams = lp(10) })
    c.addView(row(clone, del))
    self = Acc(c, title, ns, pk, us, pw, dn, n, sw, listOf(ns, pk, us, pw, dn, n, clone, del))
    sw.setOnCheckedChangeListener { v, ck ->
      if (VpnSvc.running && !rev) { rev = true; v.isChecked = !ck; rev = false; toast("Pehle VPN band karo (STOP dabao), phir ON/OFF karo") }
    }
    accBox.addView(c, at); accs.add(at, self); renumber()
  }

  private fun renumber() { accs.forEachIndexed { i, a -> a.title.text = "Account ${i + 1}" } }

  private fun js(a: Acc) = JSONObject().put("ns", a.ns.text.toString().trim()).put("pk", a.pk.text.toString().trim())
    .put("u", a.us.text.toString().trim()).put("p", a.pw.text.toString()).put("d", a.dns.text.toString().trim())
    .put("n", a.n.selectedItem as Int).put("on", a.sw.isChecked)

  private fun save() {
    val arr = JSONArray(); accs.forEach { arr.put(js(it)) }
    P.edit().putString("acc2", arr.toString()).apply()
  }

  private fun valid(a: Acc) = listOf(a.ns, a.pk, a.us, a.pw, a.dns).none { it.text.isNullOrBlank() }

  private fun askBackground() {
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
      requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
    if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
      try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
  }

  private fun start() {
    save()
    if (accs.none { it.sw.isChecked && valid(it) }) { toast("Kam se kam ek account ON karo aur poora bharo"); return }
    accs.forEachIndexed { i, a -> if (a.sw.isChecked && !valid(a)) toast("Account ${i + 1} adhura hai, skip hoga") }
    askBackground()
    val i = VpnService.prepare(this)
    if (i != null) startActivityForResult(i, 1) else begin()
  }

  private fun logo(sz: Int) = ImageView(this).apply {
    setImageResource(if (dark) R.drawable.fg_dark else R.drawable.fg_light)
    background = GradientDrawable().apply { setColor(if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()); cornerRadius = dp(sz / 4).toFloat()
      setStroke(dp(1), if (dark) 0xFF2A2A2A.toInt() else 0xFFDDDDDD.toInt()) }
    layoutParams = LinearLayout.LayoutParams(dp(sz), dp(sz))
  }

  private fun statCard(t: String, big: TextView, small: TextView) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; background = rr(cardC); setPadding(dp(14), dp(14), dp(14), dp(14))
    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(8), dp(4), 0) }
    addView(tv(t, 13f, sub)); addView(big); addView(small)
  }

  private fun buildHome(): View {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(24)) }
    statusTv = tv("", 15f, acc, true).apply { layoutParams = lp(16) }
    startB = btn("START") { start() }
    stopB = btn("STOP", red) { startService(Intent(this, VpnSvc::class.java).setAction("stop")) }
    fun big() = tv("0", 22f, fg, true); fun sm() = tv("", 12f, sub)
    upBig = big(); upSm = sm(); dnBig = big(); dnSm = sm(); coBig = big(); coSm = sm(); tmBig = big(); tmSm = sm()
    val head = row(logo(44), tv("Mollad DNS", 22f, fg, true).apply { setPadding(dp(12), 0, 0, 0) }).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
    col.addView(head)
    col.addView(row(statCard("Upload", upBig, upSm), statCard("Download", dnBig, dnSm)))
    col.addView(row(statCard("Connections", coBig, coSm), statCard("Time", tmBig, tmSm)))
    col.addView(statusTv); col.addView(row(startB, stopB))
    return ScrollView(this).apply { addView(col) }
  }

  private fun buildAccounts(col: LinearLayout) {
    hintTv = tv("", 12f, sub)
    addB = btn("+ Add account", 0xFF2962FF.toInt()) { addCard(null); save() }.apply { layoutParams = lp(12) }
    accBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(tv("Accounts", 22f, fg, true)); col.addView(hintTv); col.addView(accBox); col.addView(addB)
    val saved = try { JSONArray(P.getString("acc2", "[]")) } catch (e: Exception) { JSONArray() }
    if (saved.length() == 0) addCard(null) else for (i in 0 until saved.length()) addCard(saved.getJSONObject(i))
  }

  // ---------- Logs ----------
  private fun buildLogs(): View {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(8)) }
    logTv = tv("", 11f, fg).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
    logSv = ScrollView(this).apply { addView(logTv); layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) } }
    col.addView(tv("Logs", 22f, fg, true))
    col.addView(row(
      btn("Clear", red) { LogBus.clear(); lastLog = ""; logTv.text = "" },
      btn("Copy") { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("log", LogBus.text())); toast("Copy ho gaya") }))
    col.addView(logSv)
    return col
  }

  // ---------- Settings ----------
  private fun buildSettings(): View {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(24)) }
    buildAccounts(col)
    col.addView(tv("Settings", 22f, fg, true).apply { layoutParams = lp(32) })
    col.addView(tv("Connection timeout (seconds)", 15f, fg, true).apply { layoutParams = lp(12) })
    col.addView(tv("Ek tunnel itne second tak connect hone ka wait karega, phir fail maan ke dobara try karega. Jo account der se connect hota hai uske liye zyada rakho (5 - 300).", 12f, sub))
    val to = et("Timeout", P.getInt("to", 20).toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
    col.addView(to)
    col.addView(row(btn("Save timeout") {
      val v = (to.text.toString().toIntOrNull() ?: 20).coerceIn(5, 300)
      to.setText(v.toString()); P.edit().putInt("to", v).apply(); toast("Timeout $v sec save hua (agle connect se lagu)")
    }))
    col.addView(tv("Max speed (Mbps)", 15f, fg, true).apply { layoutParams = lp(24) })
    col.addView(tv("Switch ON karke 1 se 100 Mbps tak limit lagao. Upload aur download dono is se zyada nahi jayenge. OFF = bina limit.", 12f, sub))
    val lim = Switch(this).apply { isChecked = P.getBoolean("lim", false); text = "Speed limit ON"; setTextColor(fg); layoutParams = lp(8) }
    val sp = et("Mbps (1-100)", P.getInt("mbps", 10).toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
    col.addView(lim); col.addView(sp)
    col.addView(row(btn("Save speed") {
      val v = (sp.text.toString().toIntOrNull() ?: 10).coerceIn(1, 100)
      sp.setText(v.toString()); P.edit().putInt("mbps", v).putBoolean("lim", lim.isChecked).apply(); Limit.load(P)
      toast(if (lim.isChecked) "Max speed $v Mbps lagu" else "Speed limit OFF")
    }))
    col.addView(tv("Theme", 15f, fg, true).apply { layoutParams = lp(24) })
    col.addView(row(
      btn("Black (Dark)") { P.edit().putBoolean("dark", true).apply(); icon(true); save(); recreate() },
      btn("Light", 0xFF607D8B.toInt()) { P.edit().putBoolean("dark", false).apply(); icon(false); save(); recreate() }))
    col.addView(tv("Logo bhi theme ke saath badalta hai (home screen icon kuch second mein badalta hai).", 12f, sub))
    col.addView(tv("Background permission", 15f, fg, true).apply { layoutParams = lp(24) })
    col.addView(row(btn("Battery / Notification permission") { askBackground() }))
    return ScrollView(this).apply { addView(col) }
  }

  private fun icon(d: Boolean) {
    val pm = packageManager
    fun set(n: String, en: Boolean) = pm.setComponentEnabledSetting(android.content.ComponentName(this, "app.sd.$n"),
      if (en) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    try { set(if (d) "DarkAlias" else "LightAlias", true); set(if (d) "LightAlias" else "DarkAlias", false) } catch (e: Exception) {}
  }

  // ---------- Info ----------
  private fun buildInfo(): View {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(24), dp(16), dp(24)) }
    col.addView(logo(88).apply { layoutParams = LinearLayout.LayoutParams(dp(88), dp(88)) })
    col.addView(tv("Mollad DNS", 26f, fg, true).apply { layoutParams = lp(12) })
    col.addView(tv("Mollad Jaat", 28f, acc, true))
    col.addView(tv("Developed by Claude Sonnet", 14f, sub))
    col.addView(tv("Telegram: @mollad_jaat", 16f, 0xFF29B6F6.toInt(), true).apply {
      paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG; layoutParams = lp(10); setPadding(0, dp(6), 0, dp(6))
      setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/mollad_jaat"))) } catch (e: Exception) { toast("Telegram nahi khula") } }
    })
    val t = """
Mollad DNS kya hai?
DNS tunnel (dnstt) ke through SSH connect karke poore phone ka internet VPN ki tarah chalata hai. Chhota aur halka app hai.

Account kaise banayein
- Settings mein Accounts section hai. Wahan NS domain, Public key, User, Password aur ek UDP DNS (jaise 8.8.8.8) bharo.
- Tunnels (1-20): ye account utni baar ek saath tunnel banayega. Zyada tunnel = zyada speed, par zyada load.
- Har account ke upar ON/OFF switch hai. START dabane par sirf ON wale accounts chalenge, har ek apne number jitne tunnel banayega.

Doosra DNS use karna ho to
Ek account mein sirf ek UDP DNS hota hai. Doosra DNS (jaise 1.1.1.1) chahiye to account ka Clone dabao. Clone mein sab data copy hota hai, bas DNS badal do. Clone ka apna switch aur apna tunnel number hota hai.

START dabane par kya hota hai
- Saare tunnel ek saath connect hona shuru karte hain.
- Jaise hi pehla tunnel connect hota hai VPN chalu ho jata hai, baaki tunnel background mein ek-ek karke judte rehte hain.
- Agar kisi account ka user/password galat hai ya connection toot jata hai to wo tunnel skip hoke baaki chalte rehte hain. Wo tunnel background mein bina limit ke baar baar try karta rehta hai, kabhi band nahi hota.
- Resolver (DNS) kabhi apne aap nahi badalta.

ON/OFF aur edit
Account badalne ya switch ON/OFF karne ke liye pehle STOP dabake VPN band karo, phir badlo, phir START karo.

Home
Home par Upload, Download speed (Mbps) aur total data, kitne tunnel connected hain (jaise 3 / 5), aur VPN kitni der se chal raha hai dikhta hai. START / STOP bhi yahin hai.

Settings
- Accounts: sab account yahin banate aur badalte hain.
- Max speed: 1 se 100 Mbps tak limit lagao, upload aur download dono par. OFF rakho to bina limit.
- Connection timeout: ek tunnel kitni der connect hone ka wait kare. Jo server der se connect hota hai uske liye zyada rakho.
- Theme: Black (Dark) ya Light.
- Background permission: battery optimization band karne ki aur notification ki permission. Isse app background mein chalta rahega.

Logs
Logs tab mein har tunnel ka status dikhta hai (A1#2 = Account 1 ka tunnel 2). Connect, fail aur retry yahin dikhte hain. Copy se log copy kar sakte ho.

Tips
- Connect na ho to Logs dekho: 'Auth fail' = user/password galat, timeout = timeout badhao ya DNS badlo.
- Pehle 1 account, 1 tunnel se test karo, phir number badhao.
"""
    col.addView(tv(t.trim(), 14f, fg).apply { layoutParams = lp(16); setLineSpacing(0f, 1.15f) })
    return ScrollView(this).apply { addView(col) }
  }

  private fun show(i: Int) {
    cur = i
    pages.forEachIndexed { k, p -> p.visibility = if (k == i) View.VISIBLE else View.GONE }
    navs.forEachIndexed { k, b -> b.setTextColor(if (k == i) acc else sub) }
    refresh()
  }

  private fun fmtSize(b: Long): String = when {
    b < 1_000_000 -> "%.0f kB".format(b / 1000.0)
    b < 1_000_000_000 -> "%.1f MB".format(b / 1e6)
    else -> "%.2f GB".format(b / 1e9)
  }

  private fun refresh() {
    val r = VpnSvc.running
    accs.forEach { a -> a.ctl.forEach { it.isEnabled = !r } }
    addB.isEnabled = !r; startB.isEnabled = !r; stopB.isEnabled = r
    statusTv.text = if (r) "Chal raha hai" else "VPN band hai"
    hintTv.text = if (r) "Account ya ON/OFF badalne ke liye pehle STOP karo" else ""
    val now = System.currentTimeMillis(); val u = Stats.up.get(); val d = Stats.down.get()
    if (now - lastT >= 800) {
      val dt = (now - lastT) / 1000.0
      val us = if (r && lastT > 0 && u >= lastU) (u - lastU) * 8 / 1e6 / dt else 0.0
      val ds = if (r && lastT > 0 && d >= lastD) (d - lastD) * 8 / 1e6 / dt else 0.0
      upBig.text = "%.2f Mbps".format(us); dnBig.text = "%.2f Mbps".format(ds)
      lastT = now; lastU = u; lastD = d
    }
    upSm.text = fmtSize(u); dnSm.text = fmtSize(d)
    coBig.text = if (r) "${VpnSvc.connected()} / ${VpnSvc.total}" else "0 / 0"; coSm.text = "tunnels"
    if (r) { val sec = (now - Stats.since) / 1000; tmBig.text = "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60); tmSm.text = "chal raha" }
    else { tmBig.text = "--"; tmSm.text = "band" }
    if (cur == 1) {
      val t = LogBus.text()
      if (t != lastLog) { lastLog = t; logTv.text = t; logSv.post { logSv.fullScroll(View.FOCUS_DOWN) } }
    }
    h.removeCallbacksAndMessages(null)
    h.postDelayed({ refresh() }, 1000)
  }

  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    P = getSharedPreferences("a", 0)
    dark = P.getBoolean("dark", true)
    window.statusBarColor = bg; window.navigationBarColor = bg
    if (!dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    val hv = buildHome(); val lv = buildLogs(); val sv = buildSettings(); val iv = buildInfo()
    pages = listOf(hv, lv, sv, iv)
    val frame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f); pages.forEach { addView(it) } }
    navs = listOf("Home", "Logs", "Settings", "Info").mapIndexed { i, n ->
      Button(this).apply { text = n; isAllCaps = false; background = null; setOnClickListener { show(i) }
        layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f) }
    }
    val nav = LinearLayout(this).apply { setBackgroundColor(cardC); navs.forEach { addView(it) } }
    setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); addView(frame); addView(nav) })
    show(0)
    askBackground()
  }

  override fun onPause() { super.onPause(); save() }
  override fun onDestroy() { super.onDestroy(); h.removeCallbacksAndMessages(null) }

  private fun begin() { startForegroundService(Intent(this, VpnSvc::class.java)) }
  override fun onActivityResult(r: Int, c: Int, d: Intent?) { if (c == RESULT_OK) begin() }
}
