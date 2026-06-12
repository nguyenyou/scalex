package clibase

import java.util.concurrent.ConcurrentLinkedQueue as CLQ
import scala.jdk.CollectionConverters.*

// ── Timings ────────────────────────────────────────────────────────────────

object Timings {
  var enabled: Boolean = false
  private val entries = CLQ[(String, Long)]()

  inline def phase[A](name: String)(body: => A): A = {
    if !enabled then body
    else {
      val t0 = System.nanoTime()
      val result = body
      entries.add((name, System.nanoTime() - t0))
      result
    }
  }

  def report(): Unit = {
    if enabled then {
      val items = entries.asScala.toList
      entries.clear()
      if items.nonEmpty then {
        val total = items.map(_._2).sum
        System.err.println("Timings:")
        items.foreach { (name, nanos) =>
          val ms = nanos / 1_000_000.0
          val pct = if total > 0 then (nanos * 100.0 / total).round else 0
          System.err.println(f"  $name%-22s $ms%8.1f ms  ($pct%2d%%)")
        }
        val totalMs = total / 1_000_000.0
        System.err.println(f"  ${"total"}%-22s $totalMs%8.1f ms")
      }
    }
  }

  def reset(): Unit = entries.clear()
}
