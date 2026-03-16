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

@main def main(args: String*): Unit =
  val argList = args.toList

  if argList.contains("--version") then
    println(ScalexVersion)
    return

  val limit = argList.indexOf("--limit") match
    case -1 => 20
    case i => argList.lift(i + 1).flatMap(_.toIntOption).getOrElse(20)
  val kindFilter = argList.indexOf("--kind") match
    case -1 => None
    case i => argList.lift(i + 1)
  val verbose = argList.contains("--verbose")
  val categorize = !argList.contains("--flat")
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

  // New flags for new commands
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

  val flagsWithArgs = Set("--limit", "--kind", "--workspace", "-w", "--path", "-C", "-e", "--category",
                           "--in", "--of", "--impl-limit", "--has-method", "--extends", "--body-contains")
  val cleanArgs = argList.filterNot(a => a.startsWith("--") || a == "-w" || a == "-C" || a == "-e" || a == "-c" || a == "--flat" || {
    val prev = argList.indexOf(a) - 1
    prev >= 0 && flagsWithArgs.contains(argList(prev))
  })

  cleanArgs match
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
        |  scalex index                    Rebuild the index               (aka: reindex)
        |  scalex batch                    Run multiple queries at once    (aka: batch mode)
        |  scalex body <symbol>            Extract method/val/class body   (aka: show source)
        |  scalex hierarchy <symbol>       Full inheritance tree           (aka: type hierarchy)
        |  scalex overrides <method>       Find override implementations   (aka: find overrides)
        |  scalex explain <symbol>         Composite one-shot summary      (aka: explain symbol)
        |  scalex deps <symbol>            Show symbol dependencies        (aka: dependency graph)
        |  scalex context <file:line>      Show enclosing scopes at line   (aka: scope chain)
        |  scalex diff <git-ref>           Symbol-level diff vs git ref    (aka: symbol diff)
        |  scalex ast-pattern              Structural AST search           (aka: pattern search)
        |  scalex tests                    List test cases structurally    (aka: find tests)
        |  scalex coverage <symbol>        Is this symbol tested?          (aka: test coverage)
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
        |  --path PREFIX         Restrict results to files under PREFIX (e.g. compiler/src/)
        |  -C N                  Show N context lines around each reference (refs, grep)
        |  -e PATTERN            Grep: additional pattern (combine multiple with |); repeatable
        |  --count               Grep: show match/file count only, no full results
        |  --exact               Search: only exact name matches
        |  --prefix              Search: only exact + prefix matches
        |  --json                Output results as JSON (structured output for programmatic use)
        |  --version             Print version and exit
        |  --in OWNER            Body: restrict to members of the given enclosing type
        |  --of TRAIT            Overrides: restrict to implementations of the given trait
        |  --impl-limit N        Explain: max implementations to show (default: 5)
        |  --up                  Hierarchy: show only parents (default: both)
        |  --down                Hierarchy: show only children (default: both)
        |  --inherited           Members: include inherited members from parent types
        |  --architecture        Overview: show package dependency graph and hub types
        |  --has-method NAME     AST pattern: match types that have a method with NAME
        |  --extends TRAIT       AST pattern: match types that extend TRAIT
        |  --body-contains PAT   AST pattern: match types whose body contains PAT
        |
        |All commands accept an optional [workspace] positional arg or -w flag (default: current directory).
        |First run indexes the project (~3s for 14k files). Subsequent runs use cache (~300ms).
        |""".stripMargin)

    case "batch" :: rest =>
      val workspace = resolveWorkspace(explicitWorkspace.orElse(rest.headOption).getOrElse("."))
      val idx = WorkspaceIndex(workspace, needBlooms = true)
      idx.index()
      val ctx = CommandContext(idx = idx, workspace = workspace, limit = limit, verbose = verbose,
        jsonOutput = jsonOutput, batchMode = true, kindFilter = kindFilter, noTests = noTests,
        pathFilter = pathFilter, contextLines = contextLines, categorize = categorize,
        categoryFilter = categoryFilter, grepPatterns = grepPatterns, countOnly = countOnly,
        searchMode = searchMode, definitionsOnly = definitionsOnly, inOwner = inOwner, ofTrait = ofTrait,
        implLimit = implLimit, goUp = goUp, goDown = goDown, inherited = inherited,
        architecture = architecture, hasMethodFilter = hasMethodFilter, extendsFilter = extendsFilter,
        bodyContainsFilter = bodyContainsFilter)
      val reader = BufferedReader(InputStreamReader(System.in))
      var line = reader.readLine()
      while line != null do
        val parts = line.trim.split("\\s+").toList
        if parts.nonEmpty && parts.head.nonEmpty then
          val batchCmd = parts.head
          val batchRest = parts.tail
          println(s">>> $line")
          runCommand(batchCmd, batchRest, ctx)
          println()
        line = reader.readLine()

    case cmd :: rest =>
      val (workspace, cmdRest) = explicitWorkspace match
        case Some(ws) =>
          (resolveWorkspace(ws), rest)
        case None =>
          cmd match
            case "index" | "packages" | "overview" | "ast-pattern" =>
              (resolveWorkspace(rest.headOption.getOrElse(".")), rest)
            case _ =>
              rest match
                case arg :: Nil => (resolveWorkspace("."), List(arg))
                case ws :: arg :: tail => (resolveWorkspace(ws), arg :: tail)
                case Nil => (resolveWorkspace("."), Nil)

      val bloomCmds = Set("refs", "imports", "coverage")
      val idx = WorkspaceIndex(workspace, needBlooms = bloomCmds.contains(cmd))
      idx.index()
      val ctx = CommandContext(idx = idx, workspace = workspace, limit = limit, verbose = verbose,
        jsonOutput = jsonOutput, kindFilter = kindFilter, noTests = noTests, pathFilter = pathFilter,
        contextLines = contextLines, categorize = categorize, categoryFilter = categoryFilter,
        grepPatterns = grepPatterns, countOnly = countOnly, searchMode = searchMode,
        definitionsOnly = definitionsOnly, inOwner = inOwner, ofTrait = ofTrait, implLimit = implLimit,
        goUp = goUp, goDown = goDown, inherited = inherited, architecture = architecture,
        hasMethodFilter = hasMethodFilter, extendsFilter = extendsFilter,
        bodyContainsFilter = bodyContainsFilter)
      runCommand(cmd, cmdRest, ctx)
