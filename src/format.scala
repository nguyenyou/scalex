import java.nio.file.Path

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

// JSON emission helpers — every string value goes through jsonEscape exactly once.

/** Quoted, escaped JSON string value. */
def jStr(s: String): String = s"\"${jsonEscape(s)}\""

/** JSON array from already-rendered element strings. */
def jArr(items: Iterable[String]): String = items.mkString("[", ",", "]")

/** JSON array of escaped strings. */
def jStrArr(items: Iterable[String]): String = jArr(items.map(jStr))

/** Escaped string value or null. */
def jOpt(o: Option[String]): String = o.map(jStr).getOrElse("null")

def jsonSymbol(s: SymbolInfo, workspace: Path): String =
  val rel = workspace.relativize(s.file).toString
  s"""{"name":${jStr(s.name)},"kind":${jStr(s.kind.label)},"file":${jStr(rel)},"line":${s.line},"package":${jStr(s.packageName)},"parents":${jStrArr(s.parents)},"typeParamParents":${jStrArr(s.typeParamParents)},"signature":${jStr(s.signature)},"annotations":${jStrArr(s.annotations)}}"""

def jsonRef(r: Reference, workspace: Path): String =
  val rel = workspace.relativize(r.file).toString
  s"""{"file":${jStr(rel)},"line":${r.line},"context":${jStr(r.contextLine)},"alias":${jOpt(r.aliasInfo)}}"""

def jsonRefWithContext(r: Reference, workspace: Path, contextN: Int): String =
  val rel = workspace.relativize(r.file).toString
  val lines = readSourceLines(r.file).getOrElse(Array.empty[String])
  val total = lines.length
  val startLine = math.max(1, r.line - contextN)
  val endLine = math.min(total, r.line + contextN)
  val ctxLines = jArr((startLine to endLine).map { i =>
    s"""{"line":$i,"content":${jStr(lines(i - 1))},"match":${i == r.line}}"""
  })
  s"""{"file":${jStr(rel)},"line":${r.line},"context":${jStr(r.contextLine)},"alias":${jOpt(r.aliasInfo)},"contextLines":$ctxLines}"""

// Text formatting helpers

/** " (pkg)" suffix, or empty when the package is unknown. */
def pkgSuffix(packageName: String): String =
  if packageName.nonEmpty then s" ($packageName)" else ""

/** One source line with a left-aligned number gutter: "42   | text". */
private def numberedLine(lineNum: Int, content: String): String =
  s"${lineNum.toString.padTo(4, ' ')} | $content"

/** Print up to `limit` items, then a "... and N more" footer at `indent`. */
private def renderShown[A](items: List[A], limit: Int, indent: String, footerSuffix: String = "")(printOne: A => Unit): Unit = {
  items.take(limit).foreach(printOne)
  if items.size > limit then println(s"$indent... and ${items.size - limit} more$footerSuffix")
}

def formatSymbol(s: SymbolInfo, workspace: Path): String =
  val rel = workspace.relativize(s.file)
  s"  ${s.kind.label.padTo(9, ' ')} ${s.name}${pkgSuffix(s.packageName)} — $rel:${s.line}"

def formatSymbolVerbose(s: SymbolInfo, workspace: Path): String =
  val rel = workspace.relativize(s.file)
  val sig = if s.signature.nonEmpty then s"\n             ${s.signature}" else ""
  s"  ${s.kind.label.padTo(9, ' ')} ${s.name}${pkgSuffix(s.packageName)} — $rel:${s.line}$sig"

def formatRef(r: Reference, workspace: Path): String =
  val rel = workspace.relativize(r.file)
  val alias = r.aliasInfo.map(a => s" [$a]").getOrElse("")
  s"  $rel:${r.line} — ${r.contextLine}$alias"

def formatRefWithContext(r: Reference, workspace: Path, contextN: Int): String =
  val rel = workspace.relativize(r.file)
  val alias = r.aliasInfo.map(a => s" [$a]").getOrElse("")
  val header = s"  $rel:${r.line}$alias"
  readSourceLines(r.file) match
    case None => s"$header\n    > ${r.contextLine}"
    case Some(lines) =>
      val total = lines.length
      val startLine = math.max(1, r.line - contextN)
      val endLine = math.min(total, r.line + contextN)
      val buf = new StringBuilder(header)
      var i = startLine
      while i <= endLine do
        val marker = if i == r.line then ">" else " "
        buf.append(s"\n    $marker ${numberedLine(i, lines(i - 1))}")
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
    case r: TestCount        => renderTestCount(r, ctx)
    case r: CoverageReport   => renderCoverageReport(r, ctx)
    case r: HierarchyResult  => renderHierarchyResult(r, ctx)
    case r: OverrideList     => renderOverrideList(r, ctx)
    case r: Explanation      => renderExplanation(r, ctx)
    case r: Dependencies     => renderDependencies(r, ctx)
    case r: Scopes           => renderScopes(r, ctx)
    case r: SymbolDiff       => renderSymbolDiff(r, ctx)
    case r: AstMatches       => renderAstMatches(r, ctx)
    case r: GrepCount        => renderGrepCount(r, ctx)
    case r: GrepByMethod     => renderGrepByMethod(r, ctx)
    case r: Packages         => renderPackages(r, ctx)
    case r: PackageSymbols   => renderPackageSymbols(r, ctx)
    case r: PackageExplained => renderPackageExplained(r, ctx)
    case r: PackageSummary   => renderPackageSummary(r, ctx)
    case r: ApiSurface       => renderApiSurface(r, ctx)
    case r: RefsTop          => renderRefsTop(r, ctx)
    case r: RefsSummary      => renderRefsSummary(r, ctx)
    case r: Entrypoints      => renderEntrypoints(r, ctx)
    case r: GraphOutput      => println(r.text)
    case r: NotFound         => renderNotFound(r, ctx)
    case r: UsageError       => println(r.message)
  }
}

private def renderHint(h: NotFoundHint): Unit = {
  if h.batchMode then
    if h.suggestions.nonEmpty then
      println(s"  not found (0 matches in ${h.fileCount} files). Did you mean: ${h.suggestions.take(3).mkString(", ")}?")
    else
      println(s"  not found (0 matches in ${h.fileCount} files, top-level only)")
  else {
    if h.looksLikePath then
      println(s"""  Note: "${h.symbol}" looks like a path. Did you mean: scalex ${h.cmd} -w <workspace> ${h.symbol}?""")
    if h.suggestions.nonEmpty then {
      println(s"  Did you mean:")
      h.suggestions.foreach(s => println(s"    $s"))
    }
    println(s"  Hint: scalex indexes top-level declarations in ${h.fileCount} files (local defs, parameters, and pattern bindings are not indexed).")
    if h.parseFailures > 0 then
      println(s"  ${h.parseFailures} files had parse errors (run `scalex index --verbose` to list them).")
    println(s"""  Fallback: try `scalex grep "${h.symbol}"` or use Grep, Glob, Read tools to search manually.""")
  }
}


