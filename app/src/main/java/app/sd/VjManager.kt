package app.sd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** Everything about the saved accounts that does not need a screen (works from the app, a receiver or a service). */
object VjStore {
    val ROLE_ORDER = mapOf(
        "main" to listOf("in6", "in1", "in2", "in5", "in10"),
        "backup" to listOf("in10", "in2", "in5", "in1", "in6")
    )

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("a", 0)

    fun accounts(p: SharedPreferences): JSONArray =
        try { JSONArray(p.getString("acc2", "[]")) } catch (e: Exception) { JSONArray() }

    fun roles(p: SharedPreferences): List<String> =
        if (p.getBoolean("vj_backup", false)) listOf("main", "backup") else listOf("main")

    private fun roleObj(a: JSONArray, role: String): JSONObject? {
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("role") == role) return o
        }
        return null
    }

    fun needsRenew(o: JSONObject?): Boolean {
        if (o == null) return true
        val e = o.optLong("exp", 0L)
        return e > 0 && e - System.currentTimeMillis() <= VpnJantit.RENEW_BEFORE_MS
    }

    /** An account the app created earlier (no role yet) becomes MAIN/BACKUP, so no duplicate is made. */
    fun adopt(p: SharedPreferences) {
        val a = accounts(p)
        val now = System.currentTimeMillis()
        var changed = false
        for (role in roles(p)) {
            if (roleObj(a, role) != null) continue
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.optString("role").isBlank() && o.optString("srv").isNotBlank() && o.optLong("exp", 0L) > now) {
                    o.put("role", role); changed = true; break
                }
            }
        }
        if (changed) p.edit().putString("acc2", a.toString()).apply()
    }

    /** The role (main / backup) that needs a new account right now, or null when all is fine. */
    fun needed(p: SharedPreferences): String? {
        adopt(p)
        val a = accounts(p)
        return roles(p).firstOrNull { needsRenew(roleObj(a, it)) }
    }

    fun isFull(p: SharedPreferences, code: String) = p.getLong("vjfull_$code", 0L) > System.currentTimeMillis()

    fun pickServer(p: SharedPreferences, role: String): String? {
        val order = ROLE_ORDER[role] ?: return null
        val other = roleObj(accounts(p), if (role == "main") "backup" else "main")?.optString("srv")
        val free = order.filter { !isFull(p, it) }
        return free.firstOrNull { it != other } ?: free.firstOrNull()
    }

    fun cooldownLeft(p: SharedPreferences): Long = p.getLong("vj_last", 0L) + VpnJantit.COOLDOWN_MS - System.currentTimeMillis()

    /** The site's daily reset: 21:00 GMT+7 (about 7:30 PM IST). */
    fun nextReset(): Long {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT+07:00"))
        c.set(java.util.Calendar.HOUR_OF_DAY, 21); c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /** Puts a freshly created account into the saved list (replaces the old one of the same role). */
    fun applyResult(p: SharedPreferences, role: String, ns: String, pub: String, u: String, pw: String, srv: String, exp: Long) {
        val a = accounts(p)
        val old = if (role.isNotEmpty()) roleObj(a, role) else null
        var target: JSONObject? = old
        if (target == null && a.length() > 0) {
            val last = a.getJSONObject(a.length() - 1)
            val blank = listOf("ns", "pk", "u", "p", "d").all { last.optString(it).isBlank() } && last.optString("role").isBlank()
            if (blank) target = last
        }
        if (target != null) {
            target.put("ns", ns).put("pk", pub).put("u", u).put("p", pw)
                .put("role", role).put("srv", srv).put("exp", exp).put("on", true)
            if (target.optString("d").isBlank()) target.put("d", "8.8.8.8")
            if (old == null) target.put("n", 1)
        } else {
            a.put(JSONObject().put("ns", ns).put("pk", pub).put("u", u).put("p", pw).put("d", "8.8.8.8")
                .put("n", 1).put("on", true).put("role", role).put("srv", srv).put("exp", exp))
        }
        p.edit().putString("acc2", a.toString()).apply()
    }
}

