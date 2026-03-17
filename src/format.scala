import java.nio.file.Path
import scala.jdk.CollectionConverters.*

// ── Formatting ──────────────────────────────────────────────────────────────

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
  val tpParents = s.typeParamParents.map(p => s""""${jsonEscape(p)}"""").mkString("[", ",", "]")
  val annots = s.annotations.map(a => s""""${jsonEscape(a)}"""").mkString("[", ",", "]")
  s"""{"name":"${jsonEscape(s.name)}","kind":"${s.kind.toString.toLowerCase}","file":"$rel","line":${s.line},"package":"${jsonEscape(s.packageName)}","parents":$parents,"typeParamParents":$tpParents,"signature":"${jsonEscape(s.signature)}","annotations":$annots}"""

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

// ── Render ─────────────────────────────────────────────────────────────────

def render(result: CmdResult, ctx: CommandContext): Unit = {
  import CmdResult.*
  result match {
    case r: SymbolList      => renderSymbolList(r, ctx)
    case r: RefList          => renderRefList(r, ctx)
    case r: CategorizedRefs  => renderCategorizedRefs(r, ctx)
    case r: FlatRefs         => renderFlatRefs(r, ctx)
    case r: StringList       => renderStringList(r, ctx)
    case r: IndexStats       => renderIndexStats(r, ctx)
    case r: MemberSections   => renderMemberSections(r, ctx)
    case r: DocEntries       => renderDocEntries(r, ctx)
    case r: Overview         => renderOverview(r, ctx)
    case r: SourceBlocks     => renderSourceBlocks(r, ctx)
    case r: TestSuites       => renderTestSuites(r, ctx)
    case r: CoverageReport   => renderCoverageReport(r, ctx)
    case r: HierarchyResult  => renderHierarchyResult(r, ctx)
    case r: OverrideList     => renderOverrideList(r, ctx)
    case r: Explanation      => renderExplanation(r, ctx)
    case r: Dependencies     => renderDependencies(r, ctx)
    case r: Scopes           => renderScopes(r, ctx)
    case r: SymbolDiff       => renderSymbolDiff(r, ctx)
    case r: AstMatches       => renderAstMatches(r, ctx)
    case r: GrepCount        => renderGrepCount(r, ctx)
    case r: Packages         => renderPackages(r, ctx)
    case r: PackageSymbols   => renderPackageSymbols(r, ctx)
    case r: PackageSummary   => renderPackageSummary(r, ctx)
    case r: ApiSurface       => renderApiSurface(r, ctx)
    case r: RefsTop          => renderRefsTop(r, ctx)
    case r: RefsSummary      => renderRefsSummary(r, ctx)
    case r: Entrypoints      => renderEntrypoints(r, ctx)
    case r: NotFound         => renderNotFound(r, ctx)
    case r: UsageError       => println(r.message)
  }
}

private def renderHint(h: NotFoundHint): Unit = {
  if h.batchMode then
    if h.suggestions.nonEmpty then
      println(s"  not found (0 matches in ${h.fileCount} files). Did you mean: ${h.suggestions.take(3).mkString(", ")}?")
    else
      println(s"  not found (0 matches in ${h.fileCount} files)")
  else {
    if h.looksLikePath then
      println(s"""  Note: "${h.symbol}" looks like a path. Did you mean: scalex ${h.cmd} -w <workspace> ${h.symbol}?""")
    if h.suggestions.nonEmpty then {
      println(s"  Did you mean:")
      h.suggestions.foreach(s => println(s"    $s"))
    }
    println(s"  Hint: scalex indexes ${h.fileCount} git-tracked .scala/.java files.")
    if h.parseFailures > 0 then
      println(s"  ${h.parseFailures} files had parse errors (run `scalex index --verbose` to list them).")
    println(s"  Fallback: use Grep, Glob, or Read tools to search manually.")
  }
}

private def mkNotFoundHint(symbol: String, ctx: CommandContext, cmd: String): NotFoundHint =
  mkNotFoundWithSuggestions(symbol, ctx, cmd)

private def renderSymbolList(r: CmdResult.SymbolList, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val items = if r.truncate then r.symbols.take(ctx.limit) else r.symbols
    val arr = items.map(s => jsonSymbol(s, ctx.workspace)).mkString("[", ",", "]")
    println(arr)
  } else {
    if r.symbols.isEmpty then {
      if r.emptyMessage.nonEmpty then println(r.emptyMessage)
    } else {
      println(r.header)
      val items = if r.truncate then r.symbols.take(ctx.limit) else r.symbols
      items.foreach(s => println(ctx.fmt(s, ctx.workspace)))
      if r.truncate && r.total > ctx.limit then println(s"  ... and ${r.total - ctx.limit} more")
    }
  }
}

private def renderRefList(r: CmdResult.RefList, ctx: CommandContext): Unit = {
  r.stderrHint.foreach(System.err.println)
  if ctx.jsonOutput then {
    val jFn: Reference => String = if r.useContext then ctx.jRef else ref => jsonRef(ref, ctx.workspace)
    val arr = r.refs.take(ctx.limit).map(jFn).mkString("[", ",", "]")
    val hintStr = r.hint.getOrElse("")
    println(s"""{"results":$arr,"timedOut":${r.timedOut}$hintStr}""")
  } else {
    if r.refs.isEmpty then {
      if r.emptyMessage.nonEmpty then println(r.emptyMessage)
    } else {
      println(r.header)
      val fFn: Reference => String = if r.useContext then ctx.fmtRef else ref => formatRef(ref, ctx.workspace)
      r.refs.take(ctx.limit).foreach(ref => println(fFn(ref)))
      if r.refs.size > ctx.limit then println(s"  ... and ${r.refs.size - ctx.limit} more")
    }
  }
}

private def renderCategorizedRefs(r: CmdResult.CategorizedRefs, ctx: CommandContext): Unit = {
  r.stderrHint.foreach(System.err.println)
  if ctx.jsonOutput then {
    val entries = r.grouped.map { (cat, refs) =>
      val arr = refs.take(ctx.limit).map(ctx.jRef).mkString("[", ",", "]")
      s""""${cat.toString}":$arr"""
    }.mkString(",")
    println(s"""{"categories":{$entries},"timedOut":${r.timedOut}}""")
  } else {
    val total = r.grouped.values.map(_.size).sum
    val suffix = if r.timedOut then " (timed out — partial results)" else ""
    println(s"""References to "${r.symbol}" — $total found:$suffix""")
    val confidenceOrder = List(Confidence.High, Confidence.Medium, Confidence.Low)
    confidenceOrder.foreach { conf =>
      val catRefs = r.grouped.flatMap { (cat, refs) =>
        refs.map(ref => (cat, ref, ctx.idx.resolveConfidence(ref, r.symbol, r.targetPkgs)))
      }.filter(_._3 == conf).toList
      if catRefs.nonEmpty then {
        val label = conf match {
          case Confidence.High   => "High confidence (import-matched)"
          case Confidence.Medium => "Medium confidence (wildcard import)"
          case Confidence.Low    => "Low confidence (no matching import)"
        }
        println(s"\n  $label:")
        val byCat = catRefs.groupBy(_._1)
        val order = List(RefCategory.Definition, RefCategory.ExtendedBy, RefCategory.ImportedBy,
                         RefCategory.UsedAsType, RefCategory.Usage, RefCategory.Comment)
        order.foreach { cat =>
          byCat.get(cat).filter(_.nonEmpty).foreach { entries =>
            val sorted = entries.sortBy((_, ref, _) => (path = ctx.workspace.relativize(ref.file).toString, line = ref.line))
            println(s"\n    ${cat.toString}:")
            sorted.take(ctx.limit).foreach((_, ref, _) => println(s"    ${ctx.fmtRef(ref)}"))
            if sorted.size > ctx.limit then println(s"      ... and ${sorted.size - ctx.limit} more")
          }
        }
      }
    }
  }
}

private def renderFlatRefs(r: CmdResult.FlatRefs, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val arr = r.refs.take(ctx.limit).map(ctx.jRef).mkString("[", ",", "]")
    println(s"""{"results":$arr,"timedOut":${r.timedOut}}""")
  } else {
    val suffix = if r.timedOut then " (timed out — partial results)" else ""
    println(s"""References to "${r.symbol}" — ${r.refs.size} found:$suffix""")
    val annotated = r.refs.map(ref => (ref, ctx.idx.resolveConfidence(ref, r.symbol, r.targetPkgs)))
    val sorted = annotated.sortBy { case (ref, c) => (confidence = c.ordinal, path = ctx.workspace.relativize(ref.file).toString, line = ref.line) }
    var lastConf: Option[Confidence] = None
    var shown = 0
    sorted.foreach { case (ref, conf) =>
      if shown < ctx.limit then {
        if !lastConf.contains(conf) then {
          val label = conf match {
            case Confidence.High   => "High confidence"
            case Confidence.Medium => "Medium confidence"
            case Confidence.Low    => "Low confidence"
          }
          println(s"\n  [$label]")
          lastConf = Some(conf)
        }
        println(ctx.fmtRef(ref))
        shown += 1
      }
    }
    if r.refs.size > ctx.limit then println(s"  ... and ${r.refs.size - ctx.limit} more")
  }
}

