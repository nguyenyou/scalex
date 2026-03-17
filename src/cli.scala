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
  cleanArgs: List[String] = Nil,
)

private val flagsWithArgs = Set("--limit", "--kind", "--workspace", "-w", "--path", "--exclude-path", "-C", "-e", "--category",
                         "--in", "--of", "--impl-limit", "--depth", "--has-method", "--extends", "--body-contains", "--focus-package", "--expand",
                         "--members-limit", "--used-by", "--returns", "--takes", "--top")

def parseFlags(argList: List[String]): ParsedFlags =
  val limit = argList.indexOf("--limit") match
    case -1 => 20
    case i => argList.lift(i + 1).flatMap(_.toIntOption).getOrElse(20)
  val kindFilter = argList.indexOf("--kind") match
    case -1 => None
    case i => argList.lift(i + 1)
  val verbose = argList.contains("--verbose")
  val categorize = !argList.contains("--flat")
  val includeTests = argList.contains("--include-tests")
  val noTests = argList.contains("--no-tests")
  val pathFilter: Option[String] = argList.indexOf("--path") match
    case -1 => None
    case i => argList.lift(i + 1).map(p => p.stripPrefix("/"))
  val contextLines: Int = argList.indexOf("-C") match
    case -1 => 0
    case i => argList.lift(i + 1).flatMap(_.toIntOption).getOrElse(0)
  val jsonOutput = argList.contains("--json")
  val countOnly = argList.contains("--count")
  val searchMode: Option[String] =
    if argList.contains("--exact") then Some("exact")
    else if argList.contains("--prefix") then Some("prefix")
    else None
  val definitionsOnly = argList.contains("--definitions-only")
  val categoryFilter: Option[String] = argList.indexOf("--category") match
    case -1 => None
    case i => argList.lift(i + 1)
  val grepPatterns: List[String] = argList.zipWithIndex.collect {
    case ("-e", i) if argList.lift(i + 1).exists(a => !a.startsWith("-")) => argList(i + 1)
  }
  val explicitWorkspace: Option[String] =
    val longIdx = argList.indexOf("--workspace")
    val shortIdx = argList.indexOf("-w")
    val idx = if longIdx >= 0 then longIdx else shortIdx
    if idx >= 0 then argList.lift(idx + 1) else None
  val inOwner: Option[String] = argList.indexOf("--in") match
    case -1 => None
    case i => argList.lift(i + 1)
  val ofTrait: Option[String] = argList.indexOf("--of") match
    case -1 => None
    case i => argList.lift(i + 1)
  val implLimit: Int = argList.indexOf("--impl-limit") match
    case -1 => 5
    case i => argList.lift(i + 1).flatMap(_.toIntOption).getOrElse(5)
  val goUp = !argList.contains("--down") || argList.contains("--up")
  val goDown = !argList.contains("--up") || argList.contains("--down")
  val maxDepth: Int = argList.indexOf("--depth") match
    case -1 => -1
    case i => argList.lift(i + 1).flatMap(_.toIntOption).getOrElse(-1)
  val inherited = argList.contains("--inherited")
  val architecture = argList.contains("--architecture")
  val hasMethodFilter: Option[String] = argList.indexOf("--has-method") match
    case -1 => None
    case i => argList.lift(i + 1)
  val extendsFilter: Option[String] = argList.indexOf("--extends") match
    case -1 => None
    case i => argList.lift(i + 1)
  val bodyContainsFilter: Option[String] = argList.indexOf("--body-contains") match
    case -1 => None
    case i => argList.lift(i + 1)
  val focusPackage: Option[String] = argList.indexOf("--focus-package") match
    case -1 => None
    case i => argList.lift(i + 1)
  val expandDepth: Int = argList.indexOf("--expand") match
    case -1 => 0
    case i => argList.lift(i + 1).flatMap(_.toIntOption).getOrElse(1)
  val membersLimit: Int = argList.indexOf("--members-limit") match
    case -1 => 10
    case i => argList.lift(i + 1).flatMap(_.toIntOption).getOrElse(10)
  val brief = argList.contains("--brief")
  val strict = argList.contains("--strict")
  val usedByFilter: Option[String] = argList.indexOf("--used-by") match
    case -1 => None
    case i => argList.lift(i + 1)
  val returnsFilter: Option[String] = argList.indexOf("--returns") match
    case -1 => None
    case i => argList.lift(i + 1)
  val takesFilter: Option[String] = argList.indexOf("--takes") match
    case -1 => None
    case i => argList.lift(i + 1)
  val shallow = argList.contains("--shallow")
  val noDoc = argList.contains("--no-doc")
  val excludePath: Option[String] = argList.indexOf("--exclude-path") match
    case -1 => None
    case i => argList.lift(i + 1).map(p => p.stripPrefix("/"))
  val topN: Option[Int] = argList.indexOf("--top") match
    case -1 => None
    case i => argList.lift(i + 1).flatMap(_.toIntOption)
  val summaryMode = argList.contains("--summary")
  val timingsEnabled = argList.contains("--timings")

  val cleanArgs = argList.filterNot(a => a.startsWith("--") || a == "-w" || a == "-C" || a == "-e" || a == "-c" || {
    val prev = argList.indexOf(a) - 1
    prev >= 0 && flagsWithArgs.contains(argList(prev))
  })

  ParsedFlags(limit, kindFilter, verbose, categorize, includeTests, noTests, pathFilter,
    contextLines, jsonOutput, countOnly, searchMode, definitionsOnly, categoryFilter, grepPatterns,
    explicitWorkspace, inOwner, ofTrait, implLimit, goUp, goDown, maxDepth, inherited, architecture,
    hasMethodFilter, extendsFilter, bodyContainsFilter, focusPackage, expandDepth, membersLimit,
    brief, strict, usedByFilter, returnsFilter, takesFilter, shallow, noDoc, excludePath, topN,
    summaryMode, timingsEnabled, cleanArgs)

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
    shallow = f.shallow, noDoc = f.noDoc, excludePath = f.excludePath, summaryMode = f.summaryMode)

