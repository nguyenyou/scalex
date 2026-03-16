import scala.collection.mutable
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

// ── Command helpers ─────────────────────────────────────────────────────────

def hasRegexHint(pattern: String): Boolean =
  pattern.contains("\\|") || pattern.contains("\\(") || pattern.contains("\\)")

def fixPosixRegex(pattern: String): (pattern: String, wasFixed: Boolean) =
  val fixed = pattern.replace("\\|", "|").replace("\\(", "(").replace("\\)", ")")
  (fixed, fixed != pattern)

// ── Suggestions for not-found ────────────────────────────────────────────────

def suggestMatches(query: String, idx: WorkspaceIndex, limit: Int = 5): List[String] =
  val results = idx.search(query)
  results.take(limit).map { s =>
    s"${s.kind.toString.toLowerCase} ${s.name} (${s.packageName})"
  }

def mkNotFoundWithSuggestions(symbol: String, ctx: CommandContext, cmd: String): NotFoundHint =
  val suggestions = suggestMatches(symbol, ctx.idx)
  NotFoundHint(symbol, ctx.idx.fileCount, ctx.idx.parseFailures, cmd, ctx.batchMode,
    symbol.contains("/") || symbol.startsWith("."), suggestions)

// ── Shared filters ──────────────────────────────────────────────────────────

def filterSymbols(symbols: List[SymbolInfo], ctx: CommandContext): List[SymbolInfo] =
  var r = symbols
  ctx.kindFilter.foreach { k =>
    val kk = k.toLowerCase
    r = r.filter(_.kind.toString.toLowerCase == kk)
  }
  if ctx.noTests then r = r.filter(s => !isTestFile(s.file, ctx.workspace))
  ctx.pathFilter.foreach { p => r = r.filter(s => matchesPath(s.file, p, ctx.workspace)) }
  r

def filterRefs(refs: List[Reference], ctx: CommandContext): List[Reference] =
  var r = refs
  if ctx.noTests then r = r.filter(ref => !isTestFile(ref.file, ctx.workspace))
  ctx.pathFilter.foreach { p => r = r.filter(ref => matchesPath(ref.file, p, ctx.workspace)) }
  r

// ── Command implementations ─────────────────────────────────────────────────

def cmdIndex(args: List[String], ctx: CommandContext): CmdResult =
  val byKind = ctx.idx.symbols.groupBy(_.kind).toList.sortBy(-_._2.size).map((k, v) => (kind = k, count = v.size))
  CmdResult.IndexStats(
    fileCount = ctx.idx.fileCount,
    symbolCount = ctx.idx.symbols.size,
    packageCount = ctx.idx.packages.size,
    symbolsByKind = byKind,
    indexTimeMs = ctx.idx.indexTimeMs,
    cachedLoad = ctx.idx.cachedLoad,
    parsedCount = ctx.idx.parsedCount,
    skippedCount = ctx.idx.skippedCount,
    parseFailures = ctx.idx.parseFailures,
    parseFailedFiles = ctx.idx.parseFailedFiles
  )

