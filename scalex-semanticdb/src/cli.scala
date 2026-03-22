import java.nio.file.{Files, Path}

// ── CLI entry point ────────────────────────────────────────────────────────

@main def main(args: String*): Unit =
  val argList = args.toList
  if argList.isEmpty || argList.head == "--help" || argList.head == "-h" then
    printUsage()
    return
  if argList.head == "--version" then
    println("scalex-semanticdb 0.1.0")
    return

  val cmd = argList.head
  val rest = argList.tail
  val flags = parseFlags(rest)

  if flags.timingsEnabled then SemTimings.enable()

  val workspace = flags.explicitWorkspace match
    case Some(w) => Path.of(w).toAbsolutePath.normalize
    case None    => Path.of(".").toAbsolutePath.normalize

  val index = SemIndex(workspace)

  if cmd == "index" then
    index.rebuild(flags.semanticdbPath)
    val result = SemCmdResult.Stats(
      index.fileCount, index.symbolCount, index.occurrenceCount,
      index.diagnosticCount, index.buildTimeMs, index.cachedLoad,
    )
    val ctx = flagsToContext(flags, index, workspace)
    render(result, ctx)
    SemTimings.report()
    return

  // For all other commands, build/load the index first
  index.build(flags.semanticdbPath)

  val ctx = flagsToContext(flags, index, workspace)

  val result = commands.get(cmd) match
    case Some(handler) => handler(flags.cleanArgs, ctx)
    case None => SemCmdResult.UsageError(s"Unknown command: $cmd\nRun with --help for usage.")

  render(result, ctx)
  SemTimings.report()

// ── Command dispatch ───────────────────────────────────────────────────────

val commands: Map[String, (List[String], SemCommandContext) => SemCmdResult] = Map(
  // Original 14
  "lookup"      -> cmdLookup,
  "refs"        -> cmdRefs,
  "supertypes"  -> cmdSupertypes,
  "subtypes"    -> cmdSubtypes,
  "members"     -> cmdMembers,
  "type"        -> cmdTypeOf,
  "callers"     -> cmdCallers,
  "callees"     -> cmdCallees,
  "flow"        -> cmdFlow,
  "related"     -> cmdRelated,
  "diagnostics" -> cmdDiagnostics,
  "symbols"     -> cmdSymbols,
  "occurrences" -> cmdOccurrences,
  "stats"       -> cmdStats,
  // New 14
  "file"        -> cmdFile,
  "packages"    -> cmdPackages,
  "package"     -> cmdPackageSymbols,
  "annotated"   -> cmdAnnotated,
  "summary"     -> cmdSummary,
  "overrides"   -> cmdOverrides,
  "entrypoints" -> cmdEntrypoints,
  "api"         -> cmdApi,
  "overview"    -> cmdOverview,
  "imports"     -> cmdImports,
  "coverage"    -> cmdCoverage,
  "deps"        -> cmdDeps,
  "context"     -> cmdContext,
  "explain"     -> cmdExplain,
)

def cmdStats(args: List[String], ctx: SemCommandContext): SemCmdResult =
  SemCmdResult.Stats(
    ctx.index.fileCount, ctx.index.symbolCount, ctx.index.occurrenceCount,
    ctx.index.diagnosticCount, ctx.index.buildTimeMs, ctx.index.cachedLoad,
  )

// ── Flag parsing ───────────────────────────────────────────────────────────

case class SemParsedFlags(
  limit: Int = 50,
  verbose: Boolean = false,
  jsonOutput: Boolean = false,
  kindFilter: Option[String] = None,
  roleFilter: Option[String] = None,
  depth: Int = 3,
  explicitWorkspace: Option[String] = None,
  semanticdbPath: Option[String] = None,
  timingsEnabled: Boolean = false,
  cleanArgs: List[String] = Nil,
)

private val flagsWithArgs = Set(
  "--limit", "--kind", "--workspace", "-w", "--role",
  "--depth", "--semanticdb-path",
)

def parseFlags(args: List[String]): SemParsedFlags =
  var flags = SemParsedFlags()
  val clean = scala.collection.mutable.ListBuffer.empty[String]
  var i = 0
  val arr = args.toArray

  while i < arr.length do
    arr(i) match
      case "--limit" if i + 1 < arr.length =>
        flags = flags.copy(limit = arr(i + 1).toIntOption.getOrElse(50))
        i += 2
      case "--kind" if i + 1 < arr.length =>
        flags = flags.copy(kindFilter = Some(arr(i + 1)))
        i += 2
      case "--workspace" | "-w" if i + 1 < arr.length =>
        flags = flags.copy(explicitWorkspace = Some(arr(i + 1)))
        i += 2
      case "--semanticdb-path" if i + 1 < arr.length =>
        flags = flags.copy(semanticdbPath = Some(arr(i + 1)))
        i += 2
      case "--role" if i + 1 < arr.length =>
        flags = flags.copy(roleFilter = Some(arr(i + 1)))
        i += 2
      case "--depth" if i + 1 < arr.length =>
        flags = flags.copy(depth = arr(i + 1).toIntOption.getOrElse(3))
        i += 2
      case "--verbose" | "-v" =>
        flags = flags.copy(verbose = true)
        i += 1
      case "--json" =>
        flags = flags.copy(jsonOutput = true)
        i += 1
      case "--timings" =>
        flags = flags.copy(timingsEnabled = true)
        i += 1
      case arg if arg.startsWith("--") =>
        // Unknown flag, skip (and its arg if it looks like a flag-with-arg)
        i += 1
      case arg =>
        clean += arg
        i += 1

  flags.copy(cleanArgs = clean.toList)

def flagsToContext(flags: SemParsedFlags, index: SemIndex, workspace: Path): SemCommandContext =
  SemCommandContext(
    index = index,
    workspace = workspace,
    limit = if flags.limit == 0 then Int.MaxValue else flags.limit,
    verbose = flags.verbose,
    jsonOutput = flags.jsonOutput,
    kindFilter = flags.kindFilter,
    roleFilter = flags.roleFilter,
    depth = flags.depth,
    timingsEnabled = flags.timingsEnabled,
  )

// ── Usage ──────────────────────────────────────────────────────────────────

def printUsage(): Unit =
  println("""scalex-semanticdb — SemanticDB index & query tool
    |
    |Usage: scalex-semanticdb <command> [args] [options]
    |
    |Commands:
    |  lookup <symbol>       Full info by FQN or display name
    |  refs <symbol>         Compiler-precise references
    |  supertypes <symbol>   Parent type chain
    |  subtypes <symbol>     Who extends this
    |  members <symbol>      Declarations/members
    |  type <symbol>         Return type / value type
    |  callers <symbol>      Who calls this method
    |  callees <symbol>      What does this method call
    |  flow <method>         Downstream call tree
    |  related <symbol>      Co-occurring symbols
    |  diagnostics [file]    Compiler warnings/errors
    |  symbols [file]        Symbols defined in file
    |  occurrences <file>    All occurrences in file
    |  index                 Rebuild index (force)
    |  stats                 Index statistics
    |
    |Options:
    |  -w, --workspace PATH         Set workspace (default: cwd)
    |  --semanticdb-path PATH       Explicit .semanticdb directory
    |  --limit N                    Max results (default: 50, 0=unlimited)
    |  --json                       JSON output
    |  --verbose, -v                Full signatures and properties
    |  --kind K                     Filter by kind
    |  --role R                     Filter occurrences (def/ref)
    |  --depth N                    Max depth for flow/subtypes (default: 3)
    |  --timings                    Print timing info to stderr
    |  --version                    Print version
    |""".stripMargin)
