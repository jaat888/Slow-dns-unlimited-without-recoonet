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
import android.graphics.drawable.Drawable
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
import android.view.MotionEvent
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
  companion object { @Volatile var inst: MainActivity? = null }
  private var resumed = false
  private var pulse: ObjectAnimator? = null
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
  private lateinit var addB: View
  private lateinit var vjStateTv: TextView
  private lateinit var logSub: TextView
  private lateinit var fdnsVal: TextView
  private lateinit var appfVal: TextView
  private lateinit var notifVal: TextView
  private lateinit var overlayVal: TextView
  private lateinit var battVal: TextView
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
    pressable(this)
  }

  /** Light press feedback: the view shrinks a little while it is touched. */
  private fun pressable(v: View): View {
    v.setOnTouchListener { x, e ->
      when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> x.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> x.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
      }
      false
    }
    return v
  }

  /** Small action button (used in the compact VPNJantit card). */
  private fun mini(t: String, filled: Boolean, f: () -> Unit) = TextView(this).apply {
    text = t; textSize = 13f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
    setTextColor(if (filled) ink else acc)
    background = rr(if (filled) acc else tintBg(acc, 0x1A), 12)
    setPadding(dp(6), 0, dp(6), 0)
    layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
    setOnClickListener { f() }
    pressable(this)
  }

  /** Fade + slide in a list of views one after another (light page animation). */
  private fun staggerIn(list: List<View>) {
    list.forEachIndexed { i, v ->
      v.alpha = 0f; v.translationY = dp(14).toFloat()
      v.animate().alpha(1f).translationY(0f).setStartDelay(i * 40L).setDuration(230).start()
    }
  }

  private fun colOf(v: View): LinearLayout? {
    var x: View? = v
    if (x is LinearLayout && x.childCount > 0 && x.getChildAt(0) is ScrollView) x = x.getChildAt(0)
    if (x is ScrollView) x = x.getChildAt(0)
    if (x is FrameLayout && x.childCount > 0) { val f0 = x.getChildAt(0); x = if (f0 is ScrollView) f0.getChildAt(0) else f0 }
    return x as? LinearLayout
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
    pressable(this)
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
    // light animation: the card fades to the new colour, the button breathes while it is busy
    heroCard.alpha = 0.55f; heroCard.animate().alpha(1f).setDuration(320).start()
    pulse?.cancel(); pulse = null
    heroBtn.scaleX = 1f; heroBtn.scaleY = 1f
    if (state == 1 || state == 3) {
      pulse = ObjectAnimator.ofPropertyValuesHolder(heroBtn,
        PropertyValuesHolder.ofFloat("scaleX", 1f, 1.025f), PropertyValuesHolder.ofFloat("scaleY", 1f, 1.025f)).apply {
        duration = 650; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
      }
    }
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
      pressable(this)
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
      val ready = valid(a)
      a.sumS.text = if (!ready) "SETUP" else if (a.sw.isChecked) "ON" else "OFF"
      a.sumS.background = rr(if (!ready) amber else if (a.sw.isChecked) 0xFF10B981.toInt() else sub, 12)
    }
  }

  private fun js(a: Acc) = JSONObject().put("ns", a.ns.text.toString().trim()).put("pk", a.pk.text.toString().trim())
    .put("u", a.us.text.toString().trim()).put("p", a.pw.text.toString()).put("d", a.dns.text.toString().trim())
    .put("n", a.n.selectedItem as Int).put("on", a.sw.isChecked)
    .put("role", a.role).put("srv", a.srv).put("exp", a.exp)

  private fun serialize(): String { val arr = JSONArray(); accs.forEach { arr.put(js(it)) }; return arr.toString() }
  private fun save() { P.edit().putString("acc2", serialize()).apply() }
  /** Called by the VPNJantit manager before it writes a new account, so nothing typed here is lost. */
  fun saveNow() { if (::accBox.isInitialized && accs.isNotEmpty()) save() }
  private fun accsDirty() = ::accBox.isInitialized && accs.isNotEmpty() && serialize() != (P.getString("acc2", "[]") ?: "[]")

  private fun loadAccounts() {
    val saved = try { JSONArray(P.getString("acc2", "[]")) } catch (e: Exception) { JSONArray() }
    if (saved.length() == 0) addCard(null) else for (i in 0 until saved.length()) addCard(saved.getJSONObject(i))
  }

  /** Rebuilds the account list from the saved data (a background VPNJantit account arrived). */
  private fun reloadAccounts() {
    accBox.removeAllViews(); detailHolder.removeAllViews(); accs.clear()
    loadAccounts(); closeDetail()
  }

  private fun openDetail(a: Acc) {
    for (i in 0 until detailHolder.childCount) detailHolder.getChildAt(i).visibility = View.GONE
    a.v.visibility = View.VISIBLE
    accListV.visibility = View.GONE; accDetailV.visibility = View.VISIBLE; accDetailV.scrollTo(0, 0)
    accDetailV.alpha = 0f; accDetailV.translationX = dp(24).toFloat()
    accDetailV.animate().alpha(1f).translationX(0f).setDuration(220).start()
  }

  private fun closeDetail() {
    if (!::accDetailV.isInitialized) return
    if (accDetailV.visibility != View.VISIBLE) return
    accDetailV.visibility = View.GONE; accListV.visibility = View.VISIBLE
    accListV.alpha = 0f; accListV.animate().alpha(1f).setDuration(200).start()
  }

  @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
  override fun onBackPressed() {
    if (::appPickV.isInitialized && appPickV.visibility == View.VISIBLE) closeAppPicker()
    else if (::accDetailV.isInitialized && accDetailV.visibility == View.VISIBLE) closeDetail() else super.onBackPressed()
  }

  private fun valid(a: Acc) = listOf(a.ns, a.pk, a.us, a.pw, a.dns).none { it.text.isNullOrBlank() }

  /** The "add account" row: same size and style as an account row. */
  private fun addRowView(): View = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    background = rr(cardC, 22, strokeC); elevation = dp(2).toFloat()
    setPadding(dp(16), dp(14), dp(16), dp(14)); layoutParams = lp(10)
    addView(badge("+", 44))
    addView(LinearLayout(this@MainActivity).apply {
      orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) }
      addView(tv("Add account", 16f, fg, true)); addView(tv("Enter NS, key, user and password", 12f, sub))
    })
    addView(tv("›", 24f, sub, true).apply { setPadding(dp(10), 0, 0, 0) })
    setOnClickListener { addCard(null); save(); openDetail(accs.last()) }
    pressable(this)
  }

  private fun switchRow(title: String, sw: Switch) = row(
    tv(title, 14f, fg, true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }, sw
  ).apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = lp(10) }

  private fun buildAccounts(): View {
    val col = pageCol()
    head(col, "Accounts", "Each account opens its own set of tunnels")
    hintTv = tv("", 12.5f, amber, true).apply { layoutParams = lp(6) }
    col.addView(hintTv)

    // VPNJantit helper card (compact)
    val vj = card(12).apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
    vjStateTv = tv("Manual", 11f, sub, true).apply { setPadding(dp(10), dp(4), dp(10), dp(4)); background = rr(tintBg(sub, 0x22), 10) }
    vj.addView(row(
      badge("VJ", 44),
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) }
        addView(tv("VPNJantit auto accounts", 15f, fg, true)); addView(tv("Creates and renews free accounts", 12f, sub))
      },
      vjStateTv).apply { gravity = Gravity.CENTER_VERTICAL })
    vj.addView(divider())
    val vjAuto = tint(Switch(this)).apply {
      isChecked = P.getBoolean("vj_auto", false)
      setOnCheckedChangeListener { _, c ->
        P.edit().putBoolean("vj_auto", c).putLong("vj_hold", 0L).apply(); VjReceiver.schedule(this@MainActivity)
        toast(if (c) "Auto-renew ON" else "Auto-renew OFF")
        if (c) vjCheckUi(true)
      }
    }
    vj.addView(switchRow("Auto-renew", vjAuto))
    val vjBk = tint(Switch(this)).apply {
      isChecked = P.getBoolean("vj_backup", false)
      setOnCheckedChangeListener { _, c ->
        P.edit().putBoolean("vj_backup", c).apply()
        toast(if (c) "A BACKUP account will be kept too" else "Only one account will be kept")
      }
    }
    vj.addView(switchRow("Also keep a BACKUP account", vjBk))
    vj.addView(row(
      mini("Create", true) { pickVpnJantitServer() },
      mini("Check now", false) { P.edit().putLong("vj_hold", 0L).apply(); vjCheckUi(true) },
      mini("Clear cookies", false) { try { VjService.clear(this) ; toast("VPNJantit site cookies cleared") } catch (e: Exception) { toast("Could not clear cookies") } }
    ).apply { layoutParams = lp(14) })
    col.addView(vj)

    detailHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    accBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    addB = addRowView()
    col.addView(accBox); col.addView(addB)
    loadAccounts()
    val listV = ScrollView(this).apply { addView(col) }
    val dcol = pageCol()
    dcol.addView(tv("‹  Accounts", 15f, acc, true).apply {
      background = rr(fieldC, 20); setPadding(dp(16), dp(9), dp(16), dp(9)); setOnClickListener { closeDetail() }
      layoutParams = LinearLayout.LayoutParams(-2, -2)
      pressable(this)
    })
    dcol.addView(detailHolder)
    accDetailV = ScrollView(this).apply { addView(dcol); visibility = View.GONE }
    accListV = listV
    return FrameLayout(this).apply { addView(listV); addView(accDetailV) }
  }

  // ---------- Logs ----------
  private fun buildLogs(): View {
    val col = pageCol().apply { setPadding(dp(16), dp(22), dp(16), dp(8)) }
    col.addView(tv("Logs", 26f, fg, true))
    logSub = tv("Live connection log", 13f, sub).apply { layoutParams = lp(2) }
    col.addView(logSub)
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
      if (key == "logmin") LogBus.configure(P)
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
      P.edit().putBoolean(key, c).apply(); if (key == "lim") Limit.load(P); if (key == "logauto" || key == "dbg") LogBus.configure(P); toast(msg(c))
    }
    if (col.childCount > 1) col.addView(divider())
    col.addView(row(settingLabel(title), sw).apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = lp(12) })
  }

  /** A settings row you tap: title on the left, the current value (and an arrow) on the right. */
  private fun tapRow(col: LinearLayout, title: String, desc: String, value: TextView, f: () -> Unit) {
    helpNotes.add(title to desc)
    if (col.childCount > 1) col.addView(divider())
    value.textSize = 13f; value.setTextColor(acc); value.setTypeface(value.typeface, Typeface.BOLD); value.gravity = Gravity.END
    val r = row(settingLabel(title), value, tv("›", 20f, sub, true).apply { setPadding(dp(8), 0, 0, 0) })
    r.gravity = Gravity.CENTER_VERTICAL; r.layoutParams = lp(12); r.setPadding(0, dp(2), 0, dp(2))
    r.setOnClickListener { f() }
    pressable(r); col.addView(r)
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

    val s6 = card(); s6.addView(secTitle("Network and apps"))
    fdnsVal = tv("", 13f, acc, true); appfVal = tv("", 13f, acc, true)
    tapRow(s6, "Forward DNS", "The DNS server your phone's DNS questions are sent to, through the tunnel. Pick Cloudflare, Google, Quad9, AdGuard or OpenDNS, or type your own IPv4 address. If sites do not open, try another one. Applies at next START.", fdnsVal) { pickForwardDns() }
    tapRow(s6, "App filter", "Choose which apps use the VPN. All apps = everything goes through the tunnel. Only selected = just the apps you tick use the VPN, the rest use your normal internet. All except = every app uses the VPN except the ones you tick. Applies at next START.", appfVal) { openAppPicker() }
    col.addView(s6)

    val s7 = card(); s7.addView(secTitle("Logs"))
    swRow(s7, "Auto clear logs", "Clears the on-screen log by itself so it never gets long. The Copy button still gives you what is on screen at that moment.", "logauto", true) { if (it) "Logs will clear automatically" else "Logs will not clear by themselves" }
    swRow(s7, "Debug logs", "ON: full details (timings, resolver, ports, DNS query rates, VPNJantit steps). OFF: short clean lines only, such as connected, failed, retrying. Turn ON when something does not work and you need to see why.", "dbg", false) { if (it) "Debug logs ON (details)" else "Debug logs OFF (clean)" }
    numRow(s7, "Clear every (minutes)", "How often the log is cleared when Auto clear logs is ON.", "logmin", 3, 1, 120)
    col.addView(s7)

    val s5 = card(); s5.addView(secTitle("Appearance and permissions"))
    s5.addView(row(
      btn("Dark", if (dark) acc else fieldC) { P.edit().putBoolean("dark", true).apply(); icon(true); save(); recreate() },
      btn("Light", if (!dark) acc else fieldC) { P.edit().putBoolean("dark", false).apply(); icon(false); save(); recreate() }))
    notifVal = tv("", 13f, acc, true); overlayVal = tv("", 13f, acc, true); battVal = tv("", 13f, acc, true)
    tapRow(s5, "Notifications", "Needed for the VPN notification, the captcha alert and the account-ready message. If it says Blocked, tap to open the app's notification settings.", notifVal) { askNotifications() }
    tapRow(s5, "Display over other apps", "Needed to create VPNJantit accounts in the background. The captcha pops up on top of whatever app you are using only when it needs you, then goes away. On some phones (Xiaomi, Oppo, Vivo) also allow Autostart and Show pop-ups in the app's permission page.", overlayVal) { askOverlay() }
    tapRow(s5, "Battery optimization", "Turn it off for this app so Android does not stop the VPN or the account creation in the background.", battVal) { askBattery() }
    helpNotes.add("Theme" to "The launcher icon changes with the theme (it can take a few seconds to update).")
    col.addView(s5)
    updateSettingSummaries()
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
- First allow Settings > Display over other apps (one time). Then the account is created fully in the background, even if you leave the app: it fills a random username and password, waits for the Cloudflare check, taps Create, then reads the NS domain, public key, username, password and expiry into the account.
- The orange line at the top of Accounts, the notification and the Logs page show the current step.
- If the site asks for a real captcha, only that captcha pops up on top of your screen. Tap it, it goes away and the work continues. "Continue in background" hides it for a minute. The app never tries to bypass a captcha.
- Auto-renew keeps your MAIN account alive. Turn on "Also keep a BACKUP account" if you want a second one. An account made earlier is reused, so no duplicate is created. MAIN prefers DigitalOcean servers (in6, in1); BACKUP prefers the longest validity (in10). A new account is made about 4 hours before the old one expires and replaces it. If the VPN is running it restarts once to load the new account.
- Server Full: the app marks that server full until the daily reset (21:00 GMT+7, about 7:30 PM IST) and tries the next one.
- The site only allows one account every 5 minutes, so the app waits 6-7 minutes between accounts and tries again by itself (an alarm wakes it, the app need not be open).
- If Android blocks the background start you get a notification instead. Tap it and the account is created.
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
        pressable(this)
      }
    }
    navItems.forEach { bar.addView(it) }
    return bar
  }

  private var shown = false

  private fun show(i: Int) {
    settingCommits.forEach { it() }
    val animate = !shown || i != cur
    shown = true
    cur = i
    if (i != 1) closeDetail()
    closeAppPicker()
    pages.forEachIndexed { k, p -> p.visibility = if (k == i) View.VISIBLE else View.GONE }
    navIcons.forEachIndexed { k, v -> v.color = if (k == i) acc else sub; v.invalidate() }
    navLabels.forEachIndexed { k, t -> t.setTextColor(if (k == i) acc else sub) }
    navItems.forEachIndexed { k, v -> v.background = if (k == i) rr(tintBg(acc, 0x22), 24) else null }
    refresh()
    if (animate) colOf(pages[i])?.let { c -> staggerIn((0 until c.childCount.coerceAtMost(8)).map { c.getChildAt(it) }) }
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
    VjManager.tick(this)
    val vl = VjManager.line(this)
    hintTv.text = if (vl.isNotEmpty()) "●  $vl" else if (r) "Stop the VPN to edit accounts or switch them ON/OFF" else ""
    hintTv.visibility = if (hintTv.text.isEmpty()) View.GONE else View.VISIBLE
    val vjWork = VjManager.isBusy(); val vjAutoOn = P.getBoolean("vj_auto", false)
    val vjCol = if (vjWork) amber else if (vjAutoOn) 0xFF10B981.toInt() else sub
    vjStateTv.text = if (vjWork) "Working" else if (vjAutoOn) "Auto" else "Manual"
    vjStateTv.setTextColor(vjCol); vjStateTv.background = rr(tintBg(vjCol, 0x22), 10)

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
      val nc = LogBus.nextClearIn()
      logSub.text = if (nc > 0) "Live log  ·  auto-clears in ${nc / 60}m ${nc % 60}s" else "Live log  ·  auto clear is off"
    }
    if (cur == 3) updatePermSummaries()
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
    LogBus.configure(P)
    Notif.channels(this)
    inst = this
    dark = P.getBoolean("dark", false)
    window.statusBarColor = bg; window.navigationBarColor = bg
    if (!dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    val hv = buildHome(); val av = buildAccounts(); val lv = buildLogs(); val sv = buildSettings(); val iv = buildHelp()
    val picker = buildAppPicker()
    pages = listOf(hv, av, lv, sv, iv)
    val frame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f); pages.forEach { addView(it) }; addView(picker) }
    setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); addView(frame); addView(buildNav()) })
    show(0)
    save()
    askBackground()
  }

  override fun onPause() {
    resumed = false
    super.onPause(); settingCommits.forEach { it() }; save(); h.removeCallbacksAndMessages(null)
    pulse?.cancel(); pulse = null; heroState = -1
  }
  override fun onResume() {
    super.onResume(); resumed = true
    // a VPNJantit account may have been created while the app was in the background
    if (accsDirty()) reloadAccounts()
    refresh()
    if (P.getBoolean("vj_auto", false)) vjCheckUi(false)
  }
  override fun onNewIntent(i: Intent?) {
    super.onNewIntent(i); setIntent(i)
    if (i?.getBooleanExtra("vj_auto", false) == true) { P.edit().putLong("vj_hold", 0L).apply(); vjCheckUi(true) }
  }
  override fun onDestroy() {
    super.onDestroy(); h.removeCallbacksAndMessages(null); pulse?.cancel()
    if (inst === this) inst = null
  }

  private fun begin() { startForegroundService(Intent(this, VpnSvc::class.java)) }
  override fun onActivityResult(r: Int, c: Int, d: Intent?) {
    if (c == RESULT_OK) begin() else pending = null
  }

  // ---------- Permissions and settings helpers ----------
  private fun askNotifications() {
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
      && !P.getBoolean("notif_asked", false)) {
      P.edit().putBoolean("notif_asked", true).apply()
      requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2); return
    }
    try { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
    catch (e: Exception) { toast("Open Settings > Apps > Mollad DNS > Notifications") }
  }

  private fun askOverlay() {
    if (Settings.canDrawOverlays(this)) { toast("Display over other apps is already allowed"); return }
    android.app.AlertDialog.Builder(this).setTitle("Allow display over other apps")
      .setMessage("VPNJantit accounts are created in the background. Mollad DNS needs this permission so the captcha can pop up on top of any app, only when it needs you. Nothing else is shown.")
      .setPositiveButton("Open settings") { _, _ ->
        try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        catch (e: Exception) { toast("Open Settings > Apps > Mollad DNS > Display over other apps") }
      }
      .setNegativeButton("Not now", null).show()
  }

  private fun askBattery() {
    if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) { toast("Battery optimization is already off"); return }
    try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
    catch (e: Exception) { toast("Open Settings > Apps > Mollad DNS > Battery") }
  }

  private fun updateSettingSummaries() {
    if (!::fdnsVal.isInitialized) return
    fdnsVal.text = P.getString("fdns", "1.1.1.1") ?: "1.1.1.1"
    val n = (P.getString("appf_list", "") ?: "").split(",").count { it.isNotBlank() }
    appfVal.text = when (P.getInt("appf_mode", 0)) { 1 -> "Only $n app(s)"; 2 -> "All except $n"; else -> "All apps" }
  }

  private fun updatePermSummaries() {
    if (!::notifVal.isInitialized) return
    val nOk = getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
    val oOk = Settings.canDrawOverlays(this)
    val bOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    notifVal.text = if (nOk) "Allowed" else "Blocked"; notifVal.setTextColor(if (nOk) 0xFF10B981.toInt() else red)
    overlayVal.text = if (oOk) "Allowed" else "Not allowed"; overlayVal.setTextColor(if (oOk) 0xFF10B981.toInt() else red)
    battVal.text = if (bOk) "Unrestricted" else "Restricted"; battVal.setTextColor(if (bOk) 0xFF10B981.toInt() else amber)
  }

  private val dnsChoices = listOf(
    "Cloudflare" to "1.1.1.1", "Google" to "8.8.8.8", "Quad9" to "9.9.9.9",
    "AdGuard (blocks ads)" to "94.140.14.14", "OpenDNS" to "208.67.222.222")

  private fun pickForwardDns() {
    val cur = P.getString("fdns", "1.1.1.1") ?: "1.1.1.1"
    val names = (dnsChoices.map { it.first + "  (" + it.second + ")" } + "Custom...").toTypedArray()
    val idx = dnsChoices.indexOfFirst { it.second == cur }
    android.app.AlertDialog.Builder(this).setTitle("Forward DNS")
      .setSingleChoiceItems(names, if (idx < 0) dnsChoices.size else idx) { d, w ->
        d.dismiss()
        if (w < dnsChoices.size) {
          P.edit().putString("fdns", dnsChoices[w].second).apply(); updateSettingSummaries()
          toast("Forward DNS: " + dnsChoices[w].second + " (applies at next START)")
        } else askCustomDns()
      }.setNegativeButton("Cancel", null).show()
  }

  private fun askCustomDns() {
    val e = EditText(this).apply {
      hint = "e.g. 9.9.9.11"; setSingleLine(); setText(P.getString("fdns", "") ?: ""); setPadding(dp(20), dp(14), dp(20), dp(14))
    }
    android.app.AlertDialog.Builder(this).setTitle("Custom forward DNS").setView(e)
      .setPositiveButton("Save") { _, _ ->
        val v = e.text.toString().trim()
        if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}\$").matches(v)) {
          P.edit().putString("fdns", v).apply(); updateSettingSummaries(); toast("Forward DNS: $v (applies at next START)")
        } else toast("Please type an IPv4 address like 9.9.9.11")
      }.setNegativeButton("Cancel", null).show()
  }

  // ---------- App filter picker ----------
  private class AppItem(val pkg: String, val label: String, val icon: Drawable?)
  private class AppRow(val root: LinearLayout, val icon: ImageView, val name: TextView, val pkg: TextView, val check: TextView)

  private val appAll = ArrayList<AppItem>()
  private val appShown = ArrayList<AppItem>()
  private val appSel = HashSet<String>()
  private var appQuery = ""
  private lateinit var appPickV: LinearLayout
  private lateinit var appListV: ListView
  private lateinit var appCountTv: TextView
  private lateinit var appModeChips: List<TextView>

  private fun newAppRow(): AppRow {
    val icon = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)) }
    val name = tv("", 15f, fg, true).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END }
    val pkg = tv("", 11f, sub).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END }
    val check = TextView(this).apply { gravity = Gravity.CENTER; textSize = 14f; setTypeface(typeface, Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)) }
    val info = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12); rightMargin = dp(8) }
      addView(name); addView(pkg)
    }
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(8), dp(6), dp(8))
      addView(icon); addView(info); addView(check)
    }
    return AppRow(root, icon, name, pkg, check)
  }

  private fun styleModeChips() {
    val m = P.getInt("appf_mode", 0)
    appModeChips.forEachIndexed { i, t ->
      t.background = rr(if (i == m) acc else fieldC, 14)
      t.setTextColor(if (i == m) ink else fg)
    }
    if (::appCountTv.isInitialized) appCountTv.alpha = if (m == 0) 0.45f else 1f
  }

  private fun buildAppPicker(): View {
    val v = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); visibility = View.GONE; isClickable = true
      setPadding(dp(16), dp(22), dp(16), dp(8))
    }
    v.addView(tv("‹  Settings", 15f, acc, true).apply {
      background = rr(fieldC, 20); setPadding(dp(16), dp(9), dp(16), dp(9)); setOnClickListener { closeAppPicker() }
      layoutParams = LinearLayout.LayoutParams(-2, -2); pressable(this)
    })
    v.addView(tv("App filter", 26f, fg, true).apply { layoutParams = lp(14) })
    v.addView(tv("Choose which apps use the VPN. Applies at next START.", 13f, sub).apply { layoutParams = lp(2) })
    val labels = listOf("All apps", "Only selected", "All except")
    appModeChips = labels.mapIndexed { i, t ->
      TextView(this).apply {
        text = t; textSize = 13f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
        setOnClickListener { P.edit().putInt("appf_mode", i).apply(); styleModeChips(); updateSettingSummaries() }
        pressable(this)
      }
    }
    v.addView(row(*appModeChips.toTypedArray()).apply { layoutParams = lp(14) })
    val search = et("Search apps").apply {
      addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) { appQuery = s?.toString()?.trim()?.lowercase() ?: ""; filterApps() }
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
      })
    }
    v.addView(search)
    appCountTv = tv("", 12f, sub).apply { layoutParams = lp(8) }
    v.addView(row(
      appCountTv.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) },
      tv("Clear selection", 12f, acc, true).apply { setPadding(dp(8), dp(4), dp(4), dp(4)); setOnClickListener { appSel.clear(); saveAppSel(); filterApps() } }
    ).apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = lp(8) })
    appListV = ListView(this).apply {
      divider = null; dividerHeight = 0; isVerticalScrollBarEnabled = false
      layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) }
      adapter = object : BaseAdapter() {
        override fun getCount() = appShown.size
        override fun getItem(p: Int): Any = appShown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
          val r: AppRow
          if (cv == null) { r = newAppRow(); r.root.tag = r } else r = cv.tag as AppRow
          val it = appShown[p]
          val on = appSel.contains(it.pkg)
          r.name.text = it.label; r.pkg.text = it.pkg; r.icon.setImageDrawable(it.icon)
          r.check.text = if (on) "✓" else ""
          r.check.setTextColor(white)
          r.check.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (on) setColor(acc) else setStroke(dp(2), strokeC)
          }
          return r.root
        }
      }
      setOnItemClickListener { _, _, pos, _ ->
        if (pos in 0 until appShown.size) {
          val pk = appShown[pos].pkg
          if (!appSel.remove(pk)) appSel.add(pk)
          saveAppSel(); (adapter as BaseAdapter).notifyDataSetChanged(); updateAppCount()
        }
      }
    }
    v.addView(appListV)
    appPickV = v
    return v
  }

  private fun saveAppSel() { P.edit().putString("appf_list", appSel.joinToString(",")).apply(); updateSettingSummaries() }

  private fun updateAppCount() { appCountTv.text = "${appSel.size} selected  ·  ${appAll.size} apps" }

  private fun filterApps() {
    val src = appAll.filter { appQuery.isEmpty() || it.label.lowercase().contains(appQuery) || it.pkg.lowercase().contains(appQuery) }
    appShown.clear()
    appShown.addAll(src.sortedWith(compareByDescending<AppItem> { appSel.contains(it.pkg) }.thenBy { it.label.lowercase() }))
    (appListV.adapter as BaseAdapter).notifyDataSetChanged()
    updateAppCount()
  }

  private fun openAppPicker() {
    appSel.clear()
    (P.getString("appf_list", "") ?: "").split(",").filter { it.isNotBlank() }.forEach { appSel.add(it) }
    styleModeChips(); updateAppCount()
    appPickV.visibility = View.VISIBLE
    appPickV.alpha = 0f; appPickV.translationY = dp(24).toFloat()
    appPickV.animate().alpha(1f).translationY(0f).setDuration(220).start()
    Thread {
      val pm = packageManager
      val items = ArrayList<AppItem>()
      try {
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        for (ri in pm.queryIntentActivities(q, 0)) {
          val pk = ri.activityInfo.packageName
          if (pk == packageName || !seen.add(pk)) continue
          val ic: Drawable? = try { ri.loadIcon(pm) } catch (e: Exception) { null }
          items.add(AppItem(pk, ri.loadLabel(pm).toString(), ic))
        }
      } catch (e: Exception) { }
      items.sortBy { it.label.lowercase() }
      runOnUiThread { appAll.clear(); appAll.addAll(items); filterApps() }
    }.start()
  }

  private fun closeAppPicker() {
    if (!::appPickV.isInitialized || appPickV.visibility != View.VISIBLE) return
    appPickV.visibility = View.GONE
    updateSettingSummaries()
  }

  // ---------- VPNJantit auto account (the work itself runs in the background service) ----------
  /** Called by the VPNJantit manager (main thread) when something changed. */
  fun vjChanged(reload: Boolean) {
    if (!resumed || !::accBox.isInitialized) return
    if (reload) reloadAccounts()
    refresh()
  }

  private fun vjCheckUi(manual: Boolean) {
    save()
    val r = VjManager.check(this, manual)
    if (accsDirty()) reloadAccounts()   // an older account may have become MAIN/BACKUP
    when (r) {
      "busy" -> if (manual) toast("VPNJantit is already working on it")
      "none" -> if (manual) toast("MAIN and BACKUP accounts are both fine")
      "perm" -> if (manual) askOverlay()
      "wait" -> if (manual) toast("Waiting for the site's cooldown. See the status line on top.")
      "fail" -> toast("Could not start the account creator")
      else -> { }
    }
    refresh()
  }

  private fun pickVpnJantitServer() {
    if (VjManager.isBusy()) { toast("VPNJantit is already working on it"); return }
    if (!Settings.canDrawOverlays(this)) { askOverlay(); return }
    val names = VpnJantit.SERVERS.map { it.label }.toTypedArray()
    android.app.AlertDialog.Builder(this).setTitle("Choose a server")
      .setItems(names) { _, i ->
        save()
        val r = VjManager.launch(this, "", VpnJantit.SERVERS[i].code)
        if (r == "perm") askOverlay() else if (r == "fail") toast("Could not start the account creator")
        refresh()
      }.show()
  }
}
