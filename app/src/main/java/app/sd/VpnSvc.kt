package app.sd
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
    @Volatile private var inst: VpnSvc? = null
    fun connected(): Int = inst?.sess?.count { it.isConnected } ?: 0
  }

  private val on = AtomicBoolean(false)
  private val stopped = AtomicBoolean(true)
  @Volatile private var hevOn = false
  private val sess = CopyOnWriteArrayList<Session>()
  private val procs = CopyOnWriteArrayList<Process>()
  private val rr = AtomicInteger()
  private val gen = AtomicInteger()
  private val busy = ConcurrentHashMap<Session, AtomicInteger>()
  @Volatile private var pool: ExecutorService = Executors.newCachedThreadPool()
  private var tun: ParcelFileDescriptor? = null
  private var ss: ServerSocket? = null
  private var wl: PowerManager.WakeLock? = null

  private fun notif(t: String): Notification =
    Notification.Builder(this, "v").setContentTitle("Mollad DNS").setContentText(t)
      .setSmallIcon(android.R.drawable.ic_lock_lock).setOngoing(true).build()

  override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
    getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("v", "VPN", NotificationManager.IMPORTANCE_LOW))
    try { startForeground(1, notif(if (i?.action == "stop") "Stopping..." else "Connecting...")) } catch (e: Exception) {}
    if (i?.action == "stop") { stop(); try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) {}; stopSelf(); return START_NOT_STICKY }
    if (on.compareAndSet(false, true)) {
      stopped.set(false)
      running = true; inst = this
      LogBus.add("VPN service start")
      LogBus.add("abi: " + android.os.Build.SUPPORTED_ABIS.joinToString() + " | libs: " + (File(applicationInfo.nativeLibraryDir).list()?.joinToString() ?: "KHALI"))
      wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sd:w").also { it.acquire() }
      val g = gen.incrementAndGet(); pool = Executors.newCachedThreadPool()
      thread { try { begin(g) } catch (e: Throwable) { LogBus.add("start error: ${e.message}") } }
    }
    return START_STICKY
  }
  override fun onDestroy() { stop() }

  private fun alive(g: Int) = on.get() && gen.get() == g

  private fun nap(ms: Long, g: Int) { var t = 0L; while (t < ms && alive(g)) { Thread.sleep(250); t += 250 } }

  private fun begin(g: Int) {
    Stats.reset(); Limit.load(getSharedPreferences("a", 0))
    val arr = try { JSONArray(getSharedPreferences("a", 0).getString("acc2", "[]")) } catch (e: Exception) { JSONArray() }
    var k = 0; var t = 0
    val jobs = ArrayList<() -> Unit>()
    for (a in 0 until arr.length()) {
      val o = arr.getJSONObject(a)
      if (!o.optBoolean("on", true)) continue
      val ns = o.optString("ns"); val pk = o.optString("pk"); val u = o.optString("u"); val p = o.optString("p"); val d = o.optString("d")
      if (ns.isEmpty() || pk.isEmpty() || u.isEmpty() || p.isEmpty() || d.isEmpty()) { LogBus.add("Account ${a + 1} adhura - skip"); continue }
      val n = o.optInt("n", 1).coerceIn(1, 20)
      t += n
      for (j in 1..n) { k++; val port = 2000 + k; val label = "A${a + 1}#$j"; jobs.add { tunnel(ns, pk, u, p, d, port, label, g) } }
    }
    total = t
    if (t == 0) { LogBus.add("Koi ON account nahi"); stop(); stopSelf(); return }
    ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 3000), 50) }
    thread { while (alive(g)) { try { val c = ss!!.accept(); pool.execute { try { handle(c) } catch (e: Exception) { c.close() } } } catch (e: Exception) { break } } }
    // tunnel ek ke baad ek start hote hain (CPU/resolver par ek saath load nahi)
    jobs.forEachIndexed { idx, j -> thread { nap(idx * 400L, g); if (alive(g)) try { j() } catch (e: Throwable) { LogBus.add("tunnel error: ${e.message}") } } }
    thread {
      val nm = getSystemService(NotificationManager::class.java)
      while (alive(g)) { nm.notify(1, notif("${connected()} / $total tunnel connected")); nap(5000, g) }
    }
    // pehla tunnel connect hote hi VPN chalu, baaki background mein judte rahenge
    while (alive(g) && sess.none { it.isConnected }) Thread.sleep(500)
    if (!alive(g)) return
    LogBus.add("Pehla tunnel connect - VPN chalu")
    synchronized(NATIVE) {
      if (!alive(g)) return
      val b = Builder().setSession("Mollad DNS").addAddress("10.10.0.2", 32).addRoute("0.0.0.0", 0).addDnsServer("1.1.1.1").setMtu(1500)
      try { b.addDisallowedApplication(packageName) } catch (e: Exception) {}
      val t2 = b.establish()
      if (t2 == null) { LogBus.add("VPN permission/establish fail"); return }
      tun = t2
      val cfg = File(filesDir, "h.yml")
      cfg.writeText("tunnel:\n  mtu: 1500\n  ipv4: 10.10.0.2\nsocks5:\n  port: 3000\n  address: 127.0.0.1\n  udp: 'udp'\n")
      TProxyService.TProxyStartService(cfg.path, t2.fd); hevOn = true
    }
  }

  private fun stop() {
    if (!stopped.compareAndSet(false, true)) return
    on.set(false); gen.incrementAndGet()
    if (inst === this) { running = false; inst = null; total = 0 }
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
    sess.forEach { it.disconnect() }; sess.clear(); busy.clear()
  }

  private fun resolver(r: String) = if (r.contains(":")) r else "$r:53"

  // Har tunnel apne account ke ek fixed UDP DNS se chalta hai. Retry kabhi band nahi hota,
  // bas fail hone par wait 3s se badhta hai (Settings ke "Retry max wait" tak).
  private fun tunnel(ns: String, pub: String, user: String, pass: String, dns: String, port: Int, label: String, g: Int) {
    val rs = resolver(dns)
    var wait = 3000L
    while (alive(g)) {
      var p: Process? = null
      var s: Session? = null
      val pr = getSharedPreferences("a", 0)
      val maxMs = pr.getInt("rmax", 30).coerceIn(3, 120) * 1000L
      try {
        val to = pr.getInt("to", 20).coerceIn(5, 300) * 1000
        var bin = File(applicationInfo.nativeLibraryDir, if (pr.getBoolean("low", false)) "libdnstt_low.so" else "libdnstt.so")
        if (!bin.exists()) bin = File(applicationInfo.nativeLibraryDir, "libdnstt.so")
        LogBus.add("[$label] dnstt start via $rs")
        p = ProcessBuilder(bin.path, "-udp", rs, "-pubkey", pub, ns, "127.0.0.1:$port")
          .redirectErrorStream(true).redirectOutput(File("/dev/null")).start()
        procs.add(p)
        nap(3000, g)
        s = JSch().getSession(user, "127.0.0.1", port)
        s.setPassword(pass); s.setConfig("StrictHostKeyChecking", "no")
        // network/airplane toggle par jaldi na girao: ~4 min tak sabr
        s.setServerAliveInterval(30000); s.setServerAliveCountMax(8)
        s.connect(to); sess.add(s); busy[s] = AtomicInteger()
        wait = 3000L
        LogBus.add("[$label] connected")
        while (alive(g) && s.isConnected && p.isAlive) Thread.sleep(2000)
        if (alive(g)) LogBus.add("[$label] tuta, dobara connect")
      } catch (e: Exception) {
        if (alive(g)) LogBus.add("[$label] fail: ${e.message} - ${wait / 1000}s baad retry")
      }
      s?.let { sess.remove(it); busy.remove(it); it.disconnect() }
      p?.let { procs.remove(it); it.destroy() }
      nap(wait, g)
      wait = (wait * 2).coerceAtMost(maxMs)
    }
  }

  // Sabse kam busy tunnel chunta hai (barabar hone par baari-baari)
  private fun acquire(): Session? {
    val l = sess.filter { it.isConnected }
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

  // DNS (UDP 53) ko SSH ke andar TCP DNS bana ke bhejta hai
  private fun udp(c: Socket, o: OutputStream) {
    val d = DatagramSocket(0, InetAddress.getByName("127.0.0.1")); val pt = d.localPort
    o.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, (pt shr 8).toByte(), pt.toByte()))
    pool.execute {
      while (!c.isClosed) {
        try {
          val b = ByteArray(2048); val pk = DatagramPacket(b, b.size); d.receive(pk)
          val q = b.copyOfRange(10, pk.length)
          pool.execute { try { val out = byteArrayOf(0, 0, 0, 1, 1, 1, 1, 1, 0, 53) + dns(q); d.send(DatagramPacket(out, out.size, pk.address, pk.port)) } catch (e: Exception) {} }
        } catch (e: Exception) { break }
      }
    }
    c.getInputStream().read(); d.close(); c.close()
  }

  private fun dns(q: ByteArray): ByteArray {
    val s = acquire() ?: throw Exception()
    try {
      val ch = s.openChannel("direct-tcpip") as ChannelDirectTCPIP
      ch.setHost("1.1.1.1"); ch.setPort(53)
      val inp = DataInputStream(ch.inputStream); val out = ch.outputStream
      ch.connect(10000)
      out.write(byteArrayOf((q.size shr 8).toByte(), q.size.toByte()) + q); out.flush()
      val r = ByteArray(inp.readUnsignedShort()); inp.readFully(r); ch.disconnect(); return r
    } finally { release(s) }
  }
}
