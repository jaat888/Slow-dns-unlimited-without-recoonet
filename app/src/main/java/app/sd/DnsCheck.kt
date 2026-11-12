package app.sd

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** One public DNS resolver. ms: -1 = not tested yet, -2 = no (valid) answer, otherwise the answer time in milliseconds. */
class DnsItem(val type: String, val addr: String, val label: String) {
    @Volatile var ms = -1
}

/** Tests public DNS resolvers (UDP, DoT, DoH) with a real DNS question, and measures the tunnel ping for the Home screen. */
object DnsCheck {

    // ───────────── built-in lists (address|name). A dead entry is simply shown as "no answer". ─────────────

    private const val UDP = """
8.8.8.8|Google
8.8.4.4|Google
1.1.1.1|Cloudflare
1.0.0.1|Cloudflare
1.1.1.2|Cloudflare Malware block
1.0.0.2|Cloudflare Malware block
1.1.1.3|Cloudflare Family
1.0.0.3|Cloudflare Family
9.9.9.9|Quad9
149.112.112.112|Quad9
9.9.9.10|Quad9 Unfiltered
149.112.112.10|Quad9 Unfiltered
9.9.9.11|Quad9 ECS
149.112.112.11|Quad9 ECS
208.67.222.222|OpenDNS
208.67.220.220|OpenDNS
208.67.222.123|OpenDNS FamilyShield
208.67.220.123|OpenDNS FamilyShield
94.140.14.14|AdGuard
94.140.15.15|AdGuard
94.140.14.140|AdGuard Unfiltered
94.140.14.141|AdGuard Unfiltered
94.140.14.15|AdGuard Family
94.140.15.16|AdGuard Family
185.228.168.9|CleanBrowsing Security
185.228.169.9|CleanBrowsing Security
185.228.168.10|CleanBrowsing Adult
185.228.169.11|CleanBrowsing Adult
185.228.168.168|CleanBrowsing Family
185.228.169.168|CleanBrowsing Family
8.26.56.26|Comodo Secure
8.20.247.20|Comodo Secure
4.2.2.1|Level3
4.2.2.2|Level3
4.2.2.3|Level3
4.2.2.4|Level3
4.2.2.5|Level3
4.2.2.6|Level3
209.244.0.3|Level3
209.244.0.4|Level3
64.6.64.6|Verisign
64.6.65.6|Verisign
45.90.28.0|NextDNS
45.90.30.0|NextDNS
84.200.69.80|DNS.WATCH
84.200.70.40|DNS.WATCH
77.88.8.8|Yandex Basic
77.88.8.1|Yandex Basic
77.88.8.88|Yandex Safe
77.88.8.2|Yandex Safe
77.88.8.7|Yandex Family
77.88.8.3|Yandex Family
156.154.70.1|Neustar UltraDNS
156.154.71.1|Neustar UltraDNS
156.154.70.2|Neustar Threat
156.154.71.2|Neustar Threat
156.154.70.3|Neustar Family
156.154.71.3|Neustar Family
156.154.70.4|Neustar Business
156.154.71.4|Neustar Business
156.154.70.5|Neustar Reliability
156.154.71.5|Neustar Reliability
80.80.80.80|Freenom World
80.80.81.81|Freenom World
195.46.39.39|SafeDNS
195.46.39.40|SafeDNS
74.82.42.42|Hurricane Electric
76.76.2.0|Control D
76.76.10.0|Control D
76.76.2.1|Control D Malware
76.76.10.1|Control D Malware
76.76.2.2|Control D Ads
76.76.10.2|Control D Ads
194.242.2.2|Mullvad
194.242.2.3|Mullvad Adblock
194.242.2.4|Mullvad Base
194.242.2.5|Mullvad Family
86.54.11.100|DNS4EU
86.54.11.200|DNS4EU
76.76.19.19|Alternate DNS
76.223.122.150|Alternate DNS
91.239.100.100|UncensoredDNS
89.233.43.71|UncensoredDNS
149.112.121.10|CIRA Protected
149.112.122.10|CIRA Protected
149.112.121.20|CIRA Family
149.112.122.20|CIRA Family
101.101.101.101|Quad101 Taiwan
101.102.103.104|Quad101 Taiwan
216.146.35.35|Dyn
216.146.36.36|Dyn
109.69.8.51|puntCAT
193.17.47.1|CZ.NIC ODVR
185.43.135.1|CZ.NIC ODVR
193.58.251.251|SkyDNS
185.121.177.177|OpenNIC
169.239.202.202|OpenNIC
80.67.169.12|FDN France
80.67.169.40|FDN France
5.9.164.112|Digitalcourage
94.130.180.225|DNS for Family
78.47.64.161|DNS for Family
116.202.176.26|LibreDNS
185.95.218.42|Digitale Gesellschaft
185.95.218.43|Digitale Gesellschaft
146.255.56.98|Applied Privacy
176.9.93.198|DNSForge
176.9.1.117|DNSForge
223.5.5.5|AliDNS China
223.6.6.6|AliDNS China
119.29.29.29|DNSPod China
119.28.28.28|Tencent China
114.114.114.114|114DNS China
114.114.115.115|114DNS China
180.76.76.76|Baidu China
1.2.4.8|CNNIC China
210.2.4.8|CNNIC China
168.126.63.1|KT Korea
168.126.63.2|KT Korea
164.124.101.2|LG U+ Korea
203.248.252.2|LG U+ Korea
210.220.163.82|SK Broadband Korea
219.250.36.130|SK Broadband Korea
"""