@main def main(args: String*): Unit =
  val f = parseFlags(args.toList)

  if args.contains("--version") then
    println(ScalexVersion)
    return

  Timings.enabled = f.timingsEnabled

  f.cleanArgs match
    case Nil | List("help") =>
      println("""Scalex — Scala code intelligence for AI agents
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
        |
        |Options:
        |  -w, --workspace PATH  Set workspace path (default: current directory)
        |  --limit N             Max results (default: 20)
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
        |  -C N                  Show N context lines around each reference (refs, grep)
        |  -e PATTERN            Grep: additional pattern (combine multiple with |); repeatable
        |  --count               Grep/refs: show counts only, no full results
        |  --top N               Refs: rank top N files by reference count
        |  --exact               Search: only exact name matches
        |  --prefix              Search: only exact + prefix matches
        |  --json                Output results as JSON (structured output for programmatic use)
        |  --version             Print version and exit
        |  --in OWNER            Body: restrict to members of the given enclosing type
        |  --of TRAIT            Overrides: restrict to implementations of the given trait
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
        |  --architecture        Overview: show package dependency graph and hub types
        |  --focus-package PKG   Overview: scope dependency graph to a single package
        |  --has-method NAME     AST pattern: match types that have a method with NAME
        |  --extends TRAIT       AST pattern: match types that extend TRAIT
        |  --body-contains PAT   AST pattern: match types whose body contains PAT
        |  --used-by PKG         API: filter importers to only those from PKG
        |  --returns TYPE        Search: filter to symbols whose signature returns TYPE
        |  --takes TYPE          Search: filter to symbols whose signature takes TYPE
        |  --timings             Print per-phase timing breakdown to stderr
        |
        |All commands accept an optional [workspace] positional arg or -w flag (default: current directory).
        |First run indexes the project (~3s for 14k files). Subsequent runs use cache (~300ms).
        |Java files (.java) are indexed with lightweight regex extraction (class/interface/enum/record).
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
          val lineCtx = flagsToContext(lineFlags, idx, workspace, batchMode = true)
          println(s">>> $line")
          Timings.reset()
          runCommand(batchCmd, lineFlags.cleanArgs, lineCtx)
          Timings.report()
          println()
        line = reader.readLine()

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