private def renderSymbolList(r: CmdResult.SymbolList, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val items = if r.truncate then r.symbols.take(ctx.limit) else r.symbols
    println(jArr(items.map(s => jsonSymbol(s, ctx.workspace))))
  } else {
    if r.symbols.isEmpty then {
      if r.emptyMessage.nonEmpty then println(r.emptyMessage)
    } else {
      println(r.header)
      if r.truncate then renderShown(r.symbols, ctx.limit, "  ")(s => println(ctx.fmt(s, ctx.workspace)))
      else r.symbols.foreach(s => println(ctx.fmt(s, ctx.workspace)))
    }
  }
}

private def renderRefList(r: CmdResult.RefList, ctx: CommandContext): Unit = {
  r.stderrHint.foreach(System.err.println)
  if ctx.jsonOutput then {
    val jFn: Reference => String = if r.useContext then ctx.jRef else ref => jsonRef(ref, ctx.workspace)
    val arr = jArr(r.refs.take(ctx.limit).map(jFn))
    val hintStr = r.hint.getOrElse("")
    println(s"""{"results":$arr,"timedOut":${r.timedOut}$hintStr}""")
  } else {
    if r.refs.isEmpty then {
      if r.emptyMessage.nonEmpty then println(r.emptyMessage)
    } else {
      println(r.header)
      val fFn: Reference => String = if r.useContext then ctx.fmtRef else ref => formatRef(ref, ctx.workspace)
      renderShown(r.refs, ctx.limit, "  ")(ref => println(fFn(ref)))
    }
  }
}

private def renderCategorizedRefs(r: CmdResult.CategorizedRefs, ctx: CommandContext): Unit = {
  r.stderrHint.foreach(System.err.println)
  if ctx.jsonOutput then {
    val entries = r.grouped.map { (cat, refs) =>
      s""""${cat.toString}":${jArr(refs.take(ctx.limit).map(ctx.jRef))}"""
    }.mkString(",")
    println(s"""{"categories":{$entries},"timedOut":${r.timedOut}}""")
  } else {
    val total = r.grouped.values.map(_.size).sum
    val suffix = timedOutSuffix(r.timedOut)
    println(s"""References to "${r.symbol}" — $total found:$suffix""")
    Confidence.values.foreach { conf =>
      val catRefs = r.grouped.flatMap { (cat, refs) =>
        refs.map(ref => (cat, ref, ctx.idx.resolveConfidence(ref, r.symbol, r.targetPkgs)))
      }.filter(_._3 == conf).toList
      if catRefs.nonEmpty then {
        println(s"\n  ${conf.label} (${conf.explanation}):")
        val byCat = catRefs.groupBy(_._1)
        refCategoryOrder.foreach { cat =>
          byCat.get(cat).filter(_.nonEmpty).foreach { entries =>
            val sorted = entries.sortBy((_, ref, _) => (path = ctx.workspace.relativize(ref.file).toString, line = ref.line))
            println(s"\n    ${cat.toString}:")
            renderShown(sorted, ctx.limit, "      ")((_, ref, _) => println(s"    ${ctx.fmtRef(ref)}"))
          }
        }
      }
    }
  }
}

