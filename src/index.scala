import scala.collection.mutable
import scala.util.Using
import java.nio.file.{Files, Path}
import java.io.{BufferedReader, BufferedInputStream, BufferedOutputStream, DataInputStream, DataOutputStream, InputStreamReader}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*
import com.google.common.hash.{BloomFilter, Funnels}
import clibase.Timings

// ── Git ─────────────────────────────────────────────────────────────────────

/** Run a git command in `workspace`, streaming its stdout lines through `consume`
  * (single pass, no intermediate list — `git ls-files` output can be large).
  * Returns None if the process cannot be started (e.g. git not installed). */
def runGitLines[A](workspace: Path, args: String*)(consume: Iterator[String] => A): Option[A] = {
  try {
    val pb = ProcessBuilder(("git" +: args)*)
    pb.directory(workspace.toFile)
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val result = Using.resource(BufferedReader(InputStreamReader(proc.getInputStream))) { reader =>
      consume(reader.lines().iterator().asScala)
    }
    proc.waitFor()
    Some(result)
  } catch {
    case e: java.io.IOException =>
      System.err.println(s"scalex: failed to run git ${args.headOption.getOrElse("")} (${e.getMessage})")
      None
  }
}

/** Runs on every invocation over potentially huge output — kept as a direct
  * implementation rather than going through runGitLines (measurably faster). */
def gitLsFiles(workspace: Path): List[GitFile] = {
  try {
    val pb = ProcessBuilder("git", "ls-files", "--stage")
    pb.directory(workspace.toFile)
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val files = Using.resource(BufferedReader(InputStreamReader(proc.getInputStream))) { reader =>
      reader.lines().iterator().asScala.flatMap { line =>
        val tabIdx = line.indexOf('\t')
        if tabIdx < 0 then None
        else {
          val parts = line.substring(0, tabIdx).split("\\s+")
          val path = line.substring(tabIdx + 1)
          if parts.length >= 2 && (path.endsWith(".scala") || path.endsWith(".java")) then
            Some(GitFile(workspace.resolve(path), parts(1)))
          else None
        }
      }.toList
    }
    proc.waitFor()
    files
  } catch {
    case e: java.io.IOException =>
      System.err.println(s"scalex: failed to run git ls-files (${e.getMessage})")
      Nil
  }
}

// ── Binary persistence ──────────────────────────────────────────────────────