private def renderStringList(r: CmdResult.StringList, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    // Skip only when output was already printed (empty items + empty header + empty emptyMessage)
    if r.items.nonEmpty || r.header.nonEmpty || r.emptyMessage.nonEmpty then
      val arr = r.items.take(ctx.limit).map(f => s""""${jsonEscape(f)}"""").mkString("[", ",", "]")
      println(arr)
  } else {
    if r.items.isEmpty then {
      if r.emptyMessage.nonEmpty then println(r.emptyMessage)
    } else {
      println(r.header)
      r.items.take(ctx.limit).foreach(f => println(s"  $f"))
      if r.total > ctx.limit then println(s"  ... and ${r.total - ctx.limit} more")
    }
  }
}

private def renderIndexStats(r: CmdResult.IndexStats, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val byKind = r.symbolsByKind.map((k, c) => s""""${k.toString.toLowerCase}":$c""").mkString(",")
    println(s"""{"fileCount":${r.fileCount},"symbolCount":${r.symbolCount},"packageCount":${r.packageCount},"symbolsByKind":{$byKind},"indexTimeMs":${r.indexTimeMs},"cachedLoad":${r.cachedLoad},"parsedCount":${r.parsedCount},"skippedCount":${r.skippedCount},"parseFailures":${r.parseFailures}}""")
  } else {
    if r.cachedLoad then
      println(s"Indexed ${r.fileCount} files (${r.skippedCount} cached, ${r.parsedCount} parsed) in ${r.indexTimeMs}ms")
    else
      println(s"Indexed ${r.fileCount} files, ${r.symbolCount} symbols in ${r.indexTimeMs}ms")
    println(s"Packages: ${r.packageCount}")
    println()
    println("Symbols by kind:")
    r.symbolsByKind.foreach { (kind, count) =>
      println(s"  ${kind.toString.padTo(10, ' ')} $count")
    }
    if r.parseFailures > 0 then {
      println(s"\n${r.parseFailures} files had parse errors:")
      if ctx.verbose then
        r.parseFailedFiles.sorted.foreach(f => println(s"  $f"))
      else
        println("  Run with --verbose to see the list.")
    }
  }
}