private def renderFlatRefs(r: CmdResult.FlatRefs, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    println(s"""{"results":${jArr(r.refs.take(ctx.limit).map(ctx.jRef))},"timedOut":${r.timedOut}}""")
  } else {
    val suffix = timedOutSuffix(r.timedOut)
    println(s"""References to "${r.symbol}" — ${r.refs.size} found:$suffix""")
    val annotated = r.refs.map(ref => (ref, ctx.idx.resolveConfidence(ref, r.symbol, r.targetPkgs)))
    val sorted = annotated.sortBy { case (ref, c) => (confidence = c.ordinal, path = ctx.workspace.relativize(ref.file).toString, line = ref.line) }
    var lastConf: Option[Confidence] = None
    var shown = 0
    sorted.foreach { case (ref, conf) =>
      if shown < ctx.limit then {
        if !lastConf.contains(conf) then {
          println(s"\n  [${conf.label}]")
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
      println(jStrArr(r.items.take(ctx.limit)))
  } else {
    if r.items.isEmpty then {
      if r.emptyMessage.nonEmpty then println(r.emptyMessage)
    } else {
      println(r.header)
      renderShown(r.items, ctx.limit, "  ")(f => println(s"  $f"))
    }
  }
}

private def renderIndexStats(r: CmdResult.IndexStats, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val byKind = r.symbolsByKind.map((k, c) => s""""${k.label}":$c""").mkString(",")
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

private def renderInlineBody(body: Option[BodyInfo], indent: String): Unit =
  body.foreach { b =>
    val bodyLines = b.sourceText.split("\n")
    bodyLines.zipWithIndex.foreach { case (line, i) =>
      println(s"$indent${numberedLine(b.startLine + i, line)}")
    }
  }

/** `,"body":…,"bodyStartLine":…,"bodyEndLine":…` fields, or empty when there is no body. */
private def jsonBodyFields(body: Option[BodyInfo]): String =
  body.map(b => s""","body":${jStr(b.sourceText)},"bodyStartLine":${b.startLine},"bodyEndLine":${b.endLine}""").getOrElse("")

/** The shared member-object core: `"name":…,"kind":…,"line":…,"signature":…` (no braces). */
private def jsonMemberFields(m: MemberInfo): String =
  s""""name":${jStr(m.name)},"kind":${jStr(m.kind.label)},"line":${m.line},"signature":${jStr(m.signature)}"""

/** Member line in `members` text output: kind + signature (or name with --brief). */
private def memberLine(m: MemberInfo, brief: Boolean): String =
  if brief then s"${m.kind.label.padTo(5, ' ')} ${m.name.padTo(30, ' ')}"
  else s"${m.kind.label.padTo(5, ' ')} ${m.signature.padTo(50, ' ')}"

/** Member line in `explain` text output: kind + name (or signature with --verbose). */
private def explainMemberLine(m: MemberInfo, verbose: Boolean): String =
  s"${m.kind.label.padTo(5, ' ')} ${if verbose then m.signature else m.name}"

private def renderMemberSections(r: CmdResult.MemberSections, ctx: CommandContext): Unit = {
  // Slice a section's items using global running counters (skipLeft, showLeft).
  // Returns (shown items, count of items in this section omitted by the limit).
  def sliceSection[A](items: List[A], skipLeft: Int, showLeft: Int): (shown: List[A], omitted: Int, newSkip: Int, newShow: Int) =
    val toSkip = skipLeft.min(items.size)
    val available = items.drop(toSkip)
    val toShow = showLeft.min(available.size)
    val shown = available.take(toShow)
    (shown = shown, omitted = available.size - toShow, newSkip = skipLeft - toSkip, newShow = showLeft - toShow)

  if ctx.jsonOutput then {
    val allMembers = r.sections.flatMap { sec =>
      val ownMembers = sec.ownMembers.map { m =>
        val rel = ctx.workspace.relativize(sec.file).toString
        val overrideJson = if m.isOverride then ""","isOverride":true""" else ""
        s"""{${jsonMemberFields(m)},"file":${jStr(rel)},"owner":${jStr(r.symbol)},"ownerKind":${jStr(sec.ownerKind.label)},"package":${jStr(sec.packageName)},"inherited":false$overrideJson${jsonBodyFields(m.body)}}"""
      }
      val inheritedMembers = sec.inherited.flatMap { (parentName, parentFile, parentPackage, members) =>
        members.map { m =>
          val rel = parentFile.map(f => ctx.workspace.relativize(f).toString).getOrElse("")
          s"""{${jsonMemberFields(m)},"file":${jStr(rel)},"owner":${jStr(parentName)},"ownerKind":"inherited","package":${jStr(parentPackage)},"inherited":true}"""
        }
      }
      val companionMembers = sec.companion.toList.flatMap { (compSym, compMembers) =>
        val rel = ctx.workspace.relativize(compSym.file).toString
        compMembers.map { m =>
          s"""{${jsonMemberFields(m)},"file":${jStr(rel)},"owner":${jStr(compSym.name)},"ownerKind":"companion","package":${jStr(compSym.packageName)},"inherited":false}"""
        }
      }
      ownMembers ++ inheritedMembers ++ companionMembers
    }
    println(jArr(allMembers.drop(ctx.offset).take(ctx.limit)))
  } else {
    if r.sections.isEmpty then {
      println(s"""No class/trait/object/enum "${r.symbol}" found""")
    } else {
      // Running counters for global pagination across all sections
      var skipLeft = ctx.offset
      var showLeft = ctx.limit
      r.sections.foreach { sec =>
        val rel = ctx.workspace.relativize(sec.file)
        println(s"Members of ${sec.ownerKind.label} ${r.symbol}${pkgSuffix(sec.packageName)} — $rel:${sec.line}:")
        if sec.ownMembers.isEmpty then println("  (no members)")
        else {
          val (shown, omitted, newSkip, newShow) = sliceSection(sec.ownMembers, skipLeft, showLeft)
          skipLeft = newSkip; showLeft = newShow
          if shown.nonEmpty || omitted > 0 then println(s"  Defined in ${r.symbol}:")
          shown.foreach { m =>
            val overrideMarker = if m.isOverride then "  [override]" else ""
            println(s"    ${memberLine(m, ctx.brief)} :${m.line}$overrideMarker")
            renderInlineBody(m.body, "      ")
          }
          if omitted > 0 then println(s"    ... and $omitted more")
        }
        sec.inherited.foreach { (parentName, _, _, pMembers) =>
          val (shown, omitted, newSkip, newShow) = sliceSection(pMembers, skipLeft, showLeft)
          skipLeft = newSkip; showLeft = newShow
          if shown.nonEmpty || omitted > 0 then println(s"  Inherited from $parentName:")
          shown.foreach(m => println(s"    ${memberLine(m, ctx.brief)} :${m.line}"))
          if omitted > 0 then println(s"    ... and $omitted more")
        }
        sec.companion.foreach { (compSym, compMembers) =>
          val compRel = ctx.workspace.relativize(compSym.file)
          if compMembers.isEmpty then {
            println(s"\n  Companion ${compSym.kind.label} ${compSym.name} — $compRel:${compSym.line}:")
            println("    (no members)")
          } else {
            val (shown, omitted, newSkip, newShow) = sliceSection(compMembers, skipLeft, showLeft)
            skipLeft = newSkip; showLeft = newShow
            if shown.nonEmpty || omitted > 0 then
              println(s"\n  Companion ${compSym.kind.label} ${compSym.name} — $compRel:${compSym.line}:")
            shown.foreach(m => println(s"    ${memberLine(m, ctx.brief)} :${m.line}"))
            if omitted > 0 then println(s"    ... and $omitted more")
          }
        }
      }
    }
  }
}

private def renderDocEntries(r: CmdResult.DocEntries, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val entries = r.entries.take(ctx.limit).map { e =>
      val rel = ctx.workspace.relativize(e.sym.file).toString
      s"""{"name":${jStr(e.sym.name)},"kind":${jStr(e.sym.kind.label)},"file":${jStr(rel)},"line":${e.sym.line},"package":${jStr(e.sym.packageName)},"doc":${jOpt(e.doc)}}"""
    }
    println(jArr(entries))
  } else {
    r.entries.take(ctx.limit).foreach { e =>
      val rel = ctx.workspace.relativize(e.sym.file)
      println(s"${e.sym.kind.label} ${r.symbol}${pkgSuffix(e.sym.packageName)} — $rel:${e.sym.line}:")
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
    val kindJson = d.symbolsByKind.map((k, c) => s""""${k.label}":$c""").mkString("{", ",", "}")
    val pkgJson = jArr(d.topPackages.map((p, c) => s"""{"package":${jStr(p)},"count":$c}"""))
    if d.hasArchitecture then {
      val depsJson = if ctx.concise then {
        // Concise JSON: top 10 most-connected packages only
        val topConnected = d.pkgDeps.toList.sortBy(-_._2.size).take(10)
        topConnected.map { (pkg, deps) =>
          s"""${jStr(pkg)}:${jStrArr(deps.toList.sorted.take(10))}"""
        }.mkString("{", ",", "}")
      } else {
        d.pkgDeps.map { (pkg, deps) =>
          s"""${jStr(pkg)}:${jStrArr(deps)}"""
        }.mkString("{", ",", "}")
      }
      val hubJson = jArr(d.hubTypes.map((n, c, sig) => s"""{"name":${jStr(n)},"score":$c,"signature":${jStr(sig)}}"""))
      val focusPkgJson = d.focusPackage.map(p => s""","focusPackage":${jStr(p)}""").getOrElse("")
      val conciseJson = if ctx.concise then {
        val totalEdges = d.pkgDeps.values.map(_.size).sum
        s""","concise":true,"totalPackagesWithDeps":${d.pkgDeps.size},"totalEdges":$totalEdges"""
      } else ""
      println(s"""{"fileCount":${d.fileCount},"symbolCount":${d.symbolCount},"packageCount":${d.packageCount},"symbolsByKind":$kindJson,"topPackages":$pkgJson,"packageDependencies":$depsJson,"hubTypes":$hubJson$focusPkgJson$conciseJson}""")
    } else {
      val extJson = jArr(d.mostExtended.map((n, c, sig) => s"""{"name":${jStr(n)},"implementations":$c,"signature":${jStr(sig)}}"""))
      println(s"""{"fileCount":${d.fileCount},"symbolCount":${d.symbolCount},"packageCount":${d.packageCount},"symbolsByKind":$kindJson,"topPackages":$pkgJson,"mostExtended":$extJson}""")
    }
  } else if ctx.concise then {
    // Fixed-size concise output: ~60 lines regardless of codebase size
    val conciseLimit = 10
    val focusNote = d.focusPackage.map(p => s" (scoped to $p)").getOrElse("")
    println(s"Project: ${d.fileCount} files, ${d.symbolCount} symbols, ${d.packageCount} packages$focusNote\n")

    // Symbols by kind — single compact line
    val kindLine = d.symbolsByKind.map((k, c) => s"${c} ${k.label}").mkString(", ")
    println(s"Symbols: $kindLine\n")

    // Top packages — capped at conciseLimit
    val shownPkgs = d.topPackages.take(conciseLimit)
    println(s"Top packages:")
    shownPkgs.foreach { (pkg, count) =>
      println(s"  ${pkg.padTo(50, ' ')} $count")
    }
    val remainingPkgs = d.packageCount - shownPkgs.size
    if remainingPkgs > 0 then
      println(s"  ... and $remainingPkgs more (use overview --limit N to show more)")

    // Package dependency summary — stats + top connectors, NOT full graph
    if d.pkgDeps.nonEmpty then {
      val totalEdges = d.pkgDeps.values.map(_.size).sum
      val pkgsWithDeps = d.pkgDeps.size
      println(s"\nPackage dependencies: $pkgsWithDeps packages, $totalEdges cross-package edges")
      val topConnectors = d.pkgDeps.toList.sortBy(-_._2.size).take(5)
      println(s"  Most connected:")
      topConnectors.foreach { (pkg, deps) =>
        println(s"    ${pkg.padTo(45, ' ')} → ${deps.size} deps")
      }
    }

    // Hub types — capped at conciseLimit
    val shownHubs = d.hubTypes.take(conciseLimit)
    if shownHubs.nonEmpty then {
      println(s"\nHub types (top ${shownHubs.size}):")
      shownHubs.foreach { (name, count, sig) =>
        val sigHint = if sig.nonEmpty then s"  $sig" else ""
        println(s"  ${name.padTo(30, ' ')} $count references$sigHint")
      }
    }

    println(s"\nDrill down: overview --architecture, overview --focus-package PKG, entrypoints")
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
    if !d.hasArchitecture then {
      println(s"\nMost extended (by package spread, then implementation count):")
      d.mostExtended.foreach { (name, count, sig) =>
        val sigHint = if sig.nonEmpty then s"  $sig" else ""
        println(s"  ${name.padTo(30, ' ')} $count impl$sigHint")
      }
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

/** Context lines around a [startLine, endLine] body span, clamped to the file. */
private def contextWindow(lines: Array[String], startLine: Int, endLine: Int, n: Int): (
  before: Seq[(lineNum: Int, text: String)], after: Seq[(lineNum: Int, text: String)]
) = {
  val total = lines.length
  val ctxStart = math.max(1, startLine - n)
  val ctxEnd = math.min(total, endLine + n)
  val before = (ctxStart until startLine).filter(i => i >= 1 && i <= total).map(i => (lineNum = i, text = lines(i - 1)))
  val after = ((endLine + 1) to ctxEnd).filter(i => i >= 1 && i <= total).map(i => (lineNum = i, text = lines(i - 1)))
  (before = before, after = after)
}

private def renderSourceBlocks(r: CmdResult.SourceBlocks, ctx: CommandContext): Unit = {
  def windowFor(file: Path, b: BodyInfo): (before: Seq[(lineNum: Int, text: String)], after: Seq[(lineNum: Int, text: String)]) =
    if r.contextLines > 0 then
      contextWindow(readSourceLines(file).getOrElse(Array.empty[String]), b.startLine, b.endLine, r.contextLines)
    else (before = Seq.empty, after = Seq.empty)

  if ctx.jsonOutput then {
    val arr = r.blocks.take(ctx.limit).map { (file, b) =>
      val rel = ctx.workspace.relativize(file).toString
      val importsJson = if r.showImports then
        extractImportLines(file).map(imp => s""","imports":${jStr(imp)}""").getOrElse("")
      else ""
      val contextJson = if r.contextLines > 0 then
        val (before, after) = windowFor(file, b)
        s""","contextBefore":${jStrArr(before.map(_.text))},"contextAfter":${jStrArr(after.map(_.text))}"""
      else ""
      val abstractJson = if b.isAbstract then ""","isAbstract":true""" else ""
      s"""{"name":${jStr(b.symbolName)},"owner":${jStr(b.ownerName)},"file":${jStr(rel)},"startLine":${b.startLine},"endLine":${b.endLine},"body":${jStr(b.sourceText)}$abstractJson$importsJson$contextJson}"""
    }
    println(jArr(arr))
  } else {
    r.blocks.take(ctx.limit).foreach { (file, b) =>
      val ownerStr = if b.ownerName.nonEmpty then s" — ${b.ownerName}" else ""
      val rel = ctx.workspace.relativize(file)
      // Imports block
      if r.showImports then
        extractImportLines(file).foreach { imp =>
          println(s"Imports — $rel:")
          imp.split("\n").foreach(l => println(s"  $l"))
          println()
        }
      val label = if b.isAbstract then "Signature" else "Body"
      val abstractNote = if b.isAbstract then " (abstract, no body)" else ""
      println(s"$label of ${b.symbolName}$ownerStr — $rel:${b.startLine}$abstractNote:")
      val (before, after) = windowFor(file, b)
      before.foreach((i, text) => println(s"  ${numberedLine(i, text)}"))
      if before.nonEmpty then println("  ---")
      val bodyLines = b.sourceText.split("\n")
      bodyLines.zipWithIndex.foreach { case (line, i) =>
        println(s"  ${numberedLine(b.startLine + i, line)}")
      }
      if after.nonEmpty then println("  ---")
      after.foreach((i, text) => println(s"  ${numberedLine(i, text)}"))
      println()
    }
  }
}

private def renderTestSuites(r: CmdResult.TestSuites, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val arr = r.suites.take(ctx.limit).map { suite =>
      val rel = ctx.workspace.relativize(suite.file).toString
      val testsJson = jArr(suite.tests.map { tc =>
        val bodyField = if r.showBody then {
          tc.body.map(b => s""","body":${jStr(b.sourceText)}""").getOrElse("")
        } else ""
        s"""{"name":${jStr(tc.name)},"line":${tc.line}$bodyField}"""
      })
      s"""{"suite":${jStr(suite.name)},"file":${jStr(rel)},"line":${suite.line},"tests":$testsJson}"""
    }
    println(jArr(arr))
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
              renderInlineBody(Some(b), "    ")
              println()
            }
          }
        }
        if !r.showBody && !ctx.verbose then println()
      }
    }
  }
}

