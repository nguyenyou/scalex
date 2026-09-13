package scalex.index

import java.util.concurrent.atomic.AtomicInteger

import scalex.*

import java.nio.file.{Files, Path}
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

// ── Deadline-bounded file scanning ──────────────────────────────────────────

/** Shared chassis for the deadline-bounded parallel file scans behind `grepFiles`/`findReferences`/`findImports`:
  * result queue, timeout flag, unreadable-file counter, and the per-file/per-line deadline checks.
  */
private[scalex] final class DeadlineScan(timeoutMs: Long) {
  private val deadline: Long = System.nanoTime() + timeoutMs * 1_000_000
  @volatile var timedOut: Boolean = false
  private val queue = ConcurrentLinkedQueue[Reference]()
  private val unreadable = AtomicInteger(0)

  def inTime: Boolean = System.nanoTime() < deadline

  def emit(r: Reference): Unit = queue.add(r)

  /** A file's lines, counting the file as unreadable (empty result) on IO errors. */
  def readLines(path: Path): collection.Seq[String] =
    try Files.readAllLines(path).asScala
    catch {
      case _: IOException =>
        unreadable.incrementAndGet()
        Seq.empty
    }

  /** Visit each candidate in parallel while the deadline holds, handing its lines to `onFile`; candidates skipped after
    * the deadline mark `timedOut`.
    */
  def scanParallel[A](
      candidates: List[A]
  )(pathOf: A => Path)(onFile: (item: A, path: Path, lines: collection.Seq[String]) => Unit): Unit =
    candidates.asJava.parallelStream().forEach { item =>
      if (inTime) {
        val path = pathOf(item)
        onFile(item, path, readLines(path))
      } else timedOut = true
    }

  /** Per-line loop with deadline checks; lines skipped after the deadline mark `timedOut`. */
  def forEachLine(lines: collection.Seq[String])(f: (line: String, lineNum: Int) => Unit): Unit =
    lines.zipWithIndex.foreach { case (line, idx) =>
      if (inTime) f(line, idx + 1)
      else timedOut = true
    }

  def reportUnreadable(label: String): Unit =
    if (unreadable.get() > 0) System.err.println(s"scalex: ${unreadable.get()} file(s) unreadable during $label")

  def results: List[Reference] = queue.asScala.toList
}