private def renderMemberSections(r: CmdResult.MemberSections, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val allMembers = r.sections.flatMap { sec =>
      val ownMembers = sec.ownMembers.map { m =>
        val rel = jsonEscape(ctx.workspace.relativize(sec.file).toString)
        val overrideJson = if m.isOverride then ""","isOverride":true""" else ""
        s"""{"name":"${jsonEscape(m.name)}","kind":"${m.kind.toString.toLowerCase}","line":${m.line},"signature":"${jsonEscape(m.signature)}","file":"$rel","owner":"${jsonEscape(r.symbol)}","ownerKind":"${sec.ownerKind.toString.toLowerCase}","package":"${jsonEscape(sec.packageName)}","inherited":false$overrideJson}"""
      }
      val inheritedMembers = sec.inherited.flatMap { (parentName, parentFile, parentPackage, members) =>
        members.map { m =>
          val rel = parentFile.map(f => jsonEscape(ctx.workspace.relativize(f).toString)).getOrElse("")
          s"""{"name":"${jsonEscape(m.name)}","kind":"${m.kind.toString.toLowerCase}","line":${m.line},"signature":"${jsonEscape(m.signature)}","file":"$rel","owner":"${jsonEscape(parentName)}","ownerKind":"inherited","package":"${jsonEscape(parentPackage)}","inherited":true}"""
        }
      }
      val companionMembers = sec.companion.toList.flatMap { (compSym, compMembers) =>
        val rel = jsonEscape(ctx.workspace.relativize(compSym.file).toString)
        compMembers.map { m =>
          s"""{"name":"${jsonEscape(m.name)}","kind":"${m.kind.toString.toLowerCase}","line":${m.line},"signature":"${jsonEscape(m.signature)}","file":"$rel","owner":"${jsonEscape(compSym.name)}","ownerKind":"companion","package":"${jsonEscape(compSym.packageName)}","inherited":false}"""
        }
      }
      ownMembers ++ inheritedMembers ++ companionMembers
    }
    println(allMembers.take(ctx.limit).mkString("[", ",", "]"))
  } else {
    if r.sections.isEmpty then {
      println(s"""No class/trait/object/enum "${r.symbol}" found""")
    } else {
      r.sections.foreach { sec =>
        val rel = ctx.workspace.relativize(sec.file)
        val pkg = if sec.packageName.nonEmpty then s" (${sec.packageName})" else ""
        println(s"Members of ${sec.ownerKind.toString.toLowerCase} ${r.symbol}$pkg — $rel:${sec.line}:")
        if sec.ownMembers.isEmpty then println("  (no members)")
        else {
          println(s"  Defined in ${r.symbol}:")
          sec.ownMembers.take(ctx.limit).foreach { m =>
            val overrideMarker = if m.isOverride then "  [override]" else ""
            if !ctx.brief then
              println(s"    ${m.kind.toString.toLowerCase.padTo(5, ' ')} ${m.signature.padTo(50, ' ')} :${m.line}$overrideMarker")
            else
              println(s"    ${m.kind.toString.toLowerCase.padTo(5, ' ')} ${m.name.padTo(30, ' ')} :${m.line}$overrideMarker")
          }
          if sec.ownMembers.size > ctx.limit then println(s"    ... and ${sec.ownMembers.size - ctx.limit} more")
        }
        sec.inherited.foreach { (parentName, _, _, pMembers) =>
          println(s"  Inherited from $parentName:")
          pMembers.take(ctx.limit).foreach { m =>
            if !ctx.brief then
              println(s"    ${m.kind.toString.toLowerCase.padTo(5, ' ')} ${m.signature.padTo(50, ' ')} :${m.line}")
            else
              println(s"    ${m.kind.toString.toLowerCase.padTo(5, ' ')} ${m.name.padTo(30, ' ')} :${m.line}")
          }
          if pMembers.size > ctx.limit then println(s"    ... and ${pMembers.size - ctx.limit} more")
        }
        sec.companion.foreach { (compSym, compMembers) =>
          val compRel = ctx.workspace.relativize(compSym.file)
          println(s"\n  Companion ${compSym.kind.toString.toLowerCase} ${compSym.name} — $compRel:${compSym.line}:")
          if compMembers.isEmpty then println("    (no members)")
          else {
            compMembers.take(ctx.limit).foreach { m =>
              if !ctx.brief then
                println(s"    ${m.kind.toString.toLowerCase.padTo(5, ' ')} ${m.signature.padTo(50, ' ')} :${m.line}")
              else
                println(s"    ${m.kind.toString.toLowerCase.padTo(5, ' ')} ${m.name.padTo(30, ' ')} :${m.line}")
            }
            if compMembers.size > ctx.limit then println(s"    ... and ${compMembers.size - ctx.limit} more")
          }
        }
      }
    }
  }
}

private def renderDocEntries(r: CmdResult.DocEntries, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val entries = r.entries.take(ctx.limit).map { e =>
      val rel = jsonEscape(ctx.workspace.relativize(e.sym.file).toString)
      val doc = e.doc.map(d => s""""${jsonEscape(d)}"""").getOrElse("null")
      s"""{"name":"${jsonEscape(e.sym.name)}","kind":"${e.sym.kind.toString.toLowerCase}","file":"$rel","line":${e.sym.line},"package":"${jsonEscape(e.sym.packageName)}","doc":$doc}"""
    }
    println(entries.mkString("[", ",", "]"))
  } else {
    r.entries.take(ctx.limit).foreach { e =>
      val rel = ctx.workspace.relativize(e.sym.file)
      val pkg = if e.sym.packageName.nonEmpty then s" (${e.sym.packageName})" else ""
      println(s"${e.sym.kind.toString.toLowerCase} ${r.symbol}$pkg — $rel:${e.sym.line}:")
      e.doc match {
        case Some(doc) => println(doc)
        case None => println("  (no scaladoc)")
      }
      println()
    }
  }
}

