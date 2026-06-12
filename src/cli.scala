import java.nio.file.{Files, Path}
import clibase.{BatchLoop, Flags, Timings}

// ── CLI entry point ─────────────────────────────────────────────────────────

def resolveWorkspace(path: String): Path =
  val p = Path.of(path).toAbsolutePath.normalize
  if Files.isDirectory(p) then p else p.getParent

@main def main(args: String*): Unit =
  val f = parseFlags(args.toList)
  if f(VersionFlag) then println(ScalexVersion)
  else runCli(f, args.toList)

private val helpHeader = """Scalex — Scala code intelligence for coding agents
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
  |Options:""".stripMargin

private val helpFooter = """All commands accept an optional [workspace] positional arg or -w flag (default: current directory).
  |First run indexes the project (~3s for 14k files). Subsequent runs use cache (~300ms).
  |Java files (.java) are indexed via JavaParser AST (class/interface/enum/record/method/field).""".stripMargin

private def runCli(f: Flags, args: List[String]): Unit =
  Timings.enabled = f(TimingsFlag)

  f.positional match
    case Nil | List("help") =>
      println(helpHeader + "\n" + scalexFlags.optionsHelp + "\n\n" + helpFooter + "\n")

    case "batch" :: rest =>
      val workspace = resolveWorkspace(f(WorkspaceFlag).orElse(rest.headOption).getOrElse("."))
      val idx = WorkspaceIndex(workspace, needBlooms = true)
      idx.index()
      Timings.report()
      BatchLoop.run { parts =>
        // Parse per-line flags so each batch line can override --path, --no-tests, etc.
        val lineFlags = parseFlags(parts.tail)
        // Inherit global --max-output / --in-package when per-line doesn't override
        val merged = lineFlags
          .updated(MaxOutputFlag, if lineFlags(MaxOutputFlag) > 0 then lineFlags(MaxOutputFlag) else f(MaxOutputFlag))
          .updated(InPackageFlag, lineFlags(InPackageFlag).orElse(f(InPackageFlag)))
        val lineCtx = flagsToContext(merged, idx, workspace, batchMode = true)
        Timings.reset()
        runCommand(parts.head, lineFlags.positional, lineCtx)
        Timings.report()
      }

    case "graph" :: _ =>
      // graph command doesn't need workspace index — extract raw args after "graph", strip global flags
      val afterGraph = args.dropWhile(_ != "graph").drop(1)
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
      val workspace = resolveWorkspace(f(WorkspaceFlag).getOrElse("."))
      val dummyIdx = WorkspaceIndex(workspace, needBlooms = false)
      val ctx = flagsToContext(f, dummyIdx, workspace)
      val result = cmdGraph(graphArgs, ctx)
      // graph bypasses runCommand (positionals strip --render/--parse), use renderWithBudget directly
      renderWithBudget(result, ctx)
      Timings.report()

    case cmd :: rest =>
      val (workspace, cmdRest) = f(WorkspaceFlag) match
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
      val effectiveNoTests = if cmd == "overview" && !f(IncludeTestsFlag) then true else f(NoTestsFlag)

      val bloomCmds = Set("refs", "imports", "coverage")
      val idx = WorkspaceIndex(workspace, needBlooms = bloomCmds.contains(cmd))
      idx.index()
      val ctx = flagsToContext(f, idx, workspace, effectiveNoTests = Some(effectiveNoTests))
      runCommand(cmd, cmdRest, ctx)
      Timings.report()
