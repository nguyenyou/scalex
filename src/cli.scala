import java.nio.file.{Files, Path}
import java.io.{BufferedReader, InputStreamReader}

// ── CLI entry point ─────────────────────────────────────────────────────────

def resolveWorkspace(path: String): Path =
  val p = Path.of(path).toAbsolutePath.normalize
  if Files.isDirectory(p) then p else p.getParent

def parseWorkspaceAndArg(rest: List[String]): Option[(workspace: Path, arg: String)] =
  rest match
    case a :: Nil => Some((resolveWorkspace("."), a))
    case ws :: a :: _ => Some((resolveWorkspace(ws), a))
    case _ => None

// ── Flag parsing (shared by main + batch) ─────────────────────────────────

case class ParsedFlags(
  limit: Int = 20, kindFilter: Option[String] = None, verbose: Boolean = false,
  categorize: Boolean = true, includeTests: Boolean = false, noTests: Boolean = false,
  pathFilter: Option[String] = None, contextLines: Int = 0, jsonOutput: Boolean = false,
  countOnly: Boolean = false, searchMode: Option[String] = None, definitionsOnly: Boolean = false,
  categoryFilter: Option[String] = None, grepPatterns: List[String] = Nil,
  explicitWorkspace: Option[String] = None,
  inOwner: Option[String] = None, ofTrait: Option[String] = None,
  implLimit: Int = 5, goUp: Boolean = true, goDown: Boolean = true, maxDepth: Int = -1,
  inherited: Boolean = false, architecture: Boolean = false,
  hasMethodFilter: Option[String] = None, extendsFilter: Option[String] = None,
  bodyContainsFilter: Option[String] = None, focusPackage: Option[String] = None,
  expandDepth: Int = 0, membersLimit: Int = 10, brief: Boolean = false, strict: Boolean = false,
  usedByFilter: Option[String] = None, returnsFilter: Option[String] = None,
  takesFilter: Option[String] = None, shallow: Boolean = false, noDoc: Boolean = false,
  excludePath: Option[String] = None, topN: Option[Int] = None, summaryMode: Boolean = false,
  timingsEnabled: Boolean = false,
  withBody: Boolean = false, maxBodyLines: Int = 0,
  showImports: Boolean = false,
  offset: Int = 0,
  related: Boolean = false,
  explainMode: Boolean = false,
  concise: Boolean = false,
  maxOutput: Int = 0,
  inPackageFilter: Option[String] = None,
  eachMethod: Boolean = false,
  cleanArgs: List[String] = Nil,
)

/** Single left-to-right pass over the arg list. Each token is classified exactly
  * once as a flag, a flag's value, or a positional arg — so a positional that
  * happens to equal a flag's value elsewhere in the list is never misclassified. */