/**
 * Decides when a VPNJantit account is needed and starts the background creator (VjService).
 * Lives in the main process; the creator reports back through [VjBridge].
 */
object VjManager {
    @Volatile var status = ""
    @Volatile private var busy = false
    @Volatile private var busySince = 0L
    private var tries = 0
    private val h = Handler(Looper.getMainLooper())

    fun isBusy(): Boolean {
        if (busy && System.currentTimeMillis() - busySince > 7 * 60_000L) { busy = false; status = "" }
        return busy
    }

    /** One line for the screen: what is happening right now, or when the next try happens. */
    fun line(c: Context): String {
        if (isBusy()) return status.ifBlank { "Working in the background..." }
        val p = VjStore.prefs(c)
        val n = p.getLong("vj_next", 0L)
        val now = System.currentTimeMillis()
        if (n > now) return "Next VPNJantit try in " + ((n - now) / 60_000L + 1) + " min"
        return ""
    }

    private fun toast(c: Context, s: String) {
        h.post { try { Toast.makeText(c.applicationContext, s, Toast.LENGTH_LONG).show() } catch (e: Throwable) { } }
    }

    private fun ui(reload: Boolean = false) { MainActivity.inst?.vjChanged(reload) }

    /**
     * Result codes: busy, none (nothing to do), wait (scheduled for later), perm (overlay permission missing),
     * fail (could not start), started.
     */
    fun check(c: Context, manual: Boolean): String {
        val p = VjStore.prefs(c)
        if (isBusy()) return "busy"
        if (!manual) {
            if (!p.getBoolean("vj_auto", false)) return "none"
            if (System.currentTimeMillis() < p.getLong("vj_hold", 0L)) return "wait"
        }
        val role = VjStore.needed(p) ?: return "none"
        return start(c, role)
    }

    /** Called every second while the app is open and by the alarm: runs a scheduled try when its time has come. */
    fun tick(c: Context) {
        val p = VjStore.prefs(c)
        if (!p.getBoolean("vj_auto", false) || isBusy()) return
        val n = p.getLong("vj_next", 0L)
        if (n > 0L && System.currentTimeMillis() >= n) check(c, false)
    }

    private fun schedule(c: Context, ms: Long, why: String) {
        val p = VjStore.prefs(c)
        val at = System.currentTimeMillis() + ms + (0..40_000).random()
        p.edit().putLong("vj_next", at).apply()
        VjReceiver.schedule(c)
        LogBus.add("VPNJantit: $why - next try in ${ms / 60_000L + 1} min")
        ui()
    }

    fun start(c: Context, role: String): String {
        val p = VjStore.prefs(c)
        if (!Settings.canDrawOverlays(c)) return "perm"
        val wait = VjStore.cooldownLeft(p)
        if (wait > 0) { schedule(c, wait, "the site allows one account every few minutes"); return "wait" }
        val srv = VjStore.pickServer(p, role)
        if (srv == null) {
            schedule(c, VjStore.nextReset() - System.currentTimeMillis(), "all servers are full")
            return "wait"
        }
        return launch(c, role, srv)
    }

    /** role = "" for an account the user asked for by hand. */
    fun launch(c: Context, role: String, srv: String): String {
        if (isBusy()) return "busy"
        if (!Settings.canDrawOverlays(c)) return "perm"
        val p = VjStore.prefs(c)
        busy = true; busySince = System.currentTimeMillis(); status = "Starting..."
        p.edit().putString("vj_role", role).putLong("vj_next", 0L).apply()
        LogBus.add("VPNJantit: creating a ${if (role.isEmpty()) "" else role.uppercase() + " "}account on $srv")
        try {
            VjService.start(c, srv)
        } catch (e: Throwable) {
            busy = false; status = ""
            LogBus.add("VPNJantit: could not start the creator: ${e.message}")
            return "fail"
        }
        ui()
        return "started"
    }

    fun cancel(c: Context) { VjService.cancel(c) }

    fun onStatus(c: Context, text: String) {
        if (text.isBlank()) return
        if (busy) status = text
        LogBus.dbg("VPNJantit: $text")
        ui()
    }

