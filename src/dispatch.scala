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
  render(result, ctx)