def cmdSearch(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex search <query>")
    case Some(query) =>
      var results = ctx.idx.search(query)
      ctx.searchMode.foreach {
        case "exact" =>
          val lower = query.toLowerCase
          results = results.filter(_.name.toLowerCase == lower)
        case "prefix" =>
          val lower = query.toLowerCase
          results = results.filter(_.name.toLowerCase.startsWith(lower))
        case _ => ()
      }
      if ctx.definitionsOnly then
        val defKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
        results = results.filter(s => defKinds.contains(s.kind))
      results = filterSymbols(results, ctx)
      if results.isEmpty then
        CmdResult.NotFound(
          s"""Found 0 symbols matching "$query"""",
          NotFoundHint(query, ctx.idx.fileCount, ctx.idx.parseFailures, "search", ctx.batchMode, query.contains("/") || query.startsWith(".")))
      else
        CmdResult.SymbolList(
          header = s"""Found ${results.size} symbols matching "$query":""",
          symbols = results,
          total = results.size)

def cmdDef(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex def <symbol>")
    case Some(symbol) =>
      var results = filterSymbols(ctx.idx.findDefinition(symbol), ctx)
      // Rank: class/trait/object/enum > type/given > def/val/var, non-test > test, shorter path first
      results = results.sortBy { s =>
        val kindRank = s.kind match
          case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Object | SymbolKind.Enum => 0
          case SymbolKind.Type | SymbolKind.Given => 1
          case _ => 2
        val testRank = if isTestFile(s.file, ctx.workspace) then 1 else 0
        val pathLen = ctx.workspace.relativize(s.file).toString.length
        (kindRank, testRank, pathLen)
      }
      if results.isEmpty then
        CmdResult.NotFound(
          s"""Definition of "$symbol": not found""",
          mkNotFoundWithSuggestions(symbol, ctx, "def"))
      else
        CmdResult.SymbolList(
          header = s"""Definition of "$symbol":""",
          symbols = results,
          total = results.size)

def cmdImpl(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex impl <trait>")
    case Some(symbol) =>
      val results = filterSymbols(ctx.idx.findImplementations(symbol), ctx)
      if results.isEmpty then
        CmdResult.NotFound(
          s"""No implementations of "$symbol" found""",
          mkNotFoundWithSuggestions(symbol, ctx, "impl"))
      else
        CmdResult.SymbolList(
          header = s"""Implementations of "$symbol" — ${results.size} found:""",
          symbols = results,
          total = results.size)

def cmdRefs(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex refs <symbol>")
    case Some(symbol) =>
      val targetPkgs = ctx.idx.symbolsByName.getOrElse(symbol.toLowerCase, Nil).map(_.packageName).toSet
      def filterByCategory(grouped: Map[RefCategory, List[Reference]]): (filtered: Map[RefCategory, List[Reference]], stderrHint: Option[String]) =
        ctx.categoryFilter match
          case Some(catName) =>
            val validCats = RefCategory.values.map(_.toString.toLowerCase).toSet
            val lower = catName.toLowerCase
            if !validCats.contains(lower) then
              (Map.empty, Some(s"Unknown category: $catName. Valid: ${RefCategory.values.map(_.toString).mkString(", ")}"))
            else
              (grouped.filter((cat, _) => cat.toString.toLowerCase == lower), None)
          case None => (grouped, None)
      if ctx.categorize then
        val rawGrouped = ctx.idx.categorizeReferences(symbol).map((cat, refs) => (cat, filterRefs(refs, ctx)))
        val (grouped, stderrHint) = filterByCategory(rawGrouped)
        CmdResult.CategorizedRefs(symbol, grouped, targetPkgs, ctx.idx.timedOut, stderrHint)
      else
        val results = filterRefs(ctx.idx.findReferences(symbol), ctx)
        CmdResult.FlatRefs(symbol, results, targetPkgs, ctx.idx.timedOut)

def cmdImports(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex imports <symbol>")
    case Some(symbol) =>
      val results = filterRefs(ctx.idx.findImports(symbol), ctx)
      if results.isEmpty then
        CmdResult.NotFound(
          s"""No imports of "$symbol" found""",
          mkNotFoundWithSuggestions(symbol, ctx, "imports"))
      else
        val suffix = if ctx.idx.timedOut then " (timed out — partial results)" else ""
        CmdResult.RefList(
          header = s"""Imports of "$symbol" — ${results.size} found:$suffix""",
          refs = results,
          timedOut = ctx.idx.timedOut,
          useContext = false)

def cmdSymbols(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex symbols <file>")
    case Some(file) =>
      val results = ctx.idx.fileSymbols(file)
      CmdResult.SymbolList(
        header = s"Symbols in $file:",
        symbols = results,
        total = results.size,
        emptyMessage = s"No symbols found in $file",
        truncate = false)

def cmdFile(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex file <query>")
    case Some(query) =>
      val results = ctx.idx.searchFiles(query)
      CmdResult.StringList(
        header = s"""Found ${results.size} files matching "$query":""",
        items = results,
        total = results.size,
        emptyMessage = s"""Found 0 files matching "$query"\n  Hint: scalex indexes ${ctx.idx.fileCount} git-tracked .scala files.""")

def cmdPackages(args: List[String], ctx: CommandContext): CmdResult =
  CmdResult.Packages(ctx.idx.packages.toList.sorted)

def cmdAnnotated(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex annotated <annotation>")
    case Some(query) =>
      val annot = query.stripPrefix("@")
      val results = filterSymbols(ctx.idx.findAnnotated(annot), ctx)
      CmdResult.SymbolList(
        header = s"Symbols annotated with @$annot — ${results.size} found:",
        symbols = results,
        total = results.size,
        emptyMessage = s"No symbols with @$annot annotation found")

def cmdGrep(args: List[String], ctx: CommandContext): CmdResult =
  val patternOpt = if ctx.grepPatterns.nonEmpty then Some(ctx.grepPatterns.mkString("|"))
                   else args.headOption
  patternOpt match
    case None => CmdResult.UsageError("Usage: scalex grep <pattern>")
    case Some(rawPattern) =>
      val (pattern, wasFixed) = fixPosixRegex(rawPattern)
      val stderrHint = if wasFixed then Some(s"""  Note: auto-corrected POSIX regex to Java regex: "$rawPattern" → "$pattern"""") else None
      val hint = if wasFixed then Some(s""","corrected":"$pattern"""") else None
      val (results, grepTimedOut) = ctx.idx.grepFiles(pattern, ctx.noTests, ctx.pathFilter)
      if ctx.countOnly then
        val fileCount = results.map(_.file).distinct.size
        CmdResult.GrepCount(results.size, fileCount, grepTimedOut, hint, stderrHint)
      else
        val suffix = if grepTimedOut then " (timed out — partial results)" else ""
        CmdResult.RefList(
          header = s"""Matches for "$pattern" — ${results.size} found:$suffix""",
          refs = results,
          timedOut = grepTimedOut,
          hint = hint,
          emptyMessage = s"""No matches for "$pattern"$suffix""",
          stderrHint = stderrHint)

def cmdMembers(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex members <Symbol>")
    case Some(symbol) =>
      val typeKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
      val defs = filterSymbols(ctx.idx.findDefinition(symbol).filter(s => typeKinds.contains(s.kind)), ctx)

      // Collect inherited members if --inherited is set
      def collectInherited(sym: SymbolInfo): List[(parentName: String, parentFile: Option[Path], parentPackage: String, members: List[MemberInfo])] = {
        if !ctx.inherited then return Nil
        val visited = mutable.HashSet.empty[String]
        visited += sym.name.toLowerCase
        val ownMembers = extractMembers(sym.file, sym.name).map(m => (m.name, m.kind)).toSet
        val result = mutable.ListBuffer.empty[(String, Option[Path], String, List[MemberInfo])]

        def walk(parentNames: List[String]): Unit = {
          parentNames.foreach { pName =>
            if !visited.contains(pName.toLowerCase) then {
              visited += pName.toLowerCase
              val parentDefs = ctx.idx.findDefinition(pName).filter(s => typeKinds.contains(s.kind))
              parentDefs.headOption.foreach { pd =>
                val parentMembers = extractMembers(pd.file, pd.name)
                val filtered = parentMembers.filterNot(m => ownMembers.contains((m.name, m.kind)))
                if filtered.nonEmpty then result += ((pd.name, Some(pd.file), pd.packageName, filtered))
                walk(pd.parents)
              }
            }
          }
        }

        walk(sym.parents)
        result.toList
      }

      if defs.isEmpty then
        CmdResult.NotFound(
          s"""No class/trait/object/enum "$symbol" found""",
          mkNotFoundWithSuggestions(symbol, ctx, "members"))
      else
        val sections = defs.map { s =>
          val members = extractMembers(s.file, symbol)
          val inherited = collectInherited(s)
          MemberSectionData(
            file = s.file,
            ownerKind = s.kind,
            packageName = s.packageName,
            line = s.line,
            ownMembers = members,
            inherited = inherited
          )
        }
        CmdResult.MemberSections(symbol, sections)

def cmdDoc(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex doc <Symbol>")
    case Some(symbol) =>
      val defs = filterSymbols(ctx.idx.findDefinition(symbol), ctx)
      if defs.isEmpty then
        CmdResult.NotFound(
          s"""Definition of "$symbol": not found""",
          mkNotFoundWithSuggestions(symbol, ctx, "doc"))
      else
        val entries = defs.map { s =>
          DocEntryData(s, extractScaladoc(s.file, s.line))
        }
        CmdResult.DocEntries(symbol, entries)

def cmdOverview(args: List[String], ctx: CommandContext): CmdResult =
  val allSymbols = if ctx.noTests then ctx.idx.symbols.filter(s => !isTestFile(s.file, ctx.workspace))
                   else ctx.idx.symbols
  val symbolsByKind = allSymbols.groupBy(_.kind).toList.sortBy(-_._2.size)
  val topPackages = allSymbols.groupBy(_.packageName).filter(_._1.nonEmpty)
    .map((pkg, syms) => (pkg, syms)).toList.sortBy(-_._2.size).take(ctx.limit)
  val mostExtended = ctx.idx.parentIndex.toList
    .filter((name, _) => ctx.idx.symbolsByName.contains(name))
    .map { (name, impls) =>
      if ctx.noTests then (name, impls.filter(s => !isTestFile(s.file, ctx.workspace)))
      else (name, impls)
    }
    .filter(_._2.nonEmpty)
    .sortBy(-_._2.size).take(ctx.limit)

  val effectiveArch = ctx.architecture || ctx.focusPackage.isDefined

  // Architecture: compute package dependency graph from imports
  val archPkgDeps: Map[String, Set[String]] = if effectiveArch then {
    val deps = mutable.HashMap.empty[String, mutable.HashSet[String]]
    allSymbols.groupBy(_.file).foreach { (file, syms) =>
      val filePkg = syms.headOption.map(_.packageName).getOrElse("")
      if filePkg.nonEmpty then {
        parseFile(file).foreach { tree =>
          val (imports, _) = extractImports(tree)
          imports.foreach { imp =>
            val trimmed = imp.trim.stripPrefix("import ")
            // Extract package from import: "com.example.Foo" → "com.example"
            val lastDot = trimmed.lastIndexOf('.')
            if lastDot > 0 then {
              val importPkg = trimmed.substring(0, lastDot)
              // Only track cross-package dependencies
              if importPkg != filePkg && ctx.idx.packages.contains(importPkg) then {
                deps.getOrElseUpdate(filePkg, mutable.HashSet.empty) += importPkg
              }
            }
          }
        }
      }
    }
    deps.map((k, v) => k -> v.toSet).toMap
  } else Map.empty

  // Focus package: filter dependency graph to direct deps/dependents
  val filteredPkgDeps = ctx.focusPackage match
    case Some(fpkg) =>
      val directDeps = archPkgDeps.getOrElse(fpkg, Set.empty)
      val dependents = archPkgDeps.filter((_, deps) => deps.contains(fpkg)).keySet
      val relevant = Set(fpkg) ++ directDeps ++ dependents
      archPkgDeps.filter((pkg, _) => relevant.contains(pkg))
        .map((pkg, deps) => pkg -> deps.filter(relevant.contains))
    case None => archPkgDeps

  // Architecture: hub types (most-referenced + most-extended)
  val hubTypes: List[(name: String, score: Int)] = if effectiveArch then {
    val refCounts = mutable.HashMap.empty[String, Int]
    ctx.idx.parentIndex.foreach { (name, impls) =>
      if ctx.idx.symbolsByName.contains(name) then
        val filteredImpls = if ctx.noTests then impls.filter(s => !isTestFile(s.file, ctx.workspace)) else impls
        refCounts(name) = refCounts.getOrElse(name, 0) + filteredImpls.size
    }
    refCounts.filter(_._2 > 0).toList.sortBy(-_._2).take(ctx.limit)
  } else Nil

  CmdResult.Overview(OverviewData(
    fileCount = if ctx.noTests then allSymbols.map(_.file).distinct.size else ctx.idx.fileCount,
    symbolCount = allSymbols.size,
    packageCount = if ctx.noTests then allSymbols.map(_.packageName).filter(_.nonEmpty).distinct.size else ctx.idx.packages.size,
    symbolsByKind = symbolsByKind.map((k, syms) => (kind = k, count = syms.size)),
    topPackages = topPackages.map((p, syms) => (pkg = p, count = syms.size)),
    mostExtended = mostExtended.map((n, impls) => (name = n, count = impls.size)),
    pkgDeps = filteredPkgDeps,
    hubTypes = hubTypes,
    hasArchitecture = effectiveArch,
    focusPackage = ctx.focusPackage
  ))

def cmdBody(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex body <symbol> [--in <owner>]")
    case Some(symbol) =>
      // Find files containing the symbol
      var defs = ctx.idx.findDefinition(symbol)
      if ctx.noTests then defs = defs.filter(s => !isTestFile(s.file, ctx.workspace))
      ctx.pathFilter.foreach { p => defs = defs.filter(s => matchesPath(s.file, p, ctx.workspace)) }
      // Also look in type definitions for member bodies
      val typeKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
      val filesToSearch = if defs.nonEmpty then {
        defs.map(_.file).distinct
      } else {
        // If not found directly, search all files for member bodies
        ctx.inOwner match
          case Some(owner) =>
            ctx.idx.findDefinition(owner).filter(s => typeKinds.contains(s.kind)).map(_.file).distinct
          case None =>
            ctx.idx.symbols.filter(s => typeKinds.contains(s.kind)).map(_.file).distinct
      }
      // Collect (file, body) pairs
      val blocks = filesToSearch.flatMap { f =>
        extractBody(f, symbol, ctx.inOwner).map(b => (file = f, body = b))
      }
      if blocks.isEmpty then
        CmdResult.NotFound(
          s"""No body found for "$symbol"""",
          mkNotFoundWithSuggestions(symbol, ctx, "body"))
      else
        CmdResult.SourceBlocks(symbol, blocks)

def cmdTests(args: List[String], ctx: CommandContext): CmdResult =
  val nameFilter = args.headOption
  var filesToScan = ctx.idx.gitFiles.map(_.path).filter(f => isTestFile(f, ctx.workspace))
  ctx.pathFilter.foreach { p => filesToScan = filesToScan.filter(f => matchesPath(f, p, ctx.workspace)) }
  val allSuites = filesToScan.flatMap(extractTests).map { suite =>
    nameFilter match
      case Some(pattern) =>
        val lower = pattern.toLowerCase
        val filtered = suite.tests.filter(_.name.toLowerCase.contains(lower))
        suite.copy(tests = filtered)
      case None => suite
  }.filter(_.tests.nonEmpty)
  val showBody = nameFilter.isDefined
  val suiteResults = allSuites.map { suite =>
    val tests = suite.tests.map { tc =>
      val body = if showBody || ctx.verbose then
        extractBody(suite.file, tc.name, Some(suite.name)).headOption
      else None
      TestCaseResult(tc.name, tc.line, body)
    }
    TestSuiteResult(suite.name, suite.file, suite.line, tests)
  }
  val emptyMsg = if nameFilter.isDefined then s"""No tests matching "${nameFilter.get}"""" else "No test suites found"
  CmdResult.TestSuites(suiteResults, showBody, emptyMsg)

def cmdCoverage(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex coverage <symbol>")
    case Some(symbol) =>
      val refs = ctx.idx.findReferences(symbol)
      val testRefs = refs.filter(r => isTestFile(r.file, ctx.workspace))
      val testFiles = testRefs.map(r => ctx.workspace.relativize(r.file).toString).distinct
      CmdResult.CoverageReport(symbol, refs.size, testRefs, testFiles)

def cmdHierarchy(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex hierarchy <symbol> [--up] [--down]")
    case Some(symbol) =>
      buildHierarchy(ctx.idx, symbol, ctx.goUp, ctx.goDown, ctx.workspace) match
        case None =>
          CmdResult.NotFound(
            s"""No definition of "$symbol" found""",
            mkNotFoundWithSuggestions(symbol, ctx, "hierarchy"))
        case Some(tree) =>
          CmdResult.HierarchyResult(symbol, tree)

def cmdOverrides(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex overrides <method> [--of <trait>]")
    case Some(methodName) =>
      val results = findOverrides(ctx.idx, methodName, ctx.ofTrait, ctx.limit)
      if results.isEmpty then
        val ofStr = ctx.ofTrait.map(t => s" of $t").getOrElse("")
        CmdResult.NotFound(
          s"""No overrides of "$methodName"$ofStr found""",
          mkNotFoundWithSuggestions(methodName, ctx, "overrides"))
      else
        val ofStr = ctx.ofTrait.map(t => s" (in implementations of $t)").getOrElse("")
        CmdResult.OverrideList(
          header = s"Overrides of $methodName$ofStr — ${results.size} found:",
          results = results)

def cmdExplain(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex explain <symbol>")
    case Some(symbol) =>
      var defs = ctx.idx.findDefinition(symbol)
      if ctx.noTests then defs = defs.filter(s => !isTestFile(s.file, ctx.workspace))
      ctx.pathFilter.foreach { p => defs = defs.filter(s => matchesPath(s.file, p, ctx.workspace)) }
      // Rank: class/trait/object/enum first (same as def command)
      defs = defs.sortBy { s =>
        val kindRank = s.kind match
          case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Object | SymbolKind.Enum => 0
          case SymbolKind.Type | SymbolKind.Given => 1
          case _ => 2
        val testRank = if isTestFile(s.file, ctx.workspace) then 1 else 0
        val pathLen = ctx.workspace.relativize(s.file).toString.length
        (kindRank, testRank, pathLen)
      }
      if defs.isEmpty then
        CmdResult.NotFound(
          s"""No definition of "$symbol" found""",
          mkNotFoundWithSuggestions(symbol, ctx, "explain"))
      else
        val sym = defs.head
        // Scaladoc
        val doc = extractScaladoc(sym.file, sym.line)
        // Members (for types)
        val typeKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
        val members = if typeKinds.contains(sym.kind) then extractMembers(sym.file, symbol).take(10) else Nil
        // Implementations
        val impls = ctx.idx.findImplementations(symbol).take(ctx.implLimit)
        // Import count
        val importCount = ctx.idx.findImports(symbol, timeoutMs = 3000).size
        CmdResult.Explanation(sym, doc, members, impls, importCount)

def cmdDeps(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex deps <symbol>")
    case Some(symbol) =>
      val (importDeps, bodyDeps) = extractDeps(ctx.idx, symbol, ctx.workspace)
      if importDeps.isEmpty && bodyDeps.isEmpty then
        CmdResult.NotFound(
          s"""No dependencies found for "$symbol"""",
          mkNotFoundWithSuggestions(symbol, ctx, "deps"))
      else
        CmdResult.Dependencies(symbol, importDeps, bodyDeps)

def cmdContext(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex context <file:line>")
    case Some(arg) =>
      val parts = arg.split(":")
      if parts.length < 2 then
        CmdResult.UsageError("Usage: scalex context <file:line> (e.g. src/Main.scala:42)")
      else
        val filePath = parts.dropRight(1).mkString(":")
        val lineNum = parts.last.toIntOption
        lineNum match
          case None => CmdResult.UsageError(s"Invalid line number: ${parts.last}")
          case Some(line) =>
            val resolved = if Path.of(filePath).isAbsolute then Path.of(filePath) else ctx.workspace.resolve(filePath)
            val scopes = extractScopes(resolved, line)
            CmdResult.Scopes(resolved, line, scopes)

def cmdDiff(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex diff <git-ref> (e.g. scalex diff HEAD~1)")
    case Some(ref) =>
      val changedFiles = runGitDiff(ctx.workspace, ref)
      val added = mutable.ListBuffer.empty[DiffSymbol]
      val removed = mutable.ListBuffer.empty[DiffSymbol]
      val modified = mutable.ListBuffer.empty[(before: DiffSymbol, after: DiffSymbol)]

      changedFiles.take(ctx.limit * 5).foreach { relPath =>
        val currentPath = ctx.workspace.resolve(relPath)
        val currentSource = try Some(Files.readString(currentPath)) catch { case _: Exception => None }
        val oldSource = gitShowFile(ctx.workspace, ref, relPath)

        val currentSyms = currentSource.map(s => extractSymbolsFromSource(s, relPath)).getOrElse(Nil)
        val oldSyms = oldSource.map(s => extractSymbolsFromSource(s, relPath)).getOrElse(Nil)

        val currentByKey = currentSyms.map(s => (s.name, s.kind) -> s).toMap
        val oldByKey = oldSyms.map(s => (s.name, s.kind) -> s).toMap

        // Added: in current but not in old
        currentByKey.foreach { case (key, sym) =>
          if !oldByKey.contains(key) then added += sym
        }
        // Removed: in old but not in current
        oldByKey.foreach { case (key, sym) =>
          if !currentByKey.contains(key) then removed += sym
        }
        // Modified: in both but signature changed
        currentByKey.foreach { case (key, cSym) =>
          oldByKey.get(key).foreach { oSym =>
            if cSym.signature != oSym.signature || cSym.line != oSym.line then
              modified += ((before = oSym, after = cSym))
          }
        }
      }

      CmdResult.SymbolDiff(ref, changedFiles.size, added.toList, removed.toList, modified.toList)

def cmdAstPattern(args: List[String], ctx: CommandContext): CmdResult =
  val results = astPatternSearch(ctx.idx, ctx.workspace, ctx.hasMethodFilter, ctx.extendsFilter, ctx.bodyContainsFilter, ctx.noTests, ctx.pathFilter, ctx.limit)
  val filters = List(
    ctx.hasMethodFilter.map(m => s"has-method=$m"),
    ctx.extendsFilter.map(e => s"extends=$e"),
    ctx.bodyContainsFilter.map(b => s"""body-contains="$b"""")
  ).flatten.mkString(", ")
  CmdResult.AstMatches(filters, results)

def cmdPackage(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex package <pkg>")
    case Some(pkg) =>
      val lower = pkg.toLowerCase
      // Match: exact → suffix (.pkg) → substring
      val matched = ctx.idx.packages.find(_.equalsIgnoreCase(pkg))
        .orElse(ctx.idx.packages.find(_.toLowerCase.endsWith("." + lower)))
        .orElse(ctx.idx.packages.find(_.toLowerCase.contains(lower)))
      matched match
        case None =>
          val pkgSuggestions = ctx.idx.packages.filter(_.toLowerCase.contains(lower)).toList.sorted.take(5)
          CmdResult.NotFound(
            s"""Package "$pkg" not found""",
            NotFoundHint(pkg, ctx.idx.fileCount, ctx.idx.parseFailures, "package", ctx.batchMode, false, pkgSuggestions))
        case Some(resolvedPkg) =>
          val symbols = filterSymbols(ctx.idx.symbols.filter(_.packageName == resolvedPkg), ctx)
          CmdResult.PackageSymbols(resolvedPkg, symbols)

// ── Command dispatch ────────────────────────────────────────────────────────

val commands: Map[String, (List[String], CommandContext) => CmdResult] = Map(
  "index" -> cmdIndex, "search" -> cmdSearch, "def" -> cmdDef, "impl" -> cmdImpl,
  "refs" -> cmdRefs, "imports" -> cmdImports, "symbols" -> cmdSymbols, "file" -> cmdFile,
  "packages" -> cmdPackages, "package" -> cmdPackage, "annotated" -> cmdAnnotated, "grep" -> cmdGrep,
  "members" -> cmdMembers, "doc" -> cmdDoc, "overview" -> cmdOverview,
  "body" -> cmdBody, "tests" -> cmdTests, "coverage" -> cmdCoverage,
  "hierarchy" -> cmdHierarchy, "overrides" -> cmdOverrides, "explain" -> cmdExplain,
  "deps" -> cmdDeps, "context" -> cmdContext, "diff" -> cmdDiff, "ast-pattern" -> cmdAstPattern,
)

def runCommand(cmd: String, args: List[String], ctx: CommandContext): Unit =
  val result = commands.get(cmd) match
    case Some(handler) => handler(args, ctx)
    case None => CmdResult.UsageError(s"Unknown command: $cmd")
  render(result, ctx)