    private const val DOT = """
dns.google|Google
one.one.one.one|Cloudflare
1dot1dot1dot1.cloudflare-dns.com|Cloudflare
security.cloudflare-dns.com|Cloudflare Security
family.cloudflare-dns.com|Cloudflare Family
dns.quad9.net|Quad9
dns10.quad9.net|Quad9 Unfiltered
dns11.quad9.net|Quad9 ECS
dns.adguard-dns.com|AdGuard
unfiltered.adguard-dns.com|AdGuard Unfiltered
family.adguard-dns.com|AdGuard Family
dns.nextdns.io|NextDNS
dns.mullvad.net|Mullvad
adblock.dns.mullvad.net|Mullvad Adblock
base.dns.mullvad.net|Mullvad Base
family.dns.mullvad.net|Mullvad Family
extended.dns.mullvad.net|Mullvad Extended
all.dns.mullvad.net|Mullvad All
security-filter-dns.cleanbrowsing.org|CleanBrowsing Security
family-filter-dns.cleanbrowsing.org|CleanBrowsing Family
adult-filter-dns.cleanbrowsing.org|CleanBrowsing Adult
dot.sb|DNS.SB
dns.switch.ch|SWITCH Switzerland
dns.digitale-gesellschaft.ch|Digitale Gesellschaft
dot.libredns.gr|LibreDNS
dot1.applied-privacy.net|Applied Privacy
dns.njal.la|Njalla
dnsforge.de|DNSForge
unicast.uncensoreddns.org|UncensoredDNS
anycast.uncensoreddns.org|UncensoredDNS
dns.alidns.com|AliDNS China
dot.pub|DNSPod China
dns.twnic.tw|Quad101 Taiwan
p0.freedns.controld.com|Control D
p1.freedns.controld.com|Control D Malware
p2.freedns.controld.com|Control D Ads
p3.freedns.controld.com|Control D Ads and trackers
ordns.he.net|Hurricane Electric
dns.aa.net.uk|AAISP UK
resolver-eu.lelux.fi|Lelux Finland
dot.ffmuc.net|FFMUC Germany
dns0.eu|dns0.eu
kids.dns0.eu|dns0.eu Kids
zero.dns0.eu|dns0.eu Zero
dns.brahma.world|Brahma World
"""

