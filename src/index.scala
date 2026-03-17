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
      if parts.length >= 2 && (path.endsWith(".scala") || path.endsWith(".java")) then
        Some(GitFile(workspace.resolve(path), parts(1)))
      else None
  }.toList
  proc.waitFor()
  files

// ── Binary persistence ──────────────────────────────────────────────────────

object IndexPersistence:
  private val MAGIC = 0x53584458
  private val VERSION: Byte = 7

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
      val pIdx = mutable.HashMap.empty[String, mutable.ListBuffer[SymbolInfo]]
      allSymbols.foreach { s =>
        s.parents.foreach { p =>
          pIdx.getOrElseUpdate(p.toLowerCase, mutable.ListBuffer.empty) += s
        }
      }
      pIdx.map((k, v) => k -> v.toList).toMap
    }

  lazy val typeParamParentIndex: Map[String, List[SymbolInfo]] =
    Timings.phase("build-typeParamParentIndex") {
      val idx = mutable.HashMap.empty[String, mutable.ListBuffer[SymbolInfo]]
      allSymbols.foreach { s =>
        s.typeParamParents.foreach { p =>
          idx.getOrElseUpdate(p.toLowerCase, mutable.ListBuffer.empty) += s
        }
      }
      idx.map((k, v) => k -> v.toList).toMap
    }

  private lazy val distinctSymbols: List[SymbolInfo] =
    Timings.phase("build-distinctSymbols") {
      val seen = mutable.HashSet.empty[(String, Path, Int)]
      allSymbols.filter(s => seen.add((s.name, s.file, s.line)))
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
      val aByAnnot = mutable.HashMap.empty[String, mutable.ListBuffer[SymbolInfo]]
      allSymbols.foreach { s =>
        s.annotations.foreach { a =>
          aByAnnot.getOrElseUpdate(a.toLowerCase, mutable.ListBuffer.empty) += s
        }
      }
      aByAnnot.map((k, v) => k -> v.toList).toMap
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
    val result = mutable.ListBuffer.empty[IndexedFile]

    cached match
      case Some(cachedMap) =>
        cachedLoad = true
        val toParseQueue = ConcurrentLinkedQueue[IndexedFile]()
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

        Timings.phase("parse") {
          toParse.asJava.parallelStream().forEach { gf =>
            val rel = workspace.relativize(gf.path).toString
            val (syms, bloom, imports, aliases, failed) =
              if rel.endsWith(".java") then extractJavaSymbols(gf.path)
              else extractSymbols(gf.path)
            toParseQueue.add(IndexedFile(rel, gf.oid, syms, bloom, imports, aliases, failed))
          }
        }
        result ++= toParseQueue.asScala
        parsedCount = toParse.size

      case None =>
        val queue = ConcurrentLinkedQueue[IndexedFile]()
        Timings.phase("parse") {
          gitFiles.asJava.parallelStream().forEach { gf =>
            val rel = workspace.relativize(gf.path).toString
            val (syms, bloom, imports, aliases, failed) =
              if rel.endsWith(".java") then extractJavaSymbols(gf.path)
              else extractSymbols(gf.path)
            queue.add(IndexedFile(rel, gf.oid, syms, bloom, imports, aliases, failed))
          }
        }
        result ++= queue.asScala
        parsedCount = gitFiles.size

    indexedFiles = result.toList
    parseFailedFiles = indexedFiles.collect {
      case f if f.parseFailed => f.relativePath
    }
    parseFailures = parseFailedFiles.size
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
      Timings.phase("cache-save") { IndexPersistence.save(workspace, indexedFiles) }

  def findDefinition(name: String): List[SymbolInfo] =
    if name.contains(".") then
      val qResult = symbolsByQName.getOrElse(name.toLowerCase, Nil)
      if qResult.nonEmpty then qResult
      else
        // Partial qualification: "cache.Cache" matches "coursier.cache.Cache"
        val lastDot = name.lastIndexOf('.')
        val simpleName = name.substring(lastDot + 1)
        val pkgSuffix = name.substring(0, lastDot).toLowerCase
        symbolsByName.getOrElse(simpleName.toLowerCase, Nil)
          .filter(_.packageName.toLowerCase.endsWith(pkgSuffix))
    else
      symbolsByName.getOrElse(name.toLowerCase, Nil)

  def findImplementations(name: String): List[SymbolInfo] =
    val direct = parentIndex.getOrElse(name.toLowerCase, Nil)
    val viaTp = typeParamParentIndex.getOrElse(name.toLowerCase, Nil)
    (direct ++ viaTp).distinctBy(s => (name = s.name, file = s.file, line = s.line))

  def findAnnotated(annotation: String): List[SymbolInfo] =
    annotationIndex.getOrElse(annotation.toLowerCase, Nil)

  def grepFiles(pattern: String, noTests: Boolean, pathFilter: Option[String],
                excludePath: Option[String] = None,
                timeoutMs: Long = defaultTimeoutMs): (results: List[Reference], timedOut: Boolean) =
    val regex = try java.util.regex.Pattern.compile(pattern)
    catch
      case e: java.util.regex.PatternSyntaxException =>
        Console.err.println(s"Invalid regex: ${e.getMessage}")
        return (Nil, false)
    var candidates = gitFiles
    if noTests then candidates = candidates.filter(gf => !isTestFile(gf.path, workspace))
    pathFilter.foreach { p => candidates = candidates.filter(gf => matchesPath(gf.path, p, workspace)) }
    excludePath.foreach { p => candidates = candidates.filter(gf => !matchesPath(gf.path, p, workspace)) }
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
    def searchRank(s: SymbolInfo): (kindRank: Int, testRank: Int, importRank: Int, pathLen: Int) =
      val kindRank = s.kind match
        case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Enum => 0
        case SymbolKind.Object => 1
        case SymbolKind.Def | SymbolKind.Val | SymbolKind.Type => 2
        case _ => 3
      val testRank = if isTestFile(s.file, workspace) then 1 else 0
      // Symbols in heavily-imported types rank higher (lower importRank = better)
      val importRank = -symbolImportRank.getOrElse(s.name.toLowerCase, 0)
      val pathLen = s.file.toString.length
      (kindRank, testRank, importRank, pathLen)
    exact.toList.sortBy(searchRank) ++ prefix.toList.sortBy(searchRank) ++ contains.toList.sortBy(searchRank) ++ fuzzy.sortBy(_.name.length).toList

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
      val fileName = f.relativePath.substring(f.relativePath.lastIndexOf('/') + 1).stripSuffix(".scala").stripSuffix(".java")
      val n = fileName.toLowerCase
      if n == lower then exact += f.relativePath
      else if n.startsWith(lower) then prefix += f.relativePath
      else if n.contains(lower) then contains += f.relativePath
      else if camelCaseMatch(lower, fileName) then fuzzy += f.relativePath
    }
    exact.toList ++ prefix.toList ++ contains.toList ++ fuzzy.sortBy(_.length).toList

  private val defaultTimeoutMs = 20_000L
  var timedOut: Boolean = false

  def findReferences(name: String, timeoutMs: Long = defaultTimeoutMs, strict: Boolean = false): List[Reference] =
    val wordMatch: (String, String) => Boolean = if strict then containsWordStrict else containsWord
    val (candidates, allCandidates, fileAliasMap) = Timings.phase("bloom-screen") {
      val candidates = indexedFiles.filter(f => f.identifierBloom == null || f.identifierBloom.mightContain(name))
      val aliasFiles = aliasIndex.getOrElse(name, Nil)
      val candidateSet = candidates.map(_.relativePath).toSet
      val extraFiles = aliasFiles.collect {
        case (f, _) if !candidateSet.contains(f.relativePath) => f
      }
      val allCandidates = candidates ++ extraFiles
      val fileAliasMap = aliasFiles.map((f, alias) => f.relativePath -> alias).toMap
      (candidates, allCandidates, fileAliasMap)
    }

    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    timedOut = false
    val results = ConcurrentLinkedQueue[Reference]()
    val seen = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
    Timings.phase("text-search") {
      allCandidates.asJava.parallelStream().forEach { idxFile =>
        if System.nanoTime() < deadline then
          val path = workspace.resolve(idxFile.relativePath)
          val lines = try Files.readAllLines(path).asScala catch
            case _: Exception => Seq.empty
          val aliasName = fileAliasMap.get(idxFile.relativePath)
          lines.zipWithIndex.foreach {
            case (line, idx) if System.nanoTime() < deadline =>
              val key = s"${idxFile.relativePath}:${idx + 1}"
              if wordMatch(line, name) && seen.add(key) then
                results.add(Reference(path, idx + 1, line.trim))
              else aliasName match
                case Some(alias) if wordMatch(line, alias) && seen.add(key) =>
                  results.add(Reference(path, idx + 1, line.trim, Some(s"via alias $alias")))
                case _ =>
            case _ =>
          }
        else timedOut = true
      }
    }
    results.asScala.toList

  def categorizeReferences(name: String, strict: Boolean = false): Map[RefCategory, List[Reference]] =
    val refs = findReferences(name, strict = strict)
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

  def findImports(name: String, timeoutMs: Long = defaultTimeoutMs, strict: Boolean = false): List[Reference] =
    val wordMatch: (String, String) => Boolean = if strict then containsWordStrict else containsWord
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
          case (line, idx) if System.nanoTime() < deadline && line.trim.startsWith("import ") && wordMatch(line, name) =>
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

  private def parseImportTarget(imp: String): Option[(pkg: String, names: List[String], isWildcard: Boolean)] =
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

  private def containsWordStrict(line: String, word: String): Boolean =
    var i = line.indexOf(word)
    while i >= 0 do
      val before = i == 0 || !isIdentChar(line(i - 1))
      val after = i + word.length >= line.length || !isIdentChar(line(i + word.length))
      if before && after then return true
      i = line.indexOf(word, i + 1)
    false

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
