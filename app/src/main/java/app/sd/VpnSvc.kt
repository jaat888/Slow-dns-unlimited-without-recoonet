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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class VpnSvc : VpnService() {
  companion object {
    @Volatile var running = false
    @Volatile var total = 0
    @Volatile private var inst: VpnSvc? = null
    fun connected(): Int = inst?.sess?.count { it.isConnected } ?: 0
  }

  private val on = AtomicBoolean(false)
  private val sess = CopyOnWriteArrayList<Session>()
  private val procs = CopyOnWriteArrayList<Process>()
  private val rr = AtomicInteger()
  private var tun: ParcelFileDescriptor? = null
  private var ss: ServerSocket? = null
  private var wl: PowerManager.WakeLock? = null

  private fun notif(t: String): Notification =
    Notification.Builder(this, "v").setContentTitle("Mollad DNS").setContentText(t)
      .setSmallIcon(android.R.drawable.ic_lock_lock).setOngoing(true).build()

  override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
    if (i?.action == "stop") { stop(); stopSelf(); return START_NOT_STICKY }
    getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("v", "VPN", NotificationManager.IMPORTANCE_LOW))
    startForeground(1, notif("Connecting..."))
    if (on.compareAndSet(false, true)) {
      running = true; inst = this
      LogBus.add("VPN service start")
      wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sd:w").also { it.acquire() }
      thread { begin() }
    }
    return START_STICKY
  }
  override fun onDestroy() { stop() }

  private fun begin() {
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
      for (j in 1..n) { k++; val port = 2000 + k; val label = "A${a + 1}#$j"; jobs.add { tunnel(ns, pk, u, p, d, port, label) } }
    }
    total = t
    if (t == 0) { LogBus.add("Koi ON account nahi"); stop(); stopSelf(); return }
    ss = ServerSocket(3000, 50, InetAddress.getByName("127.0.0.1"))
    thread { while (on.get()) { try { val c = ss!!.accept(); thread { try { handle(c) } catch (e: Exception) { c.close() } } } catch (e: Exception) { break } } }
    jobs.forEach { j -> thread { j() } }
    thread {
      val nm = getSystemService(NotificationManager::class.java)
      while (on.get()) { nm.notify(1, notif("${connected()} / $total tunnel connected")); Thread.sleep(3000) }
    }
    // pehla tunnel connect hote hi VPN chalu, baaki background mein judte rahenge
    while (on.get() && sess.none { it.isConnected }) Thread.sleep(500)
    if (!on.get()) return
    LogBus.add("Pehla tunnel connect - VPN chalu")
    val b = Builder().setSession("Mollad DNS").addAddress("10.10.0.2", 32).addRoute("0.0.0.0", 0).addDnsServer("1.1.1.1").setMtu(1500)
    b.addDisallowedApplication(packageName)
    tun = b.establish() ?: return
    val cfg = File(filesDir, "h.yml")
    cfg.writeText("tunnel:\n  mtu: 1500\n  ipv4: 10.10.0.2\nsocks5:\n  port: 3000\n  address: 127.0.0.1\n  udp: 'udp'\n")
    TProxyService.TProxyStartService(cfg.path, tun!!.fd)
  }

  private fun stop() {
    if (!on.getAndSet(false) && !running) return
    on.set(false); running = false; inst = null; total = 0
    LogBus.add("VPN stop")
    try { wl?.release() } catch (e: Exception) {}
    wl = null
    try { TProxyService.TProxyStopService() } catch (e: Throwable) {}
    try { ss?.close() } catch (e: Exception) {}
    try { tun?.close() } catch (e: Exception) {}
    procs.forEach { it.destroy() }; procs.clear()
    sess.forEach { it.disconnect() }; sess.clear()
  }

  private fun resolver(r: String) = if (r.contains(":")) r else "$r:53"

  // Har tunnel apne account ke ek fixed UDP DNS se chalta hai. Limit ke bina baar baar try karta hai.
  private fun tunnel(ns: String, pub: String, user: String, pass: String, dns: String, port: Int, label: String) {
    val bin = File(applicationInfo.nativeLibraryDir, "libdnstt.so").path
    val rs = resolver(dns)
    while (on.get()) {
      var p: Process? = null
      var s: Session? = null
      try {
        val to = getSharedPreferences("a", 0).getInt("to", 20).coerceIn(5, 300) * 1000
        LogBus.add("[$label] dnstt start via $rs")
        p = ProcessBuilder(bin, "-udp", rs, "-pubkey", pub, ns, "127.0.0.1:$port")
          .redirectErrorStream(true).redirectOutput(File("/dev/null")).start()
        procs.add(p)
        Thread.sleep(3000)
        s = JSch().getSession(user, "127.0.0.1", port)
        s.setPassword(pass); s.setConfig("StrictHostKeyChecking", "no")
        s.setServerAliveInterval(30000); s.setServerAliveCountMax(4)
        s.connect(to); sess.add(s)
        LogBus.add("[$label] connected")
        while (on.get() && s.isConnected && p.isAlive) Thread.sleep(2000)
        if (on.get()) LogBus.add("[$label] tuta, dobara connect")
      } catch (e: Exception) {
        if (on.get()) LogBus.add("[$label] fail: ${e.message} - retry")
      }
      s?.let { sess.remove(it); it.disconnect() }
      p?.let { procs.remove(it); it.destroy() }
      if (on.get()) Thread.sleep(3000)
    }
  }

  private fun pick(): Session? {
    val l = sess.filter { it.isConnected }
    return if (l.isEmpty()) null else l[Math.floorMod(rr.getAndIncrement(), l.size)]
  }

  private fun handle(c: Socket) {
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
    val s = pick() ?: run { c.close(); return }
    val ch = s.openChannel("direct-tcpip") as ChannelDirectTCPIP
    ch.setHost(host); ch.setPort(port); ch.setInputStream(CIn(i)); ch.setOutputStream(COut(o))
    o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
    ch.connect(15000)
    while (!ch.isClosed && !c.isClosed) Thread.sleep(500)
    c.close()
  }

  // DNS (UDP 53) ko SSH ke andar TCP DNS bana ke bhejta hai
  private fun udp(c: Socket, o: OutputStream) {
    val d = DatagramSocket(0, InetAddress.getByName("127.0.0.1")); val pt = d.localPort
    o.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, (pt shr 8).toByte(), pt.toByte()))
    thread {
      while (!c.isClosed) {
        try {
          val b = ByteArray(2048); val pk = DatagramPacket(b, b.size); d.receive(pk)
          val q = b.copyOfRange(10, pk.length)
          thread { try { val out = byteArrayOf(0, 0, 0, 1, 1, 1, 1, 1, 0, 53) + dns(q); d.send(DatagramPacket(out, out.size, pk.address, pk.port)) } catch (e: Exception) {} }
        } catch (e: Exception) { break }
      }
    }
    c.getInputStream().read(); d.close(); c.close()
  }

  private fun dns(q: ByteArray): ByteArray {
    val ch = (pick() ?: throw Exception()).openChannel("direct-tcpip") as ChannelDirectTCPIP
    ch.setHost("1.1.1.1"); ch.setPort(53)
    val inp = DataInputStream(ch.inputStream); val out = ch.outputStream
    ch.connect(10000)
    out.write(byteArrayOf((q.size shr 8).toByte(), q.size.toByte()) + q); out.flush()
    val r = ByteArray(inp.readUnsignedShort()); inp.readFully(r); ch.disconnect(); return r
  }
}