object IndexPersistence:
  private val MAGIC = 0x53584458
  private val VERSION: Byte = 8

  def indexPath(workspace: Path): Path = workspace.resolve(".scalex").resolve("index.bin")

  def save(workspace: Path, files: List[IndexedFile]): Unit =
    val dir = workspace.resolve(".scalex")
    if !Files.exists(dir) then Files.createDirectories(dir)

    val stringTable = mutable.LinkedHashMap.empty[String, Int]
    def intern(s: String): Int =
      stringTable.getOrElseUpdate(s, stringTable.size)

    files.foreach { f =>
      intern(f.relativePath)
      intern(f.oid)
      f.symbols.foreach { s =>
        intern(s.name)
        intern(s.packageName)
        intern(s.signature)
        s.parents.foreach(intern)
        s.typeParamParents.foreach(intern)
        s.annotations.foreach(intern)
      }
      f.imports.foreach(intern)
      f.aliases.foreach { (k, v) => intern(k); intern(v) }
    }

    val out = DataOutputStream(BufferedOutputStream(Files.newOutputStream(indexPath(workspace)), 1 << 16))
    try
      out.writeInt(MAGIC)
      out.writeByte(VERSION)

      val strings = stringTable.keys.toArray
      out.writeInt(strings.length)
      strings.foreach(out.writeUTF)

      out.writeInt(files.size)
      files.foreach { f =>
        out.writeInt(intern(f.relativePath))
        out.writeInt(intern(f.oid))

        out.writeShort(f.symbols.size)
        f.symbols.foreach { s =>
          out.writeInt(intern(s.name))
          out.writeByte(s.kind.id)
          out.writeInt(s.line)
          out.writeInt(intern(s.packageName))
          out.writeInt(intern(s.signature))
          out.writeShort(s.parents.size)
          s.parents.foreach(p => out.writeInt(intern(p)))
          out.writeShort(s.typeParamParents.size)
          s.typeParamParents.foreach(p => out.writeInt(intern(p)))
          out.writeShort(s.annotations.size)
          s.annotations.foreach(a => out.writeInt(intern(a)))
        }

        // Imports
        out.writeShort(f.imports.size)
        f.imports.foreach(i => out.writeInt(intern(i)))

        // Aliases
        out.writeShort(f.aliases.size)
        f.aliases.foreach { (k, v) =>
          out.writeInt(intern(k))
          out.writeInt(intern(v))
        }

        // Bloom filter
        f.identifierBloom match
          case Some(bloom) =>
            val bloomBytes = java.io.ByteArrayOutputStream()
            bloom.writeTo(bloomBytes)
            val ba = bloomBytes.toByteArray
            out.writeInt(ba.length)
            out.write(ba)
          case None =>
            out.writeInt(0)

        // Parse failed flag
        out.writeBoolean(f.parseFailed)
      }
    finally out.close()

  def load(workspace: Path, loadBlooms: Boolean = true): Option[Map[String, IndexedFile]] =
    val p = indexPath(workspace)
    if !Files.exists(p) then None
    else try
      Using.resource(DataInputStream(BufferedInputStream(Files.newInputStream(p), 1 << 16))) { in =>
        if in.readInt() != MAGIC then None
        else if in.readByte() != VERSION then None
        else Some(loadBody(workspace, in, loadBlooms))
      }
    catch
      case e: Exception =>
        System.err.println(s"scalex: index load failed (${e.getClass.getSimpleName}: ${e.getMessage}) — rebuilding")
        None

  /** Read the index body after the magic/version header has been validated. */
  private def loadBody(workspace: Path, in: DataInputStream, loadBlooms: Boolean): Map[String, IndexedFile] =
        val strCount = in.readInt()
        val strings = Array.fill(strCount)(in.readUTF())

        val fileCount = in.readInt()
        val result = mutable.HashMap.empty[String, IndexedFile]

        var fi = 0
        while fi < fileCount do
          val relPath = strings(in.readInt())
          val oid = strings(in.readInt())

          val symCount = in.readShort()
          val syms = List.newBuilder[SymbolInfo]
          var si = 0
          while si < symCount do
            val name = strings(in.readInt())
            val kind = SymbolKind.fromId(in.readByte())
            val line = in.readInt()
            val pkg = strings(in.readInt())
            val sig = strings(in.readInt())
            val parentCount = in.readShort()
            val parents = (0 until parentCount).map(_ => strings(in.readInt())).toList
            val tpParentCount = in.readShort()
            val tpParents = (0 until tpParentCount).map(_ => strings(in.readInt())).toList
            val annotCount = in.readShort()
            val annots = (0 until annotCount).map(_ => strings(in.readInt())).toList
            syms += SymbolInfo(name, kind, workspace.resolve(relPath), line, pkg, parents, tpParents, sig, annots)
            si += 1

          // Imports
          val importCount = in.readShort()
          val imports = (0 until importCount).map(_ => strings(in.readInt())).toList

          // Aliases
          val aliasCount = in.readShort()
          val aliases = (0 until aliasCount).map { _ =>
            val k = strings(in.readInt())
            val v = strings(in.readInt())
            k -> v
          }.toMap

          // Bloom filter
          val bloomLen = in.readInt()
          val bloom: Option[BloomFilter[CharSequence]] = if bloomLen == 0 then None
          else if loadBlooms then
            val bloomBytes = new Array[Byte](bloomLen)
            in.readFully(bloomBytes)
            Some(BloomFilter.readFrom(
              java.io.ByteArrayInputStream(bloomBytes),
              Funnels.unencodedCharsFunnel()
            ))
          else
            in.skipBytes(bloomLen)
            None

          // Parse failed flag
          val parseFailed = in.readBoolean()

          result(relPath) = IndexedFile(relPath, oid, syms.result(), bloom, imports, aliases, parseFailed)
          fi += 1

        result.toMap

// ── Deadline-bounded file scanning ──────────────────────────────────────────

/** Shared chassis for the deadline-bounded parallel file scans behind
  * `grepFiles`/`findReferences`/`findImports`: result queue, timeout flag,
  * unreadable-file counter, and the per-file/per-line deadline checks. */
private final class DeadlineScan(timeoutMs: Long) {
  private val deadline: Long = System.nanoTime() + timeoutMs * 1_000_000
  @volatile var timedOut: Boolean = false
  private val queue = ConcurrentLinkedQueue[Reference]()
  private val unreadable = java.util.concurrent.atomic.AtomicInteger(0)

  def inTime: Boolean = System.nanoTime() < deadline

  def emit(r: Reference): Unit = queue.add(r)

  /** A file's lines, counting the file as unreadable (empty result) on IO errors. */
  def readLines(path: Path): collection.Seq[String] =
    try Files.readAllLines(path).asScala catch {
      case _: java.io.IOException =>
        unreadable.incrementAndGet()
        Seq.empty
    }

