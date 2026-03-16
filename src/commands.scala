import scala.collection.mutable
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

// ── Command helpers ─────────────────────────────────────────────────────────

def printNotFoundHint(symbol: String, idx: WorkspaceIndex, cmd: String, batchMode: Boolean = false): Unit =
  if batchMode then
    println(s"  not found (0 matches in ${idx.fileCount} files)")
  else
    if symbol.contains("/") || symbol.startsWith(".") then
      println(s"  Note: \"$symbol\" looks like a path. Did you mean: scalex $cmd -w <workspace> $symbol?")
    println(s"  Hint: scalex indexes ${idx.fileCount} git-tracked .scala files.")
    if idx.parseFailures > 0 then
      println(s"  ${idx.parseFailures} files had parse errors (run `scalex index --verbose` to list them).")
    println(s"  Fallback: use Grep, Glob, or Read tools to search manually.")

def hasRegexHint(pattern: String): Boolean =
  pattern.contains("\\|") || pattern.contains("\\(") || pattern.contains("\\)")

def fixPosixRegex(pattern: String): (pattern: String, wasFixed: Boolean) =
  val fixed = pattern.replace("\\|", "|").replace("\\(", "(").replace("\\)", ")")
  (fixed, fixed != pattern)

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

def cmdIndex(args: List[String], ctx: CommandContext): Unit =
  if ctx.jsonOutput then
    val byKind = ctx.idx.symbols.groupBy(_.kind).toList.sortBy(-_._2.size)
      .map((k, v) => s""""${k.toString.toLowerCase}":${v.size}""").mkString(",")
    println(s"""{"fileCount":${ctx.idx.fileCount},"symbolCount":${ctx.idx.symbols.size},"packageCount":${ctx.idx.packages.size},"symbolsByKind":{$byKind},"indexTimeMs":${ctx.idx.indexTimeMs},"cachedLoad":${ctx.idx.cachedLoad},"parsedCount":${ctx.idx.parsedCount},"skippedCount":${ctx.idx.skippedCount},"parseFailures":${ctx.idx.parseFailures}}""")
  else
    if ctx.idx.cachedLoad then
      println(s"Indexed ${ctx.idx.fileCount} files (${ctx.idx.skippedCount} cached, ${ctx.idx.parsedCount} parsed) in ${ctx.idx.indexTimeMs}ms")
    else
      println(s"Indexed ${ctx.idx.fileCount} files, ${ctx.idx.symbols.size} symbols in ${ctx.idx.indexTimeMs}ms")
    println(s"Packages: ${ctx.idx.packages.size}")
    println()
    println("Symbols by kind:")
    ctx.idx.symbols.groupBy(_.kind).toList.sortBy(-_._2.size).foreach { (kind, syms) =>
      println(s"  ${kind.toString.padTo(10, ' ')} ${syms.size}")
    }
    if ctx.idx.parseFailures > 0 then
      println(s"\n${ctx.idx.parseFailures} files had parse errors:")
      if ctx.verbose then
        ctx.idx.parseFailedFiles.sorted.foreach(f => println(s"  $f"))
      else
        println("  Run with --verbose to see the list.")

def cmdSearch(args: List[String], ctx: CommandContext): Unit =
  args.headOption match
    case None => println("Usage: scalex search <query>")
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
      if ctx.jsonOutput then
        val arr = results.take(ctx.limit).map(s => jsonSymbol(s, ctx.workspace)).mkString("[", ",", "]")
        println(arr)
      else
        if results.isEmpty then
          println(s"Found 0 symbols matching \"$query\"")
          printNotFoundHint(query, ctx.idx, "search", ctx.batchMode)
        else
          println(s"Found ${results.size} symbols matching \"$query\":")
          results.take(ctx.limit).foreach(s => println(ctx.fmt(s, ctx.workspace)))
          if results.size > ctx.limit then println(s"  ... and ${results.size - ctx.limit} more")

def cmdDef(args: List[String], ctx: CommandContext): Unit =
  args.headOption match
    case None => println("Usage: scalex def <symbol>")
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
      if ctx.jsonOutput then
        val arr = results.take(ctx.limit).map(s => jsonSymbol(s, ctx.workspace)).mkString("[", ",", "]")
        println(arr)
      else
        if results.isEmpty then
          println(s"Definition of \"$symbol\": not found")
          printNotFoundHint(symbol, ctx.idx, "def", ctx.batchMode)
        else
          println(s"Definition of \"$symbol\":")
          results.take(ctx.limit).foreach(s => println(ctx.fmt(s, ctx.workspace)))
          if results.size > ctx.limit then println(s"  ... and ${results.size - ctx.limit} more")

def cmdImpl(args: List[String], ctx: CommandContext): Unit =
  args.headOption match
    case None => println("Usage: scalex impl <trait>")
    case Some(symbol) =>
      val results = filterSymbols(ctx.idx.findImplementations(symbol), ctx)
      if ctx.jsonOutput then
        val arr = results.take(ctx.limit).map(s => jsonSymbol(s, ctx.workspace)).mkString("[", ",", "]")
        println(arr)
      else
        if results.isEmpty then
          println(s"No implementations of \"$symbol\" found")
          printNotFoundHint(symbol, ctx.idx, "impl", ctx.batchMode)
        else
          println(s"Implementations of \"$symbol\" — ${results.size} found:")
          results.take(ctx.limit).foreach(s => println(ctx.fmt(s, ctx.workspace)))
          if results.size > ctx.limit then println(s"  ... and ${results.size - ctx.limit} more")