private def renderTestCount(r: CmdResult.TestCount, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then
    println(s"""{"suites":${r.suites},"tests":${r.tests},"dynamicSites":${r.dynamicSites}}""")
  else {
    val qualifier = if r.dynamicSites > 0 then " (literal names only)" else ""
    println(s"${r.tests} tests${qualifier} across ${r.suites} suites")
    if r.dynamicSites > 0 then
      Console.err.println(s"  ${r.dynamicSites} dynamic test sites detected — actual count requires runtime")
  }
}

private def renderCoverageReport(r: CmdResult.CoverageReport, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val refsJson = jArr(r.testRefs.take(ctx.limit).map(ctx.jRef))
    println(s"""{"symbol":${jStr(r.symbol)},"testFileCount":${r.testFiles.size},"referenceCount":${r.testRefs.size},"references":$refsJson}""")
  } else {
    if r.testRefs.isEmpty then {
      if r.totalRefs == 0 then {
        println(s"""Coverage of "${r.symbol}" — no references found""")
        r.hint.foreach(renderHint)
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
    val file = jOpt(n.file.map(f => ctx.workspace.relativize(f).toString))
    val kind = jOpt(n.kind.map(_.label))
    val line = n.line.map(_.toString).getOrElse("null")
    s"""{"name":${jStr(n.name)},"kind":$kind,"file":$file,"line":$line,"package":${jStr(n.packageName)},"isExternal":${n.isExternal}}"""
  }
  def treeJson(t: HierarchyTree): String = {
    val ps = jArr(t.parents.map(treeJson))
    val cs = jArr(t.children.map(treeJson))
    val trunc = if t.truncatedChildren > 0 then s""","truncatedChildren":${t.truncatedChildren}""" else ""
    s"""{"node":${nodeJson(t.root)},"parents":$ps,"children":$cs$trunc}"""
  }
  // One recursive printer for both directions: `down` walks children (with
  // truncation notes), parents-mode marks external nodes instead.
  def printLevel(nodes: List[HierarchyTree], indent: String, down: Boolean): Unit = {
    nodes.zipWithIndex.foreach { case (t, i) =>
      val isLast = i == nodes.size - 1
      val prefix = if isLast then s"$indent└── " else s"$indent├── "
      val nextIndent = if isLast then s"$indent    " else s"$indent│   "
      val n = t.root
      val nkind = n.kind.map(_.label + " ").getOrElse("")
      val nloc = if !down && n.isExternal then " [external]"
                 else n.file.map(f => s" — ${ctx.workspace.relativize(f)}:${n.line.getOrElse(0)}").getOrElse("")
      println(s"$prefix$nkind${n.name}${pkgSuffix(n.packageName)}$nloc")
      printLevel(if down then t.children else t.parents, nextIndent, down)
      if down && t.truncatedChildren > 0 then
        println(s"$nextIndent... and ${t.truncatedChildren} more children")
    }
  }
  if ctx.jsonOutput then {
    println(treeJson(tree))
  } else {
    val rootNode = tree.root
    val kind = rootNode.kind.map(_.label).getOrElse("unknown")
    val loc = rootNode.file.map(f => s" — ${ctx.workspace.relativize(f)}:${rootNode.line.getOrElse(0)}").getOrElse("")
    println(s"Hierarchy of $kind ${rootNode.name}${pkgSuffix(rootNode.packageName)}$loc:")
    if ctx.goUp then {
      println("  Parents:")
      if tree.parents.isEmpty then println("    (none)")
      else printLevel(tree.parents, "    ", down = false)
    }
    if ctx.goDown then {
      println("  Children:")
      if tree.children.isEmpty then println("    (none)")
      else printLevel(tree.children, "    ", down = true)
    }
  }
}

private def renderOverrideList(r: CmdResult.OverrideList, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val arr = r.results.map { o =>
      val rel = ctx.workspace.relativize(o.file).toString
      s"""{"enclosingClass":${jStr(o.enclosingClass)},"enclosingKind":${jStr(o.enclosingKind.label)},"file":${jStr(rel)},"line":${o.line},"signature":${jStr(o.signature)},"package":${jStr(o.packageName)}${jsonBodyFields(o.body)}}"""
    }
    println(jArr(arr))
  } else {
    println(r.header)
    r.results.foreach { o =>
      val rel = ctx.workspace.relativize(o.file)
      println(s"  ${o.enclosingClass}${pkgSuffix(o.packageName)} — $rel:${o.line}")
      println(s"    ${o.signature}")
      renderInlineBody(o.body, "    ")
    }
  }
}