    private const val DOH = """
https://dns.google/dns-query|Google
https://dns64.dns.google/dns-query|Google DNS64
https://cloudflare-dns.com/dns-query|Cloudflare
https://one.one.one.one/dns-query|Cloudflare
https://security.cloudflare-dns.com/dns-query|Cloudflare Security
https://family.cloudflare-dns.com/dns-query|Cloudflare Family
https://dns.quad9.net/dns-query|Quad9
https://dns10.quad9.net/dns-query|Quad9 Unfiltered
https://dns11.quad9.net/dns-query|Quad9 ECS
https://dns.adguard-dns.com/dns-query|AdGuard
https://unfiltered.adguard-dns.com/dns-query|AdGuard Unfiltered
https://family.adguard-dns.com/dns-query|AdGuard Family
https://dns.nextdns.io/dns-query|NextDNS
https://doh.opendns.com/dns-query|OpenDNS
https://doh.familyshield.opendns.com/dns-query|OpenDNS FamilyShield
https://dns.mullvad.net/dns-query|Mullvad
https://adblock.dns.mullvad.net/dns-query|Mullvad Adblock
https://base.dns.mullvad.net/dns-query|Mullvad Base
https://family.dns.mullvad.net/dns-query|Mullvad Family
https://doh.cleanbrowsing.org/doh/security-filter/|CleanBrowsing Security
https://doh.cleanbrowsing.org/doh/family-filter/|CleanBrowsing Family
https://doh.cleanbrowsing.org/doh/adult-filter/|CleanBrowsing Adult
https://doh.dns.sb/dns-query|DNS.SB
https://doh.libredns.gr/dns-query|LibreDNS
https://dns.switch.ch/dns-query|SWITCH Switzerland
https://dns.digitale-gesellschaft.ch/dns-query|Digitale Gesellschaft
https://doh.applied-privacy.net/query|Applied Privacy
https://dnsforge.de/dns-query|DNSForge
https://unicast.uncensoreddns.org/dns-query|UncensoredDNS
https://anycast.uncensoreddns.org/dns-query|UncensoredDNS
https://dns.alidns.com/dns-query|AliDNS China
https://doh.pub/dns-query|DNSPod China
https://dns.twnic.tw/dns-query|Quad101 Taiwan
https://freedns.controld.com/p0|Control D
https://freedns.controld.com/p1|Control D Malware
https://freedns.controld.com/p2|Control D Ads
https://freedns.controld.com/p3|Control D Ads and trackers
https://odvr.nic.cz/dns-query|CZ.NIC ODVR
https://dns.njal.la/dns-query|Njalla
https://ordns.he.net/dns-query|Hurricane Electric
https://dns0.eu/|dns0.eu
https://dns.aa.net.uk/dns-query|AAISP UK
https://resolver-eu.lelux.fi/dns-query|Lelux Finland
https://doh.ffmuc.net/dns-query|FFMUC Germany
https://dns.brahma.world/dns-query|Brahma World
https://wikimedia-dns.org/dns-query|Wikimedia
"""

    fun build(type: String): List<DnsItem> {
        val raw = when (type) { "dot" -> DOT; "doh" -> DOH; else -> UDP }
        return raw.lines().map { it.trim() }.filter { it.contains('|') }.map {
            val i = it.indexOf('|')
            DnsItem(type, it.substring(0, i).trim(), it.substring(i + 1).trim())
        }
    }

    // ───────────── one real DNS question ─────────────

    private fun query(): ByteArray {
        val id = (Math.random() * 65535).toInt()
        val b = ByteArrayOutputStream()
        b.write(id shr 8); b.write(id and 255)
        b.write(1); b.write(0)                       // flags: recursion desired
        b.write(0); b.write(1)                       // 1 question
        for (i in 0 until 6) b.write(0)              // no answer / authority / extra records
        for (l in listOf("example", "com")) { b.write(l.length); b.write(l.toByteArray()) }
        b.write(0)
        b.write(0); b.write(1); b.write(0); b.write(1)   // type A, class IN
        return b.toByteArray()
    }

    /** An answer counts only if it matches our question, is a reply, has no error and contains at least one record. */
    private fun valid(q: ByteArray, r: ByteArray, n: Int): Boolean {
        if (n < 12) return false
        if (r[0] != q[0] || r[1] != q[1]) return false
        if ((r[2].toInt() and 0x80) == 0) return false
        if ((r[3].toInt() and 0x0f) != 0) return false
        return (((r[6].toInt() and 0xff) shl 8) or (r[7].toInt() and 0xff)) >= 1
    }

    private fun since(t0: Long) = maxOf(1, ((System.nanoTime() - t0) / 1_000_000).toInt())

    // ───────────── the three transports ─────────────

