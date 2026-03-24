import java.nio.file.{Files, Path}

// ── CLI entry point ────────────────────────────────────────────────────────

@main def main(args: String*): Unit =
  val argList = args.toList
  if argList.isEmpty || argList.head == "--help" || argList.head == "-h" then
    printUsage()
  else if argList.head == "--version" then
    println(s"sdbx $SdbxVersion")
  else
    run(argList)

private def run(argList: List[String]): Unit =
  val cmd = argList.head
  val rest = argList.tail
  val flags = parseFlags(rest)

  if flags.timingsEnabled then SemTimings.enable()

  val workspace = flags.explicitWorkspace match
    case Some(w) => Path.of(w).toAbsolutePath.normalize
    case None    => Path.of(".").toAbsolutePath.normalize

  val index = SemIndex(workspace)

  if cmd == "daemon" then
    val (parentPid, daemonArgs) = extractDaemonParentPid(rest)
    val (socketMode, daemonArgs2) = extractDaemonSocket(daemonArgs)
    val daemonFlags = parseFlags(daemonArgs2)
    val daemonWorkspace = daemonFlags.explicitWorkspace match
      case Some(w) => Path.of(w).toAbsolutePath.normalize
      case None    => workspace
    val idleTimeout = daemonFlags.cleanArgs.headOption.flatMap(_.toLongOption).getOrElse(300L)
    val maxLifetime = daemonFlags.cleanArgs.drop(1).headOption.flatMap(_.toLongOption).getOrElse(1800L)
    runDaemon(daemonWorkspace, idleTimeout, maxLifetime, parentPid, socketMode)
  else
    // Transparent daemon forwarding: try socket before loading index
    val forwarded = cmd != "index" && {
      val sockPath = socketPath(workspace)
      Files.exists(sockPath) && {
        trySocketForward(cmd, flags, sockPath) match
          case Some(response) =>
            if !flags.jsonOutput then System.err.println("(via daemon — output is JSON)")
            println(response)
            true
          case None => false
      }
    }

    if !forwarded then
      if cmd == "index" then
        index.rebuild()
        val result = SemCmdResult.Stats(
          index.fileCount, index.symbolCount, index.occurrenceCount,
          index.buildTimeMs, index.cachedLoad, index.parsedCount, index.skippedCount,
        )
        val ctx = flagsToContext(flags, index, workspace)
        render(result, ctx)
      else
        // For all other commands, build/load the index first.
        // Symbol-only commands skip loading occurrences (~70% faster on large projects).
        val needOccs = cmd match
          case "lookup" | "symbols" | "members" | "subtypes" | "supertypes" | "type" => false
          case _ => true // refs, callers, callees, flow, path, explain, related, occurrences, batch
        index.build(needOccs)

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
  if args.isEmpty then SemCmdResult.UsageError("batch requires at least one sub-command argument.\nUsage: batch \"lookup Dog\" \"members Animal\" ...")
  else {
    val results = args.map { subCmdStr =>
      val parts = subCmdStr.trim.split("\\s+").toList.filter(_.nonEmpty)
        .map(t => if t.length > 1 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) then t.substring(1, t.length - 1) else t)
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
          inScope = subFlags.inScope.orElse(ctx.inScope),
          excludeTest = subFlags.excludeTest || ctx.excludeTest,
          excludePkgPatterns = if subFlags.excludePkgPatterns.nonEmpty then subFlags.excludePkgPatterns else ctx.excludePkgPatterns,
          sourceOnly = subFlags.sourceOnly || ctx.sourceOnly,
        )
        val result = commands.get(subCmd) match
          case Some(handler) => handler(subFlags.cleanArgs, subCtx)
          case None => SemCmdResult.UsageError(s"Unknown command: $subCmd")
        (command = subCmdStr, result = result)
      }
    }
    SemCmdResult.Batch(results)
  }

