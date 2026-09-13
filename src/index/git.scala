package scalex.index

import scalex.*

import scala.util.Using
import java.nio.file.Path
import java.io.{BufferedReader, ByteArrayOutputStream, IOException, InputStreamReader}
import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*

// ── Git ─────────────────────────────────────────────────────────────────────

final class GitFailure(message: String) extends RuntimeException(message)

/** Stream stdout while draining stderr separately, preserving the process status. */
def runGitLines[A](workspace: Path, args: String*)(consume: Iterator[String] => A): A = {
  try {
    val pb = ProcessBuilder(("git" +: args)*)
    pb.directory(workspace.toFile)
    val proc = pb.start()
    val errors = ByteArrayOutputStream()
    val drain = Thread.startVirtualThread(() => {
      Using.resource(proc.getErrorStream) { stream => stream.transferTo(errors); () }
    })
    try {
      val result = Using.resource(BufferedReader(InputStreamReader(proc.getInputStream))) { reader =>
        consume(reader.lines().iterator().asScala)
      }
      val status = proc.waitFor()
      drain.join()
      if (status != 0) {
        throw GitFailure(s"git ${args.headOption.getOrElse("")} failed (exit $status): ${errors.toString(UTF_8).trim}")
      }
      result
    } finally {
      proc.destroy()
    }
  } catch {
    case e: IOException => throw GitFailure(s"Cannot run git: ${e.getMessage}")
  }
}

def gitLsFiles(workspace: Path): List[GitFile] = {
  runGitLines(workspace, "ls-files", "--stage") { lines =>
    lines.flatMap { line =>
      val tabIdx = line.indexOf('\t')
      if (tabIdx < 0) None
      else {
        val parts = line.substring(0, tabIdx).split("\\s+")
        val path = line.substring(tabIdx + 1)
        if (parts.length >= 2 && (path.endsWith(".scala") || path.endsWith(".java")))
          Some(GitFile(workspace.resolve(path), parts(1)))
        else None
      }
    }.toList
  }
}

def runGitDiff(workspace: Path, ref: String): List[String] =
  runGitLines(workspace, "diff", "--name-only", ref) { lines =>
    lines.filter(f => f.endsWith(".scala") || f.endsWith(".java")).toList
  }

def gitShowFile(workspace: Path, ref: String, relPath: String): Option[String] = {
  val paths = runGitLines(workspace, "ls-tree", "--name-only", ref, "--", relPath)(_.toList)
  if (paths.nonEmpty) {
    Some(runGitLines(workspace, "show", s"$ref:$relPath")(_.mkString("\n")))
  } else { None }
}