def parseFlags(argList: List[String]): ParsedFlags = {
  var f = ParsedFlags()
  val positional = List.newBuilder[String]
  // Paired flags are resolved after the pass
  var sawUp = false
  var sawDown = false
  var sawExact = false
  var sawPrefix = false
  var wsLong: Option[String] = None
  var wsShort: Option[String] = None

  val args = argList.toVector
  var i = 0
  while i < args.length do {
    // For flags that take a value: read the next token and consume it
    def value: Option[String] = {
      i += 1
      args.lift(i)
    }
    def intValue(default: Int): Int = value.flatMap(_.toIntOption).getOrElse(default)

    args(i) match {
      case "--limit"          => val v = intValue(20); f = f.copy(limit = if v == 0 then Int.MaxValue else v)
      case "--kind"           => f = f.copy(kindFilter = value)
      case "--workspace"      => wsLong = value
      case "-w"               => wsShort = value
      case "--path"           => f = f.copy(pathFilter = value.map(_.stripPrefix("/")))
      case "--exclude-path"   => f = f.copy(excludePath = value.map(_.stripPrefix("/")))
      case "-C"               => f = f.copy(contextLines = intValue(0))
      case "-e"               => value.filterNot(_.startsWith("-")).foreach(p => f = f.copy(grepPatterns = f.grepPatterns :+ p))
      case "--category"       => f = f.copy(categoryFilter = value)
      case "--in"             => f = f.copy(inOwner = value)
      case "--of"             => f = f.copy(ofTrait = value)
      case "--impl-limit"     => f = f.copy(implLimit = intValue(5))
      case "--depth"          => f = f.copy(maxDepth = intValue(-1))
      case "--has-method"     => f = f.copy(hasMethodFilter = value)
      case "--extends"        => f = f.copy(extendsFilter = value)
      case "--body-contains"  => f = f.copy(bodyContainsFilter = value)
      case "--focus-package"  => f = f.copy(focusPackage = value)
      case "--expand"         => f = f.copy(expandDepth = intValue(1))
      case "--members-limit"  => f = f.copy(membersLimit = intValue(10))
      case "--used-by"        => f = f.copy(usedByFilter = value)
      case "--returns"        => f = f.copy(returnsFilter = value)
      case "--takes"          => f = f.copy(takesFilter = value)
      case "--top"            => f = f.copy(topN = value.flatMap(_.toIntOption))
      case "--max-lines"      => f = f.copy(maxBodyLines = intValue(0))
      case "--offset"         => f = f.copy(offset = intValue(0))
      case "--max-output"     => f = f.copy(maxOutput = intValue(0))
      case "--in-package"     => f = f.copy(inPackageFilter = value)
      case "--verbose"        => f = f.copy(verbose = true)
      case "--flat"           => f = f.copy(categorize = false)
      case "--include-tests"  => f = f.copy(includeTests = true)
      case "--no-tests"       => f = f.copy(noTests = true)
      case "--json"           => f = f.copy(jsonOutput = true)
      case "--count"          => f = f.copy(countOnly = true)
      case "--exact"          => sawExact = true
      case "--prefix"         => sawPrefix = true
      case "--definitions-only" => f = f.copy(definitionsOnly = true)
      case "--up"             => sawUp = true
      case "--down"           => sawDown = true
      case "--inherited"      => f = f.copy(inherited = true)
      case "--architecture"   => f = f.copy(architecture = true)
      case "--brief"          => f = f.copy(brief = true)
      case "--strict"         => f = f.copy(strict = true)
      case "--shallow"        => f = f.copy(shallow = true)
      case "--no-doc"         => f = f.copy(noDoc = true)
      case "--summary"        => f = f.copy(summaryMode = true)
      case "--timings"        => f = f.copy(timingsEnabled = true)
      case "--body" | "--with-bodies" => f = f.copy(withBody = true)
      case "--imports"        => f = f.copy(showImports = true)
      case "--related"        => f = f.copy(related = true)
      case "--explain"        => f = f.copy(explainMode = true)
      case "--concise"        => f = f.copy(concise = true)
      case "--each-method"    => f = f.copy(eachMethod = true)
      case "--categorize" | "-c" => () // categorized output is the default; kept for backwards compatibility
      case other if other.startsWith("--") => () // unknown long flags are ignored
      case other => positional += other
    }
    i += 1
  }

  f.copy(
    searchMode = if sawExact then Some("exact") else if sawPrefix then Some("prefix") else None,
    goUp = !sawDown || sawUp,
    goDown = !sawUp || sawDown,
    explicitWorkspace = wsLong.orElse(wsShort),
    cleanArgs = positional.result(),
  )
}

