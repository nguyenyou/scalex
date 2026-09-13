package scalex.index

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import java.util.concurrent.ConcurrentHashMap

import scalex.*
import scalex.extraction.*

import scala.collection.mutable
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*
import clibase.Timings

// ── Workspace index ─────────────────────────────────────────────────────────

object WorkspaceIndex {
  def empty(workspace: Path): WorkspaceIndex = {
    WorkspaceIndex(workspace, false, Nil, Nil, 0, 0, 0, false, true)
  }

  def load(workspace: Path, needBlooms: Boolean = true, reuseNameIndex: Boolean = true): WorkspaceIndex = {
    val t0 = System.nanoTime()
    val gitFiles = Timings.phase("git-ls-files") { gitLsFiles(workspace) }
    val cached = Timings.phase("cache-load") { IndexPersistence.load(workspace, needBlooms) }
    val cachedMap = cached.getOrElse(Map.empty)
    val result = mutable.ListBuffer.empty[IndexedFile]
    val toParse = mutable.ListBuffer.empty[GitFile]
    var skippedCount = 0
    // No cache = empty map: every file misses the OID compare and gets parsed.
    Timings.phase("oid-compare") {
      gitFiles.foreach { gf =>
        val rel = workspace.relativize(gf.path).toString
        cachedMap.get(rel) match {
          case Some(cf) if cf.oid == gf.oid =>
            result += cf
            skippedCount += 1
          case _ => toParse += gf
        }
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
    var indexedFiles = result.toList
    val indexTimeMs = (System.nanoTime() - t0) / 1_000_000
    if (toParse.nonEmpty) {
      if (!needBlooms) {
        // Restore bloom filters skipped during cache loading before saving.
        indexedFiles = indexedFiles.map { f =>
          if (f.identifierBloom.isEmpty) {
            val source = readSource(workspace.resolve(f.relativePath)).getOrElse("")
            f.copy(identifierBloom = Some(buildBloomFilterFromSource(source)))
          } else { f }
        }
      }
      Timings.phase("cache-save") { IndexPersistence.save(workspace, indexedFiles) }
    }
    WorkspaceIndex(
      workspace,
      needBlooms,
      gitFiles,
      indexedFiles,
      indexTimeMs,
      toParse.size,
      skippedCount,
      cached.isDefined,
      reuseNameIndex
    )
  }
}

final class WorkspaceIndex private (
    val workspace: Path,
    val needBlooms: Boolean,
    val gitFiles: List[GitFile],
    private val indexedFiles: List[IndexedFile],
    val indexTimeMs: Long,
    val parsedCount: Int,
    val skippedCount: Int,
    val cachedLoad: Boolean,
    private val reuseNameIndex: Boolean
) {
  val fileCount: Int = gitFiles.size
  val parseFailedFiles: List[String] = indexedFiles.collect { case f if f.parseFailed => f.relativePath }
  val parseFailures: Int = parseFailedFiles.size

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
      allSymbols
        .filter(_.packageName.nonEmpty)
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

  def findDefinition(name: String): List[SymbolInfo] = {
    if (name.contains(".")) {
      // Qualified resolution can recurse through owners; keep its reusable indexes.
      val qResult = symbolsByQName.getOrElse(name.toLowerCase, Nil)
      if (qResult.nonEmpty) qResult
      else {
        // Partial qualification: "cache.Cache" matches "coursier.cache.Cache"
        val lastDot = name.lastIndexOf('.')
        val simpleName = name.substring(lastDot + 1)
        val pkgSuffix = name.substring(0, lastDot).toLowerCase
        val pkgResult = symbolsByName
          .getOrElse(simpleName.toLowerCase, Nil)
          .filter(_.packageName.toLowerCase.endsWith(pkgSuffix))
        if (pkgResult.nonEmpty) pkgResult
        else {
          // Owner-qualified: "Outer.Inner" — find owner type, then filter by same file
          val ownerName = name.substring(0, lastDot)
          val ownerDefs = findDefinition(ownerName).filter(s =>
            s.kind == SymbolKind.Class || s.kind == SymbolKind.Trait ||
              s.kind == SymbolKind.Object || s.kind == SymbolKind.Enum
          )
          if (ownerDefs.isEmpty) Nil
          else {
            val ownerFiles = ownerDefs.map(_.file).toSet
            symbolsByName
              .getOrElse(simpleName.toLowerCase, Nil)
              .filter(s => ownerFiles.contains(s.file))
          }
        }
      }
    } else symbolsNamed(name)
  }

  /** Isolated lookups avoid building a complete map; batches and composite commands reuse it. */
  private[scalex] def symbolsNamed(name: String): List[SymbolInfo] = {
    val lower = name.toLowerCase
    if (reuseNameIndex) { symbolsByName.getOrElse(lower, Nil) }
    else { allSymbols.filter(_.name.toLowerCase == lower) }
  }

  def findImplementations(name: String): List[SymbolInfo] = {
    // A qualified name must resolve before its simple name is looked up
    if (name.contains(".") && findDefinition(name).isEmpty) Nil
    else {
      val simpleName = name.substring(name.lastIndexOf('.') + 1).toLowerCase
      (parentIndex.getOrElse(simpleName, Nil) ++ typeParamParentIndex.getOrElse(simpleName, Nil))
        .distinctBy(s => (name = s.name, file = s.file, line = s.line))
    }
  }

  def findAnnotated(annotation: String): List[SymbolInfo] =
    annotationIndex.getOrElse(annotation.toLowerCase, Nil)

  def grepFiles(
      pattern: String,
      noTests: Boolean,
      pathFilter: Option[String],
      excludePath: Option[String] = None,
      timeoutMs: Long = defaultTimeoutMs
  ): (results: List[Reference], timedOut: Boolean) =
    compileRegex(pattern) match {
      case None        => (Nil, false)
      case Some(regex) =>
        val keep = pathPredicate(noTests, pathFilter, excludePath, workspace)
        val candidates = gitFiles.filter(gf => keep(gf.path))
        val scan = DeadlineScan(timeoutMs)
        scan.scanParallel(candidates)(_.path) { (_, path, lines) =>
          scan.forEachLine(lines) { (line, lineNum) =>
            if (regex.matcher(line).find())
              scan.emit(Reference(path, lineNum, line.trim))
          }
        }
        scan.reportUnreadable("grep")
        (scan.results.sortBy(r => (path = workspace.relativize(r.file).toString, line = r.line)), scan.timedOut)
    }

  private def compileRegex(pattern: String): Option[Pattern] =
    try Some(Pattern.compile(pattern))
    catch {
      case e: PatternSyntaxException =>
        Console.err.println(s"Invalid regex: ${e.getMessage}")
        None
    }

  def search(query: String): List[SymbolInfo] = {
    val byTier = bucketByTier(distinctSymbols, query.toLowerCase)(_.name)
    def tier(t: MatchTier) = byTier.getOrElse(t, Nil)
    def searchRank(s: SymbolInfo): (kindRank: Int, testRank: Int, stdlibRank: Int, importRank: Int, pathLen: Int) = {
      val kindRank = s.kind match {
        case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Enum => 0
        case SymbolKind.Object                                     => 1
        case SymbolKind.Def | SymbolKind.Val | SymbolKind.Type     => 2
        case _                                                     => 3
      }
      val testRank = if (isTestFile(s.file, workspace)) 1 else 0
      val stdlibRank = stdlibPkgRank(s.packageName.toLowerCase)
      // Symbols in heavily-imported types rank higher (lower importRank = better)
      val importRank = -symbolImportRank.getOrElse(s.name.toLowerCase, 0)
      val pathLen = s.file.toString.length
      (kindRank, testRank, stdlibRank, importRank, pathLen)
    }
    List(MatchTier.Exact, MatchTier.Prefix, MatchTier.Contains, MatchTier.ReverseContains)
      .flatMap(t => tier(t).sortBy(searchRank)) ++ tier(MatchTier.CamelCase).sortBy(_.name.length)
  }

  def fileSymbols(path: String): List[SymbolInfo] = {
    val resolved =
      if (Path.of(path).isAbsolute) Path.of(path)
      else workspace.resolve(path)
    filesByPath.getOrElse(resolved, Nil)
  }

  def searchFiles(query: String): List[String] = {
    def fileName(f: IndexedFile): String =
      f.relativePath.substring(f.relativePath.lastIndexOf('/') + 1).stripSuffix(".scala").stripSuffix(".java")
    val byTier = bucketByTier(indexedFiles, query.toLowerCase)(fileName)
    def tier(t: MatchTier) = byTier.getOrElse(t, Nil).map(_.relativePath)
    // ReverseContains is not a useful tier for filenames
    tier(MatchTier.Exact) ++ tier(MatchTier.Prefix) ++ tier(MatchTier.Contains) ++
      tier(MatchTier.CamelCase).sortBy(_.length)
  }

  private val defaultTimeoutMs = 20_000L

  def findReferences(
      name: String,
      timeoutMs: Long = defaultTimeoutMs,
      strict: Boolean = false
  ): (results: List[Reference], timedOut: Boolean) = {
    val wordMatch: (String, String) => Boolean = if (strict) containsWordStrict else containsWord
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
    val seen = ConcurrentHashMap.newKeySet[String]()
    Timings.phase("text-search") {
      scan.scanParallel(allCandidates)(f => workspace.resolve(f.relativePath)) { (idxFile, path, lines) =>
        val aliasName = fileAliasMap.get(idxFile.relativePath)
        scan.forEachLine(lines) { (line, lineNum) =>
          val key = s"${idxFile.relativePath}:$lineNum"
          if (wordMatch(line, name) && seen.add(key))
            scan.emit(Reference(path, lineNum, line.trim))
          else
            aliasName match {
              case Some(alias) if wordMatch(line, alias) && seen.add(key) =>
                scan.emit(Reference(path, lineNum, line.trim, Some(s"via alias $alias")))
              case _ =>
            }
        }
      }
    }
    scan.reportUnreadable("refs")
    (scan.results, scan.timedOut)
  }

  def categorizeReferences(
      name: String,
      strict: Boolean = false
  ): (grouped: Map[RefCategory, List[Reference]], timedOut: Boolean) = {
    val (refs, timedOut) = findReferences(name, strict = strict)
    lazy val definition = Pattern.compile("""^\s*(trait|class|object|enum|given|type|def|val|var)\s+.*""")
    lazy val givenName = Pattern.compile(s""".*given\\s+\\w*$name.*""")
    lazy val inheritance = Pattern.compile(""".*\b(extends|with)\b.*""")
    lazy val comment = Pattern.compile("""^\s*(//|/\*|\*).*""")
    lazy val typed = Pattern.compile(s""".*:\\s*$name.*""")
    lazy val applied = Pattern.compile(s""".*\\[$name.*""")
    val grouped = refs.groupBy { r =>
      val line = r.contextLine
      if (
        definition.matcher(line).matches() && containsWord(line, name) &&
        (line.contains(s"trait $name") || line.contains(s"class $name") || line.contains(s"object $name") ||
          line.contains(s"enum $name") || line.contains(s"type $name") ||
          givenName.matcher(line).matches())
      )
        RefCategory.Definition
      else if (inheritance.matcher(line).matches() && containsWord(line, name))
        RefCategory.ExtendedBy
      else if (line.trim.startsWith("import "))
        RefCategory.ImportedBy
      else if (comment.matcher(line).matches())
        RefCategory.Comment
      else if (typed.matcher(line).matches() || applied.matcher(line).matches())
        RefCategory.UsedAsType
      else
        RefCategory.Usage
    }
    (grouped, timedOut)
  }

  def findImports(
      name: String,
      timeoutMs: Long = defaultTimeoutMs,
      strict: Boolean = false
  ): (results: List[Reference], timedOut: Boolean) = {
    val wordMatch: (String, String) => Boolean = if (strict) containsWordStrict else containsWord
    val candidates = indexedFiles.filter(f => f.identifierBloom.forall(_.mightContain(name)))
    val scan = DeadlineScan(timeoutMs)
    val resultPaths = ConcurrentHashMap.newKeySet[String]()
    scan.scanParallel(candidates)(f => workspace.resolve(f.relativePath)) { (idxFile, path, lines) =>
      scan.forEachLine(lines) { (line, lineNum) =>
        if (line.trim.startsWith("import ") && wordMatch(line, name)) {
          scan.emit(Reference(path, lineNum, line.trim))
          resultPaths.add(s"${idxFile.relativePath}:$lineNum")
        }
      }
    }

    // Also find wildcard imports that resolve to a package containing the target symbol
    val targetPkgs = symbolsNamed(name).map(_.packageName).toSet
    if (targetPkgs.nonEmpty)
      for (idxFile <- indexedFiles if scan.inTime)
        for (imp <- idxFile.imports)
          if (wildcardImportPkg(imp).exists(targetPkgs.contains)) {
            val path = workspace.resolve(idxFile.relativePath)
            scan.forEachLine(scan.readLines(path)) { (line, lineNum) =>
              if (line.trim == imp.trim) {
                val key = s"${idxFile.relativePath}:$lineNum"
                if (!resultPaths.contains(key)) {
                  scan.emit(Reference(path, lineNum, line.trim))
                  resultPaths.add(key)
                }
              }
            }
          }

    scan.reportUnreadable("imports")
    (scan.results, scan.timedOut)
  }

  private def filePackage(idxFile: IndexedFile): String =
    idxFile.symbols.headOption.map(_.packageName).getOrElse("")

  def filePackageByPath(relPath: String): Option[String] =
    indexedByPath.get(relPath).map(filePackage)

  /** Import lines recorded at index time for the file at `path` (absolute). Lets callers avoid re-parsing source just
    * to read imports.
    */
  def fileImports(path: Path): List[String] =
    indexedByPath.get(workspace.relativize(path).toString).map(_.imports).getOrElse(Nil)

  def resolveConfidence(ref: Reference, targetName: String, targetPackages: Set[String]): Confidence = {
    val relPath = workspace.relativize(ref.file).toString
    indexedByPath.get(relPath) match {
      case None          => Confidence.Low
      case Some(idxFile) =>
        val filePkg = filePackage(idxFile)
        if (targetPackages.contains(filePkg)) Confidence.High
        else {
          val imports = idxFile.imports
          val hasExplicit = imports.exists { imp =>
            imp.contains(s".$targetName") || imp.contains(s"{$targetName") ||
            imp.contains(s", $targetName") || imp.contains(s"$targetName,")
          }
          val hasAliasMatch = idxFile.aliases.exists { (orig, alias) =>
            alias == targetName || orig == targetName
          }
          if (hasExplicit || hasAliasMatch) Confidence.High
          else {
            val hasWildcard = imports.exists(imp => wildcardImportPkg(imp).exists(targetPackages.contains))
            if (hasWildcard) Confidence.Medium
            else Confidence.Low
          }
        }
    }
  }

  /** True if `word` occurs in `line` with no word character (per `isWordChar`) directly before or after the occurrence.
    */
  private def containsWordWith(line: String, word: String, isWordChar: Char => Boolean): Boolean = {
    var i = line.indexOf(word)
    var found = false
    while (!found && i >= 0) {
      val before = i == 0 || !isWordChar(line(i - 1))
      val after = i + word.length >= line.length || !isWordChar(line(i + word.length))
      if (before && after) found = true
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

  def findApiSurface(
      targetPkg: String,
      filterToPkg: Option[String] = None
  ): List[(symbol: SymbolInfo, importerCount: Int)] =
    Timings.phase("api-surface") {
      val targetSymNames = packageToSymbols.getOrElse(targetPkg, Set.empty)
      if (targetSymNames.isEmpty) Nil
      else {

        // Count external importers per symbol name
        val importerCounts = mutable.HashMap.empty[String, mutable.HashSet[String]]
        targetSymNames.foreach(n => importerCounts(n) = mutable.HashSet.empty)

        indexedFiles.foreach { idxFile =>
          val filePkg = filePackage(idxFile)
          if (filePkg != targetPkg) {
            // If --used-by filter is set, only count importers from matching packages
            val matchesFilter = filterToPkg match {
              case Some(fpkg) => filePkg.toLowerCase.contains(fpkg.toLowerCase) || filePkg.equalsIgnoreCase(fpkg)
              case None       => true
            }
            if (matchesFilter)
              idxFile.imports.foreach { imp =>
                parseImportTarget(imp).foreach { (pkg, names, isWildcard) =>
                  if (pkg == targetPkg) {
                    if (isWildcard)
                      // Credit all symbols in the package
                      targetSymNames.foreach { symName =>
                        importerCounts(symName).add(idxFile.relativePath)
                      }
                    else
                      names.foreach { name =>
                        if (importerCounts.contains(name))
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
}
