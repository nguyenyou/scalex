import scala.collection.mutable
import java.nio.file.{Files, Path}
import java.io.{BufferedReader, InputStreamReader}
import scala.jdk.CollectionConverters.*

// ── CLI ─────────────────────────────────────────────────────────────────────

def jsonEscape(s: String): String =
  val sb = new StringBuilder(s.length + 8)
  var i = 0
  while i < s.length do
    s.charAt(i) match
      case '"'  => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
      case c    => sb.append(c)
    i += 1
  sb.toString

def jsonSymbol(s: SymbolInfo, workspace: Path): String =
  val rel = jsonEscape(workspace.relativize(s.file).toString)
  val parents = s.parents.map(p => s""""${jsonEscape(p)}"""").mkString("[", ",", "]")
  val annots = s.annotations.map(a => s""""${jsonEscape(a)}"""").mkString("[", ",", "]")
  s"""{"name":"${jsonEscape(s.name)}","kind":"${s.kind.toString.toLowerCase}","file":"$rel","line":${s.line},"package":"${jsonEscape(s.packageName)}","parents":$parents,"signature":"${jsonEscape(s.signature)}","annotations":$annots}"""

def jsonRef(r: Reference, workspace: Path): String =
  val rel = jsonEscape(workspace.relativize(r.file).toString)
  val alias = r.aliasInfo.map(a => s""""${jsonEscape(a)}"""").getOrElse("null")
  s"""{"file":"$rel","line":${r.line},"context":"${jsonEscape(r.contextLine)}","alias":$alias}"""

def jsonRefWithContext(r: Reference, workspace: Path, contextN: Int): String =
  val rel = jsonEscape(workspace.relativize(r.file).toString)
  val alias = r.aliasInfo.map(a => s""""${jsonEscape(a)}"""").getOrElse("null")
  val lines = try java.nio.file.Files.readAllLines(r.file).asScala catch
    case _: Exception => Seq.empty
  val total = lines.size
  val startLine = math.max(1, r.line - contextN)
  val endLine = math.min(total, r.line + contextN)
  val ctxLines = (startLine to endLine).map { i =>
    s"""{"line":$i,"content":"${jsonEscape(lines(i - 1))}","match":${i == r.line}}"""
  }.mkString("[", ",", "]")
  s"""{"file":"$rel","line":${r.line},"context":"${jsonEscape(r.contextLine)}","alias":$alias,"contextLines":$ctxLines}"""

def formatSymbol(s: SymbolInfo, workspace: Path): String =
  val rel = workspace.relativize(s.file)
  val pkg = if s.packageName.nonEmpty then s" (${s.packageName})" else ""
  s"  ${s.kind.toString.toLowerCase.padTo(9, ' ')} ${s.name}$pkg — $rel:${s.line}"

def formatSymbolVerbose(s: SymbolInfo, workspace: Path): String =
  val rel = workspace.relativize(s.file)
  val pkg = if s.packageName.nonEmpty then s" (${s.packageName})" else ""
  val sig = if s.signature.nonEmpty then s"\n             ${s.signature}" else ""
  s"  ${s.kind.toString.toLowerCase.padTo(9, ' ')} ${s.name}$pkg — $rel:${s.line}$sig"

def formatRef(r: Reference, workspace: Path): String =
  val rel = workspace.relativize(r.file)
  val alias = r.aliasInfo.map(a => s" [$a]").getOrElse("")
  s"  $rel:${r.line} — ${r.contextLine}$alias"

def formatRefWithContext(r: Reference, workspace: Path, contextN: Int): String =
  val rel = workspace.relativize(r.file)
  val alias = r.aliasInfo.map(a => s" [$a]").getOrElse("")
  val header = s"  $rel:${r.line}$alias"
  val lines = try java.nio.file.Files.readAllLines(r.file).asScala catch
    case _: Exception => return s"$header\n    > ${r.contextLine}"
  val total = lines.size
  val startLine = math.max(1, r.line - contextN)
  val endLine = math.min(total, r.line + contextN)
  val buf = new StringBuilder(header)
  var i = startLine
  while i <= endLine do
    val lineContent = lines(i - 1)
    val marker = if i == r.line then ">" else " "
    val lineNum = i.toString.reverse.padTo(4, ' ').reverse
    buf.append(s"\n    $marker $lineNum | $lineContent")
    i += 1
  buf.toString

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

def resolveWorkspace(path: String): Path =
  val p = Path.of(path).toAbsolutePath.normalize
  if Files.isDirectory(p) then p else p.getParent

def parseWorkspaceAndArg(rest: List[String]): Option[(workspace: Path, arg: String)] =
  rest match
    case a :: Nil => Some((resolveWorkspace("."), a))
    case ws :: a :: _ => Some((resolveWorkspace(ws), a))
    case _ => None

def runCommand(cmd: String, rest: List[String], idx: WorkspaceIndex, workspace: Path,
               limit: Int, kindFilter: Option[String], verbose: Boolean, categorize: Boolean,
               noTests: Boolean, pathFilter: Option[String], contextLines: Int,
               jsonOutput: Boolean, grepPatterns: List[String] = Nil,
               countOnly: Boolean = false, batchMode: Boolean = false,
               searchMode: Option[String] = None, definitionsOnly: Boolean = false,
               categoryFilter: Option[String] = None,
               inOwner: Option[String] = None, ofTrait: Option[String] = None,
               implLimit: Int = 5, goUp: Boolean = true, goDown: Boolean = true,
               inherited: Boolean = false, architecture: Boolean = false,
               hasMethodFilter: Option[String] = None, extendsFilter: Option[String] = None,
               bodyContainsFilter: Option[String] = None): Unit =
  val fmt = if verbose then formatSymbolVerbose else formatSymbol
  val jRef: Reference => String =
    if contextLines > 0 then r => jsonRefWithContext(r, workspace, contextLines)
    else r => jsonRef(r, workspace)
  cmd match
    case "index" =>
      if jsonOutput then
        val byKind = idx.symbols.groupBy(_.kind).toList.sortBy(-_._2.size)
          .map((k, v) => s""""${k.toString.toLowerCase}":${v.size}""").mkString(",")
        println(s"""{"fileCount":${idx.fileCount},"symbolCount":${idx.symbols.size},"packageCount":${idx.packages.size},"symbolsByKind":{$byKind},"indexTimeMs":${idx.indexTimeMs},"cachedLoad":${idx.cachedLoad},"parsedCount":${idx.parsedCount},"skippedCount":${idx.skippedCount},"parseFailures":${idx.parseFailures}}""")
      else
        if idx.cachedLoad then
          println(s"Indexed ${idx.fileCount} files (${idx.skippedCount} cached, ${idx.parsedCount} parsed) in ${idx.indexTimeMs}ms")
        else
          println(s"Indexed ${idx.fileCount} files, ${idx.symbols.size} symbols in ${idx.indexTimeMs}ms")
        println(s"Packages: ${idx.packages.size}")
        println()
        println("Symbols by kind:")
        idx.symbols.groupBy(_.kind).toList.sortBy(-_._2.size).foreach { (kind, syms) =>
          println(s"  ${kind.toString.padTo(10, ' ')} ${syms.size}")
        }
        if idx.parseFailures > 0 then
          println(s"\n${idx.parseFailures} files had parse errors:")
          if verbose then
            idx.parseFailedFiles.sorted.foreach(f => println(s"  $f"))
          else
            println("  Run with --verbose to see the list.")

    case "search" =>
      rest.headOption match
        case None => println("Usage: scalex search <query>")
        case Some(query) =>
          var results = idx.search(query)
          searchMode.foreach {
            case "exact" =>
              val lower = query.toLowerCase
              results = results.filter(_.name.toLowerCase == lower)
            case "prefix" =>
              val lower = query.toLowerCase
              results = results.filter(_.name.toLowerCase.startsWith(lower))
            case _ => ()
          }
          if definitionsOnly then
            val defKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
            results = results.filter(s => defKinds.contains(s.kind))
          kindFilter.foreach { k =>
            val kk = k.toLowerCase
            results = results.filter(_.kind.toString.toLowerCase == kk)
          }
          if noTests then results = results.filter(s => !isTestFile(s.file, workspace))
          pathFilter.foreach { p => results = results.filter(s => matchesPath(s.file, p, workspace)) }
          if jsonOutput then
            val arr = results.take(limit).map(s => jsonSymbol(s, workspace)).mkString("[", ",", "]")
            println(arr)
          else
            if results.isEmpty then
              println(s"Found 0 symbols matching \"$query\"")
              printNotFoundHint(query, idx, "search", batchMode)
            else
              println(s"Found ${results.size} symbols matching \"$query\":")
              results.take(limit).foreach(s => println(fmt(s, workspace)))
              if results.size > limit then println(s"  ... and ${results.size - limit} more")

    case "def" =>
      rest.headOption match
        case None => println("Usage: scalex def <symbol>")
        case Some(symbol) =>
          var results = idx.findDefinition(symbol)
          kindFilter.foreach { k =>
            val kk = k.toLowerCase
            results = results.filter(_.kind.toString.toLowerCase == kk)
          }
          if noTests then results = results.filter(s => !isTestFile(s.file, workspace))
          pathFilter.foreach { p => results = results.filter(s => matchesPath(s.file, p, workspace)) }
          // Rank: class/trait/object/enum > type/given > def/val/var, non-test > test, shorter path first
          results = results.sortBy { s =>
            val kindRank = s.kind match
              case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Object | SymbolKind.Enum => 0
              case SymbolKind.Type | SymbolKind.Given => 1
              case _ => 2
            val testRank = if isTestFile(s.file, workspace) then 1 else 0
            val pathLen = workspace.relativize(s.file).toString.length
            (kindRank, testRank, pathLen)
          }
          if jsonOutput then
            val arr = results.take(limit).map(s => jsonSymbol(s, workspace)).mkString("[", ",", "]")
            println(arr)
          else
            if results.isEmpty then
              println(s"Definition of \"$symbol\": not found")
              printNotFoundHint(symbol, idx, "def", batchMode)
            else
              println(s"Definition of \"$symbol\":")
              results.take(limit).foreach(s => println(fmt(s, workspace)))
              if results.size > limit then println(s"  ... and ${results.size - limit} more")

    case "impl" =>
      rest.headOption match
        case None => println("Usage: scalex impl <trait>")
        case Some(symbol) =>
          var results = idx.findImplementations(symbol)
          kindFilter.foreach { k =>
            val kk = k.toLowerCase
            results = results.filter(_.kind.toString.toLowerCase == kk)
          }
          if noTests then results = results.filter(s => !isTestFile(s.file, workspace))
          pathFilter.foreach { p => results = results.filter(s => matchesPath(s.file, p, workspace)) }
          if jsonOutput then
            val arr = results.take(limit).map(s => jsonSymbol(s, workspace)).mkString("[", ",", "]")
            println(arr)
          else
            if results.isEmpty then
              println(s"No implementations of \"$symbol\" found")
              printNotFoundHint(symbol, idx, "impl", batchMode)
            else
              println(s"Implementations of \"$symbol\" — ${results.size} found:")
              results.take(limit).foreach(s => println(fmt(s, workspace)))
              if results.size > limit then println(s"  ... and ${results.size - limit} more")

    case "refs" =>
      rest.headOption match
        case None => println("Usage: scalex refs <symbol>")
        case Some(symbol) =>
          val fmtRef: Reference => String =
            if contextLines > 0 then r => formatRefWithContext(r, workspace, contextLines)
            else r => formatRef(r, workspace)
          val targetPkgs = idx.symbolsByName.getOrElse(symbol.toLowerCase, Nil).map(_.packageName).toSet
          def filterRefs(refs: List[Reference]): List[Reference] =
            var r = refs
            if noTests then r = r.filter(ref => !isTestFile(ref.file, workspace))
            pathFilter.foreach { p => r = r.filter(ref => matchesPath(ref.file, p, workspace)) }
            r
          def filterByCategory(grouped: Map[RefCategory, List[Reference]]): Map[RefCategory, List[Reference]] =
            categoryFilter match
              case Some(catName) =>
                val validCats = RefCategory.values.map(_.toString.toLowerCase).toSet
                val lower = catName.toLowerCase
                if !validCats.contains(lower) then
                  System.err.println(s"Unknown category: $catName. Valid: ${RefCategory.values.map(_.toString).mkString(", ")}")
                  Map.empty
                else
                  grouped.filter((cat, _) => cat.toString.toLowerCase == lower)
              case None => grouped
          if jsonOutput then
            if categorize then
              val grouped = filterByCategory(idx.categorizeReferences(symbol).map((cat, refs) => (cat, filterRefs(refs))))
              val entries = grouped.map { (cat, refs) =>
                val arr = refs.take(limit).map(jRef).mkString("[", ",", "]")
                s""""${cat.toString}":$arr"""
              }.mkString(",")
              println(s"""{"categories":{$entries},"timedOut":${idx.timedOut}}""")
            else
              val results = filterRefs(idx.findReferences(symbol))
              val arr = results.take(limit).map(jRef).mkString("[", ",", "]")
              println(s"""{"results":$arr,"timedOut":${idx.timedOut}}""")
          else
            if categorize then
              val grouped = filterByCategory(idx.categorizeReferences(symbol).map((cat, refs) => (cat, filterRefs(refs))))
              val total = grouped.values.map(_.size).sum
              val suffix = if idx.timedOut then " (timed out — partial results)" else ""
              println(s"References to \"$symbol\" — $total found:$suffix")
              val confidenceOrder = List(Confidence.High, Confidence.Medium, Confidence.Low)
              confidenceOrder.foreach { conf =>
                val catRefs = grouped.flatMap { (cat, refs) =>
                  refs.map(r => (cat, r, idx.resolveConfidence(r, symbol, targetPkgs)))
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
                      entries.take(limit).foreach((_, r, _) => println(s"    ${fmtRef(r)}"))
                      if entries.size > limit then println(s"      ... and ${entries.size - limit} more")
                    }
                  }
              }
            else
              val results = filterRefs(idx.findReferences(symbol))
              val suffix = if idx.timedOut then " (timed out — partial results)" else ""
              println(s"References to \"$symbol\" — ${results.size} found:$suffix")
              val annotated = results.map(r => (r, idx.resolveConfidence(r, symbol, targetPkgs)))
              val sorted = annotated.sortBy { case (_, c) => c.ordinal }
              var lastConf: Option[Confidence] = None
              var shown = 0
              sorted.foreach { case (r, conf) =>
                if shown < limit then
                  if !lastConf.contains(conf) then
                    val label = conf match
                      case Confidence.High   => "High confidence"
                      case Confidence.Medium => "Medium confidence"
                      case Confidence.Low    => "Low confidence"
                    println(s"\n  [$label]")
                    lastConf = Some(conf)
                  println(fmtRef(r))
                  shown += 1
              }
              if results.size > limit then println(s"  ... and ${results.size - limit} more")

    case "imports" =>
      rest.headOption match
        case None => println("Usage: scalex imports <symbol>")
        case Some(symbol) =>
          var results = idx.findImports(symbol)
          if noTests then results = results.filter(r => !isTestFile(r.file, workspace))
          pathFilter.foreach { p => results = results.filter(r => matchesPath(r.file, p, workspace)) }
          if jsonOutput then
            val arr = results.take(limit).map(r => jsonRef(r, workspace)).mkString("[", ",", "]")
            println(s"""{"results":$arr,"timedOut":${idx.timedOut}}""")
          else
            if results.isEmpty then
              println(s"No imports of \"$symbol\" found")
              printNotFoundHint(symbol, idx, "imports", batchMode)
            else
              val suffix = if idx.timedOut then " (timed out — partial results)" else ""
              println(s"Imports of \"$symbol\" — ${results.size} found:$suffix")
              results.take(limit).foreach(r => println(formatRef(r, workspace)))
              if results.size > limit then println(s"  ... and ${results.size - limit} more")

    case "symbols" =>
      rest.headOption match
        case None => println("Usage: scalex symbols <file>")
        case Some(file) =>
          val results = idx.fileSymbols(file)
          if jsonOutput then
            val arr = results.map(s => jsonSymbol(s, workspace)).mkString("[", ",", "]")
            println(arr)
          else
            if results.isEmpty then println(s"No symbols found in $file")
            else
              println(s"Symbols in $file:")
              results.foreach(s => println(fmt(s, workspace)))

    case "file" =>
      rest.headOption match
        case None => println("Usage: scalex file <query>")
        case Some(query) =>
          val results = idx.searchFiles(query)
          if jsonOutput then
            val arr = results.take(limit).map(f => s""""${jsonEscape(f)}"""").mkString("[", ",", "]")
            println(arr)
          else
            if results.isEmpty then
              println(s"Found 0 files matching \"$query\"")
              println(s"  Hint: scalex indexes ${idx.fileCount} git-tracked .scala files.")
            else
              println(s"Found ${results.size} files matching \"$query\":")
              results.take(limit).foreach(f => println(s"  $f"))
              if results.size > limit then println(s"  ... and ${results.size - limit} more")

    case "packages" =>
      if jsonOutput then
        val arr = idx.packages.toList.sorted.map(p => s""""${jsonEscape(p)}"""").mkString("[", ",", "]")
        println(arr)
      else
        println(s"Packages (${idx.packages.size}):")
        idx.packages.toList.sorted.foreach(p => println(s"  $p"))

    case "annotated" =>
      rest.headOption match
        case None => println("Usage: scalex annotated <annotation>")
        case Some(query) =>
          val annot = query.stripPrefix("@")
          var results = idx.findAnnotated(annot)
          kindFilter.foreach { k =>
            val kk = k.toLowerCase
            results = results.filter(_.kind.toString.toLowerCase == kk)
          }
          if noTests then results = results.filter(s => !isTestFile(s.file, workspace))
          pathFilter.foreach { p => results = results.filter(s => matchesPath(s.file, p, workspace)) }
          if jsonOutput then
            val arr = results.take(limit).map(s => jsonSymbol(s, workspace)).mkString("[", ",", "]")
            println(arr)
          else
            if results.isEmpty then
              println(s"No symbols with @$annot annotation found")
            else
              println(s"Symbols annotated with @$annot — ${results.size} found:")
              results.take(limit).foreach(s => println(fmt(s, workspace)))
              if results.size > limit then println(s"  ... and ${results.size - limit} more")

    case "grep" =>
      val patternOpt = if grepPatterns.nonEmpty then Some(grepPatterns.mkString("|"))
                       else rest.headOption
      patternOpt match
        case None => println("Usage: scalex grep <pattern>")
        case Some(rawPattern) =>
          val (pattern, wasFixed) = fixPosixRegex(rawPattern)
          if wasFixed then
            System.err.println(s"  Note: auto-corrected POSIX regex to Java regex: \"$rawPattern\" → \"$pattern\"")
          val (results, grepTimedOut) = idx.grepFiles(pattern, noTests, pathFilter)
          if countOnly then
            val fileCount = results.map(_.file).distinct.size
            val hint = if wasFixed then s""","corrected":"$pattern"""" else ""
            if jsonOutput then println(s"""{"matches":${results.size},"files":$fileCount,"timedOut":$grepTimedOut$hint}""")
            else
              val suffix = if grepTimedOut then " (timed out — partial results)" else ""
              println(s"${results.size} matches across $fileCount files$suffix")
          else if jsonOutput then
            val arr = results.take(limit).map(jRef).mkString("[", ",", "]")
            val hint = if wasFixed then s""","corrected":"$pattern"""" else ""
            println(s"""{"results":$arr,"timedOut":$grepTimedOut$hint}""")
          else
            val suffix = if grepTimedOut then " (timed out — partial results)" else ""
            if results.isEmpty then
              println(s"No matches for \"$pattern\"$suffix")
            else
              val fmtRef: Reference => String =
                if contextLines > 0 then r => formatRefWithContext(r, workspace, contextLines)
                else r => formatRef(r, workspace)
              println(s"Matches for \"$pattern\" — ${results.size} found:$suffix")
              results.take(limit).foreach(r => println(fmtRef(r)))
              if results.size > limit then println(s"  ... and ${results.size - limit} more")

    case "members" =>
      rest.headOption match
        case None => println("Usage: scalex members <Symbol>")
        case Some(symbol) =>
          val typeKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
          var defs = idx.findDefinition(symbol).filter(s => typeKinds.contains(s.kind))
          if noTests then defs = defs.filter(s => !isTestFile(s.file, workspace))
          pathFilter.foreach { p => defs = defs.filter(s => matchesPath(s.file, p, workspace)) }
          kindFilter.foreach { k =>
            val kk = k.toLowerCase
            defs = defs.filter(_.kind.toString.toLowerCase == kk)
          }

          // Collect inherited members if --inherited is set
          def collectInherited(sym: SymbolInfo): List[(parentName: String, members: List[MemberInfo])] = {
            if !inherited then return Nil
            val visited = mutable.HashSet.empty[String]
            visited += sym.name.toLowerCase
            val ownMembers = extractMembers(sym.file, sym.name).map(m => (m.name, m.kind)).toSet
            val result = mutable.ListBuffer.empty[(String, List[MemberInfo])]

            def walk(parentNames: List[String]): Unit = {
              parentNames.foreach { pName =>
                if !visited.contains(pName.toLowerCase) then {
                  visited += pName.toLowerCase
                  val parentDefs = idx.findDefinition(pName).filter(s => typeKinds.contains(s.kind))
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

          if jsonOutput then
            val allMembers = defs.flatMap { s =>
              val ownMembers = extractMembers(s.file, symbol).map { m =>
                val rel = jsonEscape(workspace.relativize(s.file).toString)
                s"""{"name":"${jsonEscape(m.name)}","kind":"${m.kind.toString.toLowerCase}","line":${m.line},"signature":"${jsonEscape(m.signature)}","file":"$rel","owner":"${jsonEscape(symbol)}","ownerKind":"${s.kind.toString.toLowerCase}","package":"${jsonEscape(s.packageName)}","inherited":false}"""
              }
              val inheritedMembers = collectInherited(s).flatMap { case (parentName, members) =>
                val parentDef = idx.findDefinition(parentName).headOption
                members.map { m =>
                  val rel = parentDef.map(pd => jsonEscape(workspace.relativize(pd.file).toString)).getOrElse("")
                  s"""{"name":"${jsonEscape(m.name)}","kind":"${m.kind.toString.toLowerCase}","line":${m.line},"signature":"${jsonEscape(m.signature)}","file":"$rel","owner":"${jsonEscape(parentName)}","ownerKind":"inherited","package":"${parentDef.map(_.packageName).getOrElse("")}","inherited":true}"""
                }
              }
              ownMembers ++ inheritedMembers
            }
            println(allMembers.take(limit).mkString("[", ",", "]"))
          else
            if defs.isEmpty then
              println(s"No class/trait/object/enum \"$symbol\" found")
              printNotFoundHint(symbol, idx, "members", batchMode)
            else
              defs.foreach { s =>
                val rel = workspace.relativize(s.file)
                val pkg = if s.packageName.nonEmpty then s" (${s.packageName})" else ""
                val members = extractMembers(s.file, symbol)
                println(s"Members of ${s.kind.toString.toLowerCase} $symbol$pkg — $rel:${s.line}:")
                if members.isEmpty then println("  (no members)")
                else
                  println(s"  Defined in $symbol:")
                  members.take(limit).foreach { m =>
                    if verbose then
                      println(s"    ${m.kind.toString.toLowerCase.padTo(5, ' ')} ${m.signature.padTo(50, ' ')} :${m.line}")
                    else
                      println(s"    ${m.kind.toString.toLowerCase.padTo(5, ' ')} ${m.name.padTo(30, ' ')} :${m.line}")
                  }
                  if members.size > limit then println(s"    ... and ${members.size - limit} more")
                val inheritedGroups = collectInherited(s)
                inheritedGroups.foreach { case (parentName, pMembers) =>
                  println(s"  Inherited from $parentName:")
                  pMembers.take(limit).foreach { m =>
                    if verbose then
                      println(s"    ${m.kind.toString.toLowerCase.padTo(5, ' ')} ${m.signature.padTo(50, ' ')} :${m.line}")
                    else
                      println(s"    ${m.kind.toString.toLowerCase.padTo(5, ' ')} ${m.name.padTo(30, ' ')} :${m.line}")
                  }
                  if pMembers.size > limit then println(s"    ... and ${pMembers.size - limit} more")
                }
              }

    case "doc" =>
      rest.headOption match
        case None => println("Usage: scalex doc <Symbol>")
        case Some(symbol) =>
          var defs = idx.findDefinition(symbol)
          if noTests then defs = defs.filter(s => !isTestFile(s.file, workspace))
          pathFilter.foreach { p => defs = defs.filter(s => matchesPath(s.file, p, workspace)) }
          kindFilter.foreach { k =>
            val kk = k.toLowerCase
            defs = defs.filter(_.kind.toString.toLowerCase == kk)
          }
          if jsonOutput then
            val entries = defs.take(limit).map { s =>
              val rel = jsonEscape(workspace.relativize(s.file).toString)
              val doc = extractScaladoc(s.file, s.line).map(d => s""""${jsonEscape(d)}"""").getOrElse("null")
              s"""{"name":"${jsonEscape(s.name)}","kind":"${s.kind.toString.toLowerCase}","file":"$rel","line":${s.line},"package":"${jsonEscape(s.packageName)}","doc":$doc}"""
            }
            println(entries.mkString("[", ",", "]"))
          else
            if defs.isEmpty then
              println(s"Definition of \"$symbol\": not found")
              printNotFoundHint(symbol, idx, "doc", batchMode)
            else
              defs.take(limit).foreach { s =>
                val rel = workspace.relativize(s.file)
                val pkg = if s.packageName.nonEmpty then s" (${s.packageName})" else ""
                println(s"${s.kind.toString.toLowerCase} $symbol$pkg — $rel:${s.line}:")
                extractScaladoc(s.file, s.line) match
                  case Some(doc) => println(doc)
                  case None => println("  (no scaladoc)")
                println()
              }

    case "overview" =>
      val symbolsByKind = idx.symbols.groupBy(_.kind).toList.sortBy(-_._2.size)
      val topPackages = idx.packageToSymbols.toList.sortBy(-_._2.size).take(limit)
      val mostExtended = idx.parentIndex.toList
        .filter((name, _) => idx.symbolsByName.contains(name))
        .sortBy(-_._2.size).take(limit)

      // Architecture: compute package dependency graph from imports
      val archPkgDeps: Map[String, Set[String]] = if architecture then {
        val deps = mutable.HashMap.empty[String, mutable.HashSet[String]]
        idx.symbols.groupBy(_.file).foreach { (file, syms) =>
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
                  if importPkg != filePkg && idx.packages.contains(importPkg) then {
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
      val hubTypes: List[(name: String, score: Int)] = if architecture then {
        val refCounts = mutable.HashMap.empty[String, Int]
        idx.parentIndex.foreach { (name, impls) =>
          if idx.symbolsByName.contains(name) then
            refCounts(name) = refCounts.getOrElse(name, 0) + impls.size
        }
        refCounts.toList.sortBy(-_._2).take(limit)
      } else Nil

      if jsonOutput then
        val kindJson = symbolsByKind.map((k, v) => s""""${k.toString.toLowerCase}":${v.size}""").mkString("{", ",", "}")
        val pkgJson = topPackages.map((p, s) => s"""{"package":"${jsonEscape(p)}","count":${s.size}}""").mkString("[", ",", "]")
        val extJson = mostExtended.map((p, s) => s"""{"name":"${jsonEscape(p)}","implementations":${s.size}}""").mkString("[", ",", "]")
        if architecture then
          val depsJson = archPkgDeps.map { (pkg, deps) =>
            val dArr = deps.map(d => s""""${jsonEscape(d)}"""").mkString("[", ",", "]")
            s""""${jsonEscape(pkg)}":$dArr"""
          }.mkString("{", ",", "}")
          val hubJson = hubTypes.map((n, c) => s"""{"name":"${jsonEscape(n)}","score":$c}""").mkString("[", ",", "]")
          println(s"""{"fileCount":${idx.fileCount},"symbolCount":${idx.symbols.size},"packageCount":${idx.packages.size},"symbolsByKind":$kindJson,"topPackages":$pkgJson,"mostExtended":$extJson,"packageDependencies":$depsJson,"hubTypes":$hubJson}""")
        else
          println(s"""{"fileCount":${idx.fileCount},"symbolCount":${idx.symbols.size},"packageCount":${idx.packages.size},"symbolsByKind":$kindJson,"topPackages":$pkgJson,"mostExtended":$extJson}""")
      else
        println(s"Project overview (${idx.fileCount} files, ${idx.symbols.size} symbols):\n")
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
        if architecture then
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

    case "body" =>
      rest.headOption match
        case None => println("Usage: scalex body <symbol> [--in <owner>]")
        case Some(symbol) =>
          // Find files containing the symbol
          var defs = idx.findDefinition(symbol)
          if noTests then defs = defs.filter(s => !isTestFile(s.file, workspace))
          pathFilter.foreach { p => defs = defs.filter(s => matchesPath(s.file, p, workspace)) }
          // Also look in type definitions for member bodies
          val typeKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
          val filesToSearch = if defs.nonEmpty then {
            defs.map(_.file).distinct
          } else {
            // If not found directly, search all files for member bodies
            inOwner match
              case Some(owner) =>
                idx.findDefinition(owner).filter(s => typeKinds.contains(s.kind)).map(_.file).distinct
              case None =>
                idx.symbols.filter(s => typeKinds.contains(s.kind)).map(_.file).distinct
          }
          // Collect (file, body) pairs
          val resultsWithFiles = filesToSearch.flatMap { f =>
            extractBody(f, symbol, inOwner).map(b => (f, b))
          }
          if jsonOutput then
            val arr = resultsWithFiles.take(limit).map { case (file, b) =>
              val rel = jsonEscape(workspace.relativize(file).toString)
              s"""{"name":"${jsonEscape(b.symbolName)}","owner":"${jsonEscape(b.ownerName)}","file":"$rel","startLine":${b.startLine},"endLine":${b.endLine},"body":"${jsonEscape(b.sourceText)}"}"""
            }.mkString("[", ",", "]")
            println(arr)
          else
            if resultsWithFiles.isEmpty then
              println(s"No body found for \"$symbol\"")
              printNotFoundHint(symbol, idx, "body", batchMode)
            else
              resultsWithFiles.take(limit).foreach { case (file, b) =>
                val ownerStr = if b.ownerName.nonEmpty then s" — ${b.ownerName}" else ""
                val rel = workspace.relativize(file)
                println(s"Body of ${b.symbolName}$ownerStr — $rel:${b.startLine}:")
                val bodyLines = b.sourceText.split("\n")
                bodyLines.zipWithIndex.foreach { case (line, i) =>
                  println(s"  ${(b.startLine + i).toString.padTo(4, ' ')} | $line")
                }
                println()
              }

    case "tests" =>
      val nameFilter = rest.headOption
      var filesToScan = idx.gitFiles.map(_.path).filter(f => isTestFile(f, workspace))
      pathFilter.foreach { p => filesToScan = filesToScan.filter(f => matchesPath(f, p, workspace)) }
      val allSuites = filesToScan.flatMap(extractTests).map { suite =>
        nameFilter match
          case Some(pattern) =>
            val lower = pattern.toLowerCase
            val filtered = suite.tests.filter(_.name.toLowerCase.contains(lower))
            suite.copy(tests = filtered)
          case None => suite
      }.filter(_.tests.nonEmpty)
      val showBody = nameFilter.isDefined
      if jsonOutput then
        val arr = allSuites.take(limit).map { suite =>
          val rel = jsonEscape(workspace.relativize(suite.file).toString)
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
          allSuites.take(limit).foreach { suite =>
            val rel = workspace.relativize(suite.file)
            println(s"${suite.name} — $rel:${suite.line}:")
            suite.tests.foreach { tc =>
              println(s"  test  \"${tc.name}\"  :${tc.line}")
              if showBody || verbose then
                val bodies = extractBody(suite.file, tc.name, Some(suite.name))
                bodies.headOption.foreach { b =>
                  val bodyLines = b.sourceText.split("\n")
                  bodyLines.zipWithIndex.foreach { case (line, i) =>
                    println(s"    ${(b.startLine + i).toString.padTo(4, ' ')} | $line")
                  }
                  println()
                }
            }
            if !showBody && !verbose then println()
          }

    case "coverage" =>
      rest.headOption match
        case None => println("Usage: scalex coverage <symbol>")
        case Some(symbol) =>
          val refs = idx.findReferences(symbol)
          val testRefs = refs.filter(r => isTestFile(r.file, workspace))
          val testFiles = testRefs.map(r => workspace.relativize(r.file).toString).distinct
          if jsonOutput then
            val refsJson = testRefs.take(limit).map(r => jRef(r)).mkString("[", ",", "]")
            println(s"""{"symbol":"${jsonEscape(symbol)}","testFileCount":${testFiles.size},"referenceCount":${testRefs.size},"references":$refsJson}""")
          else
            if testRefs.isEmpty then
              if refs.isEmpty then
                println(s"""Coverage of "$symbol" — no references found""")
                printNotFoundHint(symbol, idx, "coverage", batchMode)
              else
                println(s"""Coverage of "$symbol" — ${refs.size} refs but 0 in test files""")
            else
              println(s"""Coverage of "$symbol" — ${testRefs.size} refs in ${testFiles.size} test files:""")
              testFiles.sorted.foreach { f =>
                val fileRefs = testRefs.filter(r => workspace.relativize(r.file).toString == f)
                println(s"  $f")
                fileRefs.take(limit).foreach { r =>
                  println(s"    :${r.line}  ${r.contextLine}")
                }
              }

    case "hierarchy" =>
      rest.headOption match
        case None => println("Usage: scalex hierarchy <symbol> [--up] [--down]")
        case Some(symbol) =>
          buildHierarchy(idx, symbol, goUp, goDown, workspace) match
            case None =>
              println(s"No definition of \"$symbol\" found")
              printNotFoundHint(symbol, idx, "hierarchy", batchMode)
            case Some(tree) =>
              def nodeJson(n: HierarchyNode): String = {
                val file = n.file.map(f => s""""${jsonEscape(workspace.relativize(f).toString)}"""").getOrElse("null")
                val kind = n.kind.map(k => s""""${k.toString.toLowerCase}"""").getOrElse("null")
                val line = n.line.map(_.toString).getOrElse("null")
                s"""{"name":"${jsonEscape(n.name)}","kind":$kind,"file":$file,"line":$line,"package":"${jsonEscape(n.packageName)}","isExternal":${n.isExternal}}"""
              }
              def treeJson(t: HierarchyTree): String = {
                val ps = t.parents.map(treeJson).mkString("[", ",", "]")
                val cs = t.children.map(treeJson).mkString("[", ",", "]")
                s"""{"node":${nodeJson(t.root)},"parents":$ps,"children":$cs}"""
              }
              if jsonOutput then
                println(treeJson(tree))
              else
                val rootNode = tree.root
                val pkg = if rootNode.packageName.nonEmpty then s" (${rootNode.packageName})" else ""
                val kind = rootNode.kind.map(_.toString.toLowerCase).getOrElse("unknown")
                val loc = rootNode.file.map(f => s" — ${workspace.relativize(f)}:${rootNode.line.getOrElse(0)}").getOrElse("")
                println(s"Hierarchy of $kind ${rootNode.name}$pkg$loc:")
                if goUp then
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
                                 else n.file.map(f => s" — ${workspace.relativize(f)}:${n.line.getOrElse(0)}").getOrElse("")
                      println(s"$prefix$nkind${n.name}$npkg$nloc")
                      printParents(pt.parents, nextIndent)
                    }
                  }
                  if tree.parents.isEmpty then println("    (none)")
                  else printParents(tree.parents, "    ")
                if goDown then
                  println("  Children:")
                  def printChildren(children: List[HierarchyTree], indent: String): Unit = {
                    children.zipWithIndex.foreach { case (ct, i) =>
                      val isLast = i == children.size - 1
                      val prefix = if isLast then s"$indent└── " else s"$indent├── "
                      val nextIndent = if isLast then s"$indent    " else s"$indent│   "
                      val n = ct.root
                      val npkg = if n.packageName.nonEmpty then s" (${n.packageName})" else ""
                      val nkind = n.kind.map(_.toString.toLowerCase + " ").getOrElse("")
                      val nloc = n.file.map(f => s" — ${workspace.relativize(f)}:${n.line.getOrElse(0)}").getOrElse("")
                      println(s"$prefix$nkind${n.name}$npkg$nloc")
                      printChildren(ct.children, nextIndent)
                    }
                  }
                  if tree.children.isEmpty then println("    (none)")
                  else printChildren(tree.children, "    ")

    case "overrides" =>
      rest.headOption match
        case None => println("Usage: scalex overrides <method> [--of <trait>]")
        case Some(methodName) =>
          val results = findOverrides(idx, methodName, ofTrait, limit)
          if jsonOutput then
            val arr = results.map { o =>
              val rel = jsonEscape(workspace.relativize(o.file).toString)
              s"""{"enclosingClass":"${jsonEscape(o.enclosingClass)}","enclosingKind":"${o.enclosingKind.toString.toLowerCase}","file":"$rel","line":${o.line},"signature":"${jsonEscape(o.signature)}","package":"${jsonEscape(o.packageName)}"}"""
            }.mkString("[", ",", "]")
            println(arr)
          else
            if results.isEmpty then
              val ofStr = ofTrait.map(t => s" of $t").getOrElse("")
              println(s"No overrides of \"$methodName\"$ofStr found")
              printNotFoundHint(methodName, idx, "overrides", batchMode)
            else
              val ofStr = ofTrait.map(t => s" (in implementations of $t)").getOrElse("")
              println(s"Overrides of $methodName$ofStr — ${results.size} found:")
              results.foreach { o =>
                val rel = workspace.relativize(o.file)
                val pkg = if o.packageName.nonEmpty then s" (${o.packageName})" else ""
                println(s"  ${o.enclosingClass}$pkg — $rel:${o.line}")
                println(s"    ${o.signature}")
              }

    case "explain" =>
      rest.headOption match
        case None => println("Usage: scalex explain <symbol>")
        case Some(symbol) =>
          var defs = idx.findDefinition(symbol)
          if noTests then defs = defs.filter(s => !isTestFile(s.file, workspace))
          pathFilter.foreach { p => defs = defs.filter(s => matchesPath(s.file, p, workspace)) }
          if defs.isEmpty then
            if jsonOutput then println("""{"error":"not found"}""")
            else
              println(s"No definition of \"$symbol\" found")
              printNotFoundHint(symbol, idx, "explain", batchMode)
          else
            val sym = defs.head
            val rel = workspace.relativize(sym.file)
            val pkg = if sym.packageName.nonEmpty then s" (${sym.packageName})" else ""

            // Scaladoc
            val doc = extractScaladoc(sym.file, sym.line)

            // Members (for types)
            val typeKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
            val members = if typeKinds.contains(sym.kind) then extractMembers(sym.file, symbol).take(10) else Nil

            // Implementations
            val impls = idx.findImplementations(symbol).take(implLimit)

            // Import count
            val importCount = idx.findImports(symbol, timeoutMs = 3000).size

            if jsonOutput then
              val docJson = doc.map(d => s""""${jsonEscape(d)}"""").getOrElse("null")
              val membersJson = members.map { m =>
                s"""{"name":"${jsonEscape(m.name)}","kind":"${m.kind.toString.toLowerCase}","line":${m.line},"signature":"${jsonEscape(m.signature)}"}"""
              }.mkString("[", ",", "]")
              val implsJson = impls.map(s => jsonSymbol(s, workspace)).mkString("[", ",", "]")
              println(s"""{"definition":${jsonSymbol(sym, workspace)},"doc":$docJson,"members":$membersJson,"implementations":$implsJson,"importCount":$importCount}""")
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
                impls.foreach(s => println(formatSymbol(s, workspace)))
                println()
              println(s"  Imported by: $importCount files")

    case "deps" =>
      rest.headOption match
        case None => println("Usage: scalex deps <symbol>")
        case Some(symbol) =>
          val (importDeps, bodyDeps) = extractDeps(idx, symbol, workspace)
          if jsonOutput then
            val iArr = importDeps.map { d =>
              val file = d.file.map(f => s""""${jsonEscape(workspace.relativize(f).toString)}"""").getOrElse("null")
              val line = d.line.map(_.toString).getOrElse("null")
              s"""{"name":"${jsonEscape(d.name)}","kind":"${jsonEscape(d.kind)}","file":$file,"line":$line,"package":"${jsonEscape(d.packageName)}"}"""
            }.mkString("[", ",", "]")
            val bArr = bodyDeps.map { d =>
              val file = d.file.map(f => s""""${jsonEscape(workspace.relativize(f).toString)}"""").getOrElse("null")
              val line = d.line.map(_.toString).getOrElse("null")
              s"""{"name":"${jsonEscape(d.name)}","kind":"${jsonEscape(d.kind)}","file":$file,"line":$line,"package":"${jsonEscape(d.packageName)}"}"""
            }.mkString("[", ",", "]")
            println(s"""{"imports":$iArr,"bodyReferences":$bArr}""")
          else
            if importDeps.isEmpty && bodyDeps.isEmpty then
              println(s"No dependencies found for \"$symbol\"")
              printNotFoundHint(symbol, idx, "deps", batchMode)
            else
              println(s"Dependencies of \"$symbol\":")
              if importDeps.nonEmpty then
                println(s"\n  Imports:")
                importDeps.take(limit).foreach { d =>
                  val loc = d.file.map(f => s" — ${workspace.relativize(f)}:${d.line.getOrElse(0)}").getOrElse("")
                  println(s"    ${d.kind.padTo(9, ' ')} ${d.name}$loc")
                }
                if importDeps.size > limit then println(s"    ... and ${importDeps.size - limit} more")
              if bodyDeps.nonEmpty then
                println(s"\n  Body references:")
                bodyDeps.take(limit).foreach { d =>
                  val loc = d.file.map(f => s" — ${workspace.relativize(f)}:${d.line.getOrElse(0)}").getOrElse("")
                  println(s"    ${d.kind.padTo(9, ' ')} ${d.name}$loc")
                }
                if bodyDeps.size > limit then println(s"    ... and ${bodyDeps.size - limit} more")

    case "context" =>
      rest.headOption match
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
                val resolved = if Path.of(filePath).isAbsolute then Path.of(filePath) else workspace.resolve(filePath)
                val scopes = extractScopes(resolved, line)
                if jsonOutput then
                  val arr = scopes.map { s =>
                    s"""{"name":"${jsonEscape(s.name)}","kind":"${jsonEscape(s.kind)}","line":${s.line}}"""
                  }.mkString("[", ",", "]")
                  val rel = jsonEscape(workspace.relativize(resolved).toString)
                  println(s"""{"file":"$rel","line":$line,"scopes":$arr}""")
                else
                  val rel = workspace.relativize(resolved)
                  println(s"Context at $rel:$line:")
                  if scopes.isEmpty then println("  (no enclosing scopes found)")
                  else
                    scopes.foreach { s =>
                      println(s"  ${s.kind.padTo(9, ' ')} ${s.name} (line ${s.line})")
                    }

    case "diff" =>
      rest.headOption match
        case None => println("Usage: scalex diff <git-ref> (e.g. scalex diff HEAD~1)")
        case Some(ref) =>
          val changedFiles = runGitDiff(workspace, ref)
          if changedFiles.isEmpty then
            if jsonOutput then println("""{"added":[],"removed":[],"modified":[]}""")
            else println(s"No Scala files changed compared to $ref")
          else
            val added = mutable.ListBuffer.empty[DiffSymbol]
            val removed = mutable.ListBuffer.empty[DiffSymbol]
            val modified = mutable.ListBuffer.empty[(before: DiffSymbol, after: DiffSymbol)]

            changedFiles.take(limit * 5).foreach { relPath =>
              val currentPath = workspace.resolve(relPath)
              val currentSource = try Some(Files.readString(currentPath)) catch { case _: Exception => None }
              val oldSource = gitShowFile(workspace, ref, relPath)

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

            if jsonOutput then
              def diffSymJson(s: DiffSymbol): String =
                s"""{"name":"${jsonEscape(s.name)}","kind":"${s.kind.toString.toLowerCase}","file":"${jsonEscape(s.file)}","line":${s.line},"package":"${jsonEscape(s.packageName)}","signature":"${jsonEscape(s.signature)}"}"""
              val addedJson = added.take(limit).map(diffSymJson).mkString("[", ",", "]")
              val removedJson = removed.take(limit).map(diffSymJson).mkString("[", ",", "]")
              val modifiedJson = modified.take(limit).map { case (o, n) =>
                s"""{"old":${diffSymJson(o)},"new":${diffSymJson(n)}}"""
              }.mkString("[", ",", "]")
              println(s"""{"ref":"${jsonEscape(ref)}","filesChanged":${changedFiles.size},"added":$addedJson,"removed":$removedJson,"modified":$modifiedJson}""")
            else
              println(s"Symbol changes compared to $ref (${changedFiles.size} files changed):")
              if added.nonEmpty then
                println(s"\n  Added (${added.size}):")
                added.take(limit).foreach { s =>
                  println(s"    + ${s.kind.toString.toLowerCase.padTo(9, ' ')} ${s.name} — ${s.file}:${s.line}")
                }
                if added.size > limit then println(s"    ... and ${added.size - limit} more")
              if removed.nonEmpty then
                println(s"\n  Removed (${removed.size}):")
                removed.take(limit).foreach { s =>
                  println(s"    - ${s.kind.toString.toLowerCase.padTo(9, ' ')} ${s.name} — ${s.file}:${s.line}")
                }
                if removed.size > limit then println(s"    ... and ${removed.size - limit} more")
              if modified.nonEmpty then
                println(s"\n  Modified (${modified.size}):")
                modified.take(limit).foreach { case (o, n) =>
                  println(s"    ~ ${n.kind.toString.toLowerCase.padTo(9, ' ')} ${n.name} — ${n.file}:${n.line}")
                }
                if modified.size > limit then println(s"    ... and ${modified.size - limit} more")
              if added.isEmpty && removed.isEmpty && modified.isEmpty then
                println("  No symbol-level changes detected")

    case "ast-pattern" =>
      val results = astPatternSearch(idx, workspace, hasMethodFilter, extendsFilter, bodyContainsFilter, noTests, pathFilter, limit)
      if jsonOutput then
        val arr = results.map { m =>
          val rel = jsonEscape(workspace.relativize(m.file).toString)
          s"""{"name":"${jsonEscape(m.name)}","kind":"${m.kind.toString.toLowerCase}","file":"$rel","line":${m.line},"package":"${jsonEscape(m.packageName)}","signature":"${jsonEscape(m.signature)}"}"""
        }.mkString("[", ",", "]")
        println(arr)
      else
        val filters = List(
          hasMethodFilter.map(m => s"has-method=$m"),
          extendsFilter.map(e => s"extends=$e"),
          bodyContainsFilter.map(b => s"body-contains=\"$b\"")
        ).flatten.mkString(", ")
        if results.isEmpty then
          println(s"No types matching AST pattern ($filters)")
        else
          println(s"Types matching AST pattern ($filters) — ${results.size} found:")
          results.foreach { m =>
            val rel = workspace.relativize(m.file)
            val pkg = if m.packageName.nonEmpty then s" (${m.packageName})" else ""
            println(s"  ${m.kind.toString.toLowerCase.padTo(9, ' ')} ${m.name}$pkg — $rel:${m.line}")
          }

    case other =>
      println(s"Unknown command: $other")

@main def main(args: String*): Unit =
  val argList = args.toList

  if argList.contains("--version") then
    println(ScalexVersion)
    return

  val limit = argList.indexOf("--limit") match
    case -1 => 20
    case i => argList.lift(i + 1).flatMap(_.toIntOption).getOrElse(20)
  val kindFilter = argList.indexOf("--kind") match
    case -1 => None
    case i => argList.lift(i + 1)
  val verbose = argList.contains("--verbose")
  val categorize = !argList.contains("--flat")
  val noTests = argList.contains("--no-tests")
  val pathFilter: Option[String] = argList.indexOf("--path") match
    case -1 => None
    case i => argList.lift(i + 1).map(p => p.stripPrefix("/"))
  val contextLines: Int = argList.indexOf("-C") match
    case -1 => 0
    case i => argList.lift(i + 1).flatMap(_.toIntOption).getOrElse(0)
  val jsonOutput = argList.contains("--json")
  val countOnly = argList.contains("--count")
  val searchMode: Option[String] =
    if argList.contains("--exact") then Some("exact")
    else if argList.contains("--prefix") then Some("prefix")
    else None
  val definitionsOnly = argList.contains("--definitions-only")
  val categoryFilter: Option[String] = argList.indexOf("--category") match
    case -1 => None
    case i => argList.lift(i + 1)
  val grepPatterns: List[String] = argList.zipWithIndex.collect {
    case ("-e", i) if argList.lift(i + 1).exists(a => !a.startsWith("-")) => argList(i + 1)
  }
  val explicitWorkspace: Option[String] =
    val longIdx = argList.indexOf("--workspace")
    val shortIdx = argList.indexOf("-w")
    val idx = if longIdx >= 0 then longIdx else shortIdx
    if idx >= 0 then argList.lift(idx + 1) else None

  // New flags for new commands
  val inOwner: Option[String] = argList.indexOf("--in") match
    case -1 => None
    case i => argList.lift(i + 1)
  val ofTrait: Option[String] = argList.indexOf("--of") match
    case -1 => None
    case i => argList.lift(i + 1)
  val implLimit: Int = argList.indexOf("--impl-limit") match
    case -1 => 5
    case i => argList.lift(i + 1).flatMap(_.toIntOption).getOrElse(5)
  val goUp = !argList.contains("--down") || argList.contains("--up")
  val goDown = !argList.contains("--up") || argList.contains("--down")
  val inherited = argList.contains("--inherited")
  val architecture = argList.contains("--architecture")
  val hasMethodFilter: Option[String] = argList.indexOf("--has-method") match
    case -1 => None
    case i => argList.lift(i + 1)
  val extendsFilter: Option[String] = argList.indexOf("--extends") match
    case -1 => None
    case i => argList.lift(i + 1)
  val bodyContainsFilter: Option[String] = argList.indexOf("--body-contains") match
    case -1 => None
    case i => argList.lift(i + 1)

  val flagsWithArgs = Set("--limit", "--kind", "--workspace", "-w", "--path", "-C", "-e", "--category",
                           "--in", "--of", "--impl-limit", "--has-method", "--extends", "--body-contains")
  val cleanArgs = argList.filterNot(a => a.startsWith("--") || a == "-w" || a == "-C" || a == "-e" || a == "-c" || a == "--flat" || {
    val prev = argList.indexOf(a) - 1
    prev >= 0 && flagsWithArgs.contains(argList(prev))
  })

  cleanArgs match
    case Nil | List("help") =>
      println("""Scalex — Scala code intelligence for AI agents
        |
        |Commands:
        |  scalex search <query>           Search symbols by name          (aka: find symbol)
        |  scalex def <symbol>             Where is this symbol defined?   (aka: find definition)
        |  scalex impl <trait>             Who extends this trait/class?   (aka: find implementations)
        |  scalex refs <symbol>            Who uses this symbol?           (aka: find references)
        |  scalex imports <symbol>         Who imports this symbol?        (aka: import graph)
        |  scalex members <symbol>         What's inside this class/trait? (aka: list members)
        |  scalex doc <symbol>             Show scaladoc for a symbol      (aka: show docs)
        |  scalex overview                 Codebase summary                (aka: project overview)
        |  scalex symbols <file>           What's defined in this file?    (aka: file symbols)
        |  scalex file <query>             Search files by name            (aka: find file)
        |  scalex annotated <annotation>   Find symbols with annotation    (aka: find annotated)
        |  scalex grep <pattern>           Regex search in file contents   (aka: content search)
        |  scalex packages                 What packages exist?            (aka: list packages)
        |  scalex index                    Rebuild the index               (aka: reindex)
        |  scalex batch                    Run multiple queries at once    (aka: batch mode)
        |  scalex body <symbol>            Extract method/val/class body   (aka: show source)
        |  scalex hierarchy <symbol>       Full inheritance tree           (aka: type hierarchy)
        |  scalex overrides <method>       Find override implementations   (aka: find overrides)
        |  scalex explain <symbol>         Composite one-shot summary      (aka: explain symbol)
        |  scalex deps <symbol>            Show symbol dependencies        (aka: dependency graph)
        |  scalex context <file:line>      Show enclosing scopes at line   (aka: scope chain)
        |  scalex diff <git-ref>           Symbol-level diff vs git ref    (aka: symbol diff)
        |  scalex ast-pattern              Structural AST search           (aka: pattern search)
        |  scalex tests                    List test cases structurally    (aka: find tests)
        |  scalex coverage <symbol>        Is this symbol tested?          (aka: test coverage)
        |
        |Options:
        |  -w, --workspace PATH  Set workspace path (default: current directory)
        |  --limit N             Max results (default: 20)
        |  --kind K              Filter by kind: class, trait, object, def, val, type, enum, given, extension
        |  --verbose             Show signatures and extends clauses
        |  --categorize, -c      Group refs by category (default; kept for backwards compatibility)
        |  --flat                Refs: flat list instead of categorized (overrides default)
        |  --definitions-only    Search: only return class/trait/object/enum definitions
        |  --category CAT        Refs: filter to a single category (Definition/ExtendedBy/ImportedBy/UsedAsType/Usage/Comment)
        |  --no-tests            Exclude test files (test/, tests/, testing/, bench-*, *Spec.scala, etc.)
        |  --path PREFIX         Restrict results to files under PREFIX (e.g. compiler/src/)
        |  -C N                  Show N context lines around each reference (refs, grep)
        |  -e PATTERN            Grep: additional pattern (combine multiple with |); repeatable
        |  --count               Grep: show match/file count only, no full results
        |  --exact               Search: only exact name matches
        |  --prefix              Search: only exact + prefix matches
        |  --json                Output results as JSON (structured output for programmatic use)
        |  --version             Print version and exit
        |  --in OWNER            Body: restrict to members of the given enclosing type
        |  --of TRAIT            Overrides: restrict to implementations of the given trait
        |  --impl-limit N        Explain: max implementations to show (default: 5)
        |  --up                  Hierarchy: show only parents (default: both)
        |  --down                Hierarchy: show only children (default: both)
        |  --inherited           Members: include inherited members from parent types
        |  --architecture        Overview: show package dependency graph and hub types
        |  --has-method NAME     AST pattern: match types that have a method with NAME
        |  --extends TRAIT       AST pattern: match types that extend TRAIT
        |  --body-contains PAT   AST pattern: match types whose body contains PAT
        |
        |All commands accept an optional [workspace] positional arg or -w flag (default: current directory).
        |First run indexes the project (~3s for 14k files). Subsequent runs use cache (~300ms).
        |""".stripMargin)

    case "batch" :: rest =>
      val workspace = resolveWorkspace(explicitWorkspace.orElse(rest.headOption).getOrElse("."))
      val idx = WorkspaceIndex(workspace, needBlooms = true)
      idx.index()
      val reader = BufferedReader(InputStreamReader(System.in))
      var line = reader.readLine()
      while line != null do
        val parts = line.trim.split("\\s+").toList
        if parts.nonEmpty && parts.head.nonEmpty then
          val batchCmd = parts.head
          val batchRest = parts.tail
          println(s">>> $line")
          runCommand(batchCmd, batchRest, idx, workspace, limit, kindFilter, verbose, categorize, noTests, pathFilter, contextLines, jsonOutput, grepPatterns, countOnly, batchMode = true, searchMode, definitionsOnly, categoryFilter,
                     inOwner = inOwner, ofTrait = ofTrait, implLimit = implLimit, goUp = goUp, goDown = goDown, inherited = inherited, architecture = architecture,
                     hasMethodFilter = hasMethodFilter, extendsFilter = extendsFilter, bodyContainsFilter = bodyContainsFilter)
          println()
        line = reader.readLine()

    case cmd :: rest =>
      val (workspace, cmdRest) = explicitWorkspace match
        case Some(ws) =>
          (resolveWorkspace(ws), rest)
        case None =>
          cmd match
            case "index" | "packages" | "overview" | "ast-pattern" =>
              (resolveWorkspace(rest.headOption.getOrElse(".")), rest)
            case _ =>
              rest match
                case arg :: Nil => (resolveWorkspace("."), List(arg))
                case ws :: arg :: tail => (resolveWorkspace(ws), arg :: tail)
                case Nil => (resolveWorkspace("."), Nil)

      val bloomCmds = Set("refs", "imports", "coverage")
      val idx = WorkspaceIndex(workspace, needBlooms = bloomCmds.contains(cmd))
      idx.index()
      runCommand(cmd, cmdRest, idx, workspace, limit, kindFilter, verbose, categorize, noTests, pathFilter, contextLines, jsonOutput, grepPatterns, countOnly, searchMode = searchMode, definitionsOnly = definitionsOnly, categoryFilter = categoryFilter,
                 inOwner = inOwner, ofTrait = ofTrait, implLimit = implLimit, goUp = goUp, goDown = goDown, inherited = inherited, architecture = architecture,
                 hasMethodFilter = hasMethodFilter, extendsFilter = extendsFilter, bodyContainsFilter = bodyContainsFilter)
