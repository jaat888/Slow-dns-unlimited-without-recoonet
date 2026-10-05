package app.sd
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object LogBus {
  private const val AUTO_CLEAR_MS = 3 * 60 * 1000L   // har 3 minute mein log saaf
  private val q = ArrayDeque<String>()
  private val f = SimpleDateFormat("HH:mm:ss", Locale.US)
  private var last = System.currentTimeMillis()
  private fun auto() { val n = System.currentTimeMillis(); if (n - last >= AUTO_CLEAR_MS) { q.clear(); last = n } }
  @Synchronized fun add(s: String) { auto(); q.add("${f.format(Date())}  $s"); while (q.size > 400) q.removeFirst() }
  @Synchronized fun text(): String { auto(); return q.joinToString("\n") }
  @Synchronized fun clear() { q.clear(); last = System.currentTimeMillis() }
}
