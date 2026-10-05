package app.sd
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object LogBus {
  private val q = ArrayDeque<String>()
  private val f = SimpleDateFormat("HH:mm:ss", Locale.US)
  @Synchronized fun add(s: String) { q.add("${f.format(Date())}  $s"); while (q.size > 400) q.removeFirst() }
  @Synchronized fun text(): String = q.joinToString("\n")
  @Synchronized fun clear() { q.clear() }
}