private def flagsToContext(f: ParsedFlags, idx: WorkspaceIndex, workspace: Path,
                           batchMode: Boolean = false, effectiveNoTests: Option[Boolean] = None): CommandContext =
  val noTests = effectiveNoTests.getOrElse(f.noTests)
  CommandContext(idx = idx, workspace = workspace, limit = f.limit, verbose = f.verbose,
    jsonOutput = f.jsonOutput, batchMode = batchMode, kindFilter = f.kindFilter, noTests = noTests,
    pathFilter = f.pathFilter, contextLines = f.contextLines, categorize = f.categorize,
    categoryFilter = f.categoryFilter, grepPatterns = f.grepPatterns, countOnly = f.countOnly,
    topN = f.topN, searchMode = f.searchMode, definitionsOnly = f.definitionsOnly,
    inOwner = f.inOwner, ofTrait = f.ofTrait, implLimit = f.implLimit,
    goUp = f.goUp, goDown = f.goDown, maxDepth = f.maxDepth, inherited = f.inherited,
    architecture = f.architecture, focusPackage = f.focusPackage,
    hasMethodFilter = f.hasMethodFilter, extendsFilter = f.extendsFilter,
    bodyContainsFilter = f.bodyContainsFilter, expandDepth = f.expandDepth,
    membersLimit = f.membersLimit, brief = f.brief, strict = f.strict,
    usedByFilter = f.usedByFilter, returnsFilter = f.returnsFilter, takesFilter = f.takesFilter,
    shallow = f.shallow, noDoc = f.noDoc, excludePath = f.excludePath, summaryMode = f.summaryMode,
    withBody = f.withBody, maxBodyLines = f.maxBodyLines, showImports = f.showImports,
    offset = f.offset, related = f.related, explainMode = f.explainMode, concise = f.concise,
    maxOutput = f.maxOutput, inPackageFilter = f.inPackageFilter,
    eachMethod = f.eachMethod)

@main def main(args: String*): Unit =
  if args.contains("--version") then println(ScalexVersion)
  else runCli(parseFlags(args.toList), args.toList)