    fun onResult(c: Context, d: Intent) {
        val p = VjStore.prefs(c)
        busy = false; status = ""
        val role = p.getString("vj_role", "") ?: ""
        p.edit().putString("vj_role", "").apply()
        val srv = d.getStringExtra(VpnJantit.EXTRA_SERVER).orEmpty()
        val ok = d.getBooleanExtra(VpnJantit.R_OK, false)
        // Keep what the user typed in the app, then write the new account
        MainActivity.inst?.saveNow()

        if (!ok) {
            val err = d.getStringExtra(VpnJantit.R_ERROR).orEmpty().ifBlank { "Unknown error" }
            LogBus.add("VPNJantit: FAILED - $err")
            if (err == "Cancelled") { p.edit().putLong("vj_hold", System.currentTimeMillis() + 10 * 60_000L).apply(); ui(); return }
            if (Regex("full", RegexOption.IGNORE_CASE).containsMatchIn(err) && srv.isNotEmpty()) {
                p.edit().putLong("vjfull_$srv", VjStore.nextReset()).apply()
                toast(c, "$srv is full, trying another server")
                if (role.isNotEmpty() && tries++ < 5) { start(c, role) } else tries = 0
                ui(); return
            }
            tries = 0
            toast(c, "VPNJantit: $err")
            Notif.alert(c, 44, "VPNJantit could not create an account", err)
            val retry = d.getLongExtra(VpnJantit.R_RETRY_MS, 0L)
            if (role.isNotEmpty()) {
                if (retry > 0) schedule(c, retry, err) else p.edit().putLong("vj_hold", System.currentTimeMillis() + 10 * 60_000L).apply()
            }
            ui(); return
        }

        tries = 0
        val u = d.getStringExtra(VpnJantit.R_USER).orEmpty()
        val pw = d.getStringExtra(VpnJantit.R_PASS).orEmpty()
        val pub = d.getStringExtra(VpnJantit.R_PUBKEY).orEmpty()
        val ns = d.getStringExtra(VpnJantit.R_NS).orEmpty().ifBlank { "ns-$srv.vpnjantit.com" }
        val exp = d.getLongExtra(VpnJantit.R_EXPIRES, 0L)
        if (u.isBlank() || pw.isBlank() || pub.isBlank()) {
            toast(c, "The account details came back incomplete")
            p.edit().putLong("vj_hold", System.currentTimeMillis() + 10 * 60_000L).apply()
            ui(); return
        }
        VjStore.applyResult(p, role, ns, pub, u, pw, srv, exp)
        p.edit().putLong("vj_last", System.currentTimeMillis()).apply()
        VjReceiver.schedule(c)
        val e = if (exp > 0) " - expires " + java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.US).format(java.util.Date(exp)) else ""
        val label = (if (role.isNotEmpty()) role.uppercase() + " " else "") + srv + ": " + u + e
        LogBus.add("VPNJantit: account created ($label)")
        toast(c, "Account created ($srv): $u$e")
        Notif.alert(c, 43, "VPNJantit account ready", label)

        if (VpnSvc.running) {
            LogBus.add("VPNJantit: restarting the VPN to load the new account")
            try { c.startService(Intent(c, VpnSvc::class.java).setAction("restart")) } catch (x: Throwable) { }
        }
        // The other role (BACKUP) may need an account too: wait out the site's cooldown first
        VjStore.needed(p)?.let { schedule(c, VpnJantit.COOLDOWN_MS, "next account") }
        ui(true)
    }
}

/** Receives status lines and results from the account creator (another process). */
class VjBridge : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        if (i == null) return
        try {
            when (i.action) {
                VpnJantit.ACTION_STATUS -> VjManager.onStatus(c, i.getStringExtra(VpnJantit.X_TEXT).orEmpty())
                VpnJantit.ACTION_RESULT -> VjManager.onResult(c, i)
            }
        } catch (e: Throwable) {
            LogBus.add("VPNJantit bridge error: ${e.message}")
        }
    }
}
