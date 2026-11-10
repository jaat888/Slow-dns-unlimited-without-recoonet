package app.sd
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object LogBus {
  private const val AUTO_CLEAR_MS = 3 * 60 * 1000L   // the on-screen log is cleared every 3 minutes
  private val q = ArrayDeque<String>()
  private val f = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
  private var last = System.currentTimeMillis()
  private var file: File? = null
  private var ready = false

  private fun auto() { val n = System.currentTimeMillis(); if (n - last >= AUTO_CLEAR_MS) { q.clear(); last = n } }

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
  @Synchronized fun text(): String { auto(); return q.joinToString("\n") }
  @Synchronized fun clear() { q.clear(); last = System.currentTimeMillis() }
}