def cmdRefs(args: List[String], ctx: CommandContext): Unit =
  args.headOption match
    case None => println("Usage: scalex refs <symbol>")
    case Some(symbol) =>
      val targetPkgs = ctx.idx.symbolsByName.getOrElse(symbol.toLowerCase, Nil).map(_.packageName).toSet
      def filterByCategory(grouped: Map[RefCategory, List[Reference]]): Map[RefCategory, List[Reference]] =
        ctx.categoryFilter match
          case Some(catName) =>
            val validCats = RefCategory.values.map(_.toString.toLowerCase).toSet
            val lower = catName.toLowerCase
            if !validCats.contains(lower) then
              System.err.println(s"Unknown category: $catName. Valid: ${RefCategory.values.map(_.toString).mkString(", ")}")
              Map.empty
            else
              grouped.filter((cat, _) => cat.toString.toLowerCase == lower)
          case None => grouped
      if ctx.jsonOutput then
        if ctx.categorize then
          val grouped = filterByCategory(ctx.idx.categorizeReferences(symbol).map((cat, refs) => (cat, filterRefs(refs, ctx))))
          val entries = grouped.map { (cat, refs) =>
            val arr = refs.take(ctx.limit).map(ctx.jRef).mkString("[", ",", "]")
            s""""${cat.toString}":$arr"""
          }.mkString(",")
          println(s"""{"categories":{$entries},"timedOut":${ctx.idx.timedOut}}""")
        else
          val results = filterRefs(ctx.idx.findReferences(symbol), ctx)
          val arr = results.take(ctx.limit).map(ctx.jRef).mkString("[", ",", "]")
          println(s"""{"results":$arr,"timedOut":${ctx.idx.timedOut}}""")
      else
        if ctx.categorize then
          val grouped = filterByCategory(ctx.idx.categorizeReferences(symbol).map((cat, refs) => (cat, filterRefs(refs, ctx))))
          val total = grouped.values.map(_.size).sum
          val suffix = if ctx.idx.timedOut then " (timed out — partial results)" else ""
          println(s"References to \"$symbol\" — $total found:$suffix")
          val confidenceOrder = List(Confidence.High, Confidence.Medium, Confidence.Low)
          confidenceOrder.foreach { conf =>
            val catRefs = grouped.flatMap { (cat, refs) =>
              refs.map(r => (cat, r, ctx.idx.resolveConfidence(r, symbol, targetPkgs)))
            }.filter(_._3 == conf).toList
            if catRefs.nonEmpty then
              val label = conf match
                case Confidence.High   => "High confidence (import-matched)"
                case Confidence.Medium => "Medium confidence (wildcard import)"
                case Confidence.Low    => "Low confidence (no matching import)"
              println(s"\n  $label:")
              val byCat = catRefs.groupBy(_._1)
              val order = List(RefCategory.Definition, RefCategory.ExtendedBy, RefCategory.ImportedBy,
                               RefCategory.UsedAsType, RefCategory.Usage, RefCategory.Comment)
              order.foreach { cat =>
                byCat.get(cat).filter(_.nonEmpty).foreach { entries =>
                  println(s"\n    ${cat.toString}:")
                  entries.take(ctx.limit).foreach((_, r, _) => println(s"    ${ctx.fmtRef(r)}"))
                  if entries.size > ctx.limit then println(s"      ... and ${entries.size - ctx.limit} more")
                }
              }
          }
        else
          val results = filterRefs(ctx.idx.findReferences(symbol), ctx)
          val suffix = if ctx.idx.timedOut then " (timed out — partial results)" else ""
          println(s"References to \"$symbol\" — ${results.size} found:$suffix")
          val annotated = results.map(r => (r, ctx.idx.resolveConfidence(r, symbol, targetPkgs)))
          val sorted = annotated.sortBy { case (_, c) => c.ordinal }
          var lastConf: Option[Confidence] = None
          var shown = 0
          sorted.foreach { case (r, conf) =>
            if shown < ctx.limit then
              if !lastConf.contains(conf) then
                val label = conf match
                  case Confidence.High   => "High confidence"
                  case Confidence.Medium => "Medium confidence"
                  case Confidence.Low    => "Low confidence"
                println(s"\n  [$label]")
                lastConf = Some(conf)
              println(ctx.fmtRef(r))
              shown += 1
          }
          if results.size > ctx.limit then println(s"  ... and ${results.size - ctx.limit} more")

def cmdImports(args: List[String], ctx: CommandContext): Unit =
  args.headOption match
    case None => println("Usage: scalex imports <symbol>")
    case Some(symbol) =>
      val results = filterRefs(ctx.idx.findImports(symbol), ctx)
      if ctx.jsonOutput then
        val arr = results.take(ctx.limit).map(r => jsonRef(r, ctx.workspace)).mkString("[", ",", "]")
        println(s"""{"results":$arr,"timedOut":${ctx.idx.timedOut}}""")
      else
        if results.isEmpty then
          println(s"No imports of \"$symbol\" found")
          printNotFoundHint(symbol, ctx.idx, "imports", ctx.batchMode)
        else
          val suffix = if ctx.idx.timedOut then " (timed out — partial results)" else ""
          println(s"Imports of \"$symbol\" — ${results.size} found:$suffix")
          results.take(ctx.limit).foreach(r => println(formatRef(r, ctx.workspace)))
          if results.size > ctx.limit then println(s"  ... and ${results.size - ctx.limit} more")

def cmdSymbols(args: List[String], ctx: CommandContext): Unit =
  args.headOption match
    case None => println("Usage: scalex symbols <file>")
    case Some(file) =>
      val results = ctx.idx.fileSymbols(file)
      if ctx.jsonOutput then
        val arr = results.map(s => jsonSymbol(s, ctx.workspace)).mkString("[", ",", "]")
        println(arr)
      else
        if results.isEmpty then println(s"No symbols found in $file")
        else
          println(s"Symbols in $file:")
          results.foreach(s => println(ctx.fmt(s, ctx.workspace)))

