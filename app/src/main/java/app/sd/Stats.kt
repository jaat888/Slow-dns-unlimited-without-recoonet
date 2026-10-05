package app.sd
import android.content.SharedPreferences
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

object Stats {
  val up = AtomicLong(); val down = AtomicLong()
  val upP = Pace(); val downP = Pace()
  @Volatile var since = 0L
  fun reset() { up.set(0); down.set(0); since = System.currentTimeMillis() }
}

// Speed limit (Mbps). Upload aur download dono par alag alag lagta hai.
object Limit {
  @Volatile var bps = 0L
  fun load(p: SharedPreferences) {
    val m = p.getInt("mbps", 10).coerceIn(1, 100)
    bps = if (p.getBoolean("lim", false)) m * 125000L else 0L
  }
}

class Pace {
  private var next = 0L
  fun wait(n: Int) {
    val b = Limit.bps; if (b <= 0 || n <= 0) return
    var w = 0L
    synchronized(this) {
      val now = System.nanoTime(); if (next < now) next = now
      next += n * 1_000_000_000L / b
      w = next - now - 200_000_000L
    }
    if (w > 0) try { Thread.sleep(w / 1_000_000, (w % 1_000_000).toInt()) } catch (e: InterruptedException) {}
  }
}

class CIn(s: InputStream) : FilterInputStream(s) {
  override fun read(): Int { val r = super.read(); if (r >= 0) { Stats.up.addAndGet(1); Stats.upP.wait(1) }; return r }
  override fun read(b: ByteArray, o: Int, l: Int): Int {
    val n = super.read(b, o, l); if (n > 0) { Stats.up.addAndGet(n.toLong()); Stats.upP.wait(n) }; return n
  }
}

class COut(s: OutputStream) : FilterOutputStream(s) {
  override fun write(b: Int) { Stats.downP.wait(1); out.write(b); Stats.down.addAndGet(1) }
  override fun write(b: ByteArray, o: Int, l: Int) { Stats.downP.wait(l); out.write(b, o, l); Stats.down.addAndGet(l.toLong()) }
}
