package app.sd
import android.content.SharedPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object LogBus {
  // Auto clear of the on-screen log: settings "logauto" (on/off) and "logmin" (minutes)
  /** Debug logs ON = every detail; OFF = short, clean lines like a normal VPN app. */
  @Volatile var debug = false
  @Volatile private var autoOn = true
  @Volatile private var autoMs = 3 * 60 * 1000L
  private val q = ArrayDeque<String>()
  private val f = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
  private var last = System.currentTimeMillis()
  private var file: File? = null
  private var ready = false

  fun configure(p: SharedPreferences) {
    debug = p.getBoolean("dbg", false)
    autoOn = p.getBoolean("logauto", true)
    autoMs = p.getInt("logmin", 3).coerceIn(1, 120) * 60_000L
  }

  private fun auto() { val n = System.currentTimeMillis(); if (autoOn && n - last >= autoMs) { q.clear(); last = n } }

  // The log is also written to disk (run.log) so it survives a crash.
  // When a new process starts, the previous run.log is shown on screen.
  @Synchronized fun setup(dir: File) {
    if (ready) return
    ready = true
    try {
      val fl = File(dir, "run.log"); file = fl
      if (fl.exists()) {
        val old = fl.readLines().takeLast(80)
        if (old.isNotEmpty()) { q.add("=== LOG FROM THE PREVIOUS RUN (before the crash) ==="); old.forEach { q.add(it) }; q.add("=== new run ===") }
        fl.delete()
      }
      last = System.currentTimeMillis()
    } catch (e: Throwable) {}
  }

  @Synchronized fun add(s: String) {
    auto()
    val line = "${f.format(Date())}  $s"
    q.add(line); while (q.size > 800) q.removeFirst()
    try { file?.let { if (it.length() > 150000) it.writeText(""); it.appendText(line + "\n") } } catch (e: Throwable) {}
  }
  /** Only written when Debug logs is ON. */
  fun dbg(s: String) { if (debug) add(s) }
  @Synchronized fun text(): String { auto(); return q.joinToString("\n") }
  @Synchronized fun clear() { q.clear(); last = System.currentTimeMillis() }
  /** Seconds until the next automatic clear (0 when auto clear is off). */
  @Synchronized fun nextClearIn(): Long = if (!autoOn) 0L else ((autoMs - (System.currentTimeMillis() - last)) / 1000L).coerceAtLeast(0L)
}
