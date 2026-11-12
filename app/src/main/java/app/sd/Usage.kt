package app.sd

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar
import kotlin.math.min

/**
 * How much data went through the VPN tunnels, kept for a few weeks (30 minute buckets).
 * Counted from the same numbers as the Home screen (Stats.up / Stats.down), so it is only VPN data, not other apps' normal data.
 * Shown in Settings > Traffic monitor > Usage.
 */
object Usage {
    private const val BUCKET = 30 * 60_000L
    private var sp: SharedPreferences? = null
    private var lastUp = 0L
    private var lastDown = 0L
    private var syncs = 0

    private fun pref(c: Context): SharedPreferences {
        var p = sp
        if (p == null) { p = c.applicationContext.getSharedPreferences("usage", 0); sp = p }
        return p!!
    }

    /** Call after Stats.reset() (a new VPN session starts counting from zero). */
    @Synchronized fun rebase() { lastUp = 0L; lastDown = 0L }

    /** Adds the bytes moved since the last call to the current 30 minute bucket. */
    @Synchronized fun sync(c: Context) {
        try {
            val u = Stats.up.get(); val d = Stats.down.get()
            val du = if (u >= lastUp) u - lastUp else u
            val dd = if (d >= lastDown) d - lastDown else d
            lastUp = u; lastDown = d
            if (du <= 0L && dd <= 0L) return
            val p = pref(c)
            val key = "b" + (System.currentTimeMillis() / BUCKET)
            val old = (p.getString(key, "0,0") ?: "0,0").split(",")
            val nu = (old.getOrNull(0)?.toLongOrNull() ?: 0L) + du
            val nd = (old.getOrNull(1)?.toLongOrNull() ?: 0L) + dd
            val e = p.edit().putString(key, "$nu,$nd")
            if (++syncs % 200 == 0) {
                val limit = System.currentTimeMillis() / BUCKET - 75L * 48L
                for (k in p.all.keys) if (k.startsWith("b") && (k.substring(1).toLongOrNull() ?: Long.MAX_VALUE) < limit) e.remove(k)
            }
            e.apply()
        } catch (e: Throwable) { }
    }

    /** [upload, download] bytes for buckets that start in [from, to). */
    @Synchronized fun sum(c: Context, from: Long, to: Long): LongArray {
        var u = 0L; var d = 0L
        try {
            for ((k, v) in pref(c).all) {
                if (!k.startsWith("b")) continue
                val t = (k.substring(1).toLongOrNull() ?: continue) * BUCKET
                if (t < from || t >= to) continue
                val q = (v as? String)?.split(",") ?: continue
                u += q.getOrNull(0)?.toLongOrNull() ?: 0L
                d += q.getOrNull(1)?.toLongOrNull() ?: 0L
            }
        } catch (e: Throwable) { }
        return longArrayOf(u, d)
    }

    @Synchronized fun clear(c: Context) {
        try { pref(c).edit().clear().apply() } catch (e: Throwable) { }
        lastUp = Stats.up.get(); lastDown = Stats.down.get()
    }

    // ---------- dates ----------

    /** Midnight at the start of the day [daysAgo] days before [now] (negative = in the future). */
    fun midnight(now: Long, daysAgo: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return c.timeInMillis
    }

    private fun cycleAt(y: Int, m: Int, day: Int): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(y, m, 1, 0, 0, 0)
        c.set(Calendar.DAY_OF_MONTH, min(day, c.getActualMaximum(Calendar.DAY_OF_MONTH)))
        return c.timeInMillis
    }

    /** Start of the usage month that contains [now], when a month starts on [day] (1-28). */
    fun cycleStart(now: Long, day: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        var y = c.get(Calendar.YEAR); var m = c.get(Calendar.MONTH)
        var s = cycleAt(y, m, day)
        if (s > now) { m--; if (m < 0) { m = 11; y-- }; s = cycleAt(y, m, day) }
        return s
    }

    /** Start of the usage month that follows the one starting at [start]. */
    fun nextStart(start: Long, day: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = start
        var y = c.get(Calendar.YEAR); var m = c.get(Calendar.MONTH) + 1
        if (m > 11) { m = 0; y++ }
        return cycleAt(y, m, day)
    }
}