def cmdFile(args: List[String], ctx: CommandContext): Unit =
  args.headOption match
    case None => println("Usage: scalex file <query>")
    case Some(query) =>
      val results = ctx.idx.searchFiles(query)
      if ctx.jsonOutput then
        val arr = results.take(ctx.limit).map(f => s""""${jsonEscape(f)}"""").mkString("[", ",", "]")
        println(arr)
      else
        if results.isEmpty then
          println(s"Found 0 files matching \"$query\"")
          println(s"  Hint: scalex indexes ${ctx.idx.fileCount} git-tracked .scala files.")
        else
          println(s"Found ${results.size} files matching \"$query\":")
          results.take(ctx.limit).foreach(f => println(s"  $f"))
          if results.size > ctx.limit then println(s"  ... and ${results.size - ctx.limit} more")

def cmdPackages(args: List[String], ctx: CommandContext): Unit =
  if ctx.jsonOutput then
    val arr = ctx.idx.packages.toList.sorted.map(p => s""""${jsonEscape(p)}"""").mkString("[", ",", "]")
    println(arr)
  else
    println(s"Packages (${ctx.idx.packages.size}):")
    ctx.idx.packages.toList.sorted.foreach(p => println(s"  $p"))

def cmdAnnotated(args: List[String], ctx: CommandContext): Unit =
  args.headOption match
    case None => println("Usage: scalex annotated <annotation>")
    case Some(query) =>
      val annot = query.stripPrefix("@")
      val results = filterSymbols(ctx.idx.findAnnotated(annot), ctx)
      if ctx.jsonOutput then
        val arr = results.take(ctx.limit).map(s => jsonSymbol(s, ctx.workspace)).mkString("[", ",", "]")
        println(arr)
      else
        if results.isEmpty then
          println(s"No symbols with @$annot annotation found")
        else
          println(s"Symbols annotated with @$annot — ${results.size} found:")
          results.take(ctx.limit).foreach(s => println(ctx.fmt(s, ctx.workspace)))
          if results.size > ctx.limit then println(s"  ... and ${results.size - ctx.limit} more")

def cmdGrep(args: List[String], ctx: CommandContext): Unit =
  val patternOpt = if ctx.grepPatterns.nonEmpty then Some(ctx.grepPatterns.mkString("|"))
                   else args.headOption
  patternOpt match
    case None => println("Usage: scalex grep <pattern>")
    case Some(rawPattern) =>
      val (pattern, wasFixed) = fixPosixRegex(rawPattern)
      if wasFixed then
        System.err.println(s"  Note: auto-corrected POSIX regex to Java regex: \"$rawPattern\" → \"$pattern\"")
      val (results, grepTimedOut) = ctx.idx.grepFiles(pattern, ctx.noTests, ctx.pathFilter)
      if ctx.countOnly then
        val fileCount = results.map(_.file).distinct.size
        val hint = if wasFixed then s""","corrected":"$pattern"""" else ""
        if ctx.jsonOutput then println(s"""{"matches":${results.size},"files":$fileCount,"timedOut":$grepTimedOut$hint}""")
        else
          val suffix = if grepTimedOut then " (timed out — partial results)" else ""
          println(s"${results.size} matches across $fileCount files$suffix")
      else if ctx.jsonOutput then
        val arr = results.take(ctx.limit).map(ctx.jRef).mkString("[", ",", "]")
        val hint = if wasFixed then s""","corrected":"$pattern"""" else ""
        println(s"""{"results":$arr,"timedOut":$grepTimedOut$hint}""")
      else
        val suffix = if grepTimedOut then " (timed out — partial results)" else ""
        if results.isEmpty then
          println(s"No matches for \"$pattern\"$suffix")
        else
          println(s"Matches for \"$pattern\" — ${results.size} found:$suffix")
          results.take(ctx.limit).foreach(r => println(ctx.fmtRef(r)))
          if results.size > ctx.limit then println(s"  ... and ${results.size - ctx.limit} more")

def cmdMembers(args: List[String], ctx: CommandContext): Unit =
  args.headOption match
    case None => println("Usage: scalex members <Symbol>")
    case Some(symbol) =>
      val typeKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
      val defs = filterSymbols(ctx.idx.findDefinition(symbol).filter(s => typeKinds.contains(s.kind)), ctx)

      // Collect inherited members if --inherited is set
      def collectInherited(sym: SymbolInfo): List[(parentName: String, members: List[MemberInfo])] = {
        if !ctx.inherited then return Nil
        val visited = mutable.HashSet.empty[String]
        visited += sym.name.toLowerCase
        val ownMembers = extractMembers(sym.file, sym.name).map(m => (m.name, m.kind)).toSet
        val result = mutable.ListBuffer.empty[(String, List[MemberInfo])]

        def walk(parentNames: List[String]): Unit = {
          parentNames.foreach { pName =>
            if !visited.contains(pName.toLowerCase) then {
              visited += pName.toLowerCase
              val parentDefs = ctx.idx.findDefinition(pName).filter(s => typeKinds.contains(s.kind))
              parentDefs.headOption.foreach { pd =>
                val parentMembers = extractMembers(pd.file, pd.name)
                val filtered = parentMembers.filterNot(m => ownMembers.contains((m.name, m.kind)))
                if filtered.nonEmpty then result += ((pd.name, filtered))
                walk(pd.parents)
              }
            }
          }
        }

        walk(sym.parents)
        result.toList
      }

      if ctx.jsonOutput then
        val allMembers = defs.flatMap { s =>
          val ownMembers = extractMembers(s.file, symbol).map { m =>
            val rel = jsonEscape(ctx.workspace.relativize(s.file).toString)
            s"""{"name":"${jsonEscape(m.name)}","kind":"${m.kind.toString.toLowerCase}","line":${m.line},"signature":"${jsonEscape(m.signature)}","file":"$rel","owner":"${jsonEscape(symbol)}","ownerKind":"${s.kind.toString.toLowerCase}","package":"${jsonEscape(s.packageName)}","inherited":false}"""
          }
          val inheritedMembers = collectInherited(s).flatMap { case (parentName, members) =>
            val parentDef = ctx.idx.findDefinition(parentName).headOption
            members.map { m =>
              val rel = parentDef.map(pd => jsonEscape(ctx.workspace.relativize(pd.file).toString)).getOrElse("")
              s"""{"name":"${jsonEscape(m.name)}","kind":"${m.kind.toString.toLowerCase}","line":${m.line},"signature":"${jsonEscape(m.signature)}","file":"$rel","owner":"${jsonEscape(parentName)}","ownerKind":"inherited","package":"${parentDef.map(_.packageName).getOrElse("")}","inherited":true}"""
            }
          }
          ownMembers ++ inheritedMembers
        }
        println(allMembers.take(ctx.limit).mkString("[", ",", "]"))
      else
        if defs.isEmpty then
          println(s"No class/trait/object/enum \"$symbol\" found")
          printNotFoundHint(symbol, ctx.idx, "members", ctx.batchMode)
        else
          defs.foreach { s =>
            val rel = ctx.workspace.relativize(s.file)
            val pkg = if s.packageName.nonEmpty then s" (${s.packageName})" else ""
            val members = extractMembers(s.file, symbol)
            println(s"Members of ${s.kind.toString.toLowerCase} $symbol$pkg — $rel:${s.line}:")
            if members.isEmpty then println("  (no members)")
            else
              println(s"  Defined in $symbol:")
              members.take(ctx.limit).foreach { m =>
                if ctx.verbose then
                  println(s"    ${m.kind.toString.toLowerCase.padTo(5, ' ')} ${m.signature.padTo(50, ' ')} :${m.line}")
                else
                  println(s"    ${m.kind.toString.toLowerCase.padTo(5, ' ')} ${m.name.padTo(30, ' ')} :${m.line}")
              }
              if members.size > ctx.limit then println(s"    ... and ${members.size - ctx.limit} more")
            val inheritedGroups = collectInherited(s)
            inheritedGroups.foreach { case (parentName, pMembers) =>
              println(s"  Inherited from $parentName:")
              pMembers.take(ctx.limit).foreach { m =>
                if ctx.verbose then
                  println(s"    ${m.kind.toString.toLowerCase.padTo(5, ' ')} ${m.signature.padTo(50, ' ')} :${m.line}")
                else
                  println(s"    ${m.kind.toString.toLowerCase.padTo(5, ' ')} ${m.name.padTo(30, ' ')} :${m.line}")
              }
              if pMembers.size > ctx.limit then println(s"    ... and ${pMembers.size - ctx.limit} more")
            }
          }

def cmdDoc(args: List[String], ctx: CommandContext): Unit =
  args.headOption match
    case None => println("Usage: scalex doc <Symbol>")
    case Some(symbol) =>
      val defs = filterSymbols(ctx.idx.findDefinition(symbol), ctx)
      if ctx.jsonOutput then
        val entries = defs.take(ctx.limit).map { s =>
          val rel = jsonEscape(ctx.workspace.relativize(s.file).toString)
          val doc = extractScaladoc(s.file, s.line).map(d => s""""${jsonEscape(d)}"""").getOrElse("null")
          s"""{"name":"${jsonEscape(s.name)}","kind":"${s.kind.toString.toLowerCase}","file":"$rel","line":${s.line},"package":"${jsonEscape(s.packageName)}","doc":$doc}"""
        }
        println(entries.mkString("[", ",", "]"))
      else
        if defs.isEmpty then
          println(s"Definition of \"$symbol\": not found")
          printNotFoundHint(symbol, ctx.idx, "doc", ctx.batchMode)
        else
          defs.take(ctx.limit).foreach { s =>
            val rel = ctx.workspace.relativize(s.file)
            val pkg = if s.packageName.nonEmpty then s" (${s.packageName})" else ""
            println(s"${s.kind.toString.toLowerCase} $symbol$pkg — $rel:${s.line}:")
            extractScaladoc(s.file, s.line) match
              case Some(doc) => println(doc)
              case None => println("  (no scaladoc)")
            println()
          }

def cmdOverview(args: List[String], ctx: CommandContext): Unit =
  val symbolsByKind = ctx.idx.symbols.groupBy(_.kind).toList.sortBy(-_._2.size)
  val topPackages = ctx.idx.packageToSymbols.toList.sortBy(-_._2.size).take(ctx.limit)
  val mostExtended = ctx.idx.parentIndex.toList
    .filter((name, _) => ctx.idx.symbolsByName.contains(name))
    .sortBy(-_._2.size).take(ctx.limit)

  // Architecture: compute package dependency graph from imports
  val archPkgDeps: Map[String, Set[String]] = if ctx.architecture then {
    val deps = mutable.HashMap.empty[String, mutable.HashSet[String]]
    ctx.idx.symbols.groupBy(_.file).foreach { (file, syms) =>
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

  // Architecture: hub types (most-referenced + most-extended)
  val hubTypes: List[(name: String, score: Int)] = if ctx.architecture then {
    val refCounts = mutable.HashMap.empty[String, Int]
    ctx.idx.parentIndex.foreach { (name, impls) =>
      if ctx.idx.symbolsByName.contains(name) then
        refCounts(name) = refCounts.getOrElse(name, 0) + impls.size
    }
    refCounts.toList.sortBy(-_._2).take(ctx.limit)
  } else Nil

  if ctx.jsonOutput then
    val kindJson = symbolsByKind.map((k, v) => s""""${k.toString.toLowerCase}":${v.size}""").mkString("{", ",", "}")
    val pkgJson = topPackages.map((p, s) => s"""{"package":"${jsonEscape(p)}","count":${s.size}}""").mkString("[", ",", "]")
    val extJson = mostExtended.map((p, s) => s"""{"name":"${jsonEscape(p)}","implementations":${s.size}}""").mkString("[", ",", "]")
    if ctx.architecture then
      val depsJson = archPkgDeps.map { (pkg, deps) =>
        val dArr = deps.map(d => s""""${jsonEscape(d)}"""").mkString("[", ",", "]")
        s""""${jsonEscape(pkg)}":$dArr"""
      }.mkString("{", ",", "}")
      val hubJson = hubTypes.map((n, c) => s"""{"name":"${jsonEscape(n)}","score":$c}""").mkString("[", ",", "]")
      println(s"""{"fileCount":${ctx.idx.fileCount},"symbolCount":${ctx.idx.symbols.size},"packageCount":${ctx.idx.packages.size},"symbolsByKind":$kindJson,"topPackages":$pkgJson,"mostExtended":$extJson,"packageDependencies":$depsJson,"hubTypes":$hubJson}""")
    else
      println(s"""{"fileCount":${ctx.idx.fileCount},"symbolCount":${ctx.idx.symbols.size},"packageCount":${ctx.idx.packages.size},"symbolsByKind":$kindJson,"topPackages":$pkgJson,"mostExtended":$extJson}""")
  else
    println(s"Project overview (${ctx.idx.fileCount} files, ${ctx.idx.symbols.size} symbols):\n")
    println("Symbols by kind:")
    symbolsByKind.foreach { (kind, syms) =>
      println(s"  ${kind.toString.padTo(10, ' ')} ${syms.size}")
    }
    println(s"\nTop packages (by symbol count):")
    topPackages.foreach { (pkg, syms) =>
      println(s"  ${pkg.padTo(50, ' ')} ${syms.size}")
    }
    println(s"\nMost extended (by implementation count):")
    mostExtended.foreach { (name, impls) =>
      println(s"  ${name.padTo(30, ' ')} ${impls.size} impl")
    }
    if ctx.architecture then
      println(s"\nPackage dependencies:")
      if archPkgDeps.isEmpty then println("  (no cross-package dependencies found)")
      else archPkgDeps.toList.sortBy(_._1).foreach { (pkg, deps) =>
        println(s"  $pkg → ${deps.toList.sorted.mkString(", ")}")
      }
      println(s"\nHub types (by extension count):")
      if hubTypes.isEmpty then println("  (none)")
      else hubTypes.foreach { (name, count) =>
        println(s"  ${name.padTo(30, ' ')} $count references")
      }

def cmdBody(args: List[String], ctx: CommandContext): Unit =
  args.headOption match
    case None => println("Usage: scalex body <symbol> [--in <owner>]")
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
      val resultsWithFiles = filesToSearch.flatMap { f =>
        extractBody(f, symbol, ctx.inOwner).map(b => (f, b))
      }
      if ctx.jsonOutput then
        val arr = resultsWithFiles.take(ctx.limit).map { case (file, b) =>
          val rel = jsonEscape(ctx.workspace.relativize(file).toString)
          s"""{"name":"${jsonEscape(b.symbolName)}","owner":"${jsonEscape(b.ownerName)}","file":"$rel","startLine":${b.startLine},"endLine":${b.endLine},"body":"${jsonEscape(b.sourceText)}"}"""
        }.mkString("[", ",", "]")
        println(arr)
      else
        if resultsWithFiles.isEmpty then
          println(s"No body found for \"$symbol\"")
          printNotFoundHint(symbol, ctx.idx, "body", ctx.batchMode)
        else
          resultsWithFiles.take(ctx.limit).foreach { case (file, b) =>
            val ownerStr = if b.ownerName.nonEmpty then s" — ${b.ownerName}" else ""
            val rel = ctx.workspace.relativize(file)
            println(s"Body of ${b.symbolName}$ownerStr — $rel:${b.startLine}:")
            val bodyLines = b.sourceText.split("\n")
            bodyLines.zipWithIndex.foreach { case (line, i) =>
              println(s"  ${(b.startLine + i).toString.padTo(4, ' ')} | $line")
            }
            println()
          }

def cmdTests(args: List[String], ctx: CommandContext): Unit =
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
  if ctx.jsonOutput then
    val arr = allSuites.take(ctx.limit).map { suite =>
      val rel = jsonEscape(ctx.workspace.relativize(suite.file).toString)
      val fileLines = if showBody then
        try Files.readAllLines(suite.file).asScala.toArray catch case _: Exception => Array.empty[String]
      else Array.empty[String]
      val testsJson = suite.tests.map { tc =>
        val bodyField = if showBody then {
          val bodies = extractBody(suite.file, tc.name, Some(suite.name))
          bodies.headOption.map(b => s""","body":"${jsonEscape(b.sourceText)}"""").getOrElse("")
        } else ""
        s"""{"name":"${jsonEscape(tc.name)}","line":${tc.line}$bodyField}"""
      }.mkString("[", ",", "]")
      s"""{"suite":"${jsonEscape(suite.name)}","file":"$rel","line":${suite.line},"tests":$testsJson}"""
    }.mkString("[", ",", "]")
    println(arr)
  else
    if allSuites.isEmpty then
      if nameFilter.isDefined then println(s"No tests matching \"${nameFilter.get}\"")
      else println("No test suites found")
    else
      allSuites.take(ctx.limit).foreach { suite =>
        val rel = ctx.workspace.relativize(suite.file)
        println(s"${suite.name} — $rel:${suite.line}:")
        suite.tests.foreach { tc =>
          println(s"  test  \"${tc.name}\"  :${tc.line}")
          if showBody || ctx.verbose then
            val bodies = extractBody(suite.file, tc.name, Some(suite.name))
            bodies.headOption.foreach { b =>
              val bodyLines = b.sourceText.split("\n")
              bodyLines.zipWithIndex.foreach { case (line, i) =>
                println(s"    ${(b.startLine + i).toString.padTo(4, ' ')} | $line")
              }
              println()
            }
        }
        if !showBody && !ctx.verbose then println()
      }

def cmdCoverage(args: List[String], ctx: CommandContext): Unit =
  args.headOption match
    case None => println("Usage: scalex coverage <symbol>")
    case Some(symbol) =>
      val refs = ctx.idx.findReferences(symbol)
      val testRefs = refs.filter(r => isTestFile(r.file, ctx.workspace))
      val testFiles = testRefs.map(r => ctx.workspace.relativize(r.file).toString).distinct
      if ctx.jsonOutput then
        val refsJson = testRefs.take(ctx.limit).map(r => ctx.jRef(r)).mkString("[", ",", "]")
        println(s"""{"symbol":"${jsonEscape(symbol)}","testFileCount":${testFiles.size},"referenceCount":${testRefs.size},"references":$refsJson}""")
      else
        if testRefs.isEmpty then
          if refs.isEmpty then
            println(s"""Coverage of "$symbol" — no references found""")
            printNotFoundHint(symbol, ctx.idx, "coverage", ctx.batchMode)
          else
            println(s"""Coverage of "$symbol" — ${refs.size} refs but 0 in test files""")
        else
          println(s"""Coverage of "$symbol" — ${testRefs.size} refs in ${testFiles.size} test files:""")
          testFiles.sorted.foreach { f =>
            val fileRefs = testRefs.filter(r => ctx.workspace.relativize(r.file).toString == f)
            println(s"  $f")
            fileRefs.take(ctx.limit).foreach { r =>
              println(s"    :${r.line}  ${r.contextLine}")
            }
          }

def cmdHierarchy(args: List[String], ctx: CommandContext): Unit =
  args.headOption match
    case None => println("Usage: scalex hierarchy <symbol> [--up] [--down]")
    case Some(symbol) =>
      buildHierarchy(ctx.idx, symbol, ctx.goUp, ctx.goDown, ctx.workspace) match
        case None =>
          println(s"No definition of \"$symbol\" found")
          printNotFoundHint(symbol, ctx.idx, "hierarchy", ctx.batchMode)
        case Some(tree) =>
          def nodeJson(n: HierarchyNode): String = {
            val file = n.file.map(f => s""""${jsonEscape(ctx.workspace.relativize(f).toString)}"""").getOrElse("null")
            val kind = n.kind.map(k => s""""${k.toString.toLowerCase}"""").getOrElse("null")
            val line = n.line.map(_.toString).getOrElse("null")
            s"""{"name":"${jsonEscape(n.name)}","kind":$kind,"file":$file,"line":$line,"package":"${jsonEscape(n.packageName)}","isExternal":${n.isExternal}}"""
          }
          def treeJson(t: HierarchyTree): String = {
            val ps = t.parents.map(treeJson).mkString("[", ",", "]")
            val cs = t.children.map(treeJson).mkString("[", ",", "]")
            s"""{"node":${nodeJson(t.root)},"parents":$ps,"children":$cs}"""
          }
          if ctx.jsonOutput then
            println(treeJson(tree))
          else
            val rootNode = tree.root
            val pkg = if rootNode.packageName.nonEmpty then s" (${rootNode.packageName})" else ""
            val kind = rootNode.kind.map(_.toString.toLowerCase).getOrElse("unknown")
            val loc = rootNode.file.map(f => s" — ${ctx.workspace.relativize(f)}:${rootNode.line.getOrElse(0)}").getOrElse("")
            println(s"Hierarchy of $kind ${rootNode.name}$pkg$loc:")
            if ctx.goUp then
              println("  Parents:")
              def printParents(parents: List[HierarchyTree], indent: String): Unit = {
                parents.zipWithIndex.foreach { case (pt, i) =>
                  val isLast = i == parents.size - 1
                  val prefix = if isLast then s"$indent└── " else s"$indent├── "
                  val nextIndent = if isLast then s"$indent    " else s"$indent│   "
                  val n = pt.root
                  val npkg = if n.packageName.nonEmpty then s" (${n.packageName})" else ""
                  val nkind = n.kind.map(_.toString.toLowerCase + " ").getOrElse("")
                  val nloc = if n.isExternal then " [external]"
                             else n.file.map(f => s" — ${ctx.workspace.relativize(f)}:${n.line.getOrElse(0)}").getOrElse("")
                  println(s"$prefix$nkind${n.name}$npkg$nloc")
                  printParents(pt.parents, nextIndent)
                }
              }
              if tree.parents.isEmpty then println("    (none)")
              else printParents(tree.parents, "    ")
            if ctx.goDown then
              println("  Children:")
              def printChildren(children: List[HierarchyTree], indent: String): Unit = {
                children.zipWithIndex.foreach { case (ct, i) =>
                  val isLast = i == children.size - 1
                  val prefix = if isLast then s"$indent└── " else s"$indent├── "
                  val nextIndent = if isLast then s"$indent    " else s"$indent│   "
                  val n = ct.root
                  val npkg = if n.packageName.nonEmpty then s" (${n.packageName})" else ""
                  val nkind = n.kind.map(_.toString.toLowerCase + " ").getOrElse("")
                  val nloc = n.file.map(f => s" — ${ctx.workspace.relativize(f)}:${n.line.getOrElse(0)}").getOrElse("")
                  println(s"$prefix$nkind${n.name}$npkg$nloc")
                  printChildren(ct.children, nextIndent)
                }
              }
              if tree.children.isEmpty then println("    (none)")
              else printChildren(tree.children, "    ")

def cmdOverrides(args: List[String], ctx: CommandContext): Unit =
  args.headOption match
    case None => println("Usage: scalex overrides <method> [--of <trait>]")
    case Some(methodName) =>
      val results = findOverrides(ctx.idx, methodName, ctx.ofTrait, ctx.limit)
      if ctx.jsonOutput then
        val arr = results.map { o =>
          val rel = jsonEscape(ctx.workspace.relativize(o.file).toString)
          s"""{"enclosingClass":"${jsonEscape(o.enclosingClass)}","enclosingKind":"${o.enclosingKind.toString.toLowerCase}","file":"$rel","line":${o.line},"signature":"${jsonEscape(o.signature)}","package":"${jsonEscape(o.packageName)}"}"""
        }.mkString("[", ",", "]")
        println(arr)
      else
        if results.isEmpty then
          val ofStr = ctx.ofTrait.map(t => s" of $t").getOrElse("")
          println(s"No overrides of \"$methodName\"$ofStr found")
          printNotFoundHint(methodName, ctx.idx, "overrides", ctx.batchMode)
        else
          val ofStr = ctx.ofTrait.map(t => s" (in implementations of $t)").getOrElse("")
          println(s"Overrides of $methodName$ofStr — ${results.size} found:")
          results.foreach { o =>
            val rel = ctx.workspace.relativize(o.file)
            val pkg = if o.packageName.nonEmpty then s" (${o.packageName})" else ""
            println(s"  ${o.enclosingClass}$pkg — $rel:${o.line}")
            println(s"    ${o.signature}")
          }

def cmdExplain(args: List[String], ctx: CommandContext): Unit =
  args.headOption match
    case None => println("Usage: scalex explain <symbol>")
    case Some(symbol) =>
      var defs = ctx.idx.findDefinition(symbol)
      if ctx.noTests then defs = defs.filter(s => !isTestFile(s.file, ctx.workspace))
      ctx.pathFilter.foreach { p => defs = defs.filter(s => matchesPath(s.file, p, ctx.workspace)) }
      if defs.isEmpty then
        if ctx.jsonOutput then println("""{"error":"not found"}""")
        else
          println(s"No definition of \"$symbol\" found")
          printNotFoundHint(symbol, ctx.idx, "explain", ctx.batchMode)
      else
        val sym = defs.head
        val rel = ctx.workspace.relativize(sym.file)
        val pkg = if sym.packageName.nonEmpty then s" (${sym.packageName})" else ""

        // Scaladoc
        val doc = extractScaladoc(sym.file, sym.line)

        // Members (for types)
        val typeKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
        val members = if typeKinds.contains(sym.kind) then extractMembers(sym.file, symbol).take(10) else Nil

        // Implementations
        val impls = ctx.idx.findImplementations(symbol).take(ctx.implLimit)

        // Import count
        val importCount = ctx.idx.findImports(symbol, timeoutMs = 3000).size

        if ctx.jsonOutput then
          val docJson = doc.map(d => s""""${jsonEscape(d)}"""").getOrElse("null")
          val membersJson = members.map { m =>
            s"""{"name":"${jsonEscape(m.name)}","kind":"${m.kind.toString.toLowerCase}","line":${m.line},"signature":"${jsonEscape(m.signature)}"}"""
          }.mkString("[", ",", "]")
          val implsJson = impls.map(s => jsonSymbol(s, ctx.workspace)).mkString("[", ",", "]")
          println(s"""{"definition":${jsonSymbol(sym, ctx.workspace)},"doc":$docJson,"members":$membersJson,"implementations":$implsJson,"importCount":$importCount}""")
        else
          println(s"Explanation of ${sym.kind.toString.toLowerCase} $symbol$pkg:\n")
          println(s"  Definition: $rel:${sym.line}")
          println(s"  Signature: ${sym.signature}")
          if sym.parents.nonEmpty then println(s"  Extends: ${sym.parents.mkString(", ")}")
          println()
          doc match
            case Some(d) =>
              println("  Scaladoc:")
              d.split("\n").foreach(l => println(s"    $l"))
              println()
            case None =>
              println("  Scaladoc: (none)\n")
          if members.nonEmpty then
            println(s"  Members (top ${members.size}):")
            members.foreach(m => println(s"    ${m.kind.toString.toLowerCase.padTo(5, ' ')} ${m.name}"))
            println()
          if impls.nonEmpty then
            println(s"  Implementations (top ${impls.size}):")
            impls.foreach(s => println(formatSymbol(s, ctx.workspace)))
            println()
          println(s"  Imported by: $importCount files")

def cmdDeps(args: List[String], ctx: CommandContext): Unit =
  args.headOption match
    case None => println("Usage: scalex deps <symbol>")
    case Some(symbol) =>
      val (importDeps, bodyDeps) = extractDeps(ctx.idx, symbol, ctx.workspace)
      if ctx.jsonOutput then
        val iArr = importDeps.map { d =>
          val file = d.file.map(f => s""""${jsonEscape(ctx.workspace.relativize(f).toString)}"""").getOrElse("null")
          val line = d.line.map(_.toString).getOrElse("null")
          s"""{"name":"${jsonEscape(d.name)}","kind":"${jsonEscape(d.kind)}","file":$file,"line":$line,"package":"${jsonEscape(d.packageName)}"}"""
        }.mkString("[", ",", "]")
        val bArr = bodyDeps.map { d =>
          val file = d.file.map(f => s""""${jsonEscape(ctx.workspace.relativize(f).toString)}"""").getOrElse("null")
          val line = d.line.map(_.toString).getOrElse("null")
          s"""{"name":"${jsonEscape(d.name)}","kind":"${jsonEscape(d.kind)}","file":$file,"line":$line,"package":"${jsonEscape(d.packageName)}"}"""
        }.mkString("[", ",", "]")
        println(s"""{"imports":$iArr,"bodyReferences":$bArr}""")
      else
        if importDeps.isEmpty && bodyDeps.isEmpty then
          println(s"No dependencies found for \"$symbol\"")
          printNotFoundHint(symbol, ctx.idx, "deps", ctx.batchMode)
        else
          println(s"Dependencies of \"$symbol\":")
          if importDeps.nonEmpty then
            println(s"\n  Imports:")
            importDeps.take(ctx.limit).foreach { d =>
              val loc = d.file.map(f => s" — ${ctx.workspace.relativize(f)}:${d.line.getOrElse(0)}").getOrElse("")
              println(s"    ${d.kind.padTo(9, ' ')} ${d.name}$loc")
            }
            if importDeps.size > ctx.limit then println(s"    ... and ${importDeps.size - ctx.limit} more")
          if bodyDeps.nonEmpty then
            println(s"\n  Body references:")
            bodyDeps.take(ctx.limit).foreach { d =>
              val loc = d.file.map(f => s" — ${ctx.workspace.relativize(f)}:${d.line.getOrElse(0)}").getOrElse("")
              println(s"    ${d.kind.padTo(9, ' ')} ${d.name}$loc")
            }
            if bodyDeps.size > ctx.limit then println(s"    ... and ${bodyDeps.size - ctx.limit} more")

def cmdContext(args: List[String], ctx: CommandContext): Unit =
  args.headOption match
    case None => println("Usage: scalex context <file:line>")
    case Some(arg) =>
      val parts = arg.split(":")
      if parts.length < 2 then
        println("Usage: scalex context <file:line> (e.g. src/Main.scala:42)")
      else
        val filePath = parts.dropRight(1).mkString(":")
        val lineNum = parts.last.toIntOption
        lineNum match
          case None => println(s"Invalid line number: ${parts.last}")
          case Some(line) =>
            val resolved = if Path.of(filePath).isAbsolute then Path.of(filePath) else ctx.workspace.resolve(filePath)
            val scopes = extractScopes(resolved, line)
            if ctx.jsonOutput then
              val arr = scopes.map { s =>
                s"""{"name":"${jsonEscape(s.name)}","kind":"${jsonEscape(s.kind)}","line":${s.line}}"""
              }.mkString("[", ",", "]")
              val rel = jsonEscape(ctx.workspace.relativize(resolved).toString)
              println(s"""{"file":"$rel","line":$line,"scopes":$arr}""")
            else
              val rel = ctx.workspace.relativize(resolved)
              println(s"Context at $rel:$line:")
              if scopes.isEmpty then println("  (no enclosing scopes found)")
              else
                scopes.foreach { s =>
                  println(s"  ${s.kind.padTo(9, ' ')} ${s.name} (line ${s.line})")
                }

def cmdDiff(args: List[String], ctx: CommandContext): Unit =
  args.headOption match
    case None => println("Usage: scalex diff <git-ref> (e.g. scalex diff HEAD~1)")
    case Some(ref) =>
      val changedFiles = runGitDiff(ctx.workspace, ref)
      if changedFiles.isEmpty then
        if ctx.jsonOutput then println("""{"added":[],"removed":[],"modified":[]}""")
        else println(s"No Scala files changed compared to $ref")
      else
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
                modified += ((oSym, cSym))
            }
          }
        }

        if ctx.jsonOutput then
          def diffSymJson(s: DiffSymbol): String =
            s"""{"name":"${jsonEscape(s.name)}","kind":"${s.kind.toString.toLowerCase}","file":"${jsonEscape(s.file)}","line":${s.line},"package":"${jsonEscape(s.packageName)}","signature":"${jsonEscape(s.signature)}"}"""
          val addedJson = added.take(ctx.limit).map(diffSymJson).mkString("[", ",", "]")
          val removedJson = removed.take(ctx.limit).map(diffSymJson).mkString("[", ",", "]")
          val modifiedJson = modified.take(ctx.limit).map { case (o, n) =>
            s"""{"old":${diffSymJson(o)},"new":${diffSymJson(n)}}"""
          }.mkString("[", ",", "]")
          println(s"""{"ref":"${jsonEscape(ref)}","filesChanged":${changedFiles.size},"added":$addedJson,"removed":$removedJson,"modified":$modifiedJson}""")
        else
          println(s"Symbol changes compared to $ref (${changedFiles.size} files changed):")
          if added.nonEmpty then
            println(s"\n  Added (${added.size}):")
            added.take(ctx.limit).foreach { s =>
              println(s"    + ${s.kind.toString.toLowerCase.padTo(9, ' ')} ${s.name} — ${s.file}:${s.line}")
            }
            if added.size > ctx.limit then println(s"    ... and ${added.size - ctx.limit} more")
          if removed.nonEmpty then
            println(s"\n  Removed (${removed.size}):")
            removed.take(ctx.limit).foreach { s =>
              println(s"    - ${s.kind.toString.toLowerCase.padTo(9, ' ')} ${s.name} — ${s.file}:${s.line}")
            }
            if removed.size > ctx.limit then println(s"    ... and ${removed.size - ctx.limit} more")
          if modified.nonEmpty then
            println(s"\n  Modified (${modified.size}):")
            modified.take(ctx.limit).foreach { case (o, n) =>
              println(s"    ~ ${n.kind.toString.toLowerCase.padTo(9, ' ')} ${n.name} — ${n.file}:${n.line}")
            }
            if modified.size > ctx.limit then println(s"    ... and ${modified.size - ctx.limit} more")
          if added.isEmpty && removed.isEmpty && modified.isEmpty then
            println("  No symbol-level changes detected")

def cmdAstPattern(args: List[String], ctx: CommandContext): Unit =
  val results = astPatternSearch(ctx.idx, ctx.workspace, ctx.hasMethodFilter, ctx.extendsFilter, ctx.bodyContainsFilter, ctx.noTests, ctx.pathFilter, ctx.limit)
  if ctx.jsonOutput then
    val arr = results.map { m =>
      val rel = jsonEscape(ctx.workspace.relativize(m.file).toString)
      s"""{"name":"${jsonEscape(m.name)}","kind":"${m.kind.toString.toLowerCase}","file":"$rel","line":${m.line},"package":"${jsonEscape(m.packageName)}","signature":"${jsonEscape(m.signature)}"}"""
    }.mkString("[", ",", "]")
    println(arr)
  else
    val filters = List(
      ctx.hasMethodFilter.map(m => s"has-method=$m"),
      ctx.extendsFilter.map(e => s"extends=$e"),
      ctx.bodyContainsFilter.map(b => s"body-contains=\"$b\"")
    ).flatten.mkString(", ")
    if results.isEmpty then
      println(s"No types matching AST pattern ($filters)")
    else
      println(s"Types matching AST pattern ($filters) — ${results.size} found:")
      results.foreach { m =>
        val rel = ctx.workspace.relativize(m.file)
        val pkg = if m.packageName.nonEmpty then s" (${m.packageName})" else ""
        println(s"  ${m.kind.toString.toLowerCase.padTo(9, ' ')} ${m.name}$pkg — $rel:${m.line}")
      }

// ── Command dispatch ────────────────────────────────────────────────────────

val commands: Map[String, (List[String], CommandContext) => Unit] = Map(
  "index" -> cmdIndex, "search" -> cmdSearch, "def" -> cmdDef, "impl" -> cmdImpl,
  "refs" -> cmdRefs, "imports" -> cmdImports, "symbols" -> cmdSymbols, "file" -> cmdFile,
  "packages" -> cmdPackages, "annotated" -> cmdAnnotated, "grep" -> cmdGrep,
  "members" -> cmdMembers, "doc" -> cmdDoc, "overview" -> cmdOverview,
  "body" -> cmdBody, "tests" -> cmdTests, "coverage" -> cmdCoverage,
  "hierarchy" -> cmdHierarchy, "overrides" -> cmdOverrides, "explain" -> cmdExplain,
  "deps" -> cmdDeps, "context" -> cmdContext, "diff" -> cmdDiff, "ast-pattern" -> cmdAstPattern,
)

def runCommand(cmd: String, args: List[String], ctx: CommandContext): Unit =
  commands.get(cmd) match
    case Some(handler) => handler(args, ctx)
    case None => println(s"Unknown command: $cmd")
