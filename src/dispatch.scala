import java.io.{ByteArrayOutputStream, PrintStream}

// ── Command dispatch ────────────────────────────────────────────────────────

val commands: Map[String, (List[String], CommandContext) => CmdResult] = Map(
  "index" -> cmdIndex, "search" -> cmdSearch, "def" -> cmdDef, "impl" -> cmdImpl,
  "refs" -> cmdRefs, "imports" -> cmdImports, "symbols" -> cmdSymbols, "file" -> cmdFile,
  "packages" -> cmdPackages, "package" -> cmdPackage, "annotated" -> cmdAnnotated, "grep" -> cmdGrep,
  "members" -> cmdMembers, "doc" -> cmdDoc, "overview" -> cmdOverview,
  "body" -> cmdBody, "tests" -> cmdTests, "coverage" -> cmdCoverage,
  "hierarchy" -> cmdHierarchy, "overrides" -> cmdOverrides, "explain" -> cmdExplain,
  "deps" -> cmdDeps, "context" -> cmdContext, "diff" -> cmdDiff, "ast-pattern" -> cmdAstPattern,
  "api" -> cmdApi,
  "summary" -> cmdSummary,
  "entrypoints" -> cmdEntrypoints,
  "graph" -> cmdGraph,
)

def runCommand(cmd: String, args: List[String], ctx: CommandContext): Unit =
  val result = commands.get(cmd) match
    case Some(handler) => handler(args, ctx)
    case None => CmdResult.UsageError(s"Unknown command: $cmd")
  if ctx.maxOutput > 0 then {
    val budget = ctx.maxOutput
    val baos = ByteArrayOutputStream()
    val budgetStream = BudgetPrintStream(baos, budget)
    // Use Console.withOut to redirect Scala's println, and System.setOut for Java's System.out
    val savedOut = System.out
    System.setOut(budgetStream)
    try Console.withOut(budgetStream) { render(result, ctx) }
    finally System.setOut(savedOut)
    val output = baos.toString("UTF-8")
    if budgetStream.exceeded then {
      // Truncate at a line boundary at or before the budget
      val cut = output.lastIndexOf('\n', budget)
      val truncated = if cut > 0 then output.substring(0, cut) else output.substring(0, math.min(output.length, budget))
      savedOut.print(truncated)
      if !truncated.endsWith("\n") then savedOut.println()
      savedOut.println(s"(output truncated at $budget chars — use --limit, --offset, --path, or --in-package to narrow)")
    } else {
      savedOut.print(output)
    }
  } else {
    render(result, ctx)
  }

// Buffers slightly past the budget so post-hoc line-boundary truncation works.
// Once past the budget, sets `exceeded` and stops writing.
private class BudgetPrintStream(baos: ByteArrayOutputStream, budget: Int) extends PrintStream(baos, true, "UTF-8"):
  // Buffer up to one extra line (4KB) past the budget to find a clean line break
  private val hardCap = budget + 4096
  @volatile var exceeded: Boolean = false

  override def write(b: Int): Unit =
    if baos.size() < hardCap then super.write(b)
    if baos.size() >= budget then exceeded = true

  override def write(buf: Array[Byte], off: Int, len: Int): Unit =
    val remaining = hardCap - baos.size()
    if remaining <= 0 then { exceeded = true; return }
    if len <= remaining then super.write(buf, off, len)
    else super.write(buf, off, remaining)
    if baos.size() >= budget then exceeded = true