  /** Visit each candidate in parallel while the deadline holds, handing its
    * lines to `onFile`; candidates skipped after the deadline mark `timedOut`. */
  def scanParallel[A](candidates: List[A])(pathOf: A => Path)(onFile: (item: A, path: Path, lines: collection.Seq[String]) => Unit): Unit =
    candidates.asJava.parallelStream().forEach { item =>
      if inTime then {
        val path = pathOf(item)
        onFile(item, path, readLines(path))
      } else timedOut = true
    }

  /** Per-line loop with deadline checks; lines skipped after the deadline mark `timedOut`. */
  def forEachLine(lines: collection.Seq[String])(f: (line: String, lineNum: Int) => Unit): Unit =
    lines.zipWithIndex.foreach { case (line, idx) =>
      if inTime then f(line, idx + 1)
      else timedOut = true
    }

  def reportUnreadable(label: String): Unit =
    if unreadable.get() > 0 then System.err.println(s"scalex: ${unreadable.get()} file(s) unreadable during $label")

  def results: List[Reference] = queue.asScala.toList
}

// ── Ranked name matching ─────────────────────────────────────────────────────

/** Match-quality tiers for ranked name search, best first. */
enum MatchTier {
  case Exact, Prefix, Contains, ReverseContains, CamelCase
}

private def isSegmentStart(name: String, i: Int): Boolean =
  i == 0 || name(i).isUpper || (i > 0 && name(i - 1) == '_')

/** True if every char of `query` (lowercase) appears in `name` walking
  * camelCase/snake_case segment starts, e.g. "usl" matches "UserServiceLive". */
def camelCaseMatch(query: String, name: String): Boolean =
  query.length >= 2 && {
    val qLower = query.toLowerCase
    val nLower = name.toLowerCase
    var qi = 0
    var ni = 0
    while qi < qLower.length && ni < nLower.length do
      if qLower(qi) == nLower(ni) then
        qi += 1
        ni += 1
      else
        ni += 1
        while ni < nLower.length && !isSegmentStart(name, ni) do ni += 1
    qi == qLower.length
  }

/** Classify how `name` matches a query (`lowerQuery` must be pre-lowercased).
  * One classifier behind symbol search, file search, and suggestion ranking. */
def nameMatchTier(lowerQuery: String, name: String): Option[MatchTier] = {
  val n = name.toLowerCase
  if n == lowerQuery then Some(MatchTier.Exact)
  else if n.startsWith(lowerQuery) then Some(MatchTier.Prefix)
  else if n.contains(lowerQuery) then Some(MatchTier.Contains)
  else if lowerQuery.endsWith(n) && n.length >= 3 && n.length > lowerQuery.length / 2 then Some(MatchTier.ReverseContains)
  else if camelCaseMatch(lowerQuery, name) then Some(MatchTier.CamelCase)
  else None
}

/** Bucket items by their name-match tier against `lowerQuery`, preserving
  * encounter order within each tier. Items that match no tier are dropped.
  * One bucketer behind symbol search and file search. */
private def bucketByTier[A](items: List[A], lowerQuery: String)(nameOf: A => String): Map[MatchTier, List[A]] =
  items.flatMap(a => nameMatchTier(lowerQuery, nameOf(a)).map(t => (tier = t, item = a)))
    .groupMap(_.tier)(_.item)

/** Inverted index: group items under each (already-normalized) key produced by `keys`. */
private def buildMultiIndex[A, K](items: List[A])(keys: A => IterableOnce[K]): Map[K, List[A]] = {
  val idx = mutable.HashMap.empty[K, mutable.ListBuffer[A]]
  items.foreach { item =>
    keys(item).iterator.foreach { k =>
      idx.getOrElseUpdate(k, mutable.ListBuffer.empty) += item
    }
  }
  idx.map((k, v) => k -> v.toList).toMap
}

// ── Workspace index ─────────────────────────────────────────────────────────

