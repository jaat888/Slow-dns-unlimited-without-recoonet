package app.sd

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

/** Small line icons for the bottom navigation (drawn in code, no image files needed). */
internal class NavIcon(ctx: Context, private val kind: Int) : View(ctx) {
  var color = 0xFF8794A8.toInt()
  private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
  private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

  override fun onDraw(c: Canvas) {
    val w = width.toFloat(); val h = height.toFloat()
    p.color = color; p.strokeWidth = w / 11f
    when (kind) {
      0 -> {
        val path = Path()
        path.moveTo(w * .12f, h * .48f); path.lineTo(w * .5f, h * .14f); path.lineTo(w * .88f, h * .48f)
        path.moveTo(w * .24f, h * .42f); path.lineTo(w * .24f, h * .84f); path.lineTo(w * .76f, h * .84f); path.lineTo(w * .76f, h * .42f)
        c.drawPath(path, p)
      }
      1 -> {
        c.drawCircle(w * .5f, h * .32f, w * .17f, p)
        c.drawArc(RectF(w * .16f, h * .56f, w * .84f, h * 1.0f), 180f, 180f, false, p)
      }
      2 -> {
        c.drawLine(w * .18f, h * .26f, w * .82f, h * .26f, p)
        c.drawLine(w * .18f, h * .5f, w * .82f, h * .5f, p)
        c.drawLine(w * .18f, h * .74f, w * .6f, h * .74f, p)
      }
      3 -> {
        c.drawLine(w * .16f, h * .26f, w * .84f, h * .26f, p)
        c.drawLine(w * .16f, h * .5f, w * .84f, h * .5f, p)
        c.drawLine(w * .16f, h * .74f, w * .84f, h * .74f, p)
        p.style = Paint.Style.FILL
        c.drawCircle(w * .66f, h * .26f, w * .09f, p)
        c.drawCircle(w * .34f, h * .5f, w * .09f, p)
        c.drawCircle(w * .6f, h * .74f, w * .09f, p)
        p.style = Paint.Style.STROKE
      }
      else -> {
        c.drawCircle(w * .5f, h * .5f, w * .38f, p)
        t.color = color; t.textSize = w * .5f
        c.drawText("?", w * .5f, h * .5f + t.textSize * .35f, t)
      }
    }
  }
}

/** Power symbol for the big connect button. */
internal class PowerIcon(ctx: Context) : View(ctx) {
  var color = 0xFFFFFFFF.toInt()
  private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

  override fun onDraw(c: Canvas) {
    val w = width.toFloat(); val h = height.toFloat()
    p.color = color; p.strokeWidth = w / 10f
    val r = w * .34f
    c.drawArc(RectF(w / 2 - r, h / 2 - r + h * .04f, w / 2 + r, h / 2 + r + h * .04f), -50f, 280f, false, p)
    c.drawLine(w / 2, h * .12f, w / 2, h * .5f, p)
  }
}

class MainActivity : Activity() {
  private val h = Handler(Looper.getMainLooper())
  private lateinit var P: SharedPreferences
  private var dark = false

  // ---------- Theme ----------
  private val bg get() = if (dark) 0xFF0B1020.toInt() else 0xFFF4F7FC.toInt()
  private val cardC get() = if (dark) 0xFF141B30.toInt() else 0xFFFFFFFF.toInt()
  private val fieldC get() = if (dark) 0xFF1C2542.toInt() else 0xFFEEF2FC.toInt()
  private val strokeC get() = if (dark) 0xFF263052.toInt() else 0xFFE3E9F6.toInt()
  private val fg get() = if (dark) 0xFFEAF0FF.toInt() else 0xFF0F1630.toInt()
  private val sub get() = if (dark) 0xFF8E9ABB.toInt() else 0xFF6A7490.toInt()
  private val acc get() = if (dark) 0xFF3B82F6.toInt() else 0xFF2563EB.toInt()
  private val ink = 0xFFFFFFFF.toInt()
  private val red = 0xFFEF4444.toInt()
  private val amber = 0xFFF59E0B.toInt()
  private val deep = 0xFF1E3A8A.toInt()
  private val white = 0xFFFFFFFF.toInt()
  private val whiteDim = 0xCCFFFFFF.toInt()

  private class Acc(val v: LinearLayout, val title: TextView, val ns: EditText, val pk: EditText, val us: EditText,
                    val pw: EditText, val dns: EditText, val n: Spinner, val sw: Switch, val ctl: List<View>) {
    var role = ""; var srv = ""; var exp = 0L
    lateinit var info: TextView
    lateinit var sumT: TextView; lateinit var sumI: TextView; lateinit var sumS: TextView; lateinit var sumV: View
  }

  private val accs = mutableListOf<Acc>()
  private lateinit var accBox: LinearLayout
  private lateinit var detailHolder: LinearLayout
  private lateinit var accListV: View
  private lateinit var accDetailV: ScrollView
  private lateinit var statusTv: TextView
  private lateinit var subTv: TextView
  private lateinit var hintTv: TextView
  private lateinit var summaryTv: TextView
  private lateinit var heroBtn: FrameLayout
  private lateinit var heroCard: LinearLayout
  private lateinit var statePill: TextView
  private lateinit var connectLbl: TextView
  private lateinit var powerIcon: PowerIcon
  private val settingCommits = ArrayList<() -> Unit>()
  private val helpNotes = ArrayList<Pair<String, String>>()
  private var heroState = -1
  private var pending: Boolean? = null
  private var pendingT = 0L
  private var lastTap = 0L
  private lateinit var addB: Button
  private lateinit var logTv: TextView
  private lateinit var logSv: ScrollView
  private lateinit var pages: List<View>
  private lateinit var navIcons: List<NavIcon>
  private lateinit var navLabels: List<TextView>
  private var cur = 0
  private var rev = false
  private var lastLog = ""
  private lateinit var upBig: TextView; private lateinit var upSm: TextView
  private lateinit var dnBig: TextView; private lateinit var dnSm: TextView
  private lateinit var coBig: TextView; private lateinit var coSm: TextView
  private lateinit var tmBig: TextView; private lateinit var tmSm: TextView
  private var lastUs = 0.0; private var lastDs = 0.0
  private var lastT = 0L; private var lastU = 0L; private var lastD = 0L

  // ---------- UI helpers ----------
  private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
  private fun rr(c: Int, r: Int = 12, stroke: Int = 0) = GradientDrawable().apply {
    setColor(c); cornerRadius = dp(r).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
  }
  private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
  private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

  private fun tv(t: String, s: Float = 14f, c: Int = fg, bold: Boolean = false) = TextView(this).apply {
    text = t; textSize = s; setTextColor(c); if (bold) setTypeface(typeface, Typeface.BOLD)
  }

  private fun et(hint: String, v: String = "") = EditText(this).apply {
    this.hint = hint; setText(v); setSingleLine(); setTextColor(fg); setHintTextColor(sub); textSize = 14f
    background = rr(fieldC, 14, strokeC); setPadding(dp(16), dp(12), dp(16), dp(12)); layoutParams = lp(6)
  }

