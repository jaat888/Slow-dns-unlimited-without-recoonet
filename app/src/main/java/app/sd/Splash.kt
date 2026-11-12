package app.sd

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/**
 * The app logo: an "M" with a signal (a dot and two arcs) rising from its middle. Drawn with a canvas so it can be animated.
 * Same shape as the launcher icon (res/drawable/fg_dark.xml).
 */
class LogoView(ctx: Context, private val dark: Boolean) : View(ctx) {
    /** 0..1 build-up: the M is drawn, then the dot pops, then the arcs sweep out. */
    var t = 0f
        set(v) { field = v; invalidate() }
    /** 0..1, repeating: a soft ring that spreads out from the dot, like a signal. */
    var ripple = 0f
        set(v) { field = v; invalidate() }
    /** 0..1: soft glow behind the logo. */
    var glow = 0f
        set(v) { field = v; invalidate() }

    private val mainC = if (dark) Color.WHITE else 0xFF0F1630.toInt()
    private val accC = if (dark) 0xFF00E676.toInt() else 0xFF2563EB.toInt()
    private val mPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mPath = Path()
    private val seg = Path()
    private val measure: PathMeasure
    private val over = OvershootInterpolator(2.4f)
    private val cx = 54f
    private val cy = 48f

    init {
        mPaint.style = Paint.Style.STROKE; mPaint.strokeWidth = 9f; mPaint.strokeCap = Paint.Cap.ROUND; mPaint.strokeJoin = Paint.Join.ROUND
        mPaint.color = mainC
        arcPaint.style = Paint.Style.STROKE; arcPaint.strokeWidth = 4.4f; arcPaint.strokeCap = Paint.Cap.ROUND; arcPaint.color = accC
        dotPaint.style = Paint.Style.FILL; dotPaint.color = accC
        ringPaint.style = Paint.Style.STROKE; ringPaint.strokeWidth = 1.8f; ringPaint.color = accC
        mPath.moveTo(32f, 72f); mPath.lineTo(32f, 46f); mPath.lineTo(54f, 68f); mPath.lineTo(76f, 46f); mPath.lineTo(76f, 72f)
        measure = PathMeasure(mPath, false)
    }

    private fun part(a: Float, b: Float): Float = ((t - a) / (b - a)).coerceIn(0f, 1f)

    override fun onDraw(c: Canvas) {
        val s = min(width, height) / 108f
        c.save()
        c.translate((width - 108f * s) / 2f, (height - 108f * s) / 2f)
        c.scale(s, s)

        // soft glow
        if (glow > 0f) {
            glowPaint.shader = RadialGradient(cx, 56f, 62f, intArrayOf((accC and 0x00FFFFFF) or ((glow * (if (dark) 90 else 60)).toInt() shl 24), 0), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, 56f, 62f, glowPaint)
        }

        // 1) the M is drawn like a pen stroke
        val p1 = DecelerateInterpolator(1.4f).getInterpolation(part(0f, 0.55f))
        if (p1 > 0f) {
            seg.reset()
            measure.getSegment(0f, measure.length * p1, seg, true)
            mPaint.alpha = 255
            c.drawPath(seg, mPaint)
        }

        // 2) the dot pops
        val p2 = part(0.50f, 0.68f)
        if (p2 > 0f) c.drawCircle(cx, cy, 4f * over.getInterpolation(p2), dotPaint)

        // 3) the two arcs sweep out, one after the other
        val radii = floatArrayOf(10f, 19f)
        for (i in 0..1) {
            val p = part(0.62f + 0.16f * i, 0.82f + 0.16f * i)
            if (p <= 0f) continue
            val r = radii[i]
            arcPaint.alpha = (p * 255).toInt()
            c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -140f + 50f * (1f - p), 100f * p, false, arcPaint)
        }

        // 4) signal rings spreading out from the dot
        if (t >= 0.85f) {
            for (k in 0..1) {
                val q = (ripple + k * 0.5f) % 1f
                ringPaint.alpha = ((1f - q) * 120).toInt()
                c.drawCircle(cx, cy, 6f + 40f * q, ringPaint)
            }
        }
        c.restore()
    }
}