class WorkspaceIndex(val workspace: Path, val needBlooms: Boolean = true):
  var gitFiles: List[GitFile] = Nil
  private var indexedFiles: List[IndexedFile] = Nil

  private lazy val allSymbols: List[SymbolInfo] =
    Timings.phase("build-allSymbols") {
      indexedFiles.flatMap(_.symbols)
    }

  lazy val symbols: List[SymbolInfo] = allSymbols

  lazy val filesByPath: Map[Path, List[SymbolInfo]] =
    Timings.phase("build-filesByPath") {
      allSymbols.groupBy(_.file)
    }

  lazy val symbolsByName: Map[String, List[SymbolInfo]] =
    Timings.phase("build-symbolsByName") {
      allSymbols.groupBy(_.name.toLowerCase)
    }

  lazy val symbolsByQName: Map[String, List[SymbolInfo]] =
    Timings.phase("build-symbolsByQName") {
      allSymbols.filter(_.packageName.nonEmpty)
        .groupBy(s => s"${s.packageName}.${s.name}".toLowerCase)
    }

  lazy val packages: Set[String] =
    Timings.phase("build-packages") {
      allSymbols.iterator.map(_.packageName).filter(_.nonEmpty).toSet
    }

  lazy val parentIndex: Map[String, List[SymbolInfo]] =
    Timings.phase("build-parentIndex") {
      buildMultiIndex(allSymbols)(_.parents.map(_.toLowerCase))
    }

  lazy val typeParamParentIndex: Map[String, List[SymbolInfo]] =
    Timings.phase("build-typeParamParentIndex") {
      buildMultiIndex(allSymbols)(_.typeParamParents.map(_.toLowerCase))
    }

  private lazy val distinctSymbols: List[SymbolInfo] =
    Timings.phase("build-distinctSymbols") {
      allSymbols.distinctBy(s => (name = s.name, file = s.file, line = s.line))
    }

  lazy val packageToSymbols: Map[String, Set[String]] =
    Timings.phase("build-packageToSymbols") {
      val pkgToSyms = mutable.HashMap.empty[String, mutable.HashSet[String]]
      allSymbols.foreach { s =>
        pkgToSyms.getOrElseUpdate(s.packageName, mutable.HashSet.empty) += s.name
      }
      pkgToSyms.map((k, v) => k -> v.toSet).toMap
    }

  private lazy val indexedByPath: Map[String, IndexedFile] =
    Timings.phase("build-indexedByPath") {
      indexedFiles.map(f => f.relativePath -> f).toMap
    }

  private lazy val aliasIndex: Map[String, List[(file: IndexedFile, alias: String)]] =
    Timings.phase("build-aliasIndex") {
      val aIdx = mutable.HashMap.empty[String, mutable.ListBuffer[(file: IndexedFile, alias: String)]]
      indexedFiles.foreach { f =>
        f.aliases.foreach { (orig, alias) =>
          aIdx.getOrElseUpdate(orig, mutable.ListBuffer.empty) += ((f, alias))
        }
      }
      aIdx.map((k, v) => k -> v.toList).toMap
    }

  private lazy val symbolImportRank: Map[String, Int] =
    Timings.phase("build-symbolImportRank") {
      // Count how many files import each symbol name (by counting import lines mentioning it)
      val counts = mutable.HashMap.empty[String, Int]
      indexedFiles.foreach { idxFile =>
        idxFile.imports.foreach { imp =>
          parseImportTarget(imp).foreach { (_, names, _) =>
            names.foreach { name =>
              val lower = name.toLowerCase
              counts(lower) = counts.getOrElse(lower, 0) + 1
            }
          }
        }
      }
      counts.toMap
    }

  private lazy val annotationIndex: Map[String, List[SymbolInfo]] =
    Timings.phase("build-annotationIndex") {
      buildMultiIndex(allSymbols)(_.annotations.map(_.toLowerCase))
    }

  var fileCount: Int = 0
  var indexTimeMs: Long = 0
  var parsedCount: Int = 0
  var skippedCount: Int = 0
  var parseFailures: Int = 0
  var parseFailedFiles: List[String] = Nil
  var cachedLoad: Boolean = false

  def index(): Unit =
    val t0 = System.nanoTime()
    gitFiles = Timings.phase("git-ls-files") { gitLsFiles(workspace) }
    fileCount = gitFiles.size

    val cached = Timings.phase("cache-load") { IndexPersistence.load(workspace, needBlooms) }
    cachedLoad = cached.isDefined
    // No cache = empty map: every file misses the OID compare and gets parsed
    val cachedMap = cached.getOrElse(Map.empty)
    val result = mutable.ListBuffer.empty[IndexedFile]
    val toParse = mutable.ListBuffer.empty[GitFile]

    Timings.phase("oid-compare") {
      gitFiles.foreach { gf =>
        val rel = workspace.relativize(gf.path).toString
        cachedMap.get(rel) match
          case Some(cf) if cf.oid == gf.oid =>
            result += cf
            skippedCount += 1
          case _ =>
            toParse += gf
      }
    }

    val parsedQueue = ConcurrentLinkedQueue[IndexedFile]()
    Timings.phase("parse") {
      toParse.asJava.parallelStream().forEach { gf =>
        val rel = workspace.relativize(gf.path).toString
        val (syms, bloom, imports, aliases, failed) = extractSymbols(gf.path)
        parsedQueue.add(IndexedFile(rel, gf.oid, syms, bloom, imports, aliases, failed))
      }
    }
    result ++= parsedQueue.asScala
    parsedCount = toParse.size

    indexedFiles = result.toList
    parseFailedFiles = indexedFiles.collect {
      case f if f.parseFailed => f.relativePath
    }
    parseFailures = parseFailedFiles.size
    indexTimeMs = (System.nanoTime() - t0) / 1_000_000

    if parsedCount > 0 then
      if !needBlooms then
        // Reload with blooms for newly parsed files that have empty blooms
        indexedFiles = indexedFiles.map { f =>
          if f.identifierBloom.isEmpty then
            val source = try Files.readString(workspace.resolve(f.relativePath)) catch
              case _: java.io.IOException => ""
            f.copy(identifierBloom = Some(buildBloomFilterFromSource(source)))
          else f
        }
      Timings.phase("cache-save") { IndexPersistence.save(workspace, indexedFiles) }

  def findDefinition(name: String): List[SymbolInfo] = {
    if name.contains(".") then {
      val qResult = symbolsByQName.getOrElse(name.toLowerCase, Nil)
      if qResult.nonEmpty then qResult
      else {
        // Partial qualification: "cache.Cache" matches "coursier.cache.Cache"
        val lastDot = name.lastIndexOf('.')
        val simpleName = name.substring(lastDot + 1)
        val pkgSuffix = name.substring(0, lastDot).toLowerCase
        val pkgResult = symbolsByName.getOrElse(simpleName.toLowerCase, Nil)
          .filter(_.packageName.toLowerCase.endsWith(pkgSuffix))
        if pkgResult.nonEmpty then pkgResult
        else {
          // Owner-qualified: "Outer.Inner" — find owner type, then filter by same file
          val ownerName = name.substring(0, lastDot)
          val ownerDefs = findDefinition(ownerName).filter(s =>
            s.kind == SymbolKind.Class || s.kind == SymbolKind.Trait ||
            s.kind == SymbolKind.Object || s.kind == SymbolKind.Enum)
          if ownerDefs.isEmpty then Nil
          else {
            val ownerFiles = ownerDefs.map(_.file).toSet
            symbolsByName.getOrElse(simpleName.toLowerCase, Nil)
              .filter(s => ownerFiles.contains(s.file))
          }
        }
      }
    }
    else symbolsByName.getOrElse(name.toLowerCase, Nil)
  }

  def findImplementations(name: String): List[SymbolInfo] = {
    // A qualified name must resolve before its simple name is looked up
    if name.contains(".") && findDefinition(name).isEmpty then Nil
    else {
      val simpleName = name.substring(name.lastIndexOf('.') + 1).toLowerCase
      (parentIndex.getOrElse(simpleName, Nil) ++ typeParamParentIndex.getOrElse(simpleName, Nil))
        .distinctBy(s => (name = s.name, file = s.file, line = s.line))
    }
  }

  def findAnnotated(annotation: String): List[SymbolInfo] =
    annotationIndex.getOrElse(annotation.toLowerCase, Nil)

  def grepFiles(pattern: String, noTests: Boolean, pathFilter: Option[String],
                excludePath: Option[String] = None,
                timeoutMs: Long = defaultTimeoutMs): (results: List[Reference], timedOut: Boolean) =
    compileRegex(pattern) match
      case None => (Nil, false)
      case Some(regex) =>
        val keep = pathPredicate(noTests, pathFilter, excludePath, workspace)
        val candidates = gitFiles.filter(gf => keep(gf.path))
        val scan = DeadlineScan(timeoutMs)
        scan.scanParallel(candidates)(_.path) { (_, path, lines) =>
          scan.forEachLine(lines) { (line, lineNum) =>
            if regex.matcher(line).find() then
              scan.emit(Reference(path, lineNum, line.trim))
          }
        }
        scan.reportUnreadable("grep")
        (scan.results.sortBy(r => (path = workspace.relativize(r.file).toString, line = r.line)), scan.timedOut)

  private def compileRegex(pattern: String): Option[java.util.regex.Pattern] =
    try Some(java.util.regex.Pattern.compile(pattern))
    catch
      case e: java.util.regex.PatternSyntaxException =>
        Console.err.println(s"Invalid regex: ${e.getMessage}")
        None

  def search(query: String): List[SymbolInfo] =
    val byTier = bucketByTier(distinctSymbols, query.toLowerCase)(_.name)
    def tier(t: MatchTier) = byTier.getOrElse(t, Nil)
    def searchRank(s: SymbolInfo): (kindRank: Int, testRank: Int, stdlibRank: Int, importRank: Int, pathLen: Int) =
      val kindRank = s.kind match
        case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Enum => 0
        case SymbolKind.Object => 1
        case SymbolKind.Def | SymbolKind.Val | SymbolKind.Type => 2
        case _ => 3
      val testRank = if isTestFile(s.file, workspace) then 1 else 0
      val stdlibRank = stdlibPkgRank(s.packageName.toLowerCase)
      // Symbols in heavily-imported types rank higher (lower importRank = better)
      val importRank = -symbolImportRank.getOrElse(s.name.toLowerCase, 0)
      val pathLen = s.file.toString.length
      (kindRank, testRank, stdlibRank, importRank, pathLen)
    List(MatchTier.Exact, MatchTier.Prefix, MatchTier.Contains, MatchTier.ReverseContains)
      .flatMap(t => tier(t).sortBy(searchRank)) ++ tier(MatchTier.CamelCase).sortBy(_.name.length)

  def fileSymbols(path: String): List[SymbolInfo] =
    val resolved = if Path.of(path).isAbsolute then Path.of(path)
                   else workspace.resolve(path)
    filesByPath.getOrElse(resolved, Nil)

  def searchFiles(query: String): List[String] =
    def fileName(f: IndexedFile): String =
      f.relativePath.substring(f.relativePath.lastIndexOf('/') + 1).stripSuffix(".scala").stripSuffix(".java")
    val byTier = bucketByTier(indexedFiles, query.toLowerCase)(fileName)
    def tier(t: MatchTier) = byTier.getOrElse(t, Nil).map(_.relativePath)
    // ReverseContains is not a useful tier for filenames
    tier(MatchTier.Exact) ++ tier(MatchTier.Prefix) ++ tier(MatchTier.Contains) ++
      tier(MatchTier.CamelCase).sortBy(_.length)

  private val defaultTimeoutMs = 20_000L

  def findReferences(name: String, timeoutMs: Long = defaultTimeoutMs, strict: Boolean = false): (results: List[Reference], timedOut: Boolean) =
    val wordMatch: (String, String) => Boolean = if strict then containsWordStrict else containsWord
    val (allCandidates, fileAliasMap) = Timings.phase("bloom-screen") {
      val candidates = indexedFiles.filter(f => f.identifierBloom.forall(_.mightContain(name)))
      val aliasFiles = aliasIndex.getOrElse(name, Nil)
      val candidateSet = candidates.map(_.relativePath).toSet
      val extraFiles = aliasFiles.collect {
        case (f, _) if !candidateSet.contains(f.relativePath) => f
      }
      val fileAliasMap = aliasFiles.map((f, alias) => f.relativePath -> alias).toMap
      (allCandidates = candidates ++ extraFiles, fileAliasMap = fileAliasMap)
    }

    val scan = DeadlineScan(timeoutMs)
    val seen = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
    Timings.phase("text-search") {
      scan.scanParallel(allCandidates)(f => workspace.resolve(f.relativePath)) { (idxFile, path, lines) =>
        val aliasName = fileAliasMap.get(idxFile.relativePath)
        scan.forEachLine(lines) { (line, lineNum) =>
          val key = s"${idxFile.relativePath}:$lineNum"
          if wordMatch(line, name) && seen.add(key) then
            scan.emit(Reference(path, lineNum, line.trim))
          else aliasName match
            case Some(alias) if wordMatch(line, alias) && seen.add(key) =>
              scan.emit(Reference(path, lineNum, line.trim, Some(s"via alias $alias")))
            case _ =>
        }
      }
    }
    scan.reportUnreadable("refs")
    (scan.results, scan.timedOut)

  def categorizeReferences(name: String, strict: Boolean = false): (grouped: Map[RefCategory, List[Reference]], timedOut: Boolean) =
    val (refs, timedOut) = findReferences(name, strict = strict)
    val grouped = refs.groupBy { r =>
      val line = r.contextLine
      if line.matches("""^\s*(trait|class|object|enum|given|type|def|val|var)\s+.*""") && containsWord(line, name) &&
         (line.contains(s"trait $name") || line.contains(s"class $name") || line.contains(s"object $name") ||
          line.contains(s"enum $name") || line.contains(s"type $name") ||
          line.matches(s""".*given\\s+\\w*$name.*""")) then
        RefCategory.Definition
      else if line.matches(""".*\b(extends|with)\b.*""") && containsWord(line, name) then
        RefCategory.ExtendedBy
      else if line.trim.startsWith("import ") then
        RefCategory.ImportedBy
      else if line.matches("""^\s*(//|/\*|\*).*""") then
        RefCategory.Comment
      else if line.matches(s""".*:\\s*$name.*""") || line.matches(s""".*\\[$name.*""") then
        RefCategory.UsedAsType
      else
        RefCategory.Usage
    }
    (grouped, timedOut)

  def findImports(name: String, timeoutMs: Long = defaultTimeoutMs, strict: Boolean = false): (results: List[Reference], timedOut: Boolean) =
    val wordMatch: (String, String) => Boolean = if strict then containsWordStrict else containsWord
    val candidates = indexedFiles.filter(f => f.identifierBloom.forall(_.mightContain(name)))
    val scan = DeadlineScan(timeoutMs)
    val resultPaths = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
    scan.scanParallel(candidates)(f => workspace.resolve(f.relativePath)) { (idxFile, path, lines) =>
      scan.forEachLine(lines) { (line, lineNum) =>
        if line.trim.startsWith("import ") && wordMatch(line, name) then
          scan.emit(Reference(path, lineNum, line.trim))
          resultPaths.add(s"${idxFile.relativePath}:$lineNum")
      }
    }

    // Also find wildcard imports that resolve to a package containing the target symbol
    val targetPkgs = symbolsByName.getOrElse(name.toLowerCase, Nil).map(_.packageName).toSet
    if targetPkgs.nonEmpty then
      for idxFile <- indexedFiles if scan.inTime do
        for imp <- idxFile.imports do
          if wildcardImportPkg(imp).exists(targetPkgs.contains) then
            val path = workspace.resolve(idxFile.relativePath)
            scan.forEachLine(scan.readLines(path)) { (line, lineNum) =>
              if line.trim == imp.trim then
                val key = s"${idxFile.relativePath}:$lineNum"
                if !resultPaths.contains(key) then
                  scan.emit(Reference(path, lineNum, line.trim))
                  resultPaths.add(key)
            }

    scan.reportUnreadable("imports")
    (scan.results, scan.timedOut)

  private def filePackage(idxFile: IndexedFile): String =
    idxFile.symbols.headOption.map(_.packageName).getOrElse("")

  def filePackageByPath(relPath: String): Option[String] =
    indexedByPath.get(relPath).map(filePackage)

  /** Import lines recorded at index time for the file at `path` (absolute).
    * Lets callers avoid re-parsing source just to read imports. */
  def fileImports(path: Path): List[String] =
    indexedByPath.get(workspace.relativize(path).toString).map(_.imports).getOrElse(Nil)

  def resolveConfidence(ref: Reference, targetName: String, targetPackages: Set[String]): Confidence =
    val relPath = workspace.relativize(ref.file).toString
    indexedByPath.get(relPath) match
      case None => Confidence.Low
      case Some(idxFile) =>
        val filePkg = filePackage(idxFile)
        if targetPackages.contains(filePkg) then Confidence.High
        else
          val imports = idxFile.imports
          val hasExplicit = imports.exists { imp =>
            imp.contains(s".$targetName") || imp.contains(s"{$targetName") ||
            imp.contains(s", $targetName") || imp.contains(s"$targetName,")
          }
          val hasAliasMatch = idxFile.aliases.exists { (orig, alias) =>
            alias == targetName || orig == targetName
          }
          if hasExplicit || hasAliasMatch then Confidence.High
          else
            val hasWildcard = imports.exists(imp => wildcardImportPkg(imp).exists(targetPackages.contains))
            if hasWildcard then Confidence.Medium
            else Confidence.Low

  /** True if `word` occurs in `line` with no word character (per `isWordChar`)
    * directly before or after the occurrence. */
  private def containsWordWith(line: String, word: String, isWordChar: Char => Boolean): Boolean = {
    var i = line.indexOf(word)
    var found = false
    while !found && i >= 0 do {
      val before = i == 0 || !isWordChar(line(i - 1))
      val after = i + word.length >= line.length || !isWordChar(line(i + word.length))
      if before && after then found = true
      else i = line.indexOf(word, i + 1)
    }
    found
  }

  private def containsWord(line: String, word: String): Boolean =
    containsWordWith(line, word, _.isLetterOrDigit)

  private def containsWordStrict(line: String, word: String): Boolean =
    containsWordWith(line, word, isIdentChar)

  private def isIdentChar(c: Char): Boolean =
    c.isLetterOrDigit || c == '_' || c == '$'

  def findApiSurface(targetPkg: String, filterToPkg: Option[String] = None): List[(symbol: SymbolInfo, importerCount: Int)] =
    Timings.phase("api-surface") {
      val targetSymNames = packageToSymbols.getOrElse(targetPkg, Set.empty)
      if targetSymNames.isEmpty then Nil
      else {

      // Count external importers per symbol name
      val importerCounts = mutable.HashMap.empty[String, mutable.HashSet[String]]
      targetSymNames.foreach(n => importerCounts(n) = mutable.HashSet.empty)

      indexedFiles.foreach { idxFile =>
        val filePkg = filePackage(idxFile)
        if filePkg != targetPkg then {
          // If --used-by filter is set, only count importers from matching packages
          val matchesFilter = filterToPkg match
            case Some(fpkg) => filePkg.toLowerCase.contains(fpkg.toLowerCase) || filePkg.equalsIgnoreCase(fpkg)
            case None => true
          if matchesFilter then
            idxFile.imports.foreach { imp =>
              parseImportTarget(imp).foreach { (pkg, names, isWildcard) =>
                if pkg == targetPkg then {
                  if isWildcard then
                    // Credit all symbols in the package
                    targetSymNames.foreach { symName =>
                      importerCounts(symName).add(idxFile.relativePath)
                    }
                  else
                    names.foreach { name =>
                      if importerCounts.contains(name) then
                        importerCounts(name).add(idxFile.relativePath)
                    }
                }
              }
            }
        }
      }

      // Build result with SymbolInfo objects
      val symsByName = allSymbols.filter(_.packageName == targetPkg).groupBy(_.name)
      targetSymNames.toList.flatMap { name =>
        symsByName.getOrElse(name, Nil).headOption.map { sym =>
          (symbol = sym, importerCount = importerCounts.getOrElse(name, mutable.HashSet.empty).size)
        }
      }
      }
    }