  private fun pill(t: String, bgc: Int, fgc: Int, f: () -> Unit) = Button(this).apply {
    text = t; isAllCaps = false; setTextColor(fgc); textSize = 14f; setTypeface(typeface, Typeface.BOLD)
    background = rr(bgc, 16); stateListAnimator = null; minHeight = 0; minimumHeight = 0
    setPadding(dp(12), 0, dp(12), 0); setOnClickListener { f() }
    layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(3), dp(8), dp(3), 0) }
  }

  private fun btn(t: String, c: Int = acc, f: () -> Unit) =
    pill(t, c, if (c == acc) ink else if (c == fieldC) fg else 0xFFFFFFFF.toInt(), f)

  private fun tint(s: Switch): Switch {
    val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    s.thumbTintList = ColorStateList(st, intArrayOf(acc, if (dark) 0xFF9AA5B1.toInt() else 0xFFFFFFFF.toInt()))
    s.trackTintList = ColorStateList(st, intArrayOf((acc and 0x00FFFFFF) or (0x80 shl 24), if (dark) 0xFF3A4654.toInt() else 0xFFCBD5E1.toInt()))
    return s
  }

  private fun row(vararg v: View) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; v.forEach { addView(it) } }

  private fun card(top: Int = 12) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; background = rr(cardC, 22, strokeC); elevation = dp(2).toFloat()
    setPadding(dp(18), dp(16), dp(18), dp(18)); layoutParams = lp(top)
  }

  private fun secTitle(t: String) = tv(t, 16f, fg, true).apply {
    val bar = GradientDrawable().apply { setColor(acc); cornerRadius = dp(2).toFloat(); setSize(dp(4), dp(18)) }
    setCompoundDrawablesWithIntrinsicBounds(bar, null, null, null); compoundDrawablePadding = dp(10)
  }

  private fun tintBg(c: Int, a: Int = 0x26) = (c and 0x00FFFFFF) or (a shl 24)

  private fun badge(g: String, size: Int = 30, c: Int = acc) = TextView(this).apply {
    text = g; gravity = Gravity.CENTER; setTextColor(c); textSize = if (size >= 40) 13f else 14f; setTypeface(typeface, Typeface.BOLD)
    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tintBg(c)) }
    layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
  }

  private fun divider() = View(this).apply {
    setBackgroundColor(strokeC); layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12) }
  }

  private fun pageCol() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(22), dp(16), dp(28)) }

  private fun head(col: LinearLayout, title: String, subtitle: String) {
    col.addView(tv(title, 26f, fg, true))
    col.addView(tv(subtitle, 13f, sub).apply { layoutParams = lp(2) })
  }

  private fun logo(sz: Int) = ImageView(this).apply {
    setImageResource(if (dark) R.drawable.fg_dark else R.drawable.fg_light)
    background = GradientDrawable().apply {
      setColor(if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()); cornerRadius = dp(sz / 4).toFloat(); setStroke(dp(1), strokeC)
    }
    layoutParams = LinearLayout.LayoutParams(dp(sz), dp(sz))
  }

  // ---------- Home ----------
  private fun statCard(glyph: String, label: String, big: TextView, small: TextView) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; background = rr(cardC, 22, strokeC); elevation = dp(2).toFloat()
    setPadding(dp(16), dp(14), dp(16), dp(14))
    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(10), dp(4), 0) }
    addView(row(badge(glyph), tv(label, 13f, sub).apply { setPadding(dp(8), 0, 0, 0) }).apply { gravity = Gravity.CENTER_VERTICAL })
    addView(big.apply { setPadding(0, dp(10), 0, dp(2)) }); addView(small)
  }

  private fun heroTile(label: String, big: TextView, small: TextView) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; background = rr(0x2EFFFFFF, 16); setPadding(dp(14), dp(12), dp(14), dp(12))
    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
    addView(tv(label, 12f, whiteDim)); addView(big.apply { setPadding(0, dp(4), 0, dp(2)) }); addView(small)
  }

  private fun chip(t: String, f: () -> Unit) = TextView(this).apply {
    text = t; textSize = 14f; gravity = Gravity.CENTER; setTextColor(acc); setTypeface(typeface, Typeface.BOLD)
    background = rr(fieldC, 16)
    layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
    setOnClickListener { f() }
  }

  private fun buildHome(): View {
    val col = pageCol()
    val themeChip = TextView(this).apply {
      text = if (dark) "LIGHT" else "DARK"; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(acc)
      background = rr(fieldC, 20); setPadding(dp(14), dp(8), dp(14), dp(8))
      setOnClickListener { P.edit().putBoolean("dark", !dark).apply(); icon(!dark); save(); recreate() }
    }
    col.addView(row(
      logo(40),
      tv("Mollad DNS", 18f, fg, true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) } },
      themeChip).apply { gravity = Gravity.CENTER_VERTICAL })
    col.addView(tv("Your connection", 30f, fg, true).apply { layoutParams = lp(22) })
    col.addView(tv("Choose an account. Connect on your terms.", 14f, sub).apply { layoutParams = lp(2) })

    // Status card
    statePill = tv("Disconnected", 12f, white, true).apply { setPadding(dp(14), dp(6), dp(14), dp(6)); background = rr(0x40FFFFFF, 20) }
    statusTv = tv("Ready when you are", 28f, white, true).apply { layoutParams = lp(16) }
    subTv = tv("Tap Connect to start", 13f, whiteDim).apply { layoutParams = lp(2) }
    val line = View(this).apply { setBackgroundColor(0x55FFFFFF); layoutParams = LinearLayout.LayoutParams(-1, dp(2)).apply { topMargin = dp(16); bottomMargin = dp(16) } }
    fun bigW() = tv("0", 20f, white, true)
    fun smW() = tv("", 11f, whiteDim)
    upBig = bigW(); upSm = smW(); dnBig = bigW(); dnSm = smW()
    heroCard = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)); layoutParams = lp(20)
      addView(statePill, LinearLayout.LayoutParams(-2, -2))
      addView(statusTv); addView(subTv); addView(line)
      addView(row(heroTile("↓  Download", dnBig, dnSm), heroTile("↑  Upload", upBig, upSm)))
    }
    col.addView(heroCard)

    fun big() = tv("0", 21f, fg, true)
    fun sm() = tv("", 11f, sub)
    coBig = big(); coSm = sm(); tmBig = big(); tmSm = sm()
    col.addView(row(statCard("⇄", "Tunnels", coBig, coSm), statCard("◷", "Session time", tmBig, tmSm)))

    col.addView(row(chip("Accounts") { show(1) }, chip("Logs") { show(2) }, chip("Settings") { show(3) }).apply { layoutParams = lp(14) })

    summaryTv = tv("", 13f, fg).apply { setLineSpacing(0f, 1.2f) }
    col.addView(card(14).apply { addView(secTitle("Active accounts")); addView(summaryTv.apply { layoutParams = lp(8) }) })

    // Sticky connect button
    powerIcon = PowerIcon(this).apply { color = white }
    connectLbl = tv("Connect", 18f, white, true)
    heroBtn = FrameLayout(this).apply {
      layoutParams = LinearLayout.LayoutParams(-1, dp(58)).apply { setMargins(dp(16), dp(8), dp(16), dp(12)) }; elevation = dp(6).toFloat()
      addView(LinearLayout(this@MainActivity).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        addView(powerIcon, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) })
        addView(connectLbl)
      }, FrameLayout.LayoutParams(-1, -1))
      setOnClickListener { toggle() }
    }
    val scroll = ScrollView(this).apply { addView(col); layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
    return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(scroll); addView(heroBtn) }
  }

  private fun setHero(state: Int) {
    if (state == heroState) return
    heroState = state
    val c1: Int; val c2: Int
    when (state) {
      2 -> { c1 = 0xFF047857.toInt(); c2 = 0xFF10B981.toInt() }
      1, 3 -> { c1 = 0xFFB45309.toInt(); c2 = 0xFFF59E0B.toInt() }
      else -> { c1 = 0xFF13294F.toInt(); c2 = 0xFF2F63C8.toInt() }
    }
    val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply { cornerRadius = dp(30).toFloat() }
    val glowL = GradientDrawable().apply {
      colors = intArrayOf(0x40FFFFFF, 0x00FFFFFF); gradientType = GradientDrawable.RADIAL_GRADIENT
      gradientRadius = dp(260).toFloat(); setGradientCenter(1f, 0f); cornerRadius = dp(30).toFloat()
    }
    heroCard.background = android.graphics.drawable.LayerDrawable(arrayOf<android.graphics.drawable.Drawable>(base, glowL))
    heroCard.elevation = dp(6).toFloat()
    heroBtn.background = rr(if (state == 2) red else acc, 29)
    connectLbl.text = when (state) { 2 -> "Disconnect"; 1 -> "Connecting..."; 3 -> "Disconnecting..."; else -> "Connect" }
  }

  // ---------- Accounts ----------
  private fun addCard(a: JSONObject?, at: Int = accs.size) {
    val c = card().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
    val title = tv("", 16f, fg, true)
    val info = tv("", 12f, acc, true).apply { visibility = View.GONE }
    val sw = tint(Switch(this).apply { isChecked = a?.optBoolean("on", true) ?: true })
    val ns = et("e.g. ns-in6.vpnjantit.com", a?.optString("ns") ?: "")
    val pk = et("Server public key", a?.optString("pk") ?: "")
    val us = et("SSH username", a?.optString("u") ?: "")
    val pw = et("SSH password", a?.optString("p") ?: "").apply {
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    val dn = et("e.g. 8.8.8.8 or 1.1.1.1:53", a?.optString("d") ?: "")
    val list = (1..20).toList()
    val n = Spinner(this).apply {
      adapter = object : ArrayAdapter<Int>(this@MainActivity, android.R.layout.simple_spinner_item, list) {
        override fun getView(p: Int, v: View?, g: ViewGroup): View = (super.getView(p, v, g) as TextView).also { it.setTextColor(fg) }
        override fun getDropDownView(p: Int, v: View?, g: ViewGroup): View =
          (super.getDropDownView(p, v, g) as TextView).also { it.setTextColor(fg); it.setBackgroundColor(cardC); it.setPadding(dp(16), dp(10), dp(16), dp(10)) }
      }
      setSelection(((a?.optInt("n", 1) ?: 1).coerceIn(1, 20)) - 1)
    }
    val nWrap = FrameLayout(this).apply {
      background = rr(fieldC, 10, strokeC); layoutParams = LinearLayout.LayoutParams(dp(110), dp(42))
      addView(n, FrameLayout.LayoutParams(-1, -1))
    }
    lateinit var self: Acc
    val clone = btn("Clone", fieldC) {
      val j = js(self).put("role", "").put("srv", "").put("exp", 0L)
      addCard(j, accs.indexOf(self) + 1); closeDetail(); renumber(); save()
      toast("Cloned. Now change its DNS.")
    }
    val del = btn("Delete", red) { accBox.removeView(self.sumV); detailHolder.removeView(c); accs.remove(self); closeDetail(); renumber(); save() }

    c.addView(row(
      badge("DNS", 44),
      LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) }; addView(title); addView(info) },
      sw).apply { gravity = Gravity.CENTER_VERTICAL })
    c.addView(divider())
    fun cap(t: String) = tv(t, 12f, sub, true).apply { layoutParams = lp(14) }
    c.addView(cap("NS domain")); c.addView(ns)
    c.addView(cap("Public key")); c.addView(pk)
    c.addView(cap("Username")); c.addView(us)
    c.addView(cap("Password"))
    val eye = tv("Show", 13f, acc, true).apply {
      setPadding(dp(14), dp(12), dp(4), dp(12))
      setOnClickListener {
        val show = text.toString() == "Show"
        val pos = pw.text.length
        pw.inputType = InputType.TYPE_CLASS_TEXT or (if (show) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
        pw.setSelection(pos)
        text = if (show) "Hide" else "Show"
      }
    }
    pw.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    c.addView(row(pw, eye).apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = lp(6) })
    c.addView(cap("UDP DNS resolver")); c.addView(dn)
    c.addView(row(tv("Parallel tunnels (1-20)", 13f, sub).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }, nWrap)
      .apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = lp(14) })
    c.addView(row(clone, del))
    self = Acc(c, title, ns, pk, us, pw, dn, n, sw, listOf(ns, pk, us, pw, dn, n, clone, del))
    self.info = info
    val sT = tv("", 16f, fg, true)
    val sI = tv("", 12f, sub)
    val sS = tv("ON", 11f, white, true).apply { gravity = Gravity.CENTER; setPadding(dp(10), dp(4), dp(10), dp(4)) }
    val sV = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
      background = rr(cardC, 22, strokeC); elevation = dp(2).toFloat()
      setPadding(dp(16), dp(14), dp(16), dp(14)); layoutParams = lp(10)
      addView(badge("DNS", 44))
      addView(LinearLayout(this@MainActivity).apply {
        orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) }
        addView(sT); addView(sI)
      })
      addView(sS)
      addView(tv("›", 24f, sub, true).apply { setPadding(dp(10), 0, 0, 0) })
      setOnClickListener { openDetail(self) }
    }
    self.sumT = sT; self.sumI = sI; self.sumS = sS; self.sumV = sV
    self.role = a?.optString("role") ?: ""; self.srv = a?.optString("srv") ?: ""; self.exp = a?.optLong("exp", 0L) ?: 0L
    sw.setOnCheckedChangeListener { v, ck ->
      if (VpnSvc.running && !rev) { rev = true; v.isChecked = !ck; rev = false; toast("Stop the VPN first, then switch accounts ON/OFF") }
    }
    accBox.addView(sV, at); c.visibility = View.GONE; detailHolder.addView(c); accs.add(at, self); renumber()
  }

  private fun remainTxt(exp: Long): String {
    val ms = exp - System.currentTimeMillis()
    if (ms <= 0) return "EXPIRED"
    val m = ms / 60000
    return if (m >= 2880) "${m / 1440} days left" else "${m / 60}h ${m % 60}m left"
  }

  private fun renumber() {
    val now = System.currentTimeMillis()
    accs.forEachIndexed { i, a ->
      a.title.text = "Account ${i + 1}"
      val parts = ArrayList<String>()
      if (a.role.isNotBlank()) parts.add(a.role.uppercase() + " " + a.srv) else if (a.srv.isNotBlank()) parts.add(a.srv)
      if (a.exp > 0) parts.add(remainTxt(a.exp))
      a.info.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
      a.info.text = parts.joinToString("  ·  ")
      a.info.setTextColor(if (a.exp > 0 && a.exp < now) red else acc)
      a.sumT.text = a.title.text
      a.sumI.text = if (parts.isEmpty()) a.ns.text.toString().ifBlank { "Not set up yet" } else parts.joinToString("  ·  ")
      a.sumI.setTextColor(if (a.exp > 0 && a.exp < now) red else sub)
      a.sumS.text = if (a.sw.isChecked) "ON" else "OFF"
      a.sumS.background = rr(if (a.sw.isChecked) 0xFF10B981.toInt() else sub, 12)
    }
  }

  private fun js(a: Acc) = JSONObject().put("ns", a.ns.text.toString().trim()).put("pk", a.pk.text.toString().trim())
    .put("u", a.us.text.toString().trim()).put("p", a.pw.text.toString()).put("d", a.dns.text.toString().trim())
    .put("n", a.n.selectedItem as Int).put("on", a.sw.isChecked)
    .put("role", a.role).put("srv", a.srv).put("exp", a.exp)

  private fun save() {
    val arr = JSONArray(); accs.forEach { arr.put(js(it)) }
    P.edit().putString("acc2", arr.toString()).apply()
  }

  private fun openDetail(a: Acc) {
    for (i in 0 until detailHolder.childCount) detailHolder.getChildAt(i).visibility = View.GONE
    a.v.visibility = View.VISIBLE
    accListV.visibility = View.GONE; accDetailV.visibility = View.VISIBLE; accDetailV.scrollTo(0, 0)
  }

  private fun closeDetail() {
    if (!::accDetailV.isInitialized) return
    accDetailV.visibility = View.GONE; accListV.visibility = View.VISIBLE
  }

  @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
  override fun onBackPressed() {
    if (::accDetailV.isInitialized && accDetailV.visibility == View.VISIBLE) closeDetail() else super.onBackPressed()
  }

  private fun valid(a: Acc) = listOf(a.ns, a.pk, a.us, a.pw, a.dns).none { it.text.isNullOrBlank() }

  private fun buildAccounts(): View {
    val col = pageCol()
    head(col, "Accounts", "Each account opens its own set of tunnels")
    hintTv = tv("", 12f, amber).apply { layoutParams = lp(6) }
    col.addView(hintTv)

    // VPNJantit helper card
    val vj = card(14)
    vj.addView(secTitle("VPNJantit auto accounts"))
    vj.addView(tv("Creates and renews free accounts for you. Details are in Help.", 12f, sub).apply { layoutParams = lp(6); setLineSpacing(0f, 1.1f) })
    val vjAuto = tint(Switch(this)).apply {
      isChecked = P.getBoolean("vj_auto", false)
      setOnCheckedChangeListener { _, c ->
        P.edit().putBoolean("vj_auto", c).apply(); VjReceiver.schedule(this@MainActivity)
        toast(if (c) "Auto-renew ON: MAIN + BACKUP accounts will be created automatically" else "Auto-renew OFF")
        if (c) { vjHold = 0L; vjCheck(true) }
      }
    }
    vj.addView(row(
      tv("Auto-renew", 15f, fg, true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }, vjAuto
    ).apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = lp(12) })
    val vjBk = tint(Switch(this)).apply {
      isChecked = P.getBoolean("vj_backup", false)
      setOnCheckedChangeListener { _, c ->
        P.edit().putBoolean("vj_backup", c).apply()
        toast(if (c) "A BACKUP account will be kept too" else "Only one account will be kept")
      }
    }
    vj.addView(row(
      tv("Also keep a BACKUP account", 15f, fg, true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }, vjBk
    ).apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = lp(12) })
    vj.addView(row(
      btn("Create account", deep) { pickVpnJantitServer() },
      btn("Check now", fieldC) { vjHold = 0L; vjCheck(true) }))
    vj.addView(row(
      btn("Clear site cookies", fieldC) {
        startActivityForResult(Intent(this, VpnJantitActivity::class.java).putExtra(VpnJantit.EXTRA_ONLY_CLEAR, true), VpnJantit.REQ_CLEAR)
      }))
    col.addView(vj)

    detailHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    accBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    addB = btn("+ Add account manually", deep) { addCard(null); save(); openDetail(accs.last()) }.apply { layoutParams = lp(14) }
    col.addView(accBox); col.addView(addB)
    val saved = try { JSONArray(P.getString("acc2", "[]")) } catch (e: Exception) { JSONArray() }
    if (saved.length() == 0) addCard(null) else for (i in 0 until saved.length()) addCard(saved.getJSONObject(i))
    val listV = ScrollView(this).apply { addView(col) }
    val dcol = pageCol()
    dcol.addView(tv("‹  Accounts", 15f, acc, true).apply {
      background = rr(fieldC, 20); setPadding(dp(16), dp(9), dp(16), dp(9)); setOnClickListener { closeDetail() }
      layoutParams = LinearLayout.LayoutParams(-2, -2)
    })
    dcol.addView(detailHolder)
    accDetailV = ScrollView(this).apply { addView(dcol); visibility = View.GONE }
    accListV = listV
    return FrameLayout(this).apply { addView(listV); addView(accDetailV) }
  }

  // ---------- Logs ----------
  private fun buildLogs(): View {
    val col = pageCol().apply { setPadding(dp(16), dp(22), dp(16), dp(8)) }
    head(col, "Logs", "Live connection log (A1#2 = account 1, tunnel 2)")
    col.addView(row(
      btn("Clear", fieldC) { LogBus.clear(); lastLog = ""; logTv.text = "" },
      btn("Copy log") { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("log", LogBus.text())); toast("Copied") }))
    logTv = tv("", 11.5f, fg).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
    logSv = ScrollView(this).apply {
      addView(logTv); background = rr(if (dark) 0xFF0A0F1E.toInt() else 0xFFF3F6FC.toInt(), 22, strokeC); setPadding(dp(16), dp(14), dp(16), dp(14))
      layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) }
    }
    col.addView(logSv)
    return col
  }

  // ---------- Settings (compact: long explanations live in Help) ----------
  private fun settingLabel(title: String) = tv(title, 14f, fg, true).apply {
    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(10) }
  }

  private fun numRow(col: LinearLayout, title: String, desc: String, key: String, def: Int, lo: Int, hi: Int) {
    helpNotes.add(title to desc)
    val e = EditText(this).apply {
      setSingleLine(); inputType = InputType.TYPE_CLASS_NUMBER; setText(P.getInt(key, def).toString())
      setTextColor(fg); textSize = 14f; gravity = Gravity.CENTER
      background = rr(fieldC, 10, strokeC); setPadding(dp(8), dp(8), dp(8), dp(8))
      imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
      layoutParams = LinearLayout.LayoutParams(dp(92), dp(42))
    }
    val commit = {
      val v = (e.text.toString().toIntOrNull() ?: def).coerceIn(lo, hi)
      e.setText(v.toString()); P.edit().putInt(key, v).apply()
      if (key == "mbps") Limit.load(P)
    }
    e.setOnFocusChangeListener { _, f -> if (!f) commit() }
    e.setOnEditorActionListener { v, _, _ -> commit(); v.clearFocus(); false }
    settingCommits.add(commit)
    if (col.childCount > 1) col.addView(divider())
    col.addView(row(settingLabel(title), e).apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = lp(12) })
  }

  private fun swRow(col: LinearLayout, title: String, desc: String, key: String, def: Boolean, msg: (Boolean) -> String) {
    helpNotes.add(title to desc)
    val sw = tint(Switch(this).apply { isChecked = P.getBoolean(key, def) })
    sw.setOnCheckedChangeListener { _, c ->
      P.edit().putBoolean(key, c).apply(); if (key == "lim") Limit.load(P); toast(msg(c))
    }
    if (col.childCount > 1) col.addView(divider())
    col.addView(row(settingLabel(title), sw).apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = lp(12) })
  }

  private fun buildSettings(): View {
    val col = pageCol()
    head(col, "Settings", "Changes save automatically")

    val s1 = card(14); s1.addView(secTitle("Connection"))
    numRow(s1, "Connection timeout (s)", "How long one tunnel waits to connect before it counts as failed and retries. Raise it for slow accounts.", "to", 20, 5, 300)
    numRow(s1, "Retry max wait (s)", "After a failure a tunnel retries after 3s, then 6s, 12s, 24s... up to this limit. It never stops retrying. Set 3 to always retry every 3 seconds.", "rmax", 30, 3, 120)
    swRow(s1, "Auto reconnect", "Every tunnel is health-checked. A bad one is replaced first (new tunnel up, then the old one closed) so the VPN never drops. OFF: reconnect only when a tunnel breaks by itself.", "auto", true) { if (it) "Auto reconnect ON" else "Auto reconnect OFF" }
    numRow(s1, "Check interval (s)", "How often tunnels are checked. Smaller catches problems faster but uses a little more battery and data. Applies immediately.", "chk", 5, 1, 60)
    numRow(s1, "Failed checks before reconnect", "How many checks in a row must fail before reconnecting. Smaller reacts faster but may give false alarms. Applies immediately.", "miss", 2, 1, 10)
    col.addView(s1)

    val s2 = card(); s2.addView(secTitle("Performance"))
    numRow(s2, "QPS limit (0 = off)", "Total DNS queries per second across all tunnels. 0 = OFF. The log prints queries sent and answers received every 5 seconds; if the answer rate drops, the resolver is blocking you. Use 1000 just to count. Applies at next START.", "qps", 0, 0, 5000)
    numRow(s2, "DNS payload (bytes)", "How much data to ask for in one resolver reply. Bigger = more per request, but some resolvers drop big packets. Try 512 or 900 if you have trouble. Applies at next START.", "pay", 1232, 512, 4096)
    numRow(s2, "VPN MTU", "Maximum packet size of the phone VPN. Lower it if some sites or apps hang. Applies at next START.", "mtu", 1500, 576, 1500)
    numRow(s2, "dnstt MTU (0 = default)", "dnstt's own per-query chunk size (the -mtu flag), separate from the VPN MTU. Bigger = fewer queries for the same data and less rate limiting. 0 = use dnstt's default. Applies at next START.", "dmtu", 0, 0, 1500)
    swRow(s2, "Low-load mode", "dnstt polls less when idle: less data and battery, but replies can be slightly slower and download speed a bit lower. Applies at next START.", "low", false) { if (it) "Low-load ON (next START)" else "Low-load OFF (next START)" }
    swRow(s2, "Speed limit", "Cap upload and download to the Mbps value below. OFF = no limit.", "lim", false) { if (it) "Speed limit ON" else "Speed limit OFF" }
    numRow(s2, "Speed limit (Mbps)", "Maximum speed, 1-100 Mbps, used when Speed limit is ON.", "mbps", 10, 1, 100)
    col.addView(s2)

    val s3 = card(); s3.addView(secTitle("Resolvers and rotation"))
    helpNotes.add("Resolver rotation" to "Comma-separated public resolvers (e.g. 8.8.8.8,1.1.1.1,9.9.9.9). Each new or reconnecting tunnel takes the next one in turn, so one resolver is rate-limited less. Leave empty to use only each account's own DNS. If your network only allows one fixed resolver, list just that one. Applies at next connect.")
    s3.addView(settingLabel("Resolver rotation").apply { layoutParams = lp(12) })
    val resE = et("8.8.8.8,1.1.1.1,9.9.9.9", P.getString("resolvers", "") ?: "")
    resE.setOnFocusChangeListener { _, f -> if (!f) P.edit().putString("resolvers", resE.text.toString().trim()).apply() }
    settingCommits.add { P.edit().putString("resolvers", resE.text.toString().trim()).apply() }
    s3.addView(resE)
    numRow(s3, "Session break (s, 0 = off)", "Every N seconds a running tunnel is deliberately replaced (make-before-break, the VPN stays up) and the resolver changes if rotation is set. This keeps resolver rate-limit counters resetting. 0 = OFF. Applies immediately.", "sbreak", 0, 0, 7200)
    swRow(s3, "Account rotation", "ON: one account runs at a time and the next ON account takes over every interval, then the old one closes. Accounts rotate in the order shown. OFF: all ON accounts run together. Applies at next START.", "rot", false) { if (it) "Rotation ON (next START)" else "Rotation OFF - all accounts run together" }
    numRow(s3, "Rotation interval (s)", "How long to wait before the next account takes over. Connecting itself takes 3-5 seconds, so the real gap is a little longer. Applies immediately.", "rint", 5, 1, 3600)
    col.addView(s3)

    val s4 = card(); s4.addView(secTitle("Ports"))
    numRow(s4, "SOCKS port", "The app's local proxy port. If it is busy (for example in Termux) the app picks a free one. Applies at next START.", "sport", 3000, 1024, 65535)
    numRow(s4, "Tunnel start port", "Tunnels start from this port (A1#1 = start + 1 ...). Busy ports are skipped automatically. Applies at next START.", "tport", 2000, 1024, 65000)
    col.addView(s4)

    val s5 = card(); s5.addView(secTitle("Appearance and background"))
    s5.addView(row(
      btn("Dark", if (dark) acc else fieldC) { P.edit().putBoolean("dark", true).apply(); icon(true); save(); recreate() },
      btn("Light", if (!dark) acc else fieldC) { P.edit().putBoolean("dark", false).apply(); icon(false); save(); recreate() }))
    s5.addView(row(btn("Battery / notification permission", fieldC) { askBackground() }))
    helpNotes.add("Appearance and background" to "The launcher icon changes with the theme (it can take a few seconds to update). Turn off battery optimization and allow notifications so the VPN keeps running in the background.")
    col.addView(s5)
    return ScrollView(this).apply { addView(col) }
  }

  private fun icon(d: Boolean) {
    val pm = packageManager
    fun set(n: String, en: Boolean) = pm.setComponentEnabledSetting(android.content.ComponentName(this, "app.sd.$n"),
      if (en) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    try { set(if (d) "DarkAlias" else "LightAlias", true); set(if (d) "LightAlias" else "DarkAlias", false) } catch (e: Exception) {}
  }

  // ---------- Help ----------
  private fun helpCard(col: LinearLayout, title: String, body: String) {
    col.addView(card(12).apply {
      addView(secTitle(title))
      addView(tv(body.trim(), 13.5f, fg).apply { layoutParams = lp(8); setLineSpacing(0f, 1.2f) })
    })
  }

  private fun buildHelp(): View {
    val col = pageCol()
    col.addView(row(
      logo(64),
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
        addView(tv("Mollad DNS", 24f, fg, true)); addView(tv("by Mollad Jaat", 14f, acc, true)); addView(tv("Built with Claude", 12f, sub))
      }).apply { gravity = Gravity.CENTER_VERTICAL })
    col.addView(tv("Telegram: @mollad_jaat", 16f, 0xFF29B6F6.toInt(), true).apply {
      paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG; layoutParams = lp(12); setPadding(0, dp(6), 0, dp(6))
      setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/mollad_jaat"))) } catch (e: Exception) { toast("Could not open Telegram") } }
    })

    helpCard(col, "What is Mollad DNS?", """
Mollad DNS sends your whole phone's internet through a DNS tunnel (dnstt) with SSH on top, like a VPN. It is small and light.
""")
    helpCard(col, "Quick start", """
1. Open the Accounts tab and fill one account: NS domain, public key, username, password and a UDP DNS resolver (for example 8.8.8.8). Or use VPNJantit auto accounts (below).
2. Go to Home and tap Connect. Allow the VPN permission the first time.
3. When the card turns green and says Connected, you are online.
4. Test with 1 account and 1 tunnel first, then raise the tunnel count.
""")
    helpCard(col, "Accounts", """
- The Accounts tab shows one row per account. Tap a row to open that account and edit all its details; the arrow at the top brings you back.
- Parallel tunnels (1-20): one account opens that many tunnels at once. More tunnels = more speed but more load.
- Each account has an ON/OFF switch. Only ON accounts run when you connect.
- Need a second resolver (for example 1.1.1.1)? An account holds one UDP DNS. Tap Clone, which copies everything, and just change the DNS.
- To edit accounts or switch them ON/OFF, stop the VPN first. Fields are locked while it runs.
""")
    helpCard(col, "VPNJantit auto accounts", """
- Create account: pick a server (India list). The app works in the background with no window on screen, fills a random username and password, waits for the Cloudflare check to pass, taps Create, then reads the NS domain, public key, username, password and expiry into a new account.
- If the site asks for a real captcha, only then a window opens and you tap it yourself. The app never tries to bypass a captcha.
- Auto-renew keeps your MAIN account alive. Turn on "Also keep a BACKUP account" if you want a second one. An account made earlier is reused, so no duplicate is created. MAIN prefers DigitalOcean servers (in6, in1); BACKUP prefers the longest validity (in10). A new account is made about 4 hours before the old one expires and replaces it. If the VPN is running it restarts once to load the new account.
- Server Full: the app marks that server full until the daily reset (21:00 GMT+7, about 7:30 PM IST) and tries the next one.
- The site only allows one account every 5 minutes, so the app waits 6-7 minutes between accounts. Keep the app open during that wait.
- When the app is closed you get a notification before expiry. Tap it and everything continues.
- Clear site cookies resets the hidden browser so the site sees a fresh visitor.
- Do not use an ad-block DNS (like AdGuard) while creating accounts. The site blocks the form when it detects one.
""")
    helpCard(col, "Home screen", """
The Connect button at the bottom connects or disconnects. In the card you see upload and download speed (Mbps) with totals. Below it: how many tunnels are connected (for example 3 / 5), and how long the VPN has been up. The Active accounts card lists your ON accounts and the time left on VPNJantit accounts.
""")
    col.addView(card(12).apply {
      addView(secTitle("Settings guide"))
      helpNotes.forEach { (t, d) ->
        addView(tv(t, 14f, fg, true).apply { layoutParams = lp(12) })
        addView(tv(d, 12.5f, sub).apply { layoutParams = lp(2); setLineSpacing(0f, 1.15f) })
      }
    })
    helpCard(col, "Troubleshooting", """
- Open the Logs tab first. Each line starts with the tunnel name, for example A1#2 = account 1, tunnel 2.
- AUTH FAIL: wrong username or password, or the account expired.
- TIMEOUT: no answer through that resolver. Raise the timeout or change the UDP DNS.
- CONNECTION REFUSED: wrong server or port.
- Cannot create a VPNJantit account: the site may be full, blocking the form, or asking for a captcha. Wait and try again, clear cookies, and turn off any ad-block DNS.
- App killed in the background: allow battery and notification permission in Settings.
""")
    return ScrollView(this).apply { addView(col) }
  }

  // ---------- Navigation ----------
  private lateinit var navItems: List<LinearLayout>

  private fun buildNav(): View {
    val names = listOf("Home", "Accounts", "Logs", "Settings", "Help")
    navIcons = names.indices.map { NavIcon(this, it) }
    navLabels = names.map { n -> tv(n, 11f, sub).apply { gravity = Gravity.CENTER } }
    val bar = LinearLayout(this).apply {
      background = rr(cardC, 32, strokeC); elevation = dp(8).toFloat()
      layoutParams = LinearLayout.LayoutParams(-1, dp(64)).apply { setMargins(dp(14), dp(4), dp(14), dp(12)) }
      setPadding(dp(6), dp(6), dp(6), dp(6))
    }
    navItems = names.indices.map { i ->
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
        addView(navIcons[i], LinearLayout.LayoutParams(dp(22), dp(22)).apply { bottomMargin = dp(2) })
        addView(navLabels[i])
        setOnClickListener { show(i) }
      }
    }
    navItems.forEach { bar.addView(it) }
    return bar
  }

  private fun show(i: Int) {
    settingCommits.forEach { it() }
    cur = i
    if (i != 1) closeDetail()
    pages.forEachIndexed { k, p -> p.visibility = if (k == i) View.VISIBLE else View.GONE }
    navIcons.forEachIndexed { k, v -> v.color = if (k == i) acc else sub; v.invalidate() }
    navLabels.forEachIndexed { k, t -> t.setTextColor(if (k == i) acc else sub) }
    navItems.forEachIndexed { k, v -> v.background = if (k == i) rr(tintBg(acc, 0x22), 24) else null }
    refresh()
  }

  // ---------- Permissions / connect ----------
  private fun askBackground() {
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
      requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
    if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
      try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
  }

  // One button: connect <-> disconnect. Extra taps within 1.5 s are ignored.
  private fun toggle() {
    val now = System.currentTimeMillis()
    if (now - lastTap < 1500 || pending != null) return
    lastTap = now
    if (VpnSvc.running) {
      pending = false; pendingT = now
      startService(Intent(this, VpnSvc::class.java).setAction("stop"))
    } else start()
    refresh()
  }

  private fun start() {
    save()
    if (accs.none { it.sw.isChecked && valid(it) }) { toast("Turn ON at least one account and fill it completely"); return }
    accs.forEachIndexed { i, a -> if (a.sw.isChecked && !valid(a)) toast("Account ${i + 1} is incomplete and will be skipped") }
    askBackground()
    val i = VpnService.prepare(this)
    pending = true; pendingT = System.currentTimeMillis()
    if (i != null) startActivityForResult(i, 1) else begin()
  }

  private fun fmtSize(b: Long): String = when {
    b < 1_000_000 -> "%.0f kB".format(b / 1000.0)
    b < 1_000_000_000 -> "%.1f MB".format(b / 1e6)
    else -> "%.2f GB".format(b / 1e9)
  }

  // ---------- Refresh (runs every second) ----------
  private fun refresh() {
    val r = VpnSvc.running
    val conn = if (r) VpnSvc.connected() else 0
    accs.forEach { a -> a.ctl.forEach { it.isEnabled = !r; it.alpha = if (r) 0.5f else 1f } }
    addB.isEnabled = !r; addB.alpha = if (r) 0.5f else 1f
    val nowT = System.currentTimeMillis()
    if (pending != null && (pending == r || nowT - pendingT > 4000)) pending = null

    val state = when {
      pending == true -> 1
      pending == false -> 3
      r && conn > 0 -> 2
      r -> 1
      else -> 0
    }
    setHero(state)
    heroBtn.isEnabled = pending == null
    statePill.text = when (state) { 2 -> "Connected"; 1 -> "Connecting..."; 3 -> "Disconnecting..."; else -> "Disconnected" }
    statusTv.text = when (state) { 2 -> "You are online"; 1 -> "Getting tunnels ready"; 3 -> "Closing tunnels"; else -> "Ready when you are" }
    hintTv.text = if (vjBusy) "Creating the VPNJantit account in the background..." else if (r) "Stop the VPN to edit accounts or switch them ON/OFF" else ""

    val now = System.currentTimeMillis(); val u = Stats.up.get(); val d = Stats.down.get()
    if (now - lastT >= 800) {
      val dt = (now - lastT) / 1000.0
      lastUs = if (r && lastT > 0 && u >= lastU) (u - lastU) / dt else 0.0
      lastDs = if (r && lastT > 0 && d >= lastD) (d - lastD) / dt else 0.0
      lastT = now; lastU = u; lastD = d
    }
    upBig.text = "%.0f KB/s".format(lastUs / 1000); dnBig.text = "%.0f KB/s".format(lastDs / 1000)
    upSm.text = "%.2f Mbps  ·  %s total".format(lastUs * 8 / 1e6, fmtSize(u))
    dnSm.text = "%.2f Mbps  ·  %s total".format(lastDs * 8 / 1e6, fmtSize(d))
    coBig.text = if (r) "$conn / ${VpnSvc.total}" else "0 / 0"; coSm.text = "connected"
    if (r) {
      val sec = (now - Stats.since) / 1000
      val t = "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
      tmBig.text = t; tmSm.text = "online"
      subTv.text = if (state == 2) "Online for $t" else "Opening tunnels..."
    } else {
      tmBig.text = "--"; tmSm.text = "offline"
      subTv.text = if (state == 3) "Closing tunnels..." else if (state == 1) "Starting..." else "Tap Connect to start"
    }

    val sb = StringBuilder()
    accs.forEachIndexed { i, a ->
      if (a.sw.isChecked && valid(a)) {
        if (sb.isNotEmpty()) sb.append("\n")
        sb.append("Account ${i + 1}  ·  ${a.n.selectedItem} tunnel(s)")
        if (a.role.isNotBlank()) sb.append("  ·  ${a.role.uppercase()} ${a.srv}")
        if (a.exp > 0) sb.append("  ·  ${remainTxt(a.exp)}")
      }
    }
    summaryTv.text = if (sb.isEmpty()) "No active accounts. Add one in the Accounts tab." else sb.toString()

    renumber()
    if (cur == 2) {
      val t = LogBus.text()
      if (t != lastLog) { lastLog = t; logTv.text = t; logSv.post { logSv.fullScroll(View.FOCUS_DOWN) } }
    }
    h.removeCallbacksAndMessages(null)
    h.postDelayed({ refresh() }, 1000)
  }

  // ---------- Lifecycle ----------
  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    // The log is kept on disk; Java crash stacks are written to it too
    try {
      LogBus.setup(filesDir)
      val old = Thread.getDefaultUncaughtExceptionHandler()
      Thread.setDefaultUncaughtExceptionHandler { t, e ->
        try { LogBus.add("CRASH (${t.name}): " + android.util.Log.getStackTraceString(e).take(1500)) } catch (x: Throwable) {}
        old?.uncaughtException(t, e)
      }
    } catch (e: Throwable) {}
    P = getSharedPreferences("a", 0)
    dark = P.getBoolean("dark", false)
    window.statusBarColor = bg; window.navigationBarColor = bg
    if (!dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    val hv = buildHome(); val av = buildAccounts(); val lv = buildLogs(); val sv = buildSettings(); val iv = buildHelp()
    pages = listOf(hv, av, lv, sv, iv)
    val frame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f); pages.forEach { addView(it) } }
    setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); addView(frame); addView(buildNav()) })
    show(0)
    askBackground()
  }

  override fun onPause() { super.onPause(); settingCommits.forEach { it() }; save(); h.removeCallbacksAndMessages(null) }
  override fun onResume() { super.onResume(); refresh(); if (P.getBoolean("vj_auto", false)) vjCheck(false) }
  override fun onNewIntent(i: Intent?) {
    super.onNewIntent(i); setIntent(i)
    if (i?.getBooleanExtra("vj_auto", false) == true) { vjHold = 0L; vjCheck(true) }
  }
  override fun onDestroy() {
    super.onDestroy(); h.removeCallbacksAndMessages(null); vh.removeCallbacksAndMessages(null)
  }

  private fun begin() { startForegroundService(Intent(this, VpnSvc::class.java)) }
  override fun onActivityResult(r: Int, c: Int, d: Intent?) {
    if (r == VpnJantit.REQ_CODE) { onVpnJantitResult(c, d); return }
    if (r == VpnJantit.REQ_CLEAR) { toast("VPNJantit site cookies cleared"); return }
    if (c == RESULT_OK) begin() else pending = null
  }

  // ---------- VPNJantit auto account ----------
  private val vh = Handler(Looper.getMainLooper())
  private var vjBusy = false
  private var vjWaiting = false
  private var vjHold = 0L
  private var vjRole = ""
  private var vjSrv = ""
  private var vjTries = 0

  private val roleOrder = mapOf(
    "main" to listOf("in6", "in1", "in2", "in5", "in10"),
    "backup" to listOf("in10", "in2", "in5", "in1", "in6"))

  /** The site's daily reset: 21:00 GMT+7 (about 7:30 PM IST). */
  private fun nextReset(): Long {
    val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT+07:00"))
    c.set(java.util.Calendar.HOUR_OF_DAY, 21); c.set(java.util.Calendar.MINUTE, 0)
    c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
    if (c.timeInMillis <= System.currentTimeMillis()) c.add(java.util.Calendar.DAY_OF_YEAR, 1)
    return c.timeInMillis
  }

  private fun isFull(code: String) = P.getLong("vjfull_$code", 0L) > System.currentTimeMillis()
  private fun roleAcc(role: String) = accs.firstOrNull { it.role == role }
  private fun needsRenew(a: Acc?) = a == null || (a.exp > 0 && a.exp - System.currentTimeMillis() <= VpnJantit.RENEW_BEFORE_MS)
  private fun vjRoles() = if (P.getBoolean("vj_backup", false)) listOf("main", "backup") else listOf("main")

  /** An account the app created earlier (no role yet) becomes MAIN/BACKUP, so no duplicate is made. */
  private fun adoptRoles() {
    val now = System.currentTimeMillis()
    for (role in vjRoles()) {
      if (roleAcc(role) != null) continue
      val cand = accs.firstOrNull { it.role.isBlank() && it.srv.isNotBlank() && it.exp > now } ?: continue
      cand.role = role
    }
  }

  private fun vjNeeded(): String? { adoptRoles(); return vjRoles().firstOrNull { needsRenew(roleAcc(it)) } }

  private fun vjCheck(manual: Boolean) {
    if (vjBusy || vjWaiting) { if (manual) toast("VPNJantit is already working on it"); return }
    if (!manual && System.currentTimeMillis() < vjHold) return
    val role = vjNeeded()
    if (role == null) { if (manual) toast("MAIN and BACKUP accounts are both fine"); return }
    vjStart(role)
  }

  private fun vjSchedule(role: String, ms: Long) {
    vjWaiting = true
    val wait = ms + (0..40_000).random()
    toast("${role.uppercase()} account will be created in ${wait / 60000 + 1} min (keep the app open)")
    vh.removeCallbacksAndMessages(null)
    vh.postDelayed({ vjWaiting = false; vjStart(role) }, wait)
  }

  private fun vjStart(role: String) {
    if (vjBusy) return
    val now = System.currentTimeMillis()
    val wait = P.getLong("vj_last", 0L) + VpnJantit.COOLDOWN_MS - now
    if (wait > 0) { vjSchedule(role, wait); return }
    val other = roleAcc(if (role == "main") "backup" else "main")?.srv
    val free = roleOrder[role]!!.filter { !isFull(it) }
    val srv = free.firstOrNull { it != other } ?: free.firstOrNull()
    if (srv == null) {
      val t = nextReset()
      val hhmm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(t))
      toast("All servers are full. Will retry after the reset ($hhmm local time)")
      vjSchedule(role, t - now)
      return
    }
    vjBusy = true; vjRole = role; vjSrv = srv
    startActivityForResult(Intent(this, VpnJantitActivity::class.java).putExtra(VpnJantit.EXTRA_SERVER, srv), VpnJantit.REQ_CODE)
  }

  private fun pickVpnJantitServer() {
    if (vjBusy) return
    val names = VpnJantit.SERVERS.map { it.label }.toTypedArray()
    android.app.AlertDialog.Builder(this).setTitle("Choose a server")
      .setItems(names) { _, i ->
        vjBusy = true; vjRole = ""; vjSrv = VpnJantit.SERVERS[i].code
        startActivityForResult(Intent(this, VpnJantitActivity::class.java).putExtra(VpnJantit.EXTRA_SERVER, vjSrv), VpnJantit.REQ_CODE)
      }.show()
  }

  private fun fillAcc(a: Acc, ns: String, pub: String, u: String, p: String, role: String, srv: String, exp: Long, resetTunnels: Boolean) {
    a.ns.setText(ns); a.pk.setText(pub); a.us.setText(u); a.pw.setText(p)
    if (a.dns.text.isNullOrBlank()) a.dns.setText("8.8.8.8")
    rev = true; a.sw.isChecked = true; rev = false
    if (resetTunnels) a.n.setSelection(0)
    a.role = role; a.srv = srv; a.exp = exp
  }

  private fun onVpnJantitResult(c: Int, d: Intent?) {
    vjBusy = false
    val role = vjRole; vjRole = ""
    val srv = vjSrv
    val err = d?.getStringExtra(VpnJantit.R_ERROR)
    if (!err.isNullOrBlank()) {
      if (Regex("full", RegexOption.IGNORE_CASE).containsMatchIn(err)) {
        P.edit().putLong("vjfull_$srv", nextReset()).apply()
        toast("$srv is full, trying another server")
        if (role.isNotEmpty() && vjTries++ < 5) vjStart(role) else vjTries = 0
        return
      }
      vjTries = 0
      toast("VPNJantit: $err")
      val retry = d?.getLongExtra(VpnJantit.R_RETRY_MS, 0L) ?: 0L
      if (role.isNotEmpty()) { if (retry > 0) vjSchedule(role, retry) else vjHold = System.currentTimeMillis() + 10 * 60_000L }
      return
    }
    if (c != RESULT_OK || d == null) { vjHold = System.currentTimeMillis() + 10 * 60_000L; return }
    vjTries = 0
    val u = d.getStringExtra(VpnJantit.R_USER).orEmpty()
    val p = d.getStringExtra(VpnJantit.R_PASS).orEmpty()
    val pub = d.getStringExtra(VpnJantit.R_PUBKEY).orEmpty()
    val ns = d.getStringExtra(VpnJantit.R_NS).orEmpty().ifBlank { "ns-$srv.vpnjantit.com" }
    val exp = d.getLongExtra(VpnJantit.R_EXPIRES, 0L)
    if (u.isBlank() || p.isBlank() || pub.isBlank()) { toast("The account details came back incomplete"); vjHold = System.currentTimeMillis() + 10 * 60_000L; return }

    val old = if (role.isNotEmpty()) roleAcc(role) else null
    val blank = accs.lastOrNull()?.takeIf { a -> a.role.isBlank() && listOf(a.ns, a.pk, a.us, a.pw, a.dns).all { it.text.isNullOrBlank() } }
    val target = old ?: blank
    if (target != null) fillAcc(target, ns, pub, u, p, role, srv, exp, old == null)
    else addCard(JSONObject().put("ns", ns).put("pk", pub).put("u", u).put("p", p).put("d", "8.8.8.8").put("n", 1).put("on", true)
      .put("role", role).put("srv", srv).put("exp", exp))
    P.edit().putLong("vj_last", System.currentTimeMillis()).apply()
    save(); renumber(); VjReceiver.schedule(this)
    val e = if (exp > 0) "\nExpires: " + java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.US).format(java.util.Date(exp)) else ""
    toast((if (role.isNotEmpty()) role.uppercase() + " " else "") + "account created ($srv): $u$e")

    if (VpnSvc.running) {
      toast("Restarting the VPN to load the new account")
      pending = false; pendingT = System.currentTimeMillis()
      startService(Intent(this, VpnSvc::class.java).setAction("stop"))
      vh.postDelayed({ if (!VpnSvc.running) start() }, 3500)
    }
    if (role.isNotEmpty()) vjNeeded()?.let { vjSchedule(it, VpnJantit.COOLDOWN_MS) }
  }
}
