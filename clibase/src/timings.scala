package clibase

import java.util.concurrent.ConcurrentLinkedQueue as CLQ
import scala.jdk.CollectionConverters.*

// ── Timings ────────────────────────────────────────────────────────────────

object Timings {
  var enabled: Boolean = false
  private val entries = CLQ[(name: String, nanos: Long)]()
  private val accounted = ThreadLocal.withInitial[Long](() => 0L)
  private var requestStart = 0L

  inline def phase[A](name: String)(body: => A): A = {
    if (!enabled) body
    else {
      val t0 = System.nanoTime()
      val before = accounted.get()
      try { body }
      finally {
        val elapsed = System.nanoTime() - t0
        // Nested phases have already accounted for their elapsed time on this thread.
        val children = accounted.get() - before
        entries.add((name = name, nanos = elapsed - children))
        accounted.set(before + elapsed)
      }
    }
  }

  def report(): Unit = {
    if (enabled && requestStart != 0L) {
      val total = System.nanoTime() - requestStart
      requestStart = 0L
      val items = entries.asScala.toList
      entries.clear()
      System.err.println("Timings:")
      items.foreach { (name, nanos) =>
        val ms = nanos / 1_000_000.0
        val pct = if (total > 0) (nanos * 100.0 / total).round else 0
        System.err.println(f"  $name%-22s $ms%8.1f ms  ($pct%2d%%)")
      }
      val totalMs = total / 1_000_000.0
      System.err.println(f"  ${"request-total"}%-22s $totalMs%8.1f ms")
    }
  }

  def reset(startNanos: Long = System.nanoTime()): Unit = {
    entries.clear()
    accounted.set(0L)
    requestStart = if (enabled) { startNanos }
    else { 0L }
  }
}