// ── Import line parsing ──────────────────────────────────────────────────────

/** Parse an import line into its package, imported names, and wildcard flag.
  * Handles brace imports ("import pkg.{A, B as C, _}") and simple imports. */
def parseImportTarget(imp: String): Option[(pkg: String, names: List[String], isWildcard: Boolean)] =
  val trimmed = imp.trim.stripPrefix("import ")
  if trimmed.isEmpty then None
  else {

  // Handle brace-enclosed imports: import pkg.{A, B, C as D, _}
  val braceStart = trimmed.indexOf('{')
  if braceStart >= 0 then
    val pkg = trimmed.substring(0, braceStart).stripSuffix(".")
    val braceEnd = trimmed.indexOf('}', braceStart)
    val inner = if braceEnd >= 0 then trimmed.substring(braceStart + 1, braceEnd) else trimmed.substring(braceStart + 1)
    val parts = inner.split(',').map(_.trim).filter(_.nonEmpty)
    var isWildcard = false
    val names = mutable.ListBuffer.empty[String]
    parts.foreach { part =>
      if part == "_" || part == "*" then isWildcard = true
      else
        // Handle "Foo as Bar" or "Foo => Bar" — we want the original name (Foo)
        val asIdx = part.indexOf(" as ")
        val arrowIdx = part.indexOf(" => ")
        val name = if asIdx >= 0 then part.substring(0, asIdx).trim
                   else if arrowIdx >= 0 then part.substring(0, arrowIdx).trim
                   else part.trim
        if name.nonEmpty && name != "_" && name != "*" then names += name
    }
    Some((pkg, names.toList, isWildcard))
  else
    // Simple import: import pkg.Name or import pkg._ or import pkg.*
    val lastDot = trimmed.lastIndexOf('.')
    if lastDot < 0 then None
    else
      val pkg = trimmed.substring(0, lastDot)
      val name = trimmed.substring(lastDot + 1)
      if name == "_" || name == "*" then Some((pkg, Nil, true))
      else Some((pkg, List(name), false))
  }