private def renderOverview(r: CmdResult.Overview, ctx: CommandContext): Unit = {
  val d = r.data
  if ctx.jsonOutput then {
    val kindJson = d.symbolsByKind.map((k, c) => s""""${k.toString.toLowerCase}":$c""").mkString("{", ",", "}")
    val pkgJson = d.topPackages.map((p, c) => s"""{"package":"${jsonEscape(p)}","count":$c}""").mkString("[", ",", "]")
    val extJson = d.mostExtended.map((n, c, sig) => s"""{"name":"${jsonEscape(n)}","implementations":$c,"signature":"${jsonEscape(sig)}"}""").mkString("[", ",", "]")
    if d.hasArchitecture then {
      val depsJson = d.pkgDeps.map { (pkg, deps) =>
        val dArr = deps.map(dep => s""""${jsonEscape(dep)}"""").mkString("[", ",", "]")
        s""""${jsonEscape(pkg)}":$dArr"""
      }.mkString("{", ",", "}")
      val hubJson = d.hubTypes.map((n, c, sig) => s"""{"name":"${jsonEscape(n)}","score":$c,"signature":"${jsonEscape(sig)}"}""").mkString("[", ",", "]")
      val focusPkgJson = d.focusPackage.map(p => s""","focusPackage":"${jsonEscape(p)}"""").getOrElse("")
      println(s"""{"fileCount":${d.fileCount},"symbolCount":${d.symbolCount},"packageCount":${d.packageCount},"symbolsByKind":$kindJson,"topPackages":$pkgJson,"mostExtended":$extJson,"packageDependencies":$depsJson,"hubTypes":$hubJson$focusPkgJson}""")
    } else {
      println(s"""{"fileCount":${d.fileCount},"symbolCount":${d.symbolCount},"packageCount":${d.packageCount},"symbolsByKind":$kindJson,"topPackages":$pkgJson,"mostExtended":$extJson}""")
    }
  } else {
    println(s"Project overview (${d.fileCount} files, ${d.symbolCount} symbols):\n")
    println("Symbols by kind:")
    d.symbolsByKind.foreach { (kind, count) =>
      println(s"  ${kind.toString.padTo(10, ' ')} $count")
    }
    println(s"\nTop packages (by symbol count):")
    d.topPackages.foreach { (pkg, count) =>
      println(s"  ${pkg.padTo(50, ' ')} $count")
    }
    println(s"\nMost extended (by package spread, then implementation count):")
    d.mostExtended.foreach { (name, count, sig) =>
      val sigHint = if sig.nonEmpty then s"  $sig" else ""
      println(s"  ${name.padTo(30, ' ')} $count impl$sigHint")
    }
    if d.hasArchitecture then {
      d.focusPackage match {
        case Some(fpkg) =>
          println(s"\nPackage focus: $fpkg")
          val directDeps = d.pkgDeps.getOrElse(fpkg, Set.empty)
          println(s"\n  Depends on:")
          if directDeps.isEmpty then println("    (none)")
          else directDeps.toList.sorted.foreach(dep => println(s"    $dep"))
          val dependents = d.pkgDeps.filter((pkg, deps) => pkg != fpkg && deps.contains(fpkg)).keySet
          println(s"\n  Depended on by:")
          if dependents.isEmpty then println("    (none)")
          else dependents.toList.sorted.foreach(dep => println(s"    $dep"))
        case None =>
          println(s"\nPackage dependencies:")
          if d.pkgDeps.isEmpty then println("  (no cross-package dependencies found)")
          else d.pkgDeps.toList.sortBy(_._1).foreach { (pkg, deps) =>
            println(s"  $pkg → ${deps.toList.sorted.mkString(", ")}")
          }
      }
      println(s"\nHub types (by package spread, then extension count):")
      if d.hubTypes.isEmpty then println("  (none)")
      else d.hubTypes.foreach { (name, count, sig) =>
        val sigHint = if sig.nonEmpty then s"  $sig" else ""
        println(s"  ${name.padTo(30, ' ')} $count references$sigHint")
      }
    }
  }
}

private def renderSourceBlocks(r: CmdResult.SourceBlocks, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val arr = r.blocks.take(ctx.limit).map { (file, b) =>
      val rel = jsonEscape(ctx.workspace.relativize(file).toString)
      s"""{"name":"${jsonEscape(b.symbolName)}","owner":"${jsonEscape(b.ownerName)}","file":"$rel","startLine":${b.startLine},"endLine":${b.endLine},"body":"${jsonEscape(b.sourceText)}"}"""
    }.mkString("[", ",", "]")
    println(arr)
  } else {
    r.blocks.take(ctx.limit).foreach { (file, b) =>
      val ownerStr = if b.ownerName.nonEmpty then s" — ${b.ownerName}" else ""
      val rel = ctx.workspace.relativize(file)
      println(s"Body of ${b.symbolName}$ownerStr — $rel:${b.startLine}:")
      val bodyLines = b.sourceText.split("\n")
      bodyLines.zipWithIndex.foreach { case (line, i) =>
        println(s"  ${(b.startLine + i).toString.padTo(4, ' ')} | $line")
      }
      println()
    }
  }
}

private def renderTestSuites(r: CmdResult.TestSuites, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val arr = r.suites.take(ctx.limit).map { suite =>
      val rel = jsonEscape(ctx.workspace.relativize(suite.file).toString)
      val testsJson = suite.tests.map { tc =>
        val bodyField = if r.showBody then {
          tc.body.map(b => s""","body":"${jsonEscape(b.sourceText)}"""").getOrElse("")
        } else ""
        s"""{"name":"${jsonEscape(tc.name)}","line":${tc.line}$bodyField}"""
      }.mkString("[", ",", "]")
      s"""{"suite":"${jsonEscape(suite.name)}","file":"$rel","line":${suite.line},"tests":$testsJson}"""
    }.mkString("[", ",", "]")
    println(arr)
  } else {
    if r.suites.isEmpty then {
      println(r.emptyMessage)
    } else {
      r.suites.take(ctx.limit).foreach { suite =>
        val rel = ctx.workspace.relativize(suite.file)
        println(s"${suite.name} — $rel:${suite.line}:")
        suite.tests.foreach { tc =>
          println(s"""  test  "${tc.name}"  :${tc.line}""")
          if r.showBody || ctx.verbose then {
            tc.body.foreach { b =>
              val bodyLines = b.sourceText.split("\n")
              bodyLines.zipWithIndex.foreach { case (line, i) =>
                println(s"    ${(b.startLine + i).toString.padTo(4, ' ')} | $line")
              }
              println()
            }
          }
        }
        if !r.showBody && !ctx.verbose then println()
      }
    }
  }
}

private def renderCoverageReport(r: CmdResult.CoverageReport, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val refsJson = r.testRefs.take(ctx.limit).map(ctx.jRef).mkString("[", ",", "]")
    println(s"""{"symbol":"${jsonEscape(r.symbol)}","testFileCount":${r.testFiles.size},"referenceCount":${r.testRefs.size},"references":$refsJson}""")
  } else {
    if r.testRefs.isEmpty then {
      if r.totalRefs == 0 then {
        println(s"""Coverage of "${r.symbol}" — no references found""")
        renderHint(mkNotFoundHint(r.symbol, ctx, "coverage"))
      } else {
        println(s"""Coverage of "${r.symbol}" — ${r.totalRefs} refs but 0 in test files""")
      }
    } else {
      println(s"""Coverage of "${r.symbol}" — ${r.testRefs.size} refs in ${r.testFiles.size} test files:""")
      r.testFiles.sorted.foreach { f =>
        val fileRefs = r.testRefs.filter(ref => ctx.workspace.relativize(ref.file).toString == f)
        println(s"  $f")
        fileRefs.take(ctx.limit).foreach { ref =>
          println(s"    :${ref.line}  ${ref.contextLine}")
        }
      }
    }
  }
}

private def renderHierarchyResult(r: CmdResult.HierarchyResult, ctx: CommandContext): Unit = {
  val tree = r.tree
  def nodeJson(n: HierarchyNode): String = {
    val file = n.file.map(f => s""""${jsonEscape(ctx.workspace.relativize(f).toString)}"""").getOrElse("null")
    val kind = n.kind.map(k => s""""${k.toString.toLowerCase}"""").getOrElse("null")
    val line = n.line.map(_.toString).getOrElse("null")
    s"""{"name":"${jsonEscape(n.name)}","kind":$kind,"file":$file,"line":$line,"package":"${jsonEscape(n.packageName)}","isExternal":${n.isExternal}}"""
  }
  def treeJson(t: HierarchyTree): String = {
    val ps = t.parents.map(treeJson).mkString("[", ",", "]")
    val cs = t.children.map(treeJson).mkString("[", ",", "]")
    val trunc = if t.truncatedChildren > 0 then s""","truncatedChildren":${t.truncatedChildren}""" else ""
    s"""{"node":${nodeJson(t.root)},"parents":$ps,"children":$cs$trunc}"""
  }
  if ctx.jsonOutput then {
    println(treeJson(tree))
  } else {
    val rootNode = tree.root
    val pkg = if rootNode.packageName.nonEmpty then s" (${rootNode.packageName})" else ""
    val kind = rootNode.kind.map(_.toString.toLowerCase).getOrElse("unknown")
    val loc = rootNode.file.map(f => s" — ${ctx.workspace.relativize(f)}:${rootNode.line.getOrElse(0)}").getOrElse("")
    println(s"Hierarchy of $kind ${rootNode.name}$pkg$loc:")
    if ctx.goUp then {
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
    }
    if ctx.goDown then {
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
          if ct.truncatedChildren > 0 then
            println(s"$nextIndent... and ${ct.truncatedChildren} more children")
        }
      }
      if tree.children.isEmpty then println("    (none)")
      else printChildren(tree.children, "    ")
    }
  }
}

