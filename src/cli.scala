package scalex

import scalex.index.*
import scalex.output.*

import java.io.IOException
import java.nio.file.{Files, Path}
import scala.collection.mutable.ListBuffer
import clibase.{BatchLoop, Flag, Flags, Timings}

/** Resolve the optional workspace argument relative to the current directory. */
def resolveWorkspace(path: String): Path = {
  val p = Path.of(path).toAbsolutePath.normalize
  if (Files.isDirectory(p)) { p }
  else { p.getParent }
}

@main def main(args: String*): Unit = {
  val status = runCli(args.toList)
  if (status != 0) { System.exit(status) }
}

/** Return the process status; only main terminates the JVM. */
def runCli(args: List[String]): Int = {
  val f = parseFlags(args)
  Timings.enabled = f(TimingsFlag)
  try {
    if (f(VersionFlag)) { println(ScalexVersion); 0 }
    else {
      f.positional match {
        case Nil | List("help") =>
          println("Scalex — Scala code intelligence for coding agents\n\nCommands:")
          commandSpecs.foreach { spec =>
            val invocation = s"scalex ${spec.name} ${spec.arguments}".trim
            println(f"  $invocation%-44s ${spec.description}")
          }
          println("\nOptions:\n" + scalexFlags.optionsHelp)
          println("\nAll commands accept an optional workspace or -w PATH (default: current directory).")
          println("Scala and Java sources are indexed without compilation; unchanged files reuse the cache.")
          0
        case "batch" :: rest => runBatch(f, rest)
        case name :: rest    =>
          commandsByName.get(name) match {
            case None       => renderError(s"Unknown command: $name", f(JsonFlag)); 2
            case Some(spec) =>
              val (workspace, commandArgs) = resolveCommandArgs(spec, f, rest)
              val idx = if (spec.needsIndex) {
                WorkspaceIndex.load(workspace, spec.needsBlooms)
              } else { WorkspaceIndex.empty(workspace) }
              val noTests = (spec.defaultNoTests && !f(IncludeTestsFlag)) || f(NoTestsFlag)
              val ctx = flagsToContext(f, idx, workspace, effectiveNoTests = Some(noTests))
              val actualArgs = if (name == "graph") { graphArgs(args.dropWhile(_ != "graph").drop(1)) }
              else { commandArgs }
              val status = runCommand(name, actualArgs, ctx)
              Timings.report()
              status
          }
      }
    }
  } catch {
    case e: GitFailure  => renderError(e.getMessage, f(JsonFlag)); 1
    case e: IOException => renderError(e.getMessage, f(JsonFlag)); 1
  }
}

private[scalex] def resolveCommandArgs(
    spec: CommandSpec,
    f: Flags,
    rest: List[String]
): (workspace: Path, args: List[String]) = {
  f(WorkspaceFlag) match {
    case Some(ws)                   => (workspace = resolveWorkspace(ws), args = rest)
    case None if spec.workspaceOnly => (workspace = resolveWorkspace(rest.headOption.getOrElse(".")), args = rest)
    case None if !spec.needsIndex   => (workspace = resolveWorkspace("."), args = rest)
    case None                       =>
      rest match {
        case ws :: arg :: tail => (workspace = resolveWorkspace(ws), args = arg :: tail)
        case _                 => (workspace = resolveWorkspace("."), args = rest)
      }
  }
}

private[scalex] def runBatch(f: Flags, rest: List[String]): Int = {
  val workspace = resolveWorkspace(f(WorkspaceFlag).orElse(rest.headOption).getOrElse("."))
  val idx = WorkspaceIndex.load(workspace, needBlooms = true)
  Timings.report()
  var status = 0
  BatchLoop.run { parts =>
    val lineFlags = parseFlags(parts.tail)
    // Per-line settings override the inherited output budget and package filter.
    val merged = lineFlags
      .updated(
        MaxOutputFlag,
        if (lineFlags(MaxOutputFlag) > 0) { lineFlags(MaxOutputFlag) }
        else { f(MaxOutputFlag) }
      )
      .updated(InPackageFlag, lineFlags(InPackageFlag).orElse(f(InPackageFlag)))
    val ctx = flagsToContext(merged, idx, workspace, batchMode = true)
    Timings.reset()
    val args = if (parts.head == "graph") { graphArgs(parts.tail) }
    else { lineFlags.positional }
    status = math.max(status, runCommand(parts.head, args, ctx))
    Timings.report()
  }
  status
}

/** Strip registry flags while retaining graph-local flags and their operands. */
private[scalex] def graphArgs(args: List[String]): List[String] = {
  val remaining = ListBuffer.empty[String]
  val argv = args.toVector
  var i = 0
  while (i < argv.size) {
    scalexFlags.byName.get(argv(i)) match {
      case Some(_: Flag.BooleanFlag)     => ()
      case Some(_: Flag.OptionalIntFlag) =>
        if (argv.lift(i + 1).flatMap(_.toIntOption).isDefined) { i += 1 }
      case Some(_) => i += 1
      case None    => remaining += argv(i)
    }
    i += 1
  }
  remaining.toList
}