private def runCli(f: ParsedFlags, args: List[String]): Unit =
  Timings.enabled = f.timingsEnabled

  f.cleanArgs match
    case Nil | List("help") =>
      println("""Scalex — Scala code intelligence for coding agents
        |
        |Commands:
        |  scalex search <query>           Search symbols by name          (aka: find symbol)
        |  scalex def <symbol>             Where is this symbol defined?   (aka: find definition)
        |  scalex impl <trait>             Who extends this trait/class?   (aka: find implementations)
        |  scalex refs <symbol>            Who uses this symbol?           (aka: find references)
        |  scalex imports <symbol>         Who imports this symbol?        (aka: import graph)
        |  scalex members <symbol>         What's inside this class/trait? (aka: list members)
        |  scalex doc <symbol>             Show scaladoc for a symbol      (aka: show docs)
        |  scalex overview                 Codebase summary                (aka: project overview)
        |  scalex symbols <file>           What's defined in this file?    (aka: file symbols)
        |  scalex file <query>             Search files by name            (aka: find file)
        |  scalex annotated <annotation>   Find symbols with annotation    (aka: find annotated)
        |  scalex grep <pattern>           Regex search in file contents   (aka: content search)
        |  scalex packages                 What packages exist?            (aka: list packages)
        |  scalex package <pkg>            Symbols in a package            (aka: explore package)
        |  scalex index                    Rebuild the index               (aka: reindex)
        |  scalex batch                    Run multiple queries at once    (aka: batch mode)
        |  scalex body <symbol>            Extract method/val/class body   (aka: show source)
        |  scalex hierarchy <symbol>       Full inheritance tree (--depth N, default 5)
        |  scalex overrides <method>       Find override implementations   (aka: find overrides)
        |  scalex explain <symbol>         Composite one-shot summary      (aka: explain symbol)
        |  scalex deps <symbol>            Show symbol dependencies        (aka: dependency graph)
        |  scalex context <file:line>      Show enclosing scopes at line   (aka: scope chain)
        |  scalex diff <git-ref>           Symbol-level diff vs git ref    (aka: symbol diff)
        |  scalex ast-pattern              Structural AST search           (aka: pattern search)
        |  scalex tests                    List test cases structurally    (aka: find tests)
        |  scalex coverage <symbol>        Is this symbol tested?          (aka: test coverage)
        |  scalex api <package>            Public API surface of a package (aka: exported symbols)
        |  scalex summary <package>        Sub-packages with symbol counts   (aka: package breakdown)
        |  scalex entrypoints              Find @main, def main, extends App, test suites
        |  scalex graph --render "A->B"    Render directed graph as ASCII/Unicode art
        |  scalex graph --parse            Parse ASCII diagram from stdin into boxes+edges
        |
        |Options:
        |  -w, --workspace PATH  Set workspace path (default: current directory)
        |  --limit N             Max results (default: 20, 0 = unlimited)
        |  --offset N            Members: skip first N results for pagination (default: 0)
        |  --kind K              Filter by kind: class, trait, object, def, val, type, enum, given, extension
        |  --verbose             Show signatures and extends clauses
        |  --categorize, -c      Group refs by category (default; kept for backwards compatibility)
        |  --flat                Refs: flat list instead of categorized (overrides default)
        |  --definitions-only    Search: only return class/trait/object/enum definitions
        |  --category CAT        Refs: filter to a single category (Definition/ExtendedBy/ImportedBy/UsedAsType/Usage/Comment)
        |  --no-tests            Exclude test files (test/, tests/, testing/, bench-*, *Spec.scala, etc.)
        |  --include-tests       Override --no-tests default for overview command
        |  --path PREFIX         Restrict results to files under PREFIX (e.g. compiler/src/)
        |  --exclude-path PREFIX Exclude files under PREFIX (e.g. --exclude-path sbt-test/)
        |  -C N                  Show N context lines around each reference (refs, grep, body)
        |  -e PATTERN            Grep: additional pattern (combine multiple with |); repeatable
        |  --count               Grep/refs: show counts only, no full results
        |  --top N               Refs: rank top N files by reference count
        |  --exact               Search: only exact name matches
        |  --prefix              Search: only exact + prefix matches
        |  --json                Output results as JSON (structured output for programmatic use)
        |  --version             Print version and exit
        |  --in OWNER            Body/grep: restrict to members of the given enclosing type
        |  --each-method         Grep: with --in, report which methods match (per-method grep)
        |  --of TRAIT            Overrides: restrict to implementations of the given trait
        |  --body                Members/overrides/explain: inline method bodies into output
        |  --max-lines N         Members/overrides/explain: only inline bodies ≤ N lines (0 = unlimited)
        |  --imports             Body: prepend file's import block to output
        |  --shallow              Explain: skip implementations and import refs (definition + members only)
        |  --no-doc               Explain: suppress Scaladoc section
        |  --impl-limit N        Explain: max implementations to show (default: 5)
        |  --members-limit N    Explain: max members to show per type (default: 10)
        |  --expand N            Explain: recursively expand implementations N levels deep
        |  --up                  Hierarchy: show only parents (default: both)
        |  --down                Hierarchy: show only children (default: both)
        |  --depth N             Hierarchy/deps: max tree depth (hierarchy default: 5, no cap; deps default: 1, max: 5)
        |  --inherited           Members/explain: include inherited members from parent types
        |  --brief               Members: names only; Explain: definition + top 3 members only
        |  --summary             Symbols: show grouped counts by kind instead of full listing
        |  --strict              Refs/imports: treat _ and $ as word characters (no boundary matches)
        |  --related             Explain: show project-defined types referenced in member signatures
        |  --explain             Package: brief explain per type (definition + top 3 members + impl count)
        |  --architecture        Overview: show package dependency graph and hub types
        |  --concise             Overview: fixed-size summary (~60 lines) with top packages, hub types, dep stats
        |  --focus-package PKG   Overview: scope dependency graph to a single package
        |  --has-method NAME     AST pattern: match types that have a method with NAME
        |  --extends TRAIT       AST pattern: match types that extend TRAIT
        |  --body-contains PAT   AST pattern: match types whose body contains PAT
        |  --used-by PKG         API: filter importers to only those from PKG
        |  --returns TYPE        Search: filter to symbols whose signature returns TYPE
        |  --takes TYPE          Search: filter to symbols whose signature takes TYPE
        |  --max-output N        Truncate output at N characters (0 = unlimited); works on all commands
        |  --in-package PKG      Filter results to files whose package matches PKG prefix
        |  --timings             Print per-phase timing breakdown to stderr
        |
        |All commands accept an optional [workspace] positional arg or -w flag (default: current directory).
        |First run indexes the project (~3s for 14k files). Subsequent runs use cache (~300ms).
        |Java files (.java) are indexed via JavaParser AST (class/interface/enum/record/method/field).
        |""".stripMargin)

    case "batch" :: rest =>
      val workspace = resolveWorkspace(f.explicitWorkspace.orElse(rest.headOption).getOrElse("."))
      val idx = WorkspaceIndex(workspace, needBlooms = true)
      idx.index()
      Timings.report()
      val baseCtx = flagsToContext(f, idx, workspace, batchMode = true)
      val reader = BufferedReader(InputStreamReader(System.in))
      var line = reader.readLine()
      while line != null do
        val parts = line.trim.split("\\s+").toList
        if parts.nonEmpty && parts.head.nonEmpty then
          val batchCmd = parts.head
          // Parse per-line flags so each batch line can override --path, --no-tests, etc.
          val lineFlags = parseFlags(parts.tail)
          // Inherit global --max-output / --in-package when per-line doesn't override
          val mergedFlags = lineFlags.copy(
            maxOutput = if lineFlags.maxOutput > 0 then lineFlags.maxOutput else f.maxOutput,
            inPackageFilter = lineFlags.inPackageFilter.orElse(f.inPackageFilter),
          )
          val lineCtx = flagsToContext(mergedFlags, idx, workspace, batchMode = true)
          println(s">>> $line")
          Timings.reset()
          runCommand(batchCmd, lineFlags.cleanArgs, lineCtx)
          Timings.report()
          println()
        line = reader.readLine()

    case "graph" :: _ =>
      // graph command doesn't need workspace index — extract raw args after "graph", strip global flags
      val afterGraph = args.toList.dropWhile(_ != "graph").drop(1)
      val graphArgs = {
        val buf = scala.collection.mutable.ListBuffer[String]()
        var i = 0
        while i < afterGraph.size do
          afterGraph(i) match
            case "-w" | "--workspace" | "--max-output" | "--in-package" => i += 1 // skip flag + value
            case "--timings" | "--json" | "--each-method" => () // skip standalone flags already parsed
            case other => buf += other
          i += 1
        buf.toList
      }
      val workspace = resolveWorkspace(f.explicitWorkspace.getOrElse("."))
      val dummyIdx = WorkspaceIndex(workspace, needBlooms = false)
      val ctx = flagsToContext(f, dummyIdx, workspace)
      val result = cmdGraph(graphArgs, ctx)
      // graph bypasses runCommand (cleanArgs strips --render/--parse), use renderWithBudget directly
      renderWithBudget(result, ctx)
      Timings.report()

    case cmd :: rest =>
      val (workspace, cmdRest) = f.explicitWorkspace match
        case Some(ws) =>
          (resolveWorkspace(ws), rest)
        case None =>
          cmd match
            case "index" | "packages" | "overview" | "ast-pattern" | "entrypoints" =>
              (resolveWorkspace(rest.headOption.getOrElse(".")), rest)
            case _ =>
              rest match
                case arg :: Nil => (resolveWorkspace("."), List(arg))
                case ws :: arg :: tail => (resolveWorkspace(ws), arg :: tail)
                case Nil => (resolveWorkspace("."), Nil)

      // overview defaults to --no-tests unless --include-tests is explicitly passed
      val effectiveNoTests = if cmd == "overview" && !f.includeTests then true else f.noTests

      val bloomCmds = Set("refs", "imports", "coverage")
      val idx = WorkspaceIndex(workspace, needBlooms = bloomCmds.contains(cmd))
      idx.index()
      val ctx = flagsToContext(f, idx, workspace, effectiveNoTests = Some(effectiveNoTests))
      runCommand(cmd, cmdRest, ctx)
      Timings.report()
