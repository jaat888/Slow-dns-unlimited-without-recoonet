package app.sd

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Wakes up every hour (and when a scheduled try is due, and after a phone restart).
 * If a VPNJantit auto account is needed it starts the background creator by itself.
 * If Android does not allow that (or "Display over other apps" is off) it posts a notification instead:
 * tapping it opens the app and the new account is created there.
 */
class VjReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        AutoOff.once(c)
        try { check(c) } catch (e: Throwable) { }
        schedule(c)
    }

    private fun check(c: Context) {
        val p = c.getSharedPreferences("a", 0)
        if (!p.getBoolean("vj_auto", false)) return
        val role = VjStore.needed(p) ?: return
        val r = try { VjManager.check(c, false) } catch (e: Throwable) { "fail" }
        if (r == "started" || r == "busy" || r == "wait") return

        // Could not start in the background: tell the user
        val exp = VjStore.accounts(p).let { a ->
            var e = 0L
            for (k in 0 until a.length()) { val o = a.getJSONObject(k); if (o.optString("role") == role) e = o.optLong("exp", 0L) }
            e
        }
        val left = exp - System.currentTimeMillis()
        val why = if (r == "perm") "Allow \"Display over other apps\" in Settings so it can run in the background. " else ""
        val txt = why + when {
            exp <= 0L -> "A VPNJantit account is needed. Tap to create it."
            left <= 0 -> "Your account has expired. Tap to create a new one."
            else -> "Your account expires in ${left / 3600_000L}h ${left / 60_000L % 60}m. Tap to create a new one."
        }
        Notif.alert(c, 77, "VPNJantit renewal", txt)
    }

    companion object {
        /** Next wake-up: in an hour, or earlier when a retry is scheduled. */
        fun schedule(c: Context) {
            val am = c.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(
                c, 91, Intent(c, VjReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val p = c.getSharedPreferences("a", 0)
            if (!p.getBoolean("vj_auto", false)) { am.cancel(pi); return }
            val now = System.currentTimeMillis()
            var at = now + 60L * 60_000L
            val nx = p.getLong("vj_next", 0L)
            if (nx > now && nx < at) at = nx
            am.setAndAllowWhileIdle(AlarmManager.RTC, at, pi)
        }
    }
}
