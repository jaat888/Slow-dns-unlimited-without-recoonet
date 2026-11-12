package app.sd

/** VPNJantit free India SSH servers (from the website's list). */
data class VpnJantitServer(val code: String, val label: String)

object VpnJantit {
    val SERVERS = listOf(
        VpnJantitServer("in10", "India 10 - Mumbai"),
        VpnJantitServer("in2", "India 2 - Mumbai"),
        VpnJantitServer("in5", "India 5 - Mumbai"),
        VpnJantitServer("in6", "India 6 - Bengaluru"),
        VpnJantitServer("in1", "India 1 - Bengaluru")
    )

    /** %s = server code (in6, in10 ...). If the site's link changes, only this line needs updating. */
    const val CREATE_URL = "https://www.vpnjantit.com/create-free-account?server=%s&type=SSH"

    /** Create the new account this long before the old one expires (a 1-day account at ~20 hours). */
    const val RENEW_BEFORE_MS = 4L * 3600_000L

    /** The site keeps a ~5 minute gap between two accounts; try again after 6-7 minutes. */
    const val COOLDOWN_MS = 6L * 60_000L + 30_000L

    // Broadcasts from the account-creator service (process :vj) to the main process
    const val ACTION_STATUS = "app.sd.VJ_STATUS"
    const val ACTION_RESULT = "app.sd.VJ_RESULT"
    const val X_TEXT = "vj_text"

    const val EXTRA_SERVER = "vj_server"
    const val EXTRA_LABEL = "vj_label"

    const val R_OK = "vj_ok"
    const val R_HOST = "vj_host"
    const val R_NS = "vj_ns"
    const val R_PUBKEY = "vj_pubkey"
    const val R_USER = "vj_user"
    const val R_PASS = "vj_pass"
    const val R_EXPIRES = "vj_expires"
    const val R_ERROR = "vj_error"
    const val R_RETRY_MS = "vj_retry_ms"
    /** Why / where it stopped (last step, time, what the page showed) and how long a good run took. */
    const val R_DETAIL = "vj_detail"
    const val R_TOOK = "vj_took"
}