/** Package of a wildcard import line ("import pkg._" / "import pkg.*"), or None. */
def wildcardImportPkg(imp: String): Option[String] =
  val trimmed = imp.trim.stripPrefix("import ")
  if trimmed.endsWith("._") || trimmed.endsWith(".*") then Some(trimmed.dropRight(2)) else None

// ── Filtering helpers ────────────────────────────────────────────────────────

def isTestFile(path: Path, workspace: Path): Boolean =
  val rel = workspace.relativize(path).toString
  rel.startsWith("test/") || rel.startsWith("tests/") || rel.startsWith("testing/") ||
  rel.contains("/test/") || rel.contains("/tests/") || rel.contains("/testing/") ||
  rel.startsWith("bench-") || rel.contains("/bench-") ||
  rel.endsWith("Test.scala") || rel.endsWith("Spec.scala") || rel.endsWith("Suite.scala") ||
  rel.endsWith(".test.scala") ||
  rel.endsWith("Test.java") || rel.endsWith("Spec.java") || rel.endsWith("Suite.java")

def matchesPath(file: Path, prefix: String, workspace: Path): Boolean =
  val rel = workspace.relativize(file).toString
  rel.startsWith(prefix)

/** Predicate combining the --no-tests / --path / --exclude-path file filters,
  * shared by symbol/ref filtering and the file-scanning commands. */
def pathPredicate(noTests: Boolean, pathFilter: Option[String], excludePath: Option[String], workspace: Path): Path => Boolean =
  path =>
    (!noTests || !isTestFile(path, workspace)) &&
    pathFilter.forall(p => matchesPath(path, p, workspace)) &&
    excludePath.forall(p => !matchesPath(path, p, workspace))
