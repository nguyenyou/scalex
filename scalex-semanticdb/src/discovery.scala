import java.nio.file.{Files, Path, FileVisitResult}
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.SimpleFileVisitor
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

// ── SemanticDB file discovery (Mill only) ─────────────────────────────────

object Discovery:

  /** Find all .semanticdb files under Mill's out/ directory.
    * Scans for semanticDbDataDetailed.dest dirs, walks data/META-INF/semanticdb/
    * in each (parallel across modules). Falls back to direct out/META-INF/semanticdb/
    * for scalac -semanticdb-target usage (e.g. tests). */
  def discoverSemanticdbFiles(workspace: Path): (files: List[Path], maxMtimeMs: Long, semanticdbDirs: List[Path]) =
    val outDir = workspace.resolve("out")
    if !Files.isDirectory(outDir) then
      return (files = Nil, maxMtimeMs = 0L, semanticdbDirs = Nil)

    // Mill: find semanticDbDataDetailed.dest dirs, walk data/META-INF/semanticdb/ in parallel
    val destDirs = findMillSemanticdbDests(outDir)
    if destDirs.nonEmpty then
      val (files, maxMtime, walkedDirs) = walkMillDestDirsParallel(destDirs)
      if files.nonEmpty then
        return (files = files, maxMtimeMs = maxMtime, semanticdbDirs = walkedDirs)

    // Fallback: direct META-INF/semanticdb/ under out/ (e.g. scalac -semanticdb-target)
    val directDir = outDir.resolve("META-INF").resolve("semanticdb")
    if Files.isDirectory(directDir) then
      val (files, maxMtime) = walkSemanticdbDir(directDir)
      if files.nonEmpty then
        return (files = files, maxMtimeMs = maxMtime, semanticdbDirs = List(directDir))

    (files = Nil, maxMtimeMs = 0L, semanticdbDirs = Nil)

  /** Walk Mill dest directories in parallel, collecting .semanticdb files from data/META-INF/semanticdb/.
    * Each module's directory is walked on a separate thread via parallelStream(). */
  private def walkMillDestDirsParallel(destDirs: List[Path]): (files: List[Path], maxMtimeMs: Long, semanticdbDirs: List[Path]) =
    val fileQueue = java.util.concurrent.ConcurrentLinkedQueue[Path]()
    val dirQueue = java.util.concurrent.ConcurrentLinkedQueue[Path]()
    val maxMtimeAtomic = java.util.concurrent.atomic.AtomicLong(0L)

    destDirs.asJava.parallelStream().forEach { dest =>
      val dataDir = dest.resolve("data").resolve("META-INF").resolve("semanticdb")
      if Files.isDirectory(dataDir) then
        dirQueue.add(dataDir)
        val (_, mt) = walkSemanticdbDir(dataDir, Some(fileQueue))
        maxMtimeAtomic.accumulateAndGet(mt, Math.max)
    }

    (files = fileQueue.asScala.toList, maxMtimeMs = maxMtimeAtomic.get(), semanticdbDirs = dirQueue.asScala.toList)

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
            FileVisitResult.SKIP_SUBTREE
          else FileVisitResult.CONTINUE
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
          FileVisitResult.CONTINUE
        override def visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult =
          FileVisitResult.CONTINUE
    )
    dests.toList

  /** Walk a known META-INF/semanticdb/ directory and collect all .semanticdb files.
    * Returns (files, maxMtime). Optionally appends files to an external concurrent queue
    * for parallel aggregation. */
  private def walkSemanticdbDir(sdbDir: Path, externalQueue: Option[java.util.concurrent.ConcurrentLinkedQueue[Path]] = None): (files: List[Path], maxMtime: Long) =
    val localResults = ListBuffer.empty[Path]
    var maxMtime = 0L
    Files.walkFileTree(sdbDir, new SimpleFileVisitor[Path]:
      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
        if file.toString.endsWith(".semanticdb") then
          localResults += file
          externalQueue.foreach(_.add(file))
          maxMtime = math.max(maxMtime, attrs.lastModifiedTime().toMillis)
        FileVisitResult.CONTINUE
      override def visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult =
        FileVisitResult.CONTINUE
    )
    (files = localResults.toList, maxMtime = maxMtime)