    fun udp(ip: String, to: Int): Int = try {
        DatagramSocket().use { s ->
            s.soTimeout = to
            val q = query()
            val t0 = System.nanoTime()
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 53))
            val buf = ByteArray(1500)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            if (valid(q, buf, p.length)) since(t0) else -2
        }
    } catch (e: Throwable) { -2 }

    /** Time for connect + TLS + one question (DoT, port 853). */
    fun dot(host: String, to: Int): Int {
        var raw: Socket? = null
        var ssl: SSLSocket? = null
        return try {
            val t0 = System.nanoTime()
            raw = Socket()
            raw.connect(InetSocketAddress(host, 853), to)
            raw.soTimeout = to
            ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 853, true) as SSLSocket
            ssl.startHandshake()
            val q = query()
            val o = ssl.outputStream
            o.write(q.size shr 8); o.write(q.size and 255); o.write(q); o.flush()
            val i = DataInputStream(ssl.inputStream)
            val len = i.readUnsignedShort()
            val r = ByteArray(len)
            i.readFully(r)
            if (valid(q, r, len)) since(t0) else -2
        } catch (e: Throwable) { -2 } finally {
            try { ssl?.close() } catch (e: Throwable) { }
            try { raw?.close() } catch (e: Throwable) { }
        }
    }

    /** Time for connect + TLS + one question (DoH, POST application/dns-message). */
    fun doh(url: String, to: Int): Int = try {
        val q = query()
        val t0 = System.nanoTime()
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = to; c.readTimeout = to; c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/dns-message")
        c.setRequestProperty("Accept", "application/dns-message")
        c.outputStream.use { it.write(q) }
        val ok = c.responseCode == 200
        val r = if (ok) c.inputStream.use { it.readBytes() } else ByteArray(0)
        c.disconnect()
        if (ok && valid(q, r, r.size)) since(t0) else -2
    } catch (e: Throwable) { -2 }

    fun ping(d: DnsItem): Int = when (d.type) {
        "dot" -> dot(d.addr, 4000)
        "doh" -> doh(d.addr, 4000)
        else -> udp(d.addr, 1500)
    }

    /** Ping of the running VPN: one DNS question to the forward DNS, sent through the app's own SOCKS port (so it goes through the tunnels). */
    fun tunnel(port: Int, dns: String, to: Int): Int {
        var s: Socket? = null
        return try {
            s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", port), 1500)
            s.soTimeout = to
            val o = s.getOutputStream()
            val i = DataInputStream(s.getInputStream())
            val q = query()
            val t0 = System.nanoTime()
            o.write(byteArrayOf(5, 1, 0)); o.flush()
            i.readFully(ByteArray(2))
            val ip = InetAddress.getByName(dns).address
            o.write(byteArrayOf(5, 1, 0, 1) + ip + byteArrayOf(0, 53)); o.flush()
            i.readFully(ByteArray(10))
            o.write(q.size shr 8); o.write(q.size and 255); o.write(q); o.flush()
            val len = i.readUnsignedShort()
            val r = ByteArray(len)
            i.readFully(r)
            if (valid(q, r, len)) since(t0) else -2
        } catch (e: Throwable) { -2 } finally {
            try { s?.close() } catch (e: Throwable) { }
        }
    }

    // ───────────── bigger public list (downloaded when the user asks) ─────────────

    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /** IPv4 resolvers from public lists on the internet. Returns what it could read (empty if both sources fail). */
    fun downloadList(): List<String> {
        val out = LinkedHashSet<String>()
        for (u in listOf(
            "https://raw.githubusercontent.com/blacklanternsecurity/public-dns-servers/master/nameservers.txt",
            "https://public-dns.info/nameservers.txt")) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 20_000
                if (c.responseCode == 200) {
                    c.inputStream.bufferedReader().useLines { ls -> ls.map { it.trim() }.filter { IPV4.matches(it) }.forEach { out.add(it) } }
                }
                c.disconnect()
            } catch (e: Throwable) { LogBus.dbg("DNS list: ${e.javaClass.simpleName} for $u") }
        }
        return out.toList()
    }

    // ───────────── running many tests at once ─────────────

    /** Tests every item with a thread pool. onProgress is called after each item (from worker threads), onDone once at the end. */
    fun run(items: List<DnsItem>, threads: Int, stop: AtomicBoolean, onProgress: () -> Unit, onDone: () -> Unit) {
        Thread {
            val ex = Executors.newFixedThreadPool(threads)
            val latch = CountDownLatch(items.size)
            for (d in items) ex.execute {
                try { if (!stop.get()) d.ms = ping(d) } catch (e: Throwable) { d.ms = -2 }
                finally { latch.countDown(); onProgress() }
            }
            latch.await()
            ex.shutdown()
            onDone()
        }.start()
    }
}
