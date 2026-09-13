package scalex.index

import scalex.*

import java.nio.file.Path

// ── Filtering helpers ────────────────────────────────────────────────────────

def isTestFile(path: Path, workspace: Path): Boolean = {
  val rel = workspace.relativize(path).toString
  rel.startsWith("test/") || rel.startsWith("tests/") || rel.startsWith("testing/") ||
  rel.contains("/test/") || rel.contains("/tests/") || rel.contains("/testing/") ||
  rel.startsWith("bench-") || rel.contains("/bench-") ||
  rel.endsWith("Test.scala") || rel.endsWith("Spec.scala") || rel.endsWith("Suite.scala") ||
  rel.endsWith(".test.scala") ||
  rel.endsWith("Test.java") || rel.endsWith("Spec.java") || rel.endsWith("Suite.java")
}

def matchesPath(file: Path, prefix: String, workspace: Path): Boolean = {
  val rel = workspace.relativize(file).toString
  rel.startsWith(prefix)
}

/** Predicate combining the --no-tests / --path / --exclude-path file filters, shared by symbol/ref filtering and the
  * file-scanning commands.
  */
def pathPredicate(
    noTests: Boolean,
    pathFilter: Option[String],
    excludePath: Option[String],
    workspace: Path
): Path => Boolean =
  path =>
    (!noTests || !isTestFile(path, workspace)) &&
      pathFilter.forall(p => matchesPath(path, p, workspace)) &&
      excludePath.forall(p => !matchesPath(path, p, workspace))