private def renderOverrideList(r: CmdResult.OverrideList, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val arr = r.results.map { o =>
      val rel = jsonEscape(ctx.workspace.relativize(o.file).toString)
      s"""{"enclosingClass":"${jsonEscape(o.enclosingClass)}","enclosingKind":"${o.enclosingKind.toString.toLowerCase}","file":"$rel","line":${o.line},"signature":"${jsonEscape(o.signature)}","package":"${jsonEscape(o.packageName)}"}"""
    }.mkString("[", ",", "]")
    println(arr)
  } else {
    println(r.header)
    r.results.foreach { o =>
      val rel = ctx.workspace.relativize(o.file)
      val pkg = if o.packageName.nonEmpty then s" (${o.packageName})" else ""
      println(s"  ${o.enclosingClass}$pkg — $rel:${o.line}")
      println(s"    ${o.signature}")
    }
  }
}

private def renderExplanation(r: CmdResult.Explanation, ctx: CommandContext): Unit = {
  val sym = r.sym
  val rel = ctx.workspace.relativize(sym.file)
  val pkg = if sym.packageName.nonEmpty then s" (${sym.packageName})" else ""
  if ctx.jsonOutput then {
    val docJson = r.doc.map(d => s""""${jsonEscape(d)}"""").getOrElse("null")
    val membersJson = r.members.map { m =>
      val overrideJson = if m.isOverride then ""","isOverride":true""" else ""
      s"""{"name":"${jsonEscape(m.name)}","kind":"${m.kind.toString.toLowerCase}","line":${m.line},"signature":"${jsonEscape(m.signature)}"$overrideJson}"""
    }.mkString("[", ",", "]")
    val implsJson = r.impls.map(s => jsonSymbol(s, ctx.workspace)).mkString("[", ",", "]")
    val primaryKeys = r.members.map(m => (name = m.name, kind = m.kind)).toSet
    val companionJson = r.companion.map { (compSym, compMembers) =>
      val uniqueCompMembers = compMembers.filter(m => !primaryKeys.contains((name = m.name, kind = m.kind)))
      val cMembers = uniqueCompMembers.map { m =>
        s"""{"name":"${jsonEscape(m.name)}","kind":"${m.kind.toString.toLowerCase}","line":${m.line},"signature":"${jsonEscape(m.signature)}"}"""
      }.mkString("[", ",", "]")
      s"""{"definition":${jsonSymbol(compSym, ctx.workspace)},"members":$cMembers}"""
    }.getOrElse("null")
    def explainedImplJson(ei: ExplainedImpl): String = {
      val mJson = ei.members.map { m =>
        s"""{"name":"${jsonEscape(m.name)}","kind":"${m.kind.toString.toLowerCase}","line":${m.line},"signature":"${jsonEscape(m.signature)}"}"""
      }.mkString("[", ",", "]")
      val subJson = ei.subImpls.map(explainedImplJson).mkString("[", ",", "]")
      s"""{"definition":${jsonSymbol(ei.sym, ctx.workspace)},"members":$mJson,"subImplementations":$subJson}"""
    }
    val expandedJson = r.expandedImpls.map(explainedImplJson).mkString("[", ",", "]")
    val importCount = r.importRefs.size
    val importRefsJson = if importCount <= 10 then
      val arr = r.importRefs.map(ref => jsonRef(ref, ctx.workspace)).mkString("[", ",", "]")
      s""","importFiles":$arr"""
    else ""
    val otherJson = if r.otherMatches > 0 then s""","otherMatches":${r.otherMatches}""" else ""
    val totalImplJson = if r.totalImpls > r.impls.size then s""","totalImplementations":${r.totalImpls}""" else ""
    val inheritedJson = if r.inherited.nonEmpty then
      val groups = r.inherited.map { (parentName, parentFile, parentPackage, members) =>
        val pRel = parentFile.map(f => s""""${jsonEscape(ctx.workspace.relativize(f).toString)}"""").getOrElse("null")
        val mJson = members.map { m =>
          s"""{"name":"${jsonEscape(m.name)}","kind":"${m.kind.toString.toLowerCase}","line":${m.line},"signature":"${jsonEscape(m.signature)}"}"""
        }.mkString("[", ",", "]")
        s"""{"parent":"${jsonEscape(parentName)}","parentFile":$pRel,"parentPackage":"${jsonEscape(parentPackage)}","members":$mJson}"""
      }.mkString("[", ",", "]")
      s""","inherited":$groups"""
    else ""
    println(s"""{"definition":${jsonSymbol(sym, ctx.workspace)},"doc":$docJson,"members":$membersJson,"implementations":$implsJson,"importCount":$importCount$importRefsJson,"companion":$companionJson,"expandedImplementations":$expandedJson$otherJson$totalImplJson$inheritedJson}""")
  } else {
    println(s"Explanation of ${sym.kind.toString.toLowerCase} ${sym.name}$pkg:\n")
    println(s"  Definition: $rel:${sym.line}")
    println(s"  Signature: ${sym.signature}")
    if sym.parents.nonEmpty then println(s"  Extends: ${sym.parents.mkString(", ")}")
    println()
    r.doc match {
      case Some(d) =>
        println("  Scaladoc:")
        d.split("\n").foreach(l => println(s"    $l"))
        println()
      case None =>
        println("  Scaladoc: (none)\n")
    }
    if r.members.nonEmpty then {
      println(s"  Members (top ${r.members.size}):")
      r.members.foreach { m =>
        val label = if ctx.verbose then m.signature else m.name
        val overrideMarker = if m.isOverride then "  [override]" else ""
        println(s"    ${m.kind.toString.toLowerCase.padTo(5, ' ')} $label$overrideMarker")
      }
      println()
    }
    r.inherited.foreach { (parentName, _, _, pMembers) =>
      println(s"  Inherited from $parentName:")
      pMembers.take(ctx.membersLimit).foreach { m =>
        val label = if ctx.verbose then m.signature else m.name
        println(s"    ${m.kind.toString.toLowerCase.padTo(5, ' ')} $label")
      }
      if pMembers.size > ctx.membersLimit then println(s"    ... and ${pMembers.size - ctx.membersLimit} more")
      println()
    }
    r.companion.foreach { (compSym, compMembers) =>
      val compRel = ctx.workspace.relativize(compSym.file)
      println(s"  Companion ${compSym.kind.toString.toLowerCase} ${compSym.name} — $compRel:${compSym.line}")
      if compMembers.nonEmpty then
        // Deduplicate: skip companion members that are identical to primary members
        val primaryKeys = r.members.map(m => (name = m.name, kind = m.kind)).toSet
        val uniqueCompMembers = compMembers.filter(m => !primaryKeys.contains((name = m.name, kind = m.kind)))
        val dupeCount = compMembers.size - uniqueCompMembers.size
        if uniqueCompMembers.nonEmpty then
          uniqueCompMembers.foreach { m =>
            val label = if ctx.verbose then m.signature else m.name
            println(s"    ${m.kind.toString.toLowerCase.padTo(5, ' ')} $label")
          }
        if dupeCount > 0 then
          println(s"    ($dupeCount members shared with ${sym.kind.toString.toLowerCase}, shown above)")
      println()
    }
    if r.impls.nonEmpty then {
      val implHeader = if r.totalImpls > r.impls.size then
        s"  Implementations (showing ${r.impls.size} of ${r.totalImpls} — use --impl-limit to adjust):"
      else
        s"  Implementations (${r.impls.size}):"
      println(implHeader)
      r.impls.foreach(s => println(formatSymbol(s, ctx.workspace)))
      println()
    }
    if r.expandedImpls.nonEmpty then {
      println("  Expanded implementations:")
      def printExpanded(impls: List[ExplainedImpl], indent: String): Unit = {
        impls.foreach { ei =>
          println(s"$indent${ei.sym.kind.toString.toLowerCase} ${ei.sym.name} — ${ctx.workspace.relativize(ei.sym.file)}:${ei.sym.line}")
          ei.members.foreach { m =>
            val label = if ctx.verbose then m.signature else m.name
            println(s"$indent  ${m.kind.toString.toLowerCase.padTo(5, ' ')} $label")
          }
          if ei.subImpls.nonEmpty then printExpanded(ei.subImpls, indent + "  ")
        }
      }
      printExpanded(r.expandedImpls, "    ")
      println()
    }
    if !ctx.shallow then
      val importCount = r.importRefs.size
      if importCount == 0 then
        println("  Imported by: 0 files")
      else if importCount <= 10 then
        println(s"  Imported by ($importCount files):")
        r.importRefs.foreach(ref => println(s"    ${ctx.workspace.relativize(ref.file)}:${ref.line}"))
      else
        println(s"  Imported by: $importCount files (use `scalex imports ${sym.name}` for full list)")
    if r.otherMatches > 0 then
      Console.err.println(s"(${r.otherMatches} other match${if r.otherMatches > 1 then "es" else ""} — use package-qualified name or --path to disambiguate)")
  }
}

private def renderDependencies(r: CmdResult.Dependencies, ctx: CommandContext): Unit = {
  def depJson(d: DepInfo): String = {
    val file = d.file.map(f => s""""${jsonEscape(ctx.workspace.relativize(f).toString)}"""").getOrElse("null")
    val line = d.line.map(_.toString).getOrElse("null")
    s"""{"name":"${jsonEscape(d.name)}","kind":"${jsonEscape(d.kind)}","file":$file,"line":$line,"package":"${jsonEscape(d.packageName)}","depth":${d.depth}}"""
  }
  if ctx.jsonOutput then {
    val iArr = r.importDeps.map(depJson).mkString("[", ",", "]")
    val bArr = r.bodyDeps.map(depJson).mkString("[", ",", "]")
    println(s"""{"imports":$iArr,"bodyReferences":$bArr}""")
  } else {
    println(s"""Dependencies of "${r.symbol}":""")
    if r.importDeps.nonEmpty then {
      println(s"\n  Imports:")
      r.importDeps.take(ctx.limit).foreach { d =>
        val indent = "  " * d.depth
        val loc = d.file.map(f => s" — ${ctx.workspace.relativize(f)}:${d.line.getOrElse(0)}").getOrElse("")
        println(s"    $indent${d.kind.padTo(9, ' ')} ${d.name}$loc")
      }
      if r.importDeps.size > ctx.limit then println(s"    ... and ${r.importDeps.size - ctx.limit} more")
    }
    if r.bodyDeps.nonEmpty then {
      println(s"\n  Body references:")
      r.bodyDeps.take(ctx.limit).foreach { d =>
        val indent = "  " * d.depth
        val loc = d.file.map(f => s" — ${ctx.workspace.relativize(f)}:${d.line.getOrElse(0)}").getOrElse("")
        println(s"    $indent${d.kind.padTo(9, ' ')} ${d.name}$loc")
      }
      if r.bodyDeps.size > ctx.limit then println(s"    ... and ${r.bodyDeps.size - ctx.limit} more")
    }
  }
}

private def renderScopes(r: CmdResult.Scopes, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val arr = r.scopes.map { s =>
      s"""{"name":"${jsonEscape(s.name)}","kind":"${jsonEscape(s.kind)}","line":${s.line}}"""
    }.mkString("[", ",", "]")
    val rel = jsonEscape(ctx.workspace.relativize(r.file).toString)
    println(s"""{"file":"$rel","line":${r.line},"scopes":$arr}""")
  } else {
    val rel = ctx.workspace.relativize(r.file)
    println(s"Context at $rel:${r.line}:")
    if r.scopes.isEmpty then println("  (no enclosing scopes found)")
    else {
      r.scopes.foreach { s =>
        println(s"  ${s.kind.padTo(9, ' ')} ${s.name} (line ${s.line})")
      }
    }
  }
}

private def renderSymbolDiff(r: CmdResult.SymbolDiff, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    if r.filesChanged == 0 then {
      println("""{"added":[],"removed":[],"modified":[]}""")
    } else {
      def diffSymJson(s: DiffSymbol): String =
        s"""{"name":"${jsonEscape(s.name)}","kind":"${s.kind.toString.toLowerCase}","file":"${jsonEscape(s.file)}","line":${s.line},"package":"${jsonEscape(s.packageName)}","signature":"${jsonEscape(s.signature)}"}"""
      val addedJson = r.added.take(ctx.limit).map(diffSymJson).mkString("[", ",", "]")
      val removedJson = r.removed.take(ctx.limit).map(diffSymJson).mkString("[", ",", "]")
      val modifiedJson = r.modified.take(ctx.limit).map { (o, n) =>
        s"""{"old":${diffSymJson(o)},"new":${diffSymJson(n)}}"""
      }.mkString("[", ",", "]")
      println(s"""{"ref":"${jsonEscape(r.ref)}","filesChanged":${r.filesChanged},"added":$addedJson,"removed":$removedJson,"modified":$modifiedJson}""")
    }
  } else {
    if r.filesChanged == 0 then {
      println(s"No Scala files changed compared to ${r.ref}")
    } else {
      println(s"Symbol changes compared to ${r.ref} (${r.filesChanged} files changed):")
      if r.added.nonEmpty then {
        println(s"\n  Added (${r.added.size}):")
        r.added.take(ctx.limit).foreach { s =>
          println(s"    + ${s.kind.toString.toLowerCase.padTo(9, ' ')} ${s.name} — ${s.file}:${s.line}")
        }
        if r.added.size > ctx.limit then println(s"    ... and ${r.added.size - ctx.limit} more")
      }
      if r.removed.nonEmpty then {
        println(s"\n  Removed (${r.removed.size}):")
        r.removed.take(ctx.limit).foreach { s =>
          println(s"    - ${s.kind.toString.toLowerCase.padTo(9, ' ')} ${s.name} — ${s.file}:${s.line}")
        }
        if r.removed.size > ctx.limit then println(s"    ... and ${r.removed.size - ctx.limit} more")
      }
      if r.modified.nonEmpty then {
        println(s"\n  Modified (${r.modified.size}):")
        r.modified.take(ctx.limit).foreach { (_, n) =>
          println(s"    ~ ${n.kind.toString.toLowerCase.padTo(9, ' ')} ${n.name} — ${n.file}:${n.line}")
        }
        if r.modified.size > ctx.limit then println(s"    ... and ${r.modified.size - ctx.limit} more")
      }
      if r.added.isEmpty && r.removed.isEmpty && r.modified.isEmpty then
        println("  No symbol-level changes detected")
    }
  }
}

private def renderAstMatches(r: CmdResult.AstMatches, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val arr = r.results.map { m =>
      val rel = jsonEscape(ctx.workspace.relativize(m.file).toString)
      s"""{"name":"${jsonEscape(m.name)}","kind":"${m.kind.toString.toLowerCase}","file":"$rel","line":${m.line},"package":"${jsonEscape(m.packageName)}","signature":"${jsonEscape(m.signature)}"}"""
    }.mkString("[", ",", "]")
    println(arr)
  } else {
    if r.results.isEmpty then
      println(s"No types matching AST pattern (${r.filters})")
    else {
      println(s"Types matching AST pattern (${r.filters}) — ${r.results.size} found:")
      r.results.foreach { m =>
        val rel = ctx.workspace.relativize(m.file)
        val pkg = if m.packageName.nonEmpty then s" (${m.packageName})" else ""
        println(s"  ${m.kind.toString.toLowerCase.padTo(9, ' ')} ${m.name}$pkg — $rel:${m.line}")
      }
    }
  }
}