def cmdStats(args: List[String], ctx: SemCommandContext): SemCmdResult =
  SemCmdResult.Stats(
    ctx.index.fileCount, ctx.index.symbolCount, ctx.index.occurrenceCount,
    ctx.index.buildTimeMs, ctx.index.cachedLoad, ctx.index.parsedCount, ctx.index.skippedCount,
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
  timingsEnabled: Boolean = false,
  noAccessors: Boolean = false,
  excludePatterns: List[String] = Nil,
  smart: Boolean = false,
  inScope: Option[String] = None,
  excludeTest: Boolean = false,
  excludePkgPatterns: List[String] = Nil,
  sourceOnly: Boolean = false,
  cleanArgs: List[String] = Nil,
)

private def extractDaemonParentPid(args: List[String]): (parentPid: Option[Long], remaining: List[String]) =
  val idx = args.indexOf("--parent-pid")
  if idx >= 0 && idx + 1 < args.length then
    val pid = args(idx + 1).toLongOption
    val remaining = args.take(idx) ++ args.drop(idx + 2)
    (parentPid = pid, remaining = remaining)
  else
    (parentPid = None, remaining = args)

private def extractDaemonSocket(args: List[String]): (socketMode: Boolean, remaining: List[String]) =
  val idx = args.indexOf("--socket")
  if idx >= 0 then
    (socketMode = true, remaining = args.take(idx) ++ args.drop(idx + 1))
  else
    (socketMode = false, remaining = args)

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
      case "--role" if i + 1 < arr.length =>
        flags = flags.copy(roleFilter = Some(arr(i + 1)))
        i += 2
      case "--depth" if i + 1 < arr.length =>
        flags = flags.copy(depth = arr(i + 1).toIntOption)
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
      case "--source-only" =>
        flags = flags.copy(sourceOnly = true)
        i += 1
      case "--in" if i + 1 < arr.length =>
        flags = flags.copy(inScope = Some(arr(i + 1)))
        i += 2
      case "--exclude-test" =>
        flags = flags.copy(excludeTest = true)
        i += 1
      case "--exclude-pkg" if i + 1 < arr.length =>
        flags = flags.copy(excludePkgPatterns = arr(i + 1).split(",").map(_.trim.replace(".", "/")).filter(_.nonEmpty).toList)
        i += 2
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
    inScope = flags.inScope,
    excludeTest = flags.excludeTest,
    excludePkgPatterns = flags.excludePkgPatterns,
    sourceOnly = flags.sourceOnly,
  )

// ── Socket forwarding ────────────────────────────────────────────────────

private def trySocketForward(cmd: String, flags: SemParsedFlags, sockPath: Path): Option[String] =
  try
    val addr = java.net.UnixDomainSocketAddress.of(sockPath)
    val ch = java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX)
    try
      ch.connect(addr)
      val request = buildDaemonRequest(cmd, flags)
      val out = java.nio.channels.Channels.newOutputStream(ch)
      out.write((request + "\n").getBytes("UTF-8"))
      out.flush()
      ch.shutdownOutput()
      val reader = java.io.BufferedReader(
        java.io.InputStreamReader(java.nio.channels.Channels.newInputStream(ch))
      )
      val response = reader.readLine()
      if response != null then Some(response) else None
    finally ch.close()
  catch
    case _: Exception => None // stale socket, connection refused, Java <16 — fall through

private def escapeJson(s: String): String =
  s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

