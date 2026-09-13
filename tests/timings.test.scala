package scalex

import clibase.Timings
import java.io.{ByteArrayOutputStream, PrintStream}
import munit.FunSuite

class TimingsSuite extends FunSuite {
  private def capture(body: => Unit): String = {
    val bytes = ByteArrayOutputStream()
    val previous = System.err
    val output = PrintStream(bytes)
    System.setErr(output)
    Timings.enabled = true
    Timings.reset()
    try {
      body
      Timings.report()
      bytes.toString("UTF-8")
    } finally {
      Timings.enabled = false
      Timings.reset()
      System.setErr(previous)
      output.close()
    }
  }

  test("nested phase durations are not counted twice") {
    val started = System.nanoTime()
    val output = capture {
      Timings.phase("outer") { Timings.phase("inner") { Thread.sleep(50) } }
    }
    val elapsedMs = (System.nanoTime() - started) / 1000000.0
    val phaseMs = "(?m)^  (?:outer|inner) +([0-9.]+) ms".r
      .findAllMatchIn(output)
      .map(_.group(1).toDouble)
      .sum
    assert(phaseMs <= elapsedMs + 1, s"Nested phases report $phaseMs ms for $elapsedMs ms of elapsed time")
  }

  test("failed phases are reported and request totals include uninstrumented work") {
    val output = capture {
      intercept[IllegalStateException] {
        Timings.phase("failed") { throw IllegalStateException("expected") }
      }
    }
    assert(output.contains("failed"))
    assert(output.contains("request-total"))
  }
}
