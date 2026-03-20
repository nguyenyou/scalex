import java.io.{ByteArrayOutputStream, OutputStream, PrintStream}

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
)

def runCommand(cmd: String, args: List[String], ctx: CommandContext): Unit =
  val result = commands.get(cmd) match
    case Some(handler) => handler(args, ctx)
    case None => CmdResult.UsageError(s"Unknown command: $cmd")
  if ctx.maxOutput > 0 then {
    val budget = ctx.maxOutput
    val baos = ByteArrayOutputStream()
    val countingStream = BudgetPrintStream(baos, budget)
    val savedOut = System.out
    System.setOut(countingStream)
    try render(result, ctx)
    finally System.setOut(savedOut)
    val output = baos.toString("UTF-8")
    if countingStream.exceeded then {
      // Truncate to budget at a line boundary
      val truncated = if output.length > budget then {
        val cut = output.lastIndexOf('\n', budget)
        if cut > 0 then output.substring(0, cut) else output.substring(0, budget)
      } else output
      savedOut.print(truncated)
      if !truncated.endsWith("\n") then savedOut.println()
      savedOut.println(s"(output truncated at $budget chars — use --limit, --offset, or --path to narrow)")
    } else {
      savedOut.print(output)
    }
  } else {
    render(result, ctx)
  }

private class BudgetPrintStream(baos: ByteArrayOutputStream, budget: Int) extends PrintStream(baos, true, "UTF-8"):
  @volatile var exceeded: Boolean = false

  override def write(b: Int): Unit =
    if baos.size() < budget then super.write(b)
    else exceeded = true

  override def write(buf: Array[Byte], off: Int, len: Int): Unit =
    val remaining = budget - baos.size()
    if remaining <= 0 then exceeded = true
    else if len <= remaining then super.write(buf, off, len)
    else {
      super.write(buf, off, remaining)
      exceeded = true
    }
