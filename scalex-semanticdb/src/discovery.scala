import java.nio.file.{Files, Path, FileVisitResult}
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.SimpleFileVisitor
import scala.collection.mutable.ListBuffer

// ── SemanticDB file discovery ──────────────────────────────────────────────

object Discovery:

  /** Known build output directories to scan for META-INF/semanticdb/ */
  private val buildOutputDirs = List("out", "target", ".bloop")

  /** Directories to always skip */
  private val skipDirs = Set(".git", "node_modules", ".idea", ".metals", ".bsp", "src", "test", "tests")

  /** Find all .semanticdb files under workspace build output directories. */
  def discoverSemanticdbFiles(workspace: Path): List[Path] =
    val results = ListBuffer.empty[Path]
    val roots = buildOutputDirs
      .map(workspace.resolve)
      .filter(Files.isDirectory(_))

    if roots.isEmpty then return Nil

    for root <- roots do
      walkForSemanticdb(root, results)

    results.sortBy(_.toString).toList

  /** Find .semanticdb files under an explicit path (e.g. from --semanticdb-path). */
  def discoverFromExplicitPath(path: Path): List[Path] =
    val abs = path.toAbsolutePath.normalize
    if !Files.exists(abs) then return Nil

    if Files.isRegularFile(abs) && abs.toString.endsWith(".semanticdb") then
      return List(abs)

    val results = ListBuffer.empty[Path]
    walkForSemanticdb(abs, results)
    results.sortBy(_.toString).toList

  /** Walk a directory tree collecting .semanticdb files.
    * Skips known non-build directories and caps depth at 20. */
  private def walkForSemanticdb(root: Path, results: ListBuffer[Path]): Unit =
    if !Files.isDirectory(root) then return

    val maxDepth = 20
    Files.walkFileTree(root, java.util.EnumSet.noneOf(classOf[java.nio.file.FileVisitOption]), maxDepth,
      new SimpleFileVisitor[Path]:
        override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
          val name = dir.getFileName.toString
          if skipDirs.contains(name) then FileVisitResult.SKIP_SUBTREE
          else FileVisitResult.CONTINUE

        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
          if file.toString.endsWith(".semanticdb") &&
             file.toString.contains("META-INF" + java.io.File.separator + "semanticdb") then
            results += file
          FileVisitResult.CONTINUE

        override def visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult =
          FileVisitResult.CONTINUE
    )

  /** Collect parent directories that contain META-INF/semanticdb — these are the
    * "classes" roots needed by the Locator. Returns deduplicated roots. */
  def semanticdbRoots(semanticdbFiles: List[Path]): List[Path] =
    semanticdbFiles
      .flatMap { f =>
        // Walk up to find the directory that contains META-INF/semanticdb/...
        val str = f.toString
        val idx = str.indexOf("META-INF" + java.io.File.separator + "semanticdb")
        if idx > 0 then Some(Path.of(str.substring(0, idx)))
        else None
      }
      .distinct
      .sorted
