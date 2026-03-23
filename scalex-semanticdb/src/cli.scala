import java.nio.file.{Files, Path}

// ── CLI entry point ────────────────────────────────────────────────────────

@main def main(args: String*): Unit =
  val argList = args.toList
  if argList.isEmpty || argList.head == "--help" || argList.head == "-h" then
    printUsage()
    return
  if argList.head == "--version" then
    println(s"scalex-semanticdb $ScalexSdbVersion")
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
      index.buildTimeMs, index.cachedLoad,
    )
    val ctx = flagsToContext(flags, index, workspace)
    render(result, ctx)
    SemTimings.report()
    return

  // For all other commands, build/load the index first
  index.build(flags.semanticdbPath)

  val ctx = flagsToContext(flags, index, workspace)

  val result =
    if cmd == "batch" then runBatch(flags.cleanArgs, ctx)
    else
      commands.get(cmd) match
        case Some(handler) => handler(flags.cleanArgs, ctx)
        case None => SemCmdResult.UsageError(s"Unknown command: $cmd\nRun with --help for usage.")

  render(result, ctx)
  SemTimings.report()

// ── Command dispatch ───────────────────────────────────────────────────────

val commands: Map[String, (List[String], SemCommandContext) => SemCmdResult] = Map(
  // 15 commands (index and batch handled separately above)
  "lookup"      -> cmdLookup,
  "refs"        -> cmdRefs,
  "supertypes"  -> cmdSupertypes,
  "subtypes"    -> cmdSubtypes,
  "members"     -> cmdMembers,
  "type"        -> cmdTypeOf,
  "callers"     -> cmdCallers,
  "callees"     -> cmdCallees,
  "flow"        -> cmdFlow,
  "path"        -> cmdPath,
  "related"     -> cmdRelated,
  "explain"     -> cmdExplain,
  "symbols"     -> cmdSymbols,
  "occurrences" -> cmdOccurrences,
  "stats"       -> cmdStats,
)

def runBatch(args: List[String], ctx: SemCommandContext): SemCmdResult =
  if args.isEmpty then return SemCmdResult.UsageError("batch requires at least one sub-command argument.\nUsage: batch \"lookup Dog\" \"members Animal\" ...")

  val results = args.map { subCmdStr =>
    val parts = subCmdStr.trim.split("\\s+").toList.filter(_.nonEmpty)
    if parts.isEmpty then (command = subCmdStr, result = SemCmdResult.UsageError("Empty sub-command"))
    else {
      val subCmd  = parts.head
      val subRest = parts.tail
      val subFlags = parseFlags(subRest)
      val hasExplicitDepth = subRest.contains("--depth")
      val hasExplicitLimit = subRest.contains("--limit")
      val subCtx = ctx.copy(
        limit = if hasExplicitLimit then (if subFlags.limit == 0 then Int.MaxValue else subFlags.limit) else ctx.limit,
        verbose = subFlags.verbose || ctx.verbose,
        jsonOutput = ctx.jsonOutput,
        kindFilter = subFlags.kindFilter.orElse(ctx.kindFilter),
        roleFilter = subFlags.roleFilter.orElse(ctx.roleFilter),
        depth = if hasExplicitDepth then subFlags.depth else ctx.depth,  // both are Option[Int]
        noAccessors = subFlags.noAccessors || ctx.noAccessors,
        excludePatterns = if subFlags.excludePatterns.nonEmpty then subFlags.excludePatterns else ctx.excludePatterns,
        smart = subFlags.smart || ctx.smart,
      )
      val result = commands.get(subCmd) match
        case Some(handler) => handler(subFlags.cleanArgs, subCtx)
        case None => SemCmdResult.UsageError(s"Unknown command: $subCmd")
      (command = subCmdStr, result = result)
    }
  }
  SemCmdResult.Batch(results)

def cmdStats(args: List[String], ctx: SemCommandContext): SemCmdResult =
  SemCmdResult.Stats(
    ctx.index.fileCount, ctx.index.symbolCount, ctx.index.occurrenceCount,
    ctx.index.buildTimeMs, ctx.index.cachedLoad,
  )

// ── Flag parsing ───────────────────────────────────────────────────────────

case class SemParsedFlags(
  limit: Int = 50,
  verbose: Boolean = false,
  jsonOutput: Boolean = false,
  kindFilter: Option[String] = None,
  roleFilter: Option[String] = None,
  depth: Option[Int] = None,
  explicitWorkspace: Option[String] = None,
  semanticdbPath: Option[String] = None,
  timingsEnabled: Boolean = false,
  noAccessors: Boolean = false,
  excludePatterns: List[String] = Nil,
  smart: Boolean = false,
  cleanArgs: List[String] = Nil,
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
        flags = flags.copy(depth = arr(i + 1).toIntOption.orElse(Some(3)))
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
      case "--no-accessors" =>
        flags = flags.copy(noAccessors = true)
        i += 1
      case "--exclude" if i + 1 < arr.length =>
        flags = flags.copy(excludePatterns = arr(i + 1).split(",").map(_.trim).filter(_.nonEmpty).toList)
        i += 2
      case "--smart" =>
        flags = flags.copy(smart = true)
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
    noAccessors = flags.noAccessors,
    excludePatterns = flags.excludePatterns,
    smart = flags.smart,
  )

// ── Usage ──────────────────────────────────────────────────────────────────

def printUsage(): Unit =
  println("""scalex-semanticdb — Compiler-precise code intelligence from SemanticDB
    |
    |Usage: scalex-semanticdb <command> [args] [options]
    |
    |Call graph (compiler-only):
    |  callers <symbol>      Who calls this method (reverse call graph, --depth N for transitive)
    |  callees <symbol>      What does this method call (forward call graph)
    |  flow <method>         Recursive call tree (use --smart on large codebases)
    |  path <src> <tgt>      Find shortest call path between two symbols
    |
    |Composite:
    |  explain <symbol>      One-shot summary: type, callers, callees, members
    |
    |Compiler-precise queries:
    |  refs <symbol>         Zero-false-positive references
    |  type <symbol>         Resolved type signature
    |  related <symbol>      Co-occurring symbols ranked by frequency
    |  occurrences <file>    All symbol occurrences in file with roles
    |
    |Navigation:
    |  lookup <symbol>       Find symbol by FQN or display name
    |  supertypes <symbol>   Resolved parent type chain
    |  subtypes <symbol>     Who extends this
    |  members <symbol>      Declarations with resolved types
    |  symbols [file]        Symbols defined in file
    |
    |Batch:
    |  batch "cmd1" "cmd2"   Run multiple queries in one invocation
    |
    |Index:
    |  index                 Rebuild index (force)
    |  stats                 Index statistics
    |
    |Options:
    |  -w, --workspace PATH         Set workspace (default: cwd)
    |  --semanticdb-path PATH       Explicit .semanticdb directory
    |  --limit N                    Max results (default: 50, 0=unlimited)
    |  --json                       JSON output
    |  --verbose, -v                Full signatures and properties
    |  --kind K                     Filter by kind and narrow symbol resolution
    |  --role R                     Filter occurrences (def/ref)
    |  --depth N                    Max depth for recursive commands (callers: 1, flow/subtypes: 3, path: 5)
    |  --no-accessors               Exclude val/var accessors from flow/callees
    |  --exclude "p1,p2,..."        Exclude symbols matching FQN or file path
    |  --smart                       Auto-filter infrastructure noise (accessors, protobuf, plumbing)
    |  --timings                    Print timing info to stderr
    |  --version                    Print version
    |""".stripMargin)
