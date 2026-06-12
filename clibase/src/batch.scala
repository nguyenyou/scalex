package clibase

import java.io.{BufferedReader, InputStreamReader}

// ── Batch loop ──────────────────────────────────────────────────────────────

object BatchLoop {
  /** Read whitespace-separated command lines from stdin until EOF. Each non-empty
    * line is echoed as `>>> line`, tokenized, passed to `runLine`, and followed
    * by a blank line — so a driver reading the output can split per-query
    * results. Expensive state (an index, a connection) is built once by the
    * caller and shared across lines via the closure. */
  def run(runLine: List[String] => Unit): Unit = {
    val reader = BufferedReader(InputStreamReader(System.in))
    var line = reader.readLine()
    while line != null do {
      val parts = line.trim.split("\\s+").toList
      if parts.nonEmpty && parts.head.nonEmpty then {
        println(s">>> $line")
        runLine(parts)
        println()
      }
      line = reader.readLine()
    }
  }
}
