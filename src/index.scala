import scala.collection.mutable
import java.nio.file.{Files, Path}
import java.io.{BufferedReader, BufferedInputStream, BufferedOutputStream, DataInputStream, DataOutputStream, InputStreamReader}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*
import com.google.common.hash.{BloomFilter, Funnels}

// ── Git ─────────────────────────────────────────────────────────────────────

def gitLsFiles(workspace: Path): List[GitFile] =
  val pb = ProcessBuilder("git", "ls-files", "--stage")
  pb.directory(workspace.toFile)
  pb.redirectErrorStream(true)
  val proc = pb.start()
  val reader = BufferedReader(InputStreamReader(proc.getInputStream))
  val files = reader.lines().iterator().asScala.flatMap { line =>
    val tabIdx = line.indexOf('\t')
    if tabIdx < 0 then None
    else
      val parts = line.substring(0, tabIdx).split("\\s+")
      val path = line.substring(tabIdx + 1)
      if parts.length >= 2 && path.endsWith(".scala") then
        Some(GitFile(workspace.resolve(path), parts(1)))
      else None
  }.toList
  proc.waitFor()
  files

// ── Binary persistence ──────────────────────────────────────────────────────

object IndexPersistence:
  private val MAGIC = 0x53584458
  private val VERSION: Byte = 6

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
        val bloomBytes = java.io.ByteArrayOutputStream()
        f.identifierBloom.writeTo(bloomBytes)
        val ba = bloomBytes.toByteArray
        out.writeInt(ba.length)
        out.write(ba)

        // Parse failed flag
        out.writeBoolean(f.parseFailed)
      }
    finally out.close()

  def load(workspace: Path, loadBlooms: Boolean = true): Option[Map[String, IndexedFile]] =
    val p = indexPath(workspace)
    if !Files.exists(p) then return None

    try
      val in = DataInputStream(BufferedInputStream(Files.newInputStream(p), 1 << 16))
      try
        val magic = in.readInt()
        if magic != MAGIC then return None
        val version = in.readByte()
        if version != VERSION then return None

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
            val annotCount = in.readShort()
            val annots = (0 until annotCount).map(_ => strings(in.readInt())).toList
            syms += SymbolInfo(name, kind, workspace.resolve(relPath), line, pkg, parents, sig, annots)
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
          val bloom = if loadBlooms then
            val bloomBytes = new Array[Byte](bloomLen)
            in.readFully(bloomBytes)
            BloomFilter.readFrom(
              java.io.ByteArrayInputStream(bloomBytes),
              Funnels.unencodedCharsFunnel()
            )
          else
            in.skipBytes(bloomLen)
            null

          // Parse failed flag
          val parseFailed = in.readBoolean()

          result(relPath) = IndexedFile(relPath, oid, syms.result(), bloom, imports, aliases, parseFailed)
          fi += 1

        Some(result.toMap)
      finally in.close()
    catch
      case _: Exception => None

// ── Workspace index ─────────────────────────────────────────────────────────

