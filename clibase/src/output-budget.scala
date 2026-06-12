package clibase

import java.io.{ByteArrayOutputStream, PrintStream}

// ── Output budget ───────────────────────────────────────────────────────────

object OutputBudget {
  /** Run `body` with stdout captured. If it writes more than `budget` characters,
    * truncate at a line boundary at or before the budget and append
    * `truncationNote`; otherwise pass the output through unchanged. */
  def run(budget: Int, truncationNote: String)(body: => Unit): Unit = {
    val baos = ByteArrayOutputStream()
    val budgetStream = BudgetPrintStream(baos, budget)
    // Capture the caller's output stream before redirecting — in a CLI it's
    // System.out, in tests it's the capture stream (via Console.withOut)
    val callerOut = Console.out
    val savedOut = System.out
    System.setOut(budgetStream)
    try Console.withOut(budgetStream) { body }
    finally System.setOut(savedOut)
    val output = baos.toString("UTF-8")
    if budgetStream.exceeded then {
      val cut = output.lastIndexOf('\n', budget)
      val truncated = if cut > 0 then output.substring(0, cut) else output.substring(0, math.min(output.length, budget))
      callerOut.print(truncated)
      if !truncated.endsWith("\n") then callerOut.println()
      callerOut.println(truncationNote)
    } else {
      callerOut.print(output)
    }
  }
}

// Buffers up to 4KB past the budget so post-hoc line-boundary truncation works.
// Once past hardCap, stops writing entirely.
private class BudgetPrintStream(baos: ByteArrayOutputStream, budget: Int) extends PrintStream(baos, true, "UTF-8") {
  // Buffer up to one extra line (4KB) past the budget to find a clean line break
  private val hardCap = budget + 4096
  @volatile var exceeded: Boolean = false

  override def write(b: Int): Unit = {
    if baos.size() < hardCap then super.write(b)
    if baos.size() >= budget then exceeded = true
  }

  override def write(buf: Array[Byte], off: Int, len: Int): Unit = {
    val remaining = hardCap - baos.size()
    if remaining > 0 then {
      if len <= remaining then super.write(buf, off, len)
      else super.write(buf, off, remaining)
    }
    if baos.size() >= budget then exceeded = true
  }
}