private def renderGrepCount(r: CmdResult.GrepCount, ctx: CommandContext): Unit = {
  r.stderrHint.foreach(System.err.println)
  if ctx.jsonOutput then {
    val hintStr = r.hint.getOrElse("")
    println(s"""{"matches":${r.matches},"files":${r.files},"timedOut":${r.timedOut}$hintStr}""")
  } else {
    val suffix = if r.timedOut then " (timed out — partial results)" else ""
    println(s"${r.matches} matches across ${r.files} files$suffix")
  }
}

private def renderPackages(r: CmdResult.Packages, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val arr = r.packages.map(p => s""""${jsonEscape(p)}"""").mkString("[", ",", "]")
    println(arr)
  } else {
    println(s"Packages (${r.packages.size}):")
    r.packages.foreach(p => println(s"  $p"))
  }
}

private def pluralKind(kind: SymbolKind): String = kind match
  case SymbolKind.Class => "Classes"
  case _ => s"${kind.toString}s"

private def renderPackageSymbols(r: CmdResult.PackageSymbols, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val items = r.symbols.take(ctx.limit)
    val arr = items.map(s => jsonSymbol(s, ctx.workspace)).mkString("[", ",", "]")
    val truncated = if r.symbols.size > ctx.limit then ",\"truncated\":true" else ""
    println(s"""{"package":"${jsonEscape(r.pkg)}","symbolCount":${r.symbols.size},"symbols":$arr$truncated}""")
  } else {
    if r.symbols.isEmpty then {
      println(s"""Package ${r.pkg}: (no symbols)""")
    } else {
      println(s"Package ${r.pkg} (${r.symbols.size} symbols):\n")
      val byKind: List[(kind: SymbolKind, syms: List[SymbolInfo])] =
        r.symbols.groupBy(_.kind).toList.sortBy(-_._2.size).map((k, s) => (kind = k, syms = s))
      byKind.foreach { (kind, syms) =>
        println(s"  ${pluralKind(kind)} (${syms.size}):")
        val items = syms.sortBy(_.name).take(ctx.limit)
        items.foreach { s =>
          if ctx.verbose then
            println(s"    ${s.name.padTo(30, ' ')} ${s.signature.take(60)}  — ${ctx.workspace.relativize(s.file)}:${s.line}")
          else
            println(s"    ${s.name.padTo(30, ' ')} ${ctx.workspace.relativize(s.file)}:${s.line}")
        }
        if syms.size > ctx.limit then println(s"    ... and ${syms.size - ctx.limit} more")
      }
    }
  }
}

