package clibase

import java.io.{ByteArrayOutputStream, OutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8

object OutputBudget {

  /** Capture only enough UTF-8 bytes for the character budget plus one character. Console redirection is scoped to the
    * caller; System.out is never changed.
    */
  def capture(budget: Int)(body: => Unit): (text: String, exceeded: Boolean) = {
    val bytes = ByteArrayOutputStream()
    val cap = (budget.toLong + 1) * 4
    val bounded = new OutputStream {
      override def write(b: Int): Unit = {
        if (bytes.size().toLong < cap) { bytes.write(b) }
      }
      override def write(buf: Array[Byte], off: Int, len: Int): Unit = {
        val remaining = math.min(Int.MaxValue.toLong, cap - bytes.size()).toInt
        if (remaining > 0) { bytes.write(buf, off, math.min(len, remaining)) }
      }
    }
    val stream = PrintStream(bounded, true, UTF_8)
    try { Console.withOut(stream) { body } }
    finally { stream.close() }
    val text = bytes.toString(UTF_8)
    (text = text, exceeded = text.length > budget)
  }

  /** Text content is capped at a line boundary; the explanatory note is extra. */
  def run(budget: Int, truncationNote: String)(body: => Unit): Unit = {
    val captured = capture(budget)(body)
    if (captured.exceeded) {
      val cut = captured.text.lastIndexOf('\n', budget)
      val end = if (cut > 0) { cut }
      else { budget }
      // Never split a UTF-16 surrogate pair in a long line.
      val safeEnd = if (end > 0 && Character.isHighSurrogate(captured.text.charAt(end - 1))) { end - 1 }
      else { end }
      val truncated = captured.text.take(safeEnd)
      print(truncated)
      if (!truncated.endsWith("\n")) { println() }
      println(truncationNote)
    } else { print(captured.text) }
  }
}