/**
 * Start screen, in the style of a streaming-app intro (about 2.9 s):
 * the M is drawn, the signal appears and spreads, the name slides in letter by letter,
 * then the logo zooms toward you and the app appears behind it.
 */
class SplashView(ctx: Context, private val dark: Boolean) : FrameLayout(ctx) {
    private val logo = LogoView(ctx, dark)
    private val sub = TextView(ctx)
    private val letters = ArrayList<TextView>()
    private val d = resources.displayMetrics.density
    private val col = LinearLayout(ctx)
    private val name = LinearLayout(ctx)
    private var ended = false

    init {
        isClickable = true
        val top = if (dark) 0xFF050814.toInt() else 0xFFF7F9FF.toInt()
        val bot = if (dark) 0xFF10245C.toInt() else 0xFFE4ECFF.toInt()
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bot))
        val fg = if (dark) Color.WHITE else 0xFF0F1630.toInt()
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        col.addView(logo, LinearLayout.LayoutParams((190 * d).toInt(), (190 * d).toInt()))
        name.orientation = LinearLayout.HORIZONTAL
        name.gravity = Gravity.CENTER
        for (ch in "MOLLAD DNS") {
            val tv = TextView(ctx)
            tv.text = ch.toString()
            tv.textSize = 26f
            tv.letterSpacing = 0.12f
            tv.setTextColor(fg)
            tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            tv.alpha = 0f
            tv.translationY = 22 * d
            letters.add(tv)
            name.addView(tv)
        }
        col.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (-8 * d).toInt() })
        sub.text = "SECURE  DNS  TUNNEL"
        sub.textSize = 11f
        sub.letterSpacing = 0.28f
        sub.setTextColor(if (dark) 0xFF8FA3D9.toInt() else 0xFF5B6B95.toInt())
        sub.alpha = 0f
        col.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * d).toInt() })
        addView(col, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        logo.scaleX = 0.8f; logo.scaleY = 0.8f
    }

    fun play(onEnd: () -> Unit) {
        fun finish() {
            if (!ended) { ended = true; onEnd() }
        }
        // tap to skip
        setOnClickListener { animate().alpha(0f).setDuration(180).withEndAction { finish() }.start() }

        // 1) logo build-up + glow + gentle grow
        val build = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1450; interpolator = LinearInterpolator()
            addUpdateListener { logo.t = it.animatedValue as Float }
        }
        val glow = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100; startDelay = 200
            addUpdateListener { logo.glow = it.animatedValue as Float }
        }
        logo.animate().scaleX(1f).scaleY(1f).setDuration(1500).setInterpolator(DecelerateInterpolator(1.6f)).start()
        // 2) signal rings keep spreading
        val ring = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100; repeatCount = 1; startDelay = 1200; interpolator = LinearInterpolator()
            addUpdateListener { logo.ripple = it.animatedValue as Float }
        }
        build.start(); glow.start(); ring.start()
        // 3) the name slides in letter by letter
        letters.forEachIndexed { i, tv ->
            tv.animate().alpha(1f).translationY(0f).setStartDelay(900L + i * 55L).setDuration(420)
                .setInterpolator(DecelerateInterpolator(1.8f)).start()
        }
        sub.animate().alpha(1f).setStartDelay(1550).setDuration(450).start()
        // 4) zoom toward the viewer and reveal the app
        postDelayed({
            val zoom = AnimatorSet()
            zoom.playTogether(
                ObjectAnimator.ofFloat(col, View.SCALE_X, 1f, 1.7f), ObjectAnimator.ofFloat(col, View.SCALE_Y, 1f, 1.7f),
                ObjectAnimator.ofFloat(this@SplashView, View.ALPHA, 1f, 0f))
            zoom.duration = 480
            zoom.interpolator = AccelerateInterpolator(1.3f)
            zoom.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { finish() }
            })
            zoom.start()
        }, 2500)
    }
}