private def renderPackageSummary(r: CmdResult.PackageSummary, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val arr = r.subPackages.map { (sub, count) =>
      s"""{"subPackage":"${jsonEscape(sub)}","symbolCount":$count}"""
    }.mkString("[", ",", "]")
    println(s"""{"package":"${jsonEscape(r.pkg)}","totalSymbols":${r.totalSymbols},"subPackages":$arr}""")
  } else {
    if r.subPackages.isEmpty then {
      println(s"""Package ${r.pkg}: no symbols found""")
    } else {
      println(s"Summary of ${r.pkg} (${r.totalSymbols} symbols):\n")
      val maxNameLen = r.subPackages.map(_.subPkg.length).maxOption.getOrElse(10).min(50)
      r.subPackages.foreach { (sub, count) =>
        val label = if sub == "(root)" then "(root)" else s".${sub}"
        println(s"  ${label.padTo(maxNameLen + 2, ' ')} $count")
      }
    }
  }
}

private def renderApiSurface(r: CmdResult.ApiSurface, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val items = r.symbols.take(ctx.limit).map { (sym, count) =>
      val rel = jsonEscape(ctx.workspace.relativize(sym.file).toString)
      s"""{"name":"${jsonEscape(sym.name)}","kind":"${sym.kind.toString.toLowerCase}","file":"$rel","line":${sym.line},"package":"${jsonEscape(sym.packageName)}","importerCount":$count}"""
    }.mkString("[", ",", "]")
    val internalJson = r.internalOnly.map(n => s""""${jsonEscape(n)}"""").mkString("[", ",", "]")
    println(s"""{"package":"${jsonEscape(r.pkg)}","exportedCount":${r.symbols.size},"totalInPackage":${r.totalInPackage},"symbols":$items,"internalOnly":$internalJson}""")
  } else {
    if r.symbols.isEmpty && r.internalOnly.isEmpty then {
      println(s"""API surface of ${r.pkg}: no symbols found""")
    } else {
      val exportedCount = r.symbols.size
      println(s"API surface of ${r.pkg} ($exportedCount of ${r.totalInPackage} symbols imported externally):\n")
      r.symbols.take(ctx.limit).foreach { (sym, count) =>
        val rel = ctx.workspace.relativize(sym.file)
        val importerLabel = if count == 1 then "importer" else "importers"
        println(s"  ${sym.name.padTo(25, ' ')} ${sym.kind.toString.toLowerCase.padTo(9, ' ')} $count $importerLabel  $rel:${sym.line}")
      }
      if r.symbols.size > ctx.limit then println(s"  ... and ${r.symbols.size - ctx.limit} more")
      if r.internalOnly.nonEmpty then {
        val shown = r.internalOnly.take(10)
        val suffix = if r.internalOnly.size > 10 then s", ... and ${r.internalOnly.size - 10} more" else ""
        println(s"\n  Not imported externally (${r.internalOnly.size}): ${shown.mkString(", ")}$suffix")
      }
    }
  }
}

