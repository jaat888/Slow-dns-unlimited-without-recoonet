package app.sd

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Tells you before a VPNJantit account runs out ("Settings > Reminders"). Checks by itself in the background, also after a phone restart. */
class ExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        AutoOff.once(c)
        try { check(c) } catch (e: Throwable) { }
        schedule(c)
    }

    companion object {
        private fun on(c: Context) = c.getSharedPreferences("a", 0).getBoolean("expy", false)
        private fun leadMs(c: Context) = c.getSharedPreferences("a", 0).getInt("expyh", 3).coerceIn(1, 48) * 3_600_000L

        fun check(c: Context) {
            if (!on(c)) return
            val p = c.getSharedPreferences("a", 0)
            val a = VjStore.accounts(p)
            val now = System.currentTimeMillis()
            val done = HashSet<String>((p.getString("expy_done", "") ?: "").split(",").filter { it.isNotBlank() })
            var changed = false
            for (k in 0 until a.length()) {
                val o = a.getJSONObject(k)
                val exp = o.optLong("exp", 0L)
                if (o.optString("srv").isBlank() || exp <= now || exp - now > leadMs(c)) continue
                val key = o.optString("u") + "@" + exp
                if (key in done) continue
                done.add(key); changed = true
                val left = exp - now
                val t = (if (left >= 3_600_000L) (left / 3_600_000L).toString() + "h " else "") + (left / 60_000L % 60) + "m"
                val name = o.optString("name").ifBlank { o.optString("srv") }
                Notif.alert(c, 7100 + (k % 20), "Account ending soon", "$name (${o.optString("u")}) ends in $t. Open the app to renew it.")
            }
            if (changed) {
                // keep the list short: only entries that are still in the future
                val keep = done.filter { (it.substringAfter('@').toLongOrNull() ?: 0L) > now }
                p.edit().putString("expy_done", keep.joinToString(",")).apply()
            }
        }

        fun schedule(c: Context) {
            val am = c.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(c, 93, Intent(c, ExpiryReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (!on(c)) { am.cancel(pi); return }
            val now = System.currentTimeMillis()
            var at = now + 30 * 60_000L
            val a = VjStore.accounts(c.getSharedPreferences("a", 0))
            for (k in 0 until a.length()) {
                val o = a.getJSONObject(k)
                val exp = o.optLong("exp", 0L)
                if (o.optString("srv").isBlank() || exp <= now) continue
                val t = exp - leadMs(c)
                if (t > now + 5_000L && t < at) at = t
            }
            try { am.setAndAllowWhileIdle(AlarmManager.RTC, at, pi) } catch (e: Throwable) { }
        }
    }
}