class WorkspaceIndex(val workspace: Path, val needBlooms: Boolean = true):
  var symbols: List[SymbolInfo] = Nil
  var filesByPath: Map[Path, List[SymbolInfo]] = Map.empty
  var symbolsByName: Map[String, List[SymbolInfo]] = Map.empty
  var packages: Set[String] = Set.empty
  var gitFiles: List[GitFile] = Nil
  private var indexedFiles: List[IndexedFile] = Nil
  var parentIndex: Map[String, List[SymbolInfo]] = Map.empty

  private var distinctSymbols: List[SymbolInfo] = Nil
  var packageToSymbols: Map[String, Set[String]] = Map.empty
  private var indexedByPath: Map[String, IndexedFile] = Map.empty
  private var aliasIndex: Map[String, List[(file: IndexedFile, alias: String)]] = Map.empty
  private var annotationIndex: Map[String, List[SymbolInfo]] = Map.empty

  var fileCount: Int = 0
  var indexTimeMs: Long = 0
  var parsedCount: Int = 0
  var skippedCount: Int = 0
  var parseFailures: Int = 0
  var parseFailedFiles: List[String] = Nil
  var cachedLoad: Boolean = false

  def index(): Unit =
    val t0 = System.nanoTime()
    gitFiles = gitLsFiles(workspace)
    fileCount = gitFiles.size

    val cached = IndexPersistence.load(workspace, needBlooms)
    val result = mutable.ListBuffer.empty[IndexedFile]

    cached match
      case Some(cachedMap) =>
        cachedLoad = true
        val toParseQueue = ConcurrentLinkedQueue[IndexedFile]()
        val toParse = mutable.ListBuffer.empty[GitFile]

        gitFiles.foreach { gf =>
          val rel = workspace.relativize(gf.path).toString
          cachedMap.get(rel) match
            case Some(cf) if cf.oid == gf.oid =>
              result += cf
              skippedCount += 1
            case _ =>
              toParse += gf
        }

        toParse.asJava.parallelStream().forEach { gf =>
          val rel = workspace.relativize(gf.path).toString
          val (syms, bloom, imports, aliases, failed) = extractSymbols(gf.path)
          toParseQueue.add(IndexedFile(rel, gf.oid, syms, bloom, imports, aliases, failed))
        }
        result ++= toParseQueue.asScala
        parsedCount = toParse.size

      case None =>
        val queue = ConcurrentLinkedQueue[IndexedFile]()
        gitFiles.asJava.parallelStream().forEach { gf =>
          val rel = workspace.relativize(gf.path).toString
          val (syms, bloom, imports, aliases, failed) = extractSymbols(gf.path)
          queue.add(IndexedFile(rel, gf.oid, syms, bloom, imports, aliases, failed))
        }
        result ++= queue.asScala
        parsedCount = gitFiles.size

    indexedFiles = result.toList
    parseFailedFiles = indexedFiles.collect {
      case f if f.parseFailed => f.relativePath
    }
    parseFailures = parseFailedFiles.size
    // Single-pass over symbols: build all symbol-level indexes
    val allSyms = mutable.ListBuffer.empty[SymbolInfo]
    val byPath = mutable.HashMap.empty[Path, mutable.ListBuffer[SymbolInfo]]
    val byName = mutable.HashMap.empty[String, mutable.ListBuffer[SymbolInfo]]
    val pkgs = mutable.HashSet.empty[String]
    val pIdx = mutable.HashMap.empty[String, mutable.ListBuffer[SymbolInfo]]
    val pkgToSyms = mutable.HashMap.empty[String, mutable.HashSet[String]]
    val distinctSeen = mutable.HashSet.empty[(String, Path, Int)]
    val distinctBuf = mutable.ListBuffer.empty[SymbolInfo]
    val aByAnnot = mutable.HashMap.empty[String, mutable.ListBuffer[SymbolInfo]]
    indexedFiles.foreach { f =>
      f.symbols.foreach { s =>
        allSyms += s
        byPath.getOrElseUpdate(s.file, mutable.ListBuffer.empty) += s
        byName.getOrElseUpdate(s.name.toLowerCase, mutable.ListBuffer.empty) += s
        if s.packageName.nonEmpty then pkgs += s.packageName
        s.parents.foreach { p =>
          pIdx.getOrElseUpdate(p.toLowerCase, mutable.ListBuffer.empty) += s
        }
        s.annotations.foreach { a =>
          aByAnnot.getOrElseUpdate(a.toLowerCase, mutable.ListBuffer.empty) += s
        }
        pkgToSyms.getOrElseUpdate(s.packageName, mutable.HashSet.empty) += s.name
        val key = (s.name, s.file, s.line)
        if distinctSeen.add(key) then distinctBuf += s
      }
    }
    symbols = allSyms.toList
    distinctSymbols = distinctBuf.toList
    filesByPath = byPath.map((k, v) => k -> v.toList).toMap
    symbolsByName = byName.map((k, v) => k -> v.toList).toMap
    packages = pkgs.toSet
    parentIndex = pIdx.map((k, v) => k -> v.toList).toMap
    annotationIndex = aByAnnot.map((k, v) => k -> v.toList).toMap
    packageToSymbols = pkgToSyms.map((k, v) => k -> v.toSet).toMap

    // Single-pass over indexedFiles: build file-level indexes
    val iByPath = mutable.HashMap.empty[String, IndexedFile]
    val aIdx = mutable.HashMap.empty[String, mutable.ListBuffer[(file: IndexedFile, alias: String)]]
    indexedFiles.foreach { f =>
      iByPath(f.relativePath) = f
      f.aliases.foreach { (orig, alias) =>
        aIdx.getOrElseUpdate(orig, mutable.ListBuffer.empty) += ((f, alias))
      }
    }
    indexedByPath = iByPath.toMap
    aliasIndex = aIdx.map((k, v) => k -> v.toList).toMap
    indexTimeMs = (System.nanoTime() - t0) / 1_000_000

    if parsedCount > 0 then
      if !needBlooms then
        // Reload with blooms for newly parsed files that have null blooms
        indexedFiles = indexedFiles.map { f =>
          if f.identifierBloom == null then
            val source = try Files.readString(workspace.resolve(f.relativePath)) catch
              case _: Exception => ""
            f.copy(identifierBloom = buildBloomFilterFromSource(source))
          else f
        }
      IndexPersistence.save(workspace, indexedFiles)

  def findDefinition(name: String): List[SymbolInfo] =
    symbolsByName.getOrElse(name.toLowerCase, Nil)

  def findImplementations(name: String): List[SymbolInfo] =
    parentIndex.getOrElse(name.toLowerCase, Nil)

  def findAnnotated(annotation: String): List[SymbolInfo] =
    annotationIndex.getOrElse(annotation.toLowerCase, Nil)

  def grepFiles(pattern: String, noTests: Boolean, pathFilter: Option[String],
                timeoutMs: Long = defaultTimeoutMs): (results: List[Reference], timedOut: Boolean) =
    val regex = try java.util.regex.Pattern.compile(pattern)
    catch
      case e: java.util.regex.PatternSyntaxException =>
        System.err.println(s"Invalid regex: ${e.getMessage}")
        return (Nil, false)
    var candidates = gitFiles
    if noTests then candidates = candidates.filter(gf => !isTestFile(gf.path, workspace))
    pathFilter.foreach { p => candidates = candidates.filter(gf => matchesPath(gf.path, p, workspace)) }
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    var grepTimedOut = false
    val results = ConcurrentLinkedQueue[Reference]()
    candidates.asJava.parallelStream().forEach { gf =>
      if System.nanoTime() < deadline then {
        try {
          val lines = Files.readAllLines(gf.path).asScala
          lines.zipWithIndex.foreach { case (line, idx) =>
            if System.nanoTime() < deadline then {
              if regex.matcher(line).find() then
                results.add(Reference(gf.path, idx + 1, line.trim))
            } else grepTimedOut = true
          }
        } catch { case _: Exception => () }
      } else grepTimedOut = true
    }
    (results.asScala.toList.sortBy(r => (workspace.relativize(r.file).toString, r.line)), grepTimedOut)

  def search(query: String): List[SymbolInfo] =
    val lower = query.toLowerCase
    val exact = mutable.ListBuffer.empty[SymbolInfo]
    val prefix = mutable.ListBuffer.empty[SymbolInfo]
    val contains = mutable.ListBuffer.empty[SymbolInfo]
    val fuzzy = mutable.ListBuffer.empty[SymbolInfo]

    distinctSymbols.foreach { s =>
      val n = s.name.toLowerCase
      if n == lower then exact += s
      else if n.startsWith(lower) then prefix += s
      else if n.contains(lower) then contains += s
      else if camelCaseMatch(lower, s.name) then fuzzy += s
    }
    exact.toList ++ prefix.toList ++ contains.toList ++ fuzzy.sortBy(_.name.length).toList

  def fileSymbols(path: String): List[SymbolInfo] =
    val resolved = if Path.of(path).isAbsolute then Path.of(path)
                   else workspace.resolve(path)
    filesByPath.getOrElse(resolved, Nil)

  def searchFiles(query: String): List[String] =
    val lower = query.toLowerCase
    val exact = mutable.ListBuffer.empty[String]
    val prefix = mutable.ListBuffer.empty[String]
    val contains = mutable.ListBuffer.empty[String]
    val fuzzy = mutable.ListBuffer.empty[String]

    indexedFiles.foreach { f =>
      val fileName = f.relativePath.substring(f.relativePath.lastIndexOf('/') + 1).stripSuffix(".scala")
      val n = fileName.toLowerCase
      if n == lower then exact += f.relativePath
      else if n.startsWith(lower) then prefix += f.relativePath
      else if n.contains(lower) then contains += f.relativePath
      else if camelCaseMatch(lower, fileName) then fuzzy += f.relativePath
    }
    exact.toList ++ prefix.toList ++ contains.toList ++ fuzzy.sortBy(_.length).toList

  private val defaultTimeoutMs = 20_000L
  var timedOut: Boolean = false

  def findReferences(name: String, timeoutMs: Long = defaultTimeoutMs): List[Reference] =
    val candidates = indexedFiles.filter(f => f.identifierBloom == null || f.identifierBloom.mightContain(name))
    val aliasFiles = aliasIndex.getOrElse(name, Nil)
    val candidateSet = candidates.map(_.relativePath).toSet
    val extraFiles = aliasFiles.collect {
      case (f, _) if !candidateSet.contains(f.relativePath) => f
    }
    val allCandidates = candidates ++ extraFiles
    val fileAliasMap = aliasFiles.map((f, alias) => f.relativePath -> alias).toMap

    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    timedOut = false
    val results = ConcurrentLinkedQueue[Reference]()
    val seen = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
    allCandidates.asJava.parallelStream().forEach { idxFile =>
      if System.nanoTime() < deadline then
        val path = workspace.resolve(idxFile.relativePath)
        val lines = try Files.readAllLines(path).asScala catch
          case _: Exception => Seq.empty
        val aliasName = fileAliasMap.get(idxFile.relativePath)
        lines.zipWithIndex.foreach {
          case (line, idx) if System.nanoTime() < deadline =>
            val key = s"${idxFile.relativePath}:${idx + 1}"
            if containsWord(line, name) && seen.add(key) then
              results.add(Reference(path, idx + 1, line.trim))
            else aliasName match
              case Some(alias) if containsWord(line, alias) && seen.add(key) =>
                results.add(Reference(path, idx + 1, line.trim, Some(s"via alias $alias")))
              case _ =>
          case _ =>
        }
      else timedOut = true
    }
    results.asScala.toList

  def categorizeReferences(name: String): Map[RefCategory, List[Reference]] =
    val refs = findReferences(name)
    refs.groupBy { r =>
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

  def findImports(name: String, timeoutMs: Long = defaultTimeoutMs): List[Reference] =
    val candidates = indexedFiles.filter(f => f.identifierBloom == null || f.identifierBloom.mightContain(name))
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    timedOut = false
    val results = ConcurrentLinkedQueue[Reference]()
    val resultPaths = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
    candidates.asJava.parallelStream().forEach { idxFile =>
      if System.nanoTime() < deadline then
        val path = workspace.resolve(idxFile.relativePath)
        val lines = try Files.readAllLines(path).asScala catch
          case _: Exception => Seq.empty
        lines.zipWithIndex.foreach {
          case (line, idx) if System.nanoTime() < deadline && line.trim.startsWith("import ") && containsWord(line, name) =>
            results.add(Reference(path, idx + 1, line.trim))
            resultPaths.add(s"${idxFile.relativePath}:${idx + 1}")
          case _ =>
        }
      else timedOut = true
    }

    // Also find wildcard imports that resolve to a package containing the target symbol
    val targetPkgs = symbolsByName.getOrElse(name.toLowerCase, Nil).map(_.packageName).toSet
    if targetPkgs.nonEmpty then
      for idxFile <- indexedFiles if System.nanoTime() < deadline do
        for imp <- idxFile.imports do
          val trimmed = imp.trim.stripPrefix("import ")
          if (trimmed.endsWith("._") || trimmed.endsWith(".*")) then
            val pkg = trimmed.dropRight(2)
            if targetPkgs.contains(pkg) then
              val path = workspace.resolve(idxFile.relativePath)
              try {
                val lines = Files.readAllLines(path).asScala
                lines.zipWithIndex.foreach { case (line, lineIdx) =>
                  if line.trim == imp.trim then
                    val key = s"${idxFile.relativePath}:${lineIdx + 1}"
                    if !resultPaths.contains(key) then
                      results.add(Reference(path, lineIdx + 1, line.trim))
                      resultPaths.add(key)
                }
              } catch { case _: Exception => () }

    results.asScala.toList

  private def filePackage(idxFile: IndexedFile): String =
    idxFile.symbols.headOption.map(_.packageName).getOrElse("")

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
            val hasWildcard = imports.exists { imp =>
              val trimmed = imp.trim.stripPrefix("import ")
              (trimmed.endsWith("._") || trimmed.endsWith(".*")) && {
                val pkg = trimmed.dropRight(2)
                targetPackages.contains(pkg)
              }
            }
            if hasWildcard then Confidence.Medium
            else Confidence.Low

  private def isSegmentStart(name: String, i: Int): Boolean =
    i == 0 || name(i).isUpper || (i > 0 && name(i - 1) == '_')

  private def camelCaseMatch(query: String, name: String): Boolean =
    if query.length < 2 then return false
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

  private def containsWord(line: String, word: String): Boolean =
    var i = line.indexOf(word)
    while i >= 0 do
      val before = i == 0 || !line(i - 1).isLetterOrDigit
      val after = i + word.length >= line.length || !line(i + word.length).isLetterOrDigit
      if before && after then return true
      i = line.indexOf(word, i + 1)
    false

// ── Filtering helpers ────────────────────────────────────────────────────────

def isTestFile(path: Path, workspace: Path): Boolean =
  val rel = workspace.relativize(path).toString
  rel.contains("/test/") || rel.contains("/tests/") || rel.contains("/testing/") ||
  rel.startsWith("bench-") || rel.contains("/bench-") ||
  rel.endsWith("Test.scala") || rel.endsWith("Spec.scala") || rel.endsWith("Suite.scala") ||
  rel.endsWith(".test.scala")

def matchesPath(file: Path, prefix: String, workspace: Path): Boolean =
  val rel = workspace.relativize(file).toString
  rel.startsWith(prefix)