private def renderExplanation(r: CmdResult.Explanation, ctx: CommandContext): Unit = {
  val sym = r.sym
  val rel = ctx.workspace.relativize(sym.file)
  if ctx.jsonOutput then {
    val membersJson = jArr(r.members.map { m =>
      val overrideJson = if m.isOverride then ""","isOverride":true""" else ""
      s"""{${jsonMemberFields(m)}$overrideJson${jsonBodyFields(m.body)}}"""
    })
    val implsJson = jArr(r.impls.map(s => jsonSymbol(s, ctx.workspace)))
    val primaryKeys = r.members.map(m => (name = m.name, kind = m.kind)).toSet
    val companionJson = r.companion.map { (compSym, compMembers) =>
      val uniqueCompMembers = compMembers.filter(m => !primaryKeys.contains((name = m.name, kind = m.kind)))
      val cMembers = jArr(uniqueCompMembers.map(m => s"{${jsonMemberFields(m)}}"))
      s"""{"definition":${jsonSymbol(compSym, ctx.workspace)},"members":$cMembers}"""
    }.getOrElse("null")
    def explainedImplJson(ei: ExplainedImpl): String = {
      val mJson = jArr(ei.members.map(m => s"{${jsonMemberFields(m)}}"))
      val subJson = jArr(ei.subImpls.map(explainedImplJson))
      s"""{"definition":${jsonSymbol(ei.sym, ctx.workspace)},"members":$mJson,"subImplementations":$subJson}"""
    }
    val expandedJson = jArr(r.expandedImpls.map(explainedImplJson))
    val importCount = r.importRefs.size
    val importRefsJson = if importCount <= 10 then
      s""","importFiles":${jArr(r.importRefs.map(ref => jsonRef(ref, ctx.workspace)))}"""
    else ""
    val otherJson = if r.otherMatches.nonEmpty then
      s""","otherMatches":${jStrArr(r.otherMatches)}"""
    else ""
    val totalImplJson = if r.totalImpls > r.impls.size then s""","totalImplementations":${r.totalImpls}""" else ""
    val inheritedJson = if r.inherited.nonEmpty then
      val groups = jArr(r.inherited.map { (parentName, parentFile, parentPackage, members) =>
        val pRel = jOpt(parentFile.map(f => ctx.workspace.relativize(f).toString))
        val mJson = jArr(members.map(m => s"{${jsonMemberFields(m)}}"))
        s"""{"parent":${jStr(parentName)},"parentFile":$pRel,"parentPackage":${jStr(parentPackage)},"members":$mJson}"""
      })
      s""","inherited":$groups"""
    else ""
    val relatedJson = if r.relatedTypes.nonEmpty then
      s""","relatedTypes":${jArr(r.relatedTypes.map(s => jsonSymbol(s, ctx.workspace)))}"""
    else ""
    println(s"""{"definition":${jsonSymbol(sym, ctx.workspace)},"doc":${jOpt(r.doc)},"members":$membersJson,"implementations":$implsJson,"importCount":$importCount$importRefsJson,"companion":$companionJson,"expandedImplementations":$expandedJson$otherJson$totalImplJson$inheritedJson$relatedJson}""")
  } else {
    println(s"Explanation of ${sym.kind.label} ${sym.name}${pkgSuffix(sym.packageName)}:\n")
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
        if !ctx.brief then println("  Scaladoc: (none)\n")
    }
    if r.members.nonEmpty then {
      println(s"  Members (top ${r.members.size}):")
      r.members.foreach { m =>
        val overrideMarker = if m.isOverride then "  [override]" else ""
        println(s"    ${explainMemberLine(m, ctx.verbose)}$overrideMarker")
        renderInlineBody(m.body, "      ")
      }
      println()
    }
    r.inherited.foreach { (parentName, _, _, pMembers) =>
      println(s"  Inherited from $parentName:")
      renderShown(pMembers, ctx.membersLimit, "    ")(m => println(s"    ${explainMemberLine(m, ctx.verbose)}"))
      println()
    }
    r.companion.foreach { (compSym, compMembers) =>
      val compRel = ctx.workspace.relativize(compSym.file)
      println(s"  Companion ${compSym.kind.label} ${compSym.name} — $compRel:${compSym.line}")
      if compMembers.nonEmpty then
        // Deduplicate: skip companion members that are identical to primary members
        val primaryKeys = r.members.map(m => (name = m.name, kind = m.kind)).toSet
        val uniqueCompMembers = compMembers.filter(m => !primaryKeys.contains((name = m.name, kind = m.kind)))
        val dupeCount = compMembers.size - uniqueCompMembers.size
        if uniqueCompMembers.nonEmpty then
          uniqueCompMembers.foreach(m => println(s"    ${explainMemberLine(m, ctx.verbose)}"))
        if dupeCount > 0 then
          println(s"    ($dupeCount members shared with ${sym.kind.label}, shown above)")
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
          println(s"$indent${ei.sym.kind.label} ${ei.sym.name} — ${ctx.workspace.relativize(ei.sym.file)}:${ei.sym.line}")
          ei.members.foreach(m => println(s"$indent  ${explainMemberLine(m, ctx.verbose)}"))
          if ei.subImpls.nonEmpty then printExpanded(ei.subImpls, indent + "  ")
        }
      }
      printExpanded(r.expandedImpls, "    ")
      println()
    }
    if r.relatedTypes.nonEmpty then {
      println(s"  Related types (${r.relatedTypes.size}):")
      r.relatedTypes.foreach { s =>
        val relFile = ctx.workspace.relativize(s.file)
        println(s"    ${s.kind.label.padTo(5, ' ')} ${s.name}${pkgSuffix(s.packageName)} — $relFile:${s.line}")
      }
      println()
    }
    if !ctx.shallow && !ctx.brief then
      val importCount = r.importRefs.size
      if importCount == 0 then
        println("  Imported by: 0 files")
      else if importCount <= 10 then
        println(s"  Imported by ($importCount files):")
        r.importRefs.foreach(ref => println(s"    ${ctx.workspace.relativize(ref.file)}:${ref.line}"))
      else
        println(s"  Imported by: $importCount files (use `scalex imports ${sym.name}` for full list)")
    if r.otherMatches.nonEmpty then
      Console.err.println(s"(${r.otherMatches.size} other match${if r.otherMatches.size > 1 then "es" else ""}:)")
      r.otherMatches.foreach(m => Console.err.println(s"  scalex explain $m"))
  }
}

