package app.sd

import android.content.Context

/**
 * One time only: VPNJantit auto-renew and the account-expiry reminder are switched OFF (also on phones where they were ON before),
 * their alarms are cancelled and their old notifications are removed. After that they stay exactly as the user sets them in the app.
 */
object AutoOff {
    fun once(c: Context) {
        try {
            val p = c.getSharedPreferences("a", 0)
            if (p.getBoolean("auto_off_v1", false)) return
            p.edit().putBoolean("vj_auto", false).putBoolean("expy", false).putLong("vj_hold", 0L).putLong("vj_next", 0L).putBoolean("auto_off_v1", true).commit()
            VjReceiver.schedule(c)       // cancels its alarm
            ExpiryReceiver.schedule(c)   // cancels its alarm
            Notif.cancel(c, 77)
            for (k in 0 until 20) Notif.cancel(c, 7100 + k)
        } catch (e: Throwable) { }
    }
}
