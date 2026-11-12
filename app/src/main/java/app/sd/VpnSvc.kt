package app.sd
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import hev.htproxy.TProxyService
import org.json.JSONArray
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class VpnSvc : VpnService() {
  companion object {
    @Volatile var running = false
    val NATIVE = Any()
    @Volatile var total = 0
    @Volatile var socksPortPub = 0
    @Volatile private var inst: VpnSvc? = null
    fun connected(): Int = inst?.let { i -> i.sess.count { it.isConnected && it !in i.suspect } } ?: 0
  }

  private val on = AtomicBoolean(false)
  private val stopped = AtomicBoolean(true)
  @Volatile private var hevOn = false
  private val sess = CopyOnWriteArrayList<Session>()
  private val procs = CopyOnWriteArrayList<Process>()
  private val rr = AtomicInteger()
  private val suspect: MutableSet<Session> = java.util.Collections.newSetFromMap(ConcurrentHashMap<Session, Boolean>())
  @Volatile private var socksPort = 3000
  private val relaySocks = CopyOnWriteArrayList<DatagramSocket>()
  private val relayPorts = ConcurrentHashMap<String, Int>()
  private val gateLock = Any()
  private var gateNext = 0L
  private val qSent = AtomicInteger()
  private val qGot = AtomicInteger()
  private val gen = AtomicInteger()
  private val busy = ConcurrentHashMap<Session, AtomicInteger>()
  @Volatile private var pool: ExecutorService = Executors.newCachedThreadPool()
  private var tun: ParcelFileDescriptor? = null
  private var ss: ServerSocket? = null
  private var wl: PowerManager.WakeLock? = null
  private val resolverIdx = AtomicInteger(0)

  // Resolver rotation: picks one entry in turn from the "resolvers" setting (comma-separated, e.g. "8.8.8.8,1.1.1.1,9.9.9.9").
  // If the setting is empty, the account's own dns-target is used (old behavior).
  private fun pickResolver(accountDns: String): String {
    val pr = getSharedPreferences("a", 0)
    val list = (pr.getString("resolvers", "") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (list.isEmpty()) return resolver(accountDns)
    val i = Math.floorMod(resolverIdx.getAndIncrement(), list.size)
    return resolver(list[i])
  }

  private fun notif(t: String): Notification {
    val stopPi = PendingIntent.getService(this, 3, Intent(this, VpnSvc::class.java).setAction("stop"),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val act = Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Disconnect", stopPi).build()
    // "Hide notifications" ON: a silent, collapsed notification (Android needs one for a running VPN, but no status-bar icon, no sound, nothing on the lock screen)
    if (Notif.hidden(this)) return Notification.Builder(this, Notif.CH_HIDE).setContentTitle("Mollad DNS").setContentText(t)
      .setSmallIcon(R.drawable.ic_stat).setOngoing(true).setShowWhen(false).setVisibility(Notification.VISIBILITY_SECRET).setContentIntent(Notif.open(this)).build().let { Notif.own(it) }
    return Notification.Builder(this, Notif.CH_VPN).setContentTitle("Mollad DNS").setContentText(t)
      .setSmallIcon(R.drawable.ic_stat).setLargeIcon(Notif.logo(this)).setOngoing(true).setContentIntent(Notif.open(this)).addAction(act).build().let { Notif.own(it) }
  }

  // The DNS server the tunnel forwards phone DNS queries to (setting "fdns", default 1.1.1.1)
  private fun fdns(): String {
    val v = (getSharedPreferences("a", 0).getString("fdns", "1.1.1.1") ?: "1.1.1.1").trim()
    return if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}\$").matches(v)) v else "1.1.1.1"
  }

  override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
    LogBus.setup(filesDir); LogBus.configure(getSharedPreferences("a", 0))
    Notif.channels(this)
    try { startForeground(1, notif(if (i?.action == "stop") "Stopping..." else "Connecting...")) } catch (e: Throwable) { LogBus.add("notification/foreground error: ${e.message}") }
    // The system restarted the service (null intent) -> do not start by ourselves, otherwise a crash turns into a loop
    if (i == null) { try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) {}; stopSelf(); return START_NOT_STICKY }
    if (i?.action == "stop") { stop(); try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) {}; stopSelf(); return START_NOT_STICKY }
    // "restart": reload the accounts (for example after a new VPNJantit account) without leaving the foreground state
    if (i?.action == "restart") {
      if (on.get()) { LogBus.add("VPN restart"); stop() }
      else { try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) {}; stopSelf(); return START_NOT_STICKY }
    }
    if (on.compareAndSet(false, true)) {
      stopped.set(false)
      running = true; inst = this
      try { DnsWidget.refresh(this) } catch (e: Throwable) { }
      LogBus.add("VPN service start")
      LogBus.dbg("android " + android.os.Build.VERSION.SDK_INT + " | abi: " + android.os.Build.SUPPORTED_ABIS.joinToString() + " | libs: " + (File(applicationInfo.nativeLibraryDir).list()?.joinToString() ?: "EMPTY"))
      // Battery saver: the phone may sleep while the VPN runs (no wake lock)
      if (!getSharedPreferences("a", 0).getBoolean("nowl", false)) wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sd:w").also { it.acquire() }
      val g = gen.incrementAndGet(); pool = Executors.newCachedThreadPool()
      thread { try { begin(g) } catch (e: Throwable) { LogBus.add("start error: ${e.message}"); stop(); try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (x: Exception) {}; stopSelf() } }
    }
    return START_STICKY
  }
  override fun onDestroy() { stop() }

  private fun alive(g: Int) = on.get() && gen.get() == g

  private fun nap(ms: Long, g: Int) { var t = 0L; while (t < ms && alive(g)) { Thread.sleep(250); t += 250 } }

  private fun begin(g: Int) {
    Stats.reset(); Usage.rebase(); Limit.load(getSharedPreferences("a", 0))
    qSent.set(0); qGot.set(0); relayPorts.clear()
    thread {
      var ls = 0; var lg = 0
      while (alive(g)) {
        nap(5000, g)
        Usage.sync(this@VpnSvc)
        val s = qSent.get(); val gt = qGot.get(); val ds = s - ls; val dg = gt - lg; ls = s; lg = gt
        if (ds > 0) LogBus.dbg("DNS: ${ds / 5} queries/s sent, ${dg / 5} replies/s received (${dg * 100 / ds}%)")
      }
    }
    val arr = try { JSONArray(getSharedPreferences("a", 0).getString("acc2", "[]")) } catch (e: Exception) { JSONArray() }
    var k = 0; var t = 0
    val tbase = getSharedPreferences("a", 0).getInt("tport", 2000).coerceIn(1024, 65000)
    val jobs = ArrayList<() -> Unit>()
    val rot = getSharedPreferences("a", 0).getBoolean("rot", false)
    val accl = ArrayList<Acct>()
    for (a in 0 until arr.length()) {
      val o = arr.getJSONObject(a)
      if (!o.optBoolean("on", true)) continue
      val ns = o.optString("ns"); val pk = o.optString("pk"); val u = o.optString("u"); val p = o.optString("p"); val d = o.optString("d")
      if (ns.isEmpty() || pk.isEmpty() || u.isEmpty() || p.isEmpty() || d.isEmpty()) { LogBus.add("Account ${a + 1} is incomplete - skipped"); continue }
      var n = o.optInt("n", 1).coerceIn(1, 20)
      // Battery saver: at most 5 tunnels run in total
      if (getSharedPreferences("a", 0).getBoolean("bs", false)) {
        n = if (rot) minOf(n, 5) else minOf(n, 5 - t)
        if (n <= 0) { LogBus.add("Battery saver: only 5 tunnels run, account ${a + 1} skipped"); continue }
      }
      t += n
      accl.add(Acct(a + 1, ns, pk, u, p, d, n))
      if (!rot) for (j in 1..n) { k++; val port = tbase + k; val label = "A${a + 1}#$j"; jobs.add { tunnel(ns, pk, u, p, d, port, label, g) } }
    }
    total = if (rot) (accl.firstOrNull()?.n ?: 0) else t
    if (t == 0) { LogBus.add("No account is turned ON"); stop(); stopSelf(); return }
    ss = bindSocks(getSharedPreferences("a", 0).getInt("sport", 3000))
    socksPort = ss!!.localPort
    socksPortPub = socksPort
    LogBus.dbg("SOCKS port: $socksPort")
    thread { while (alive(g)) { try { val c = ss!!.accept(); pool.execute { try { handle(c) } catch (e: Exception) { c.close() } } } catch (e: Exception) { break } } }
    // tunnels start one after another (no burst of load on CPU/resolver)
    if (rot) { LogBus.add("Account rotation ON: ${accl.size} accounts, switching every ${getSharedPreferences("a", 0).getInt("rint", 5)} sec"); thread { try { rotate(accl, g) } catch (e: Throwable) { LogBus.add("rotate error: ${e.message}") } } }
    jobs.forEachIndexed { idx, j -> thread { nap(idx * 400L, g); if (alive(g)) try { j() } catch (e: Throwable) { LogBus.add("tunnel error: ${e.message}") } } }
    thread {
      val nm = getSystemService(NotificationManager::class.java)
      while (alive(g)) { try { nm.notify(1, notif("${connected()} / $total tunnels connected")) } catch (e: Throwable) {}; nap(5000, g) }
    }
    // the VPN starts as soon as the first tunnel connects; the rest keep joining in the background
    while (alive(g) && sess.none { it.isConnected }) Thread.sleep(500)
    if (!alive(g)) return
    LogBus.add("First tunnel connected - starting VPN")
    synchronized(NATIVE) {
      if (!alive(g)) return
      val mtu = getSharedPreferences("a", 0).getInt("mtu", 1500).coerceIn(576, 1500)
      val b = Builder().setSession("Mollad DNS").addAddress("10.10.0.2", 32).addRoute("0.0.0.0", 0).addDnsServer(fdns()).setMtu(mtu)
      LogBus.dbg("Forward DNS: ${fdns()}")
      // App filter: 0 = all apps, 1 = only the chosen apps, 2 = all apps except the chosen ones
      val pr0 = getSharedPreferences("a", 0)
      val fmode = pr0.getInt("appf_mode", 0)
      val flist = (pr0.getString("appf_list", "") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() && it != packageName }
      var allowed = 0
      if (fmode == 1 && flist.isNotEmpty()) {
        for (pk in flist) { try { b.addAllowedApplication(pk); allowed++ } catch (e: Exception) {} }
        LogBus.add("App filter: only $allowed app(s) use the VPN")
      }
      if (allowed == 0) {
        // (an "only these apps" list cannot be mixed with an "except these apps" list)
        if (fmode == 2) { for (pk in flist) { try { b.addDisallowedApplication(pk) } catch (e: Exception) {} }; LogBus.add("App filter: ${flist.size} app(s) bypass the VPN") }
        try { b.addDisallowedApplication(packageName) } catch (e: Exception) {}
      }
      LogBus.dbg("VPN establish...")
      val t2 = b.establish()
      if (t2 == null) { LogBus.add("VPN permission/establish fail"); return }
      tun = t2
      val cfg = File(filesDir, "h.yml")
      cfg.writeText("tunnel:\n  mtu: $mtu\n  ipv4: 10.10.0.2\nsocks5:\n  port: $socksPort\n  address: 127.0.0.1\n  udp: 'udp'\n")
      LogBus.dbg("hev start (fd=${t2.fd})...")
      TProxyService.TProxyStartService(cfg.path, t2.fd); hevOn = true
      LogBus.dbg("hev start OK")
    }
  }

  private fun stop() {
    if (!stopped.compareAndSet(false, true)) return
    on.set(false); gen.incrementAndGet()
    try { Usage.sync(this) } catch (e: Throwable) {}
    if (inst === this) { running = false; inst = null; total = 0; try { DnsWidget.refresh(this) } catch (e: Throwable) { } }
    try { pool.shutdownNow() } catch (e: Exception) {}
    LogBus.add("VPN stop")
    try { wl?.release() } catch (e: Exception) {}
    wl = null
    synchronized(NATIVE) {
      if (hevOn) { try { TProxyService.TProxyStopService() } catch (e: Throwable) {}; hevOn = false }
      try { tun?.close() } catch (e: Exception) {}
      tun = null
    }
    try { ss?.close() } catch (e: Exception) {}
    procs.forEach { it.destroy() }; procs.clear()
    relaySocks.forEach { try { it.close() } catch (e: Exception) {} }; relaySocks.clear(); relayPorts.clear()
    sess.forEach { it.disconnect() }; sess.clear(); busy.clear()
  }

  /** Which way dnstt talks to the resolver: udp, dot (TLS, port 853) or doh (HTTPS, port 443). Set in Settings. */
  private fun transport(): String =
    (getSharedPreferences("a", 0).getString("tr", "udp") ?: "udp").let { if (it == "dot" || it == "doh") it else "udp" }

  /** Turns what the user typed into the form dnstt needs for the chosen transport. */
  private fun resolver(r0: String): String {
    val r = r0.trim()
    return when (transport()) {
      "dot" -> {
        val h = r.removePrefix("tls://").removePrefix("https://").substringBefore('/')
        if (!h.contains(":")) "$h:853" else if (h.endsWith(":53")) h.removeSuffix(":53") + ":853" else h
      }
      "doh" -> if (r.startsWith("https://")) r else "https://" + r.substringBefore('/').substringBefore(':') + "/dns-query"
      else -> if (r.contains(":")) r else "$r:53"
    }
  }

  private class Link(val p: Process, val s: Session)

  // Free local port: the preferred one first; if it is busy (e.g. taken by Termux) any free port
  private fun pickPort(pref: Int): Int {
    val lo = InetAddress.getByName("127.0.0.1")
    if (pref in 1024..65535) {
      try { ServerSocket().use { it.bind(InetSocketAddress(lo, pref)) }; return pref } catch (e: Exception) {}
    }
    val s = ServerSocket()
    try { s.bind(InetSocketAddress(lo, 0)); return s.localPort } finally { s.close() }
  }

  private fun bindSocks(pref: Int): ServerSocket {
    val lo = InetAddress.getByName("127.0.0.1")
    try { return ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(lo, pref), 50) } } catch (e: Exception) {}
    LogBus.dbg("SOCKS port $pref is busy - picking a free port")
    return ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(lo, 0), 50) }
  }

  // Real end-to-end check: opens a direct-tcpip channel inside SSH and does a round trip to the server (sends no data)
  private fun probe(s: Session, ms: Int): Boolean {
    return try {
      val ch = s.openChannel("direct-tcpip") as ChannelDirectTCPIP
      ch.setHost(fdns()); ch.setPort(53)
      ch.inputStream
      ch.connect(ms)
      ch.disconnect(); true
    } catch (e: Throwable) { false }
  }

  // Connects one dnstt + SSH pair. Throws on failure.
  private fun openLink(ns: String, pub: String, user: String, pass: String, rs: String, pref: Int, label: String, g: Int): Link {
    val pr = getSharedPreferences("a", 0)
    val to = pr.getInt("to", 20).coerceIn(5, 300) * 1000
    val pay = pr.getInt("pay", 1232).coerceIn(512, 4096)
    val dmtu = pr.getInt("dmtu", 0).coerceIn(0, 1500)  // 0 = do not pass the flag; dnstt uses its own default
    var bin = File(applicationInfo.nativeLibraryDir, if (pr.getBoolean("low", false)) "libdnstt_low.so" else "libdnstt.so")
    if (!bin.exists()) bin = File(applicationInfo.nativeLibraryDir, "libdnstt.so")
    val port = pickPort(pref)
    val qps = pr.getInt("qps", 0).coerceIn(0, 5000)
    val tr = transport()
    val target = if (tr == "udp" && qps > 0) "127.0.0.1:" + relayFor(rs, g) else rs   // the QPS relay only exists for UDP
    val t0 = System.currentTimeMillis()
    LogBus.dbg("[$label] dnstt start | transport=${tr.uppercase()} | domain=$ns | dns-target=$target | local-port=$port | payload=$pay" +
      (if (dmtu > 0) " | dnstt-mtu=$dmtu" else "") +
      " | low-load=${pr.getBoolean("low", false)}" + (if (qps > 0) " | relay-qps-limit=$qps" else "") + " | timeout=${to / 1000}s")
    val args = mutableListOf(bin.path, "-$tr", target, "-pubkey", pub)
    if (dmtu > 0) { args.add("-mtu"); args.add(dmtu.toString()) }
    args.add(ns); args.add("127.0.0.1:$port")
    val pb = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(File("/dev/null"))
    pb.environment()["DNSTT_UDP_PAYLOAD"] = pay.toString()
    val p = pb.start()
    procs.add(p)
    var s: Session? = null
    try {
      nap(3000, g)
      val tDnstt = System.currentTimeMillis() - t0
      if (!alive(g)) throw Exception("stop")
      if (!p.isAlive) throw Exception("dnstt process exited immediately (after ${tDnstt}ms) - possible binary/arch or dns-target issue")
      LogBus.dbg("[$label] dnstt process up (${tDnstt}ms) - starting SSH handshake via dns-target=$target...")
      val tSsh0 = System.currentTimeMillis()
      val x = JSch().getSession(user, "127.0.0.1", port)
      s = x
      x.setPassword(pass); x.setConfig("StrictHostKeyChecking", "no")
      // do not drop quickly on a network/airplane-mode toggle: be patient for ~4 min (the active check catches it earlier)
      // keep-alive packets (Settings > Battery saver): 0 = none, the health check still watches the tunnel
      x.setServerAliveInterval(pr.getInt("kal", 30).coerceIn(0, 300) * 1000); x.setServerAliveCountMax(8)
      x.connect(to)
      val tSsh = System.currentTimeMillis() - tSsh0
      LogBus.dbg("[$label] SSH auth OK (${tSsh}ms) | total connect ${System.currentTimeMillis() - t0}ms | user=$user")
      return Link(p, x)
    } catch (e: Exception) {
      val tTotal = System.currentTimeMillis() - t0
      val msg = e.message ?: e.toString()
      val reason = when {
        msg.contains("Auth fail", true) || msg.contains("USERAUTH_FAILURE", true) -> "AUTH FAIL - wrong username/password"
        msg.contains("timeout", true) -> "TIMEOUT - no response from dns-target ($target), or it is too slow"
        msg.contains("stop") -> "VPN was stopping, so this was cancelled"
        msg.contains("exited immediately") -> msg
        msg.contains("refused", true) -> "CONNECTION REFUSED - the server/port may be wrong"
        else -> "ERROR - $msg"
      }
      if (LogBus.debug) LogBus.add("[$label] CONNECT FAIL (${tTotal}ms) | dns-target=$target | domain=$ns | $reason") else LogBus.add("[$label] connect failed: ${reason.substringBefore(" - ")}")
      try { s?.disconnect() } catch (z: Exception) {}
      p.destroy(); procs.remove(p)
      throw e
    }
  }

  private fun reg(l: Link) { sess.add(l.s); busy[l.s] = AtomicInteger() }

  private fun closeLink(l: Link) {
    suspect.remove(l.s); sess.remove(l.s); busy.remove(l.s)
    try { l.s.disconnect() } catch (e: Exception) {}
    procs.remove(l.p); l.p.destroy()
  }

  // ---- UDP relay: dnstt -> relay -> resolver. All tunnels' queries pass through here,
  // which is where the total queries/sec limit is applied and counted (shown in Logs).
  private fun gate(qps: Int): Boolean {
    if (qps <= 0) return true
    val per = 1_000_000_000L / qps
    var waitNs = 0L
    synchronized(gateLock) {
      val now = System.nanoTime()
      if (gateNext < now) gateNext = now
      waitNs = gateNext - now
      if (waitNs > 400_000_000L) return false   // too old by now, dnstt will resend it itself
      gateNext += per
    }
    if (waitNs > 1_000_000L) try { Thread.sleep(waitNs / 1_000_000, (waitNs % 1_000_000).toInt()) } catch (e: InterruptedException) {}
    return true
  }

  private fun relayFor(rs: String, g: Int): Int {
    synchronized(relayPorts) {
      relayPorts[rs]?.let { return it }
      val i = rs.lastIndexOf(':')
      val target = InetSocketAddress(InetAddress.getByName(rs.substring(0, i)), rs.substring(i + 1).toInt())
      val lsock = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
      relaySocks.add(lsock)
      val ups = ConcurrentHashMap<SocketAddress, DatagramSocket>()
      thread(isDaemon = true) {
        val buf = ByteArray(4096)
        while (alive(g) && !lsock.isClosed) {
          try {
            val pk = DatagramPacket(buf, buf.size); lsock.receive(pk)
            val cli = pk.socketAddress
            var up = ups[cli]
            if (up == null) {
              val ns = DatagramSocket(); relaySocks.add(ns); up = ns; ups[cli] = ns
              thread(isDaemon = true) {
                val rb = ByteArray(4096)
                while (alive(g) && !ns.isClosed) {
                  try {
                    val p = DatagramPacket(rb, rb.size); ns.receive(p); qGot.incrementAndGet()
                    lsock.send(DatagramPacket(p.data, p.length, cli))
                  } catch (e: Exception) { if (ns.isClosed) break }
                }
              }
            }
            if (!gate(getSharedPreferences("a", 0).getInt("qps", 0))) continue
            qSent.incrementAndGet()
            up.send(DatagramPacket(pk.data, pk.length, target))
          } catch (e: Exception) { if (lsock.isClosed) break }
        }
      }
      LogBus.dbg("UDP relay: 127.0.0.1:${lsock.localPort} -> $rs")
      relayPorts[rs] = lsock.localPort
      return lsock.localPort
    }
  }

  private class Acct(val no: Int, val ns: String, val pk: String, val u: String, val p: String, val d: String, val n: Int)
  private class Group(val a: Acct) {
    val links = CopyOnWriteArrayList<Link>()
    @Volatile var closed = false
    val pending = AtomicInteger()
  }
  private val serial = AtomicInteger()

  // Starts connecting all (n) tunnels of one account at once
  private fun launch(a: Acct, g: Int): Group {
    val grp = Group(a)
    val tbase = getSharedPreferences("a", 0).getInt("tport", 2000).coerceIn(1024, 63000)
    grp.pending.set(a.n)
    for (j in 1..a.n) {
      val pref = tbase + (serial.incrementAndGet() % 2000)
      thread {
        val label = "A${a.no}#$j"
        val rs = pickResolver(a.d)
        try {
          if (alive(g) && !grp.closed) {
            val l = openLink(a.ns, a.pk, a.u, a.p, rs, pref, label, g)
            synchronized(grp) {
              if (grp.closed || !alive(g)) closeLink(l)
              else { reg(l); grp.links.add(l); LogBus.add("[$label] connected") }
            }
          }
        } catch (e: Exception) {
          if (alive(g) && !grp.closed) LogBus.add("[$label] fail: ${e.message}")
        } finally { grp.pending.decrementAndGet() }
      }
    }
    return grp
  }

  private fun closeGroup(grp: Group) {
    synchronized(grp) { grp.closed = true; grp.links.forEach { closeLink(it) }; grp.links.clear() }
  }

  // Account rotation: every "rint" seconds the next account (with its own user/password/server) connects,
  // and as soon as it is up the previous account is closed. The VPN never goes down in between.
  private fun rotate(accs: List<Acct>, g: Int) {
    var ci = -1; var next = 0
    var cur: Group? = null
    var since = 0L
    var fails = 0
    while (alive(g)) {
      val pr = getSharedPreferences("a", 0)
      val every = pr.getInt("rint", 5).coerceIn(1, 3600) * 1000L
      val c = cur
      if (c != null) {
        val dead = c.pending.get() == 0 && c.links.none { it.p.isAlive && it.s.isConnected }
        val timeUp = accs.size > 1 && System.currentTimeMillis() - since >= every
        if (!dead && !timeUp) { nap(500, g); continue }
      }
      val a = accs[next]
      if (c != null) LogBus.add("Account ${accs[ci].no} -> Account ${a.no}: connecting the new account...")
      val grp = launch(a, g)
      val wait = pr.getInt("to", 20).coerceIn(5, 300) * 1000L + 10000L
      val t0 = System.currentTimeMillis()
      while (alive(g) && grp.links.isEmpty() && grp.pending.get() > 0 && System.currentTimeMillis() - t0 < wait) Thread.sleep(200)
      if (!alive(g)) { closeGroup(grp); break }
      if (grp.links.isNotEmpty()) {
        if (c != null) { closeGroup(c); LogBus.add("Account ${accs[ci].no} closed, Account ${a.no} is running") }
        else LogBus.add("Account ${a.no} is running")
        cur = grp; ci = next; next = (next + 1) % accs.size; since = System.currentTimeMillis(); total = a.n; fails = 0
      } else {
        closeGroup(grp)
        fails++
        LogBus.add("Account ${a.no} failed to connect" + (if (c != null) " - keeping the old one running" else ""))
        next = (next + 1) % accs.size
        nap((3000L * fails).coerceAtMost(15000L), g)
      }
    }
    cur?.let { closeGroup(it) }
  }

  // Each tunnel: connect -> health check every "chk" seconds -> if bad, open a NEW tunnel FIRST,
  // then close the old one (make-before-break). The VPN (tun) never goes down; the other tunnels keep running.
  private fun tunnel(ns: String, pub: String, user: String, pass: String, dns: String, pref: Int, label: String, g: Int) {
    var wait = 3000L
    var miss = 0
    var cur: Link? = null
    var linkSince = 0L
    while (alive(g)) {
      val pr = getSharedPreferences("a", 0)
      val maxMs = pr.getInt("rmax", 30).coerceIn(3, 120) * 1000L
      val l = cur
      if (l == null) {
        val rs = pickResolver(dns)
        try {
          val n = openLink(ns, pub, user, pass, rs, pref, label, g)
          reg(n); cur = n; wait = 3000L; miss = 0; linkSince = System.currentTimeMillis()
          if (LogBus.debug) LogBus.add("[$label] connected | resolver=$rs") else LogBus.add("[$label] connected")
        } catch (e: Exception) {
          if (alive(g)) LogBus.add(if (LogBus.debug) "[$label] fail: ${e.message} - retrying in ${wait / 1000}s" else "[$label] retrying in ${wait / 1000}s")
          nap(wait, g); wait = (wait * 2).coerceAtMost(maxMs)
        }
        continue
      }
      val auto = pr.getBoolean("auto", true)
      val chk = pr.getInt("chk", 5).coerceIn(1, 60) * 1000L
      val need = pr.getInt("miss", 2).coerceIn(1, 10)
      val sbreak = pr.getInt("sbreak", 0).coerceIn(0, 7200) * 1000L
      nap(if (auto) chk else 2000L, g)
      if (!alive(g)) break
      val dead = !l.p.isAlive || !l.s.isConnected
      val timeUp = sbreak > 0 && System.currentTimeMillis() - linkSince >= sbreak
      if (!dead && !timeUp && (!auto || probe(l.s, chk.toInt().coerceAtLeast(3000)))) { miss = 0; suspect.remove(l.s); continue }
      if (!dead && !timeUp) {
        miss++
        if (miss < need) { suspect.add(l.s); continue }
      }
      val reason = if (timeUp) "session break (${sbreak / 1000}s) - rotating to a new resolver/tunnel to reset counters" else "dropped/stuck"
      LogBus.add("[$label] $reason - opening a new tunnel first, then closing the old one")
      suspect.add(l.s); miss = 0
      val rs = pickResolver(dns)
      try {
        val n = openLink(ns, pub, user, pass, rs, pref, label, g)
        reg(n); cur = n; closeLink(l); wait = 3000L; linkSince = System.currentTimeMillis()
        LogBus.add("[$label] new tunnel connected (resolver=$rs), old one removed")
      } catch (e: Exception) {
        if (alive(g)) LogBus.add("[$label] reconnect failed: ${e.message} - retrying in ${wait / 1000}s")
        closeLink(l); cur = null
        nap(wait, g); wait = (wait * 2).coerceAtMost(maxMs)
      }
    }
    cur?.let { closeLink(it) }
  }

  // Picks the least busy tunnel (round-robin on ties)
  private fun acquire(): Session? {
    val l0 = sess.filter { it.isConnected }
    val l = l0.filter { it !in suspect }.ifEmpty { l0 }
    if (l.isEmpty()) return null
    val st = Math.floorMod(rr.getAndIncrement(), l.size)
    var best: Session? = null; var bc = Int.MAX_VALUE
    for (k in l.indices) { val x = l[(st + k) % l.size]; val c = busy[x]?.get() ?: 0; if (c < bc) { bc = c; best = x } }
    best?.let { busy.getOrPut(it) { AtomicInteger() }.incrementAndGet() }
    return best
  }

  private fun release(s: Session) { busy[s]?.decrementAndGet() }

  private fun handle(c: Socket) {
    var held: Session? = null
    try {
      val i = DataInputStream(c.getInputStream()); val o = c.getOutputStream()
      i.readByte(); i.skipBytes(i.readUnsignedByte()); o.write(byteArrayOf(5, 0))
      i.readByte(); val cmd = i.readByte().toInt(); i.readByte()
      val host = when (i.readUnsignedByte()) {
        1 -> { val b = ByteArray(4); i.readFully(b); InetAddress.getByAddress(b).hostAddress }
        3 -> { val b = ByteArray(i.readUnsignedByte()); i.readFully(b); String(b) }
        else -> { val b = ByteArray(16); i.readFully(b); InetAddress.getByAddress(b).hostAddress }
      }
      val port = i.readUnsignedShort()
      if (cmd == 3) { udp(c, o); return }
      val sx = acquire() ?: return
      held = sx
      val ch = sx.openChannel("direct-tcpip") as ChannelDirectTCPIP
      ch.setHost(host); ch.setPort(port); ch.setInputStream(CIn(i)); ch.setOutputStream(COut(o))
      o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
      ch.connect(15000)
      while (!ch.isClosed && !c.isClosed) Thread.sleep(1000)
      ch.disconnect()
    } finally {
      held?.let { release(it) }
      try { c.close() } catch (e: Exception) {}
    }
  }

  // Sends DNS (UDP 53) as TCP DNS inside SSH
  private fun udp(c: Socket, o: OutputStream) {
    val d = DatagramSocket(0, InetAddress.getByName("127.0.0.1")); val pt = d.localPort
    o.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, (pt shr 8).toByte(), pt.toByte()))
    pool.execute {
      while (!c.isClosed) {
        try {
          val b = ByteArray(2048); val pk = DatagramPacket(b, b.size); d.receive(pk)
          if (pk.length <= 10 || b[3].toInt() != 1 || (((b[8].toInt() and 0xff) shl 8) or (b[9].toInt() and 0xff)) != 53) continue
          val q = b.copyOfRange(10, pk.length)
          pool.execute { try { val out = byteArrayOf(0, 0, 0, 1, 1, 1, 1, 1, 0, 53) + dns(q); d.send(DatagramPacket(out, out.size, pk.address, pk.port)) } catch (e: Exception) {} }
        } catch (e: Exception) { break }
      }
    }
    c.getInputStream().read(); d.close(); c.close()
  }

  private fun dns(q: ByteArray): ByteArray {
    TrafficLog.dnsQuery(q)
    val s = acquire() ?: throw Exception()
    try {
      val ch = s.openChannel("direct-tcpip") as ChannelDirectTCPIP
      ch.setHost(fdns()); ch.setPort(53)
      val inp = DataInputStream(ch.inputStream); val out = ch.outputStream
      ch.connect(10000)
      out.write(byteArrayOf((q.size shr 8).toByte(), q.size.toByte()) + q); out.flush()
      val r = ByteArray(inp.readUnsignedShort()); inp.readFully(r); ch.disconnect(); return r
    } finally { release(s) }
  }
}