private def renderDependencies(r: CmdResult.Dependencies, ctx: CommandContext): Unit = {
  def depJson(d: DepInfo): String = {
    val file = jOpt(d.file.map(f => ctx.workspace.relativize(f).toString))
    val line = d.line.map(_.toString).getOrElse("null")
    s"""{"name":${jStr(d.name)},"kind":${jStr(d.kind)},"file":$file,"line":$line,"package":${jStr(d.packageName)},"depth":${d.depth}}"""
  }
  def printDep(d: DepInfo): Unit = {
    val indent = "  " * d.depth
    val loc = d.file.map(f => s" — ${ctx.workspace.relativize(f)}:${d.line.getOrElse(0)}").getOrElse("")
    println(s"    $indent${d.kind.padTo(9, ' ')} ${d.name}$loc")
  }
  if ctx.jsonOutput then {
    println(s"""{"imports":${jArr(r.importDeps.map(depJson))},"bodyReferences":${jArr(r.bodyDeps.map(depJson))}}""")
  } else {
    println(s"""Dependencies of "${r.symbol}":""")
    if r.importDeps.nonEmpty then {
      println(s"\n  Imports:")
      renderShown(r.importDeps, ctx.limit, "    ")(printDep)
    }
    if r.bodyDeps.nonEmpty then {
      println(s"\n  Body references:")
      renderShown(r.bodyDeps, ctx.limit, "    ")(printDep)
    }
  }
}

