package app.sd

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The app logo (shield + signal arcs) drawn with a canvas so it can be animated. Same shape as the launcher icon. */
class LogoView(ctx: Context, private val dark: Boolean) : View(ctx) {
    /** 0..1 build-up of the logo, then it keeps pulsing. */
    var t = 0f
        set(v) { field = v; invalidate() }
    var pulse = 0f
        set(v) { field = v; invalidate() }

    private val shieldFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shieldLine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shield = Path()
    private val over = OvershootInterpolator(1.6f)

    init {
        val main = if (dark) 0xFF00E676.toInt() else 0xFF2563EB.toInt()
        val line = if (dark) Color.WHITE else 0xFF0F1630.toInt()
        shieldFill.style = Paint.Style.FILL
        shieldFill.color = if (dark) 0x22FFFFFF else 0x142563EB
        shieldLine.style = Paint.Style.STROKE
        shieldLine.strokeWidth = 3.2f
        shieldLine.strokeJoin = Paint.Join.ROUND
        shieldLine.color = line
        arc.style = Paint.Style.STROKE
        arc.strokeWidth = 4.2f
        arc.strokeCap = Paint.Cap.ROUND
        arc.color = main
        dot.style = Paint.Style.FILL
        dot.color = main
        shield.moveTo(54f, 22f)
        shield.lineTo(79f, 31f)
        shield.lineTo(79f, 54f)
        shield.cubicTo(79f, 69f, 67f, 78f, 54f, 85f)
        shield.cubicTo(41f, 78f, 29f, 69f, 29f, 54f)
        shield.lineTo(29f, 31f)
        shield.close()
    }

    private fun seg(a: Float, b: Float): Float = ((t - a) / (b - a)).coerceIn(0f, 1f)

    override fun onDraw(c: Canvas) {
        val s = min(width, height) / 108f
        c.save()
        c.translate((width - 108f * s) / 2f, (height - 108f * s) / 2f)
        c.scale(s, s)

        // shield pops in
        val p1 = seg(0f, 0.38f)
        val sc = 0.55f + 0.45f * over.getInterpolation(p1)
        c.save()
        c.scale(sc, sc, 54f, 54f)
        shieldFill.alpha = (p1 * (if (dark) 34 else 20)).toInt()
        c.drawPath(shield, shieldFill)
        shieldLine.alpha = (p1 * 255).toInt()
        c.drawPath(shield, shieldLine)
        c.restore()

        // centre dot
        val p2 = seg(0.30f, 0.46f)
        val dr = 3.4f * over.getInterpolation(p2) * (1f + 0.12f * sin(pulse * 6.2832f))
        dot.alpha = (p2 * 255).toInt()
        c.drawCircle(54f, 63f, dr, dot)

        // three signal arcs, one after another
        val radii = floatArrayOf(10f, 18f, 26f)
        for (i in 0..2) {
            val p = seg(0.42f + 0.13f * i, 0.58f + 0.13f * i)
            if (p <= 0f) continue
            val wave = if (t >= 1f) 0.65f + 0.35f * sin((pulse * 6.2832f) - i * 0.9f) else 1f
            arc.alpha = (p * 255 * wave).toInt().coerceIn(0, 255)
            val r = radii[i]
            c.drawArc(RectF(54f - r, 63f - r, 54f + r, 63f + r), -140f, 100f * p, false, arc)
        }
        c.restore()
    }
}

/**
 * Start screen: the logo builds up (shield, then the signal arcs like a network connecting), the name fades in,
 * then everything fades away and the app appears. About 2.4 seconds.
 */
class SplashView(ctx: Context, dark: Boolean) : FrameLayout(ctx) {
    private val logo = LogoView(ctx, dark)
    private val title = TextView(ctx)
    private val sub = TextView(ctx)
    private val d = resources.displayMetrics.density

    init {
        isClickable = true
        val top = if (dark) 0xFF070B1E.toInt() else 0xFFF5F8FF.toInt()
        val bot = if (dark) 0xFF132A66.toInt() else 0xFFFFFFFF.toInt()
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bot))
        val fg = if (dark) Color.WHITE else 0xFF0F1630.toInt()
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        col.addView(logo, LinearLayout.LayoutParams((150 * d).toInt(), (150 * d).toInt()))
        title.text = "Mollad DNS"
        title.textSize = 28f
        title.setTextColor(fg)
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.alpha = 0f
        title.letterSpacing = 0.04f
        col.addView(title)
        sub.text = "Secure DNS tunnel"
        sub.textSize = 13f
        sub.setTextColor(if (dark) 0xFF9FB0D9.toInt() else 0xFF6A7490.toInt())
        sub.alpha = 0f
        col.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * d).toInt() })
        addView(col, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    fun play(onEnd: () -> Unit) {
        // 1) logo build-up
        val build = ValueAnimator.ofFloat(0f, 1f)
        build.duration = 1500
        build.interpolator = LinearInterpolator()
        build.addUpdateListener { logo.t = it.animatedValue as Float }
        build.start()
        // 2) name and tagline fade in
        title.translationY = 14 * d
        title.animate().alpha(1f).translationY(0f).setStartDelay(700).setDuration(450).setInterpolator(DecelerateInterpolator()).start()
        sub.animate().alpha(1f).setStartDelay(1000).setDuration(450).start()
        // 3) the arcs keep pulsing while we wait a moment
        val pulse = ValueAnimator.ofFloat(0f, 1f)
        pulse.duration = 900
        pulse.repeatCount = 1
        pulse.startDelay = 1500
        pulse.interpolator = LinearInterpolator()
        pulse.addUpdateListener { logo.pulse = it.animatedValue as Float }
        pulse.start()
        // 4) fade out
        postDelayed({
            animate().alpha(0f).setDuration(320).withEndAction { onEnd() }.start()
        }, 2350)
    }
}
