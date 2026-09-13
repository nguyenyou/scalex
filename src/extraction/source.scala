package scalex.extraction

import java.io.IOException

import scalex.*

import scala.meta.*
import scala.meta.parsers.Parsed
import java.nio.file.{Files, Path}
import com.google.common.hash.{BloomFilter, Funnels}
import scala.jdk.CollectionConverters.*

// ── Source reading & parsing helpers ─────────────────────────────────────────

/** Read a source file as UTF-8, or None if unreadable. */
def readSource(path: Path): Option[String] =
  try Some(Files.readString(path))
  catch { case _: IOException => None }

/** Read a source file as an array of lines, or None if unreadable. */
def readSourceLines(path: Path): Option[Array[String]] =
  try Some(Files.readAllLines(path).asScala.toArray)
  catch { case _: IOException => None }

private[scalex] def tryParse(input: Input.VirtualFile, dialect: Dialect): Option[Source] = {
  try {
    given Dialect = dialect
    input.parse[Source] match {
      case Parsed.Success(tree) => Some(tree)
      case _: Parsed.Error      => None
    }
  } catch { case _: Exception => None }
}

/** Parse Scala source, trying the Scala 3 dialect first, falling back to Scala 2.13. */
def parseSource(source: String, virtualPath: String): Option[Source] = {
  val input = Input.VirtualFile(virtualPath, source)
  tryParse(input, dialects.Scala3).orElse(tryParse(input, dialects.Scala213))
}

def parseFile(path: Path): Option[Source] =
  readSource(path).flatMap(source => parseSource(source, path.toString))

// ── File type routing ────────────────────────────────────────────────────────

def isJavaFile(path: Path): Boolean = path.toString.endsWith(".java")

// ── Symbol extraction + bloom filter ────────────────────────────────────────

def buildBloomFilterFromSource(source: String): BloomFilter[CharSequence] = {
  val expected = math.max(500, source.length / 15)
  val bloom = BloomFilter.create(Funnels.unencodedCharsFunnel(), expected, 0.01)
  var i = 0
  val len = source.length
  while (i < len)
    if (source(i).isLetter || source(i) == '_') {
      val start = i
      while (i < len && (source(i).isLetterOrDigit || source(i) == '_')) i += 1
      val word = source.substring(start, i)
      if (word.length >= 2) bloom.put(word)
    } else
      i += 1
  bloom
}

// ── Doc extraction (Scaladoc / Javadoc) ─────────────────────────────────────

def extractDoc(file: Path, targetLine: Int): Option[String] = {
  val lines = if (isJavaFile(file)) None else readSourceLines(file)
  lines.flatMap { lines =>
    // targetLine is 1-indexed, array is 0-indexed
    var i = targetLine - 2 // line before the symbol
    // skip blank lines between doc and symbol
    while (i >= 0 && lines(i).trim.isEmpty) i -= 1
    if (i < 0 || !lines(i).trim.endsWith("*/")) None
    else {
      // The line ends a scaladoc — walk up to the opening /** (which may be the same line)
      val endLine = i
      while (i >= 0 && !lines(i).trim.startsWith("/**")) i -= 1
      if (i >= 0) Some((i to endLine).map(lines(_)).mkString("\n"))
      else None
    }
  }
}