private def renderScopes(r: CmdResult.Scopes, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val arr = jArr(r.scopes.map { s =>
      s"""{"name":${jStr(s.name)},"kind":${jStr(s.kind)},"line":${s.line}}"""
    })
    println(s"""{"file":${jStr(ctx.workspace.relativize(r.file).toString)},"line":${r.line},"scopes":$arr}""")
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
        s"""{"name":${jStr(s.name)},"kind":${jStr(s.kind.label)},"file":${jStr(s.file)},"line":${s.line},"package":${jStr(s.packageName)},"signature":${jStr(s.signature)}}"""
      val addedJson = jArr(r.added.take(ctx.limit).map(diffSymJson))
      val removedJson = jArr(r.removed.take(ctx.limit).map(diffSymJson))
      val modifiedJson = jArr(r.modified.take(ctx.limit).map { (o, n) =>
        s"""{"old":${diffSymJson(o)},"new":${diffSymJson(n)}}"""
      })
      println(s"""{"ref":${jStr(r.ref)},"filesChanged":${r.filesChanged},"added":$addedJson,"removed":$removedJson,"modified":$modifiedJson}""")
    }
  } else {
    if r.filesChanged == 0 then {
      println(s"No Scala files changed compared to ${r.ref}")
    } else {
      println(s"Symbol changes compared to ${r.ref} (${r.filesChanged} files changed):")
      def printGroup(label: String, marker: String, syms: List[DiffSymbol]): Unit =
        if syms.nonEmpty then {
          println(s"\n  $label (${syms.size}):")
          renderShown(syms, ctx.limit, "    ")(s => println(s"    $marker ${s.kind.label.padTo(9, ' ')} ${s.name} — ${s.file}:${s.line}"))
        }
      printGroup("Added", "+", r.added)
      printGroup("Removed", "-", r.removed)
      printGroup("Modified", "~", r.modified.map(_.after))
      if r.added.isEmpty && r.removed.isEmpty && r.modified.isEmpty then
        println("  No symbol-level changes detected")
    }
  }
}

private def renderAstMatches(r: CmdResult.AstMatches, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val arr = r.results.map { m =>
      val rel = ctx.workspace.relativize(m.file).toString
      s"""{"name":${jStr(m.name)},"kind":${jStr(m.kind.label)},"file":${jStr(rel)},"line":${m.line},"package":${jStr(m.packageName)},"signature":${jStr(m.signature)}}"""
    }
    println(jArr(arr))
  } else {
    if r.results.isEmpty then
      println(s"No types matching AST pattern (${r.filters})")
    else {
      println(s"Types matching AST pattern (${r.filters}) — ${r.results.size} found:")
      r.results.foreach { m =>
        val rel = ctx.workspace.relativize(m.file)
        println(s"  ${m.kind.label.padTo(9, ' ')} ${m.name}${pkgSuffix(m.packageName)} — $rel:${m.line}")
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
    val suffix = timedOutSuffix(r.timedOut)
    println(s"${r.matches} matches across ${r.files} files$suffix")
  }
}

private def renderGrepByMethod(r: CmdResult.GrepByMethod, ctx: CommandContext): Unit = {
  r.stderrHint.foreach(System.err.println)
  val suffix = timedOutSuffix(r.timedOut)
  if ctx.jsonOutput then {
    val items = jArr(r.methods.take(ctx.limit).map { m =>
      val file = ctx.workspace.relativize(m.file).toString
      val linesJson = jArr(m.matchLines.map(ml => s"""{"line":${ml.lineNum},"text":${jStr(ml.text)}}"""))
      s"""{"name":${jStr(m.member.name)},"kind":${jStr(m.member.kind.label)},"signature":${jStr(m.member.signature)},"file":${jStr(file)},"line":${m.member.line},"matches":${m.matchCount},"matchLines":$linesJson}"""
    })
    val total = r.methods.map(_.matchCount).sum
    val truncated = if r.methods.size > ctx.limit then s""","truncated":true,"totalMethods":${r.methods.size}""" else ""
    val timedOutStr = if r.timedOut then s""","timedOut":true""" else ""
    val hintStr = r.hint.getOrElse("")
    println(s"""{"pattern":${jStr(r.pattern)},"owner":${jStr(r.owner)},"methods":$items,"totalMatches":$total$truncated$timedOutStr$hintStr}""")
  } else {
    if r.methods.isEmpty then
      println(s"""No methods in ${r.owner} whose body contains "${r.pattern}"$suffix""")
    else {
      val total = r.methods.map(_.matchCount).sum
      println(s"""Methods in ${r.owner} whose body contains "${r.pattern}" — ${r.methods.size} methods, $total matches:$suffix""")
      val maxSig = r.methods.take(ctx.limit).map(_.member.signature.length).maxOption.getOrElse(0)
      renderShown(r.methods, ctx.limit, "  ", " (use --limit 0 to show all)") { m =>
        val loc = s"${ctx.workspace.relativize(m.file)}:${m.member.line}"
        val pad = " " * (maxSig - m.member.signature.length)
        val plural = if m.matchCount == 1 then "match" else "matches"
        println(s"  ${m.member.signature}$pad — $loc  (${m.matchCount} $plural)")
        m.matchLines.foreach { ml =>
          println(s"      ${ml.lineNum} | ${ml.text}")
        }
      }
    }
  }
}

private def renderPackages(r: CmdResult.Packages, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    println(jStrArr(r.packages))
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
    val arr = jArr(r.symbols.take(ctx.limit).map(s => jsonSymbol(s, ctx.workspace)))
    val truncated = if r.symbols.size > ctx.limit then ",\"truncated\":true" else ""
    println(s"""{"package":${jStr(r.pkg)},"symbolCount":${r.symbols.size},"symbols":$arr$truncated}""")
  } else {
    if r.symbols.isEmpty then {
      println(s"""Package ${r.pkg}: (no symbols)""")
    } else {
      println(s"Package ${r.pkg} (${r.symbols.size} symbols):\n")
      val byKind: List[(kind: SymbolKind, syms: List[SymbolInfo])] =
        r.symbols.groupBy(_.kind).toList.sortBy(-_._2.size).map((k, s) => (kind = k, syms = s))
      byKind.foreach { (kind, syms) =>
        println(s"  ${pluralKind(kind)} (${syms.size}):")
        renderShown(syms.sortBy(_.name), ctx.limit, "    ") { s =>
          if ctx.verbose then
            println(s"    ${s.name.padTo(30, ' ')} ${s.signature.take(60)}  — ${ctx.workspace.relativize(s.file)}:${s.line}")
          else
            println(s"    ${s.name.padTo(30, ' ')} ${ctx.workspace.relativize(s.file)}:${s.line}")
        }
      }
    }
  }
}