private def buildDaemonRequest(cmd: String, flags: SemParsedFlags): String =
  val argsJson = flags.cleanArgs.map(a => s""""${escapeJson(a)}"""").mkString("[", ",", "]")
  val parts = scala.collection.mutable.ListBuffer.empty[String]
  if flags.limit != 50 then parts += s""""limit":${flags.limit}"""
  if flags.verbose then parts += s""""verbose":"true""""
  if flags.jsonOutput then parts += s""""json":"true""""
  flags.kindFilter.foreach(k => parts += s""""kind":"${escapeJson(k)}"""")
  flags.roleFilter.foreach(r => parts += s""""role":"${escapeJson(r)}"""")
  flags.depth.foreach(d => parts += s""""depth":$d""")
  if flags.noAccessors then parts += s""""no-accessors":"true""""
  if flags.excludePatterns.nonEmpty then parts += s""""exclude":"${escapeJson(flags.excludePatterns.mkString(","))}""""
  if flags.smart then parts += s""""smart":"true""""
  flags.inScope.foreach(s => parts += s""""in":"${escapeJson(s)}"""")
  if flags.excludeTest then parts += s""""exclude-test":"true""""
  if flags.excludePkgPatterns.nonEmpty then parts += s""""exclude-pkg":"${escapeJson(flags.excludePkgPatterns.map(_.replace("/", ".")).mkString(","))}""""
  if flags.sourceOnly then parts += s""""source-only":"true""""
  val flagsJson = "{" + parts.mkString(",") + "}"
  s"""{"command":"${escapeJson(cmd)}","args":$argsJson,"flags":$flagsJson}"""

// ── Usage ──────────────────────────────────────────────────────────────────

def printUsage(): Unit =
  println("""sdbx — Compiler-precise code intelligence from SemanticDB
    |
    |Usage: sdbx <command> [args] [options]
    |
    |Call graph (compiler-only):
    |  callers <symbol>      Who calls this method (reverse call graph, --depth N for transitive)
    |  callees <symbol>      What does this method call (forward call graph)
    |  flow <method>         Recursive call tree (use --smart on large codebases)
    |  path <src> <tgt>      Find shortest call path between two symbols
    |
    |Composite:
    |  explain <symbol>      One-shot summary: type, callers, callees, members, subtypes
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
    |Daemon (for coding agents):
    |  daemon [idle] [max]   Stdin/stdout JSON-lines server (keeps index hot in memory)
    |                        idle = idle timeout seconds (default: 300)
    |                        max  = max lifetime seconds (default: 1800)
    |                        --parent-pid PID  Monitor parent process (auto-exit on parent death)
    |                        --socket          Listen on Unix domain socket (requires Java 16+)
    |                                          Use with --parent-pid when possible for faster cleanup.
    |                                          Clients connect, send JSON-line, read response, disconnect.
    |                        Non-daemon commands auto-detect a running socket daemon and forward
    |                        queries transparently (<10ms). Falls back to local index if unavailable.
    |                        Self-terminates on: stdin EOF, parent PID exit, idle timeout,
    |                        max lifetime, query timeout (30s), heap pressure, startup timeout (120s)
    |
    |Index:
    |  index                 Rebuild index (force)
    |  stats                 Index statistics
    |
    |Options:
    |  -w, --workspace PATH         Set workspace (default: cwd, must be a Mill project root)
    |  --limit N                    Max results (default: 50, 0=unlimited)
    |  --json                       JSON output
    |  --verbose, -v                Full signatures and properties
    |  --kind K                     Filter by kind and narrow symbol resolution
    |  --role R                     Filter occurrences (def/ref)
    |  --depth N                    Max depth for recursive commands (callers: 1, flow/subtypes: 3, path: 5)
    |  --no-accessors               Exclude val/var accessors from flow/callees
    |  --exclude "p1,p2,..."        Exclude symbols matching FQN or file path
    |  --exclude-test               Exclude symbols from test source directories
    |  --exclude-pkg "p1,p2,..."    Exclude symbols by package prefix (dots auto-converted to /)
    |  --in <scope>                 Scope symbol resolution by owner class, file, or package
    |  --smart                       Auto-filter noise (synthetics, accessors, protobuf, combinators)
    |  --source-only                 Exclude generated/compiled sources from lookup results
    |  --timings                    Print timing info to stderr
    |  --version                    Print version
    |""".stripMargin)
