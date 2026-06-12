import clibase.OutputBudget

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
  renderWithBudget(result, ctx)

/** Render a CmdResult, applying --max-output truncation if set. */
def renderWithBudget(result: CmdResult, ctx: CommandContext): Unit =
  if ctx.maxOutput > 0 then
    OutputBudget.run(ctx.maxOutput,
      s"(output truncated at ${ctx.maxOutput} chars — use --limit, --offset, --path, or --in-package to narrow)") {
      render(result, ctx)
    }
  else render(result, ctx)