private def renderPackageExplained(r: CmdResult.PackageExplained, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val typesJson = jArr(r.entries.map { e =>
      val mJson = jArr(e.members.map(m => s"{${jsonMemberFields(m)}}"))
      s"""{"definition":${jsonSymbol(e.sym, ctx.workspace)},"members":$mJson,"implCount":${e.implCount}}"""
    })
    val truncatedJson = if r.totalTypes > r.entries.size then s""","totalTypes":${r.totalTypes},"truncated":true""" else ""
    println(s"""{"package":${jStr(r.pkg)},"totalSymbols":${r.totalSymbols},"types":$typesJson$truncatedJson}""")
  } else {
    if r.entries.isEmpty then {
      println(s"""Package ${r.pkg}: (no types)""")
    } else {
      val typeHeader = if r.totalTypes > r.entries.size then
        s"showing ${r.entries.size} of ${r.totalTypes} types, ${r.totalSymbols} symbols"
      else
        s"${r.entries.size} types of ${r.totalSymbols} symbols"
      println(s"Package ${r.pkg} ($typeHeader):\n")
      r.entries.foreach { e =>
        val rel = ctx.workspace.relativize(e.sym.file)
        val implSuffix = if e.implCount > 0 then s" (${e.implCount} impls)" else ""
        println(s"  ${e.sym.kind.label} ${e.sym.name}$implSuffix — $rel:${e.sym.line}")
        println(s"    ${e.sym.signature}")
        if e.members.nonEmpty then
          e.members.foreach { m =>
            println(s"    ${m.kind.label.padTo(5, ' ')} ${m.name}: ${m.signature.take(60)}")
          }
        println()
      }
      if r.totalTypes > r.entries.size then
        println(s"  ... and ${r.totalTypes - r.entries.size} more types (use --limit to adjust)")
    }
  }
}

private def renderPackageSummary(r: CmdResult.PackageSummary, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val arr = jArr(r.subPackages.map { (sub, count) =>
      s"""{"subPackage":${jStr(sub)},"symbolCount":$count}"""
    })
    println(s"""{"package":${jStr(r.pkg)},"totalSymbols":${r.totalSymbols},"subPackages":$arr}""")
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
    val items = jArr(r.symbols.take(ctx.limit).map { (sym, count) =>
      val rel = ctx.workspace.relativize(sym.file).toString
      s"""{"name":${jStr(sym.name)},"kind":${jStr(sym.kind.label)},"file":${jStr(rel)},"line":${sym.line},"package":${jStr(sym.packageName)},"importerCount":$count}"""
    })
    println(s"""{"package":${jStr(r.pkg)},"exportedCount":${r.symbols.size},"totalInPackage":${r.totalInPackage},"symbols":$items,"internalOnly":${jStrArr(r.internalOnly)}}""")
  } else {
    if r.symbols.isEmpty && r.internalOnly.isEmpty then {
      println(s"""API surface of ${r.pkg}: no symbols found""")
    } else {
      val exportedCount = r.symbols.size
      println(s"API surface of ${r.pkg} ($exportedCount of ${r.totalInPackage} symbols imported externally):\n")
      renderShown(r.symbols, ctx.limit, "  ") { (sym, count) =>
        val rel = ctx.workspace.relativize(sym.file)
        val importerLabel = if count == 1 then "importer" else "importers"
        println(s"  ${sym.name.padTo(25, ' ')} ${sym.kind.label.padTo(9, ' ')} $count $importerLabel  $rel:${sym.line}")
      }
      if r.internalOnly.nonEmpty then {
        val shown = r.internalOnly.take(10)
        val suffix = if r.internalOnly.size > 10 then s", ... and ${r.internalOnly.size - 10} more" else ""
        println(s"\n  Not imported externally (${r.internalOnly.size}): ${shown.mkString(", ")}$suffix")
      }
    }
  }
}

private def renderRefsTop(r: CmdResult.RefsTop, ctx: CommandContext): Unit = {
  val suffix = timedOutSuffix(r.timedOut)
  if ctx.jsonOutput then {
    val filesJson = jArr(r.fileRanking.map { (file, count) =>
      s"""{"file":${jStr(ctx.workspace.relativize(file).toString)},"count":$count}"""
    })
    println(s"""{"symbol":${jStr(r.symbol)},"files":$filesJson,"total":${r.total},"timedOut":${r.timedOut}}""")
  } else {
    val fileCount = r.fileRanking.size
    println(s"Top $fileCount files referencing '${r.symbol}' (${r.total} total references)$suffix:")
    r.fileRanking.foreach { (file, count) =>
      val rel = ctx.workspace.relativize(file)
      println(f"  $count%4d  $rel")
    }
  }
}

private def renderRefsSummary(r: CmdResult.RefsSummary, ctx: CommandContext): Unit = {
  if ctx.jsonOutput then {
    val counts = r.categoryCounts.map((cat, count) => s""""${cat.toString}":$count""").mkString("{", ",", "}")
    println(s"""{"symbol":${jStr(r.symbol)},"counts":$counts,"total":${r.total},"timedOut":${r.timedOut}}""")
  } else {
    val suffix = timedOutSuffix(r.timedOut)
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
      val arr = jArr(entries.map { e =>
        val rel = ctx.workspace.relativize(e.sym.file).toString
        val line = e.memberLine.getOrElse(e.sym.line)
        s"""{"name":${jStr(e.sym.name)},"kind":${jStr(e.sym.kind.label)},"file":${jStr(rel)},"line":$line,"package":${jStr(e.sym.packageName)}}"""
      })
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
          renderShown(entries, ctx.limit, "    ") { e =>
            val rel = ctx.workspace.relativize(e.sym.file)
            val line = e.memberLine.getOrElse(e.sym.line)
            println(s"    ${e.sym.kind.label.padTo(9, ' ')} ${e.sym.name} — $rel:$line")
          }
          println()
        }
      }
    }
  }
}

private def renderNotFound(r: CmdResult.NotFound, ctx: CommandContext): Unit = {
  val suggestionsJson = jStrArr(r.hint.suggestions)
  if ctx.jsonOutput then {
    r.hint.cmd match {
      case "hierarchy" =>
        // hierarchy never emits JSON for not-found case (matches original behavior)
        println(r.message)
        renderHint(r.hint)
      case "explain" => println(s"""{"error":"not found","suggestions":$suggestionsJson}""")
      case "imports" => println(s"""{"results":[],"timedOut":${r.hint.timedOut},"suggestions":$suggestionsJson}""")
      case "deps" => println(s"""{"imports":[],"bodyReferences":[],"suggestions":$suggestionsJson}""")
      case "package" | "api" => println(s"""{"error":"not found","suggestions":$suggestionsJson}""")
      case _ => println(s"""{"results":[],"suggestions":$suggestionsJson}""")
    }
  } else {
    println(r.message)
    renderHint(r.hint)
  }
}
