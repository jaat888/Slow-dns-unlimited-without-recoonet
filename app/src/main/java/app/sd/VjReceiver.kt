package app.sd

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray

/**
 * Wakes up every hour (and after a phone restart). If a VPNJantit auto account is about to expire
 * it posts a notification; tapping it opens the app and the new account is created automatically.
 * (Android cannot open a screen/WebView from the background by itself, so one tap is required.)
 */
class VjReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        try { check(c) } catch (e: Throwable) { }
        schedule(c)
    }

    private fun check(c: Context) {
        val p = c.getSharedPreferences("a", 0)
        if (!p.getBoolean("vj_auto", false)) return
        val arr = JSONArray(p.getString("acc2", "[]"))
        var soonest = Long.MAX_VALUE
        for (k in 0 until arr.length()) {
            val o = arr.getJSONObject(k)
            if (o.optString("role").isEmpty()) continue
            val exp = o.optLong("exp", 0L)
            if (exp > 0 && exp < soonest) soonest = exp
        }
        if (soonest == Long.MAX_VALUE) return
        val left = soonest - System.currentTimeMillis()
        if (left > VpnJantit.RENEW_BEFORE_MS) return

        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "VPNJantit renewal", NotificationManager.IMPORTANCE_HIGH))
        val open = Intent(c, MainActivity::class.java)
            .putExtra("vj_auto", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(c, 92, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val txt = if (left <= 0) "Your account has expired. Tap to create a new one."
        else "Your account expires in ${left / 3600_000L}h ${left / 60_000L % 60}m. Tap to create a new one."
        val n = android.app.Notification.Builder(c, CH)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("VPNJantit renewal")
            .setContentText(txt)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(77, n)
    }

    companion object {
        const val CH = "vj_renew"

        fun schedule(c: Context) {
            val am = c.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(
                c, 91, Intent(c, VjReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (!c.getSharedPreferences("a", 0).getBoolean("vj_auto", false)) { am.cancel(pi); return }
            am.setAndAllowWhileIdle(AlarmManager.RTC, System.currentTimeMillis() + 60L * 60_000L, pi)
        }
    }
}
