package app.sd

import java.util.concurrent.ConcurrentHashMap

/** Remembers which websites (DNS names) were looked up through the VPN, with a count. Shown in Settings > Traffic monitor. */
object TrafficLog {
    class Site(val name: String) {
        @Volatile var count = 0
        @Volatile var last = 0L
    }

    private val sites = ConcurrentHashMap<String, Site>()

    /** [q] is a raw DNS query packet. The name starts after the 12 byte header. */
    fun dnsQuery(q: ByteArray) {
        try {
            var i = 12
            val sb = StringBuilder()
            while (i < q.size) {
                val l = q[i].toInt() and 0xff
                if (l == 0 || l > 63) break
                if (i + 1 + l > q.size) return
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(q, i + 1, l, Charsets.US_ASCII))
                i += l + 1
            }
            val n = sb.toString().lowercase()
            if (n.isEmpty()) return
            if (sites.size > 600) clear()
            val s = sites.getOrPut(n) { Site(n) }
            s.count++
            s.last = System.currentTimeMillis()
        } catch (e: Throwable) { }
    }

    fun top(max: Int): List<Site> = sites.values.sortedByDescending { it.last }.take(max)
    fun clear() { sites.clear() }
}
