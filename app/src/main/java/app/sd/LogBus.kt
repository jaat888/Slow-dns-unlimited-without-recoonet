package app.sd
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object LogBus {
  private const val AUTO_CLEAR_MS = 3 * 60 * 1000L   // screen ka log har 3 minute mein saaf
  private val q = ArrayDeque<String>()
  private val f = SimpleDateFormat("HH:mm:ss", Locale.US)
  private var last = System.currentTimeMillis()
  private var file: File? = null
  private var ready = false

  private fun auto() { val n = System.currentTimeMillis(); if (n - last >= AUTO_CLEAR_MS) { q.clear(); last = n } }

  // Log disk par bhi likha jata hai (run.log) taaki crash ke baad bhi bacha rahe.
  // Naya process shuru hote hi pichla run.log screen par aa jata hai.
  @Synchronized fun setup(dir: File) {
    if (ready) return
    ready = true
    try {
      val fl = File(dir, "run.log"); file = fl
      if (fl.exists()) {
        val old = fl.readLines().takeLast(80)
        if (old.isNotEmpty()) { q.add("=== PICHLE RUN KA LOG (crash se pehle) ==="); old.forEach { q.add(it) }; q.add("=== ab naya run ===") }
        fl.delete()
      }
      last = System.currentTimeMillis()
    } catch (e: Throwable) {}
  }

  @Synchronized fun add(s: String) {
    auto()
    val line = "${f.format(Date())}  $s"
    q.add(line); while (q.size > 400) q.removeFirst()
    try { file?.let { if (it.length() > 60000) it.writeText(""); it.appendText(line + "\n") } } catch (e: Throwable) {}
  }
  @Synchronized fun text(): String { auto(); return q.joinToString("\n") }
  @Synchronized fun clear() { q.clear(); last = System.currentTimeMillis() }
}