private def renderRefsTop(r: CmdResult.RefsTop, ctx: CommandContext): Unit = {
  val timedOutSuffix = if r.timedOut then " (timed out — results may be incomplete)" else ""
  if ctx.jsonOutput then {
    val filesJson = r.fileRanking.map { (file, count) =>
      val rel = jsonEscape(ctx.workspace.relativize(file).toString)
      s"""{"file":"$rel","count":$count}"""
    }.mkString("[", ",", "]")
    println(s"""{"symbol":"${jsonEscape(r.symbol)}","files":$filesJson,"total":${r.total},"timedOut":${r.timedOut}}""")
  } else {
    val fileCount = r.fileRanking.size
    println(s"Top $fileCount files referencing '${r.symbol}' (${r.total} total references)$timedOutSuffix:")
    r.fileRanking.foreach { (file, count) =>
      val rel = ctx.workspace.relativize(file)
      println(f"  $count%4d  $rel")
    }
  }
}

private def renderRefsSummary(r: CmdResult.RefsSummary, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val counts = r.categoryCounts.map((cat, count) => s""""${cat.toString}":$count""").mkString("{", ",", "}")
    println(s"""{"symbol":"${jsonEscape(r.symbol)}","counts":$counts,"total":${r.total},"timedOut":${r.timedOut}}""")
  } else {
    val suffix = if r.timedOut then " (timed out — partial results)" else ""
    val parts = r.categoryCounts.map { (cat, count) =>
      val label = cat match {
        case RefCategory.Definition => "definitions"
        case RefCategory.ExtendedBy => "extensions"
        case RefCategory.ImportedBy => "importers"
        case RefCategory.UsedAsType => "type usages"
        case RefCategory.Usage => "usages"
        case RefCategory.Comment => "comments"
      }
      s"$count $label"
    }
    println(s"""References to "${r.symbol}" — ${r.total} total: ${parts.mkString(", ")}$suffix""")
  }
}

private def renderEntrypoints(r: CmdResult.Entrypoints, ctx: CommandContext): Unit = {
  import EntrypointCategory.*
  val byCategory = r.entries.groupBy(_.category)
  val categoryOrder = List(MainAnnotation, MainMethod, ExtendsApp, TestSuite)
  val categoryLabels = Map(
    MainAnnotation -> "@main annotated",
    MainMethod -> "def main(...) methods",
    ExtendsApp -> "extends App",
    TestSuite -> "Test suites"
  )
  val categoryJsonKeys = Map(
    MainAnnotation -> "mainAnnotated",
    MainMethod -> "mainMethods",
    ExtendsApp -> "extendsApp",
    TestSuite -> "testSuites"
  )
  if ctx.jsonOutput then {
    val groups = categoryOrder.map { cat =>
      val entries = byCategory.getOrElse(cat, Nil).take(ctx.limit)
      val arr = entries.map { e =>
        val rel = jsonEscape(ctx.workspace.relativize(e.sym.file).toString)
        val line = e.memberLine.getOrElse(e.sym.line)
        s"""{"name":"${jsonEscape(e.sym.name)}","kind":"${e.sym.kind.toString.toLowerCase}","file":"$rel","line":$line,"package":"${jsonEscape(e.sym.packageName)}"}"""
      }.mkString("[", ",", "]")
      s""""${categoryJsonKeys(cat)}":$arr"""
    }.mkString(",")
    println(s"""{"entrypoints":{$groups},"total":${r.total}}""")
  } else {
    if r.entries.isEmpty then
      println("No entrypoints found")
    else {
      println(s"Entrypoints — ${r.total} found:\n")
      categoryOrder.foreach { cat =>
        val entries = byCategory.getOrElse(cat, Nil)
        if entries.nonEmpty then {
          println(s"  ${categoryLabels(cat)} (${entries.size}):")
          entries.take(ctx.limit).foreach { e =>
            val rel = ctx.workspace.relativize(e.sym.file)
            val line = e.memberLine.getOrElse(e.sym.line)
            println(s"    ${e.sym.kind.toString.toLowerCase.padTo(9, ' ')} ${e.sym.name} — $rel:$line")
          }
          if entries.size > ctx.limit then println(s"    ... and ${entries.size - ctx.limit} more")
          println()
        }
      }
    }
  }
}

private def renderNotFound(r: CmdResult.NotFound, ctx: CommandContext): Unit = {
  val suggestionsJson = r.hint.suggestions.map(s => s""""${jsonEscape(s)}"""").mkString("[", ",", "]")
  if ctx.jsonOutput then {
    r.hint.cmd match {
      case "hierarchy" =>
        // hierarchy never emits JSON for not-found case (matches original behavior)
        println(r.message)
        renderHint(r.hint)
      case "explain" => println(s"""{"error":"not found","suggestions":$suggestionsJson}""")
      case "imports" => println(s"""{"results":[],"timedOut":${ctx.idx.timedOut},"suggestions":$suggestionsJson}""")
      case "deps" => println(s"""{"imports":[],"bodyReferences":[],"suggestions":$suggestionsJson}""")
      case "package" | "api" => println(s"""{"error":"not found","suggestions":$suggestionsJson}""")
      case _ => println(s"""{"results":[],"suggestions":$suggestionsJson}""")
    }
  } else {
    println(r.message)
    renderHint(r.hint)
  }
}
