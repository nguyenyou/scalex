import java.nio.file.{Files, Path, FileVisitResult}
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.SimpleFileVisitor
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

// ── SemanticDB file discovery ──────────────────────────────────────────────

object Discovery:

  /** Directories to always skip */
  private val skipDirs = Set(".git", "node_modules", ".idea", ".metals", ".bsp")

  /** Find all .semanticdb files under workspace build output directories.
    * Uses targeted discovery for Mill (semanticDbDataDetailed.dest) and sbt (target/). */
  def discoverSemanticdbFiles(workspace: Path): List[Path] =
    val results = ListBuffer.empty[Path]

    // Mill: find semanticDbDataDetailed.dest dirs, then walk only data/META-INF/semanticdb/
    val outDir = workspace.resolve("out")
    if Files.isDirectory(outDir) then
      val destDirs = findMillSemanticdbDests(outDir)
      if destDirs.nonEmpty then
        for dest <- destDirs do
          // Prefer data/ over classes/ (both contain the same files)
          val dataDir = dest.resolve("data").resolve("META-INF").resolve("semanticdb")
          if Files.isDirectory(dataDir) then
            walkSemanticdbDir(dataDir, results)
          else
            // Fallback: check classes/
            val classesDir = dest.resolve("classes").resolve("META-INF").resolve("semanticdb")
            if Files.isDirectory(classesDir) then
              walkSemanticdbDir(classesDir, results)
        if results.nonEmpty then
          return deduplicateByRelativePath(results.toList)

      // Direct META-INF/semanticdb/ under out/ (e.g. scalac -semanticdb-target)
      val directDir = outDir.resolve("META-INF").resolve("semanticdb")
      if Files.isDirectory(directDir) then
        walkSemanticdbDir(directDir, results)
        if results.nonEmpty then
          return deduplicateByRelativePath(results.toList)

      // No Mill structure found — fall through to generic walk of out/
      walkForSemanticdb(outDir, results)
      if results.nonEmpty then
        return deduplicateByRelativePath(results.toList)

    // sbt / Bloop: walk target/ and .bloop/ looking for META-INF/semanticdb
    for dirName <- List("target", ".bloop") do
      val dir = workspace.resolve(dirName)
      if Files.isDirectory(dir) then
        walkForSemanticdb(dir, results)

    deduplicateByRelativePath(results.toList)

  /** Find Mill's semanticDbDataDetailed.dest directories under out/.
    * Walks only directory entries looking for the target name — skips file content entirely. */
  private def findMillSemanticdbDests(outDir: Path): List[Path] =
    val dests = ListBuffer.empty[Path]
    val targetName = "semanticDbDataDetailed.dest"
    Files.walkFileTree(outDir, java.util.EnumSet.noneOf(classOf[java.nio.file.FileVisitOption]), 8,
      new SimpleFileVisitor[Path]:
        override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
          if dir.getFileName.toString == targetName then
            dests += dir
            FileVisitResult.SKIP_SUBTREE // don't descend into dest dirs
          else FileVisitResult.CONTINUE
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
          FileVisitResult.CONTINUE
        override def visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult =
          FileVisitResult.CONTINUE
    )
    dests.toList

  /** Find .semanticdb files under an explicit path (e.g. from --semanticdb-path). */
  def discoverFromExplicitPath(path: Path): List[Path] =
    val abs = path.toAbsolutePath.normalize
    if !Files.exists(abs) then return Nil

    if Files.isRegularFile(abs) && abs.toString.endsWith(".semanticdb") then
      return List(abs)

    val results = ListBuffer.empty[Path]
    walkForSemanticdb(abs, results)
    deduplicateByRelativePath(results.toList)

  /** Walk a known META-INF/semanticdb/ directory and collect all .semanticdb files. */
  private def walkSemanticdbDir(sdbDir: Path, results: ListBuffer[Path]): Unit =
    Files.walkFileTree(sdbDir, new SimpleFileVisitor[Path]:
      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
        if file.toString.endsWith(".semanticdb") then
          results += file
        FileVisitResult.CONTINUE
      override def visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult =
        FileVisitResult.CONTINUE
    )

  /** Walk a directory tree collecting .semanticdb files (sbt/Bloop fallback).
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

  /** Deduplicate .semanticdb files by their relative path after META-INF/semanticdb/.
    * Mill produces duplicates in classes/ and data/ directories — keep only one. */
  private def deduplicateByRelativePath(files: List[Path]): List[Path] =
    val sep = java.io.File.separator
    val marker = "META-INF" + sep + "semanticdb" + sep
    val seen = scala.collection.mutable.HashSet.empty[String]
    files.filter { f =>
      val str = f.toString
      val idx = str.indexOf(marker)
      val key = if idx >= 0 then str.substring(idx + marker.length) else str
      seen.add(key) // true if new, false if duplicate
    }.sortBy(_.toString)
