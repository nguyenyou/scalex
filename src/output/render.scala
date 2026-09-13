package scalex.output

import scalex.*

import CmdResult.*

// ── Render ─────────────────────────────────────────────────────────────────

def render(result: CmdResult, ctx: CommandContext): Unit = {
  result match {
    case r: SymbolList       => renderSymbolList(r, ctx)
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
    case r: GraphOutput      =>
      if (ctx.output.jsonOutput) { println(s"""{"rendered":${jStr(r.text)}}""") }
      else { println(r.text) }
    case r: ParsedDiagram   => renderParsedDiagram(r, ctx)
    case r: WithDiagnostics =>
      r.messages.foreach(Console.err.println)
      render(r.result, ctx)
    case r: NotFound      => renderNotFound(r, ctx)
    case r: UsageError    => renderError(r.message, ctx.output.jsonOutput)
    case r: Failure       => renderError(r.message, ctx.output.jsonOutput)
    case r: SymbolSummary => renderSymbolSummary(r, ctx)
  }
}

private[scalex] def renderHint(h: NotFoundHint): Unit = {
  if (h.batchMode)
    if (h.suggestions.nonEmpty)
      println(
        s"  not found (0 matches in ${h.fileCount} files). Did you mean: ${h.suggestions.take(3).mkString(", ")}?"
      )
    else
      println(s"  not found (0 matches in ${h.fileCount} files, top-level only)")
  else {
    if (h.looksLikePath)
      println(s"""  Note: "${h.symbol}" looks like a path. Did you mean: scalex ${h.cmd} -w <workspace> ${h.symbol}?""")
    if (h.suggestions.nonEmpty) {
      println(s"  Did you mean:")
      h.suggestions.foreach(s => println(s"    $s"))
    }
    println(
      s"  Hint: scalex indexes top-level declarations in ${h.fileCount} files (local defs, parameters, and pattern bindings are not indexed)."
    )
    if (h.parseFailures > 0)
      println(s"  ${h.parseFailures} files had parse errors (run `scalex index --verbose` to list them).")
    println(s"""  Fallback: try `scalex grep "${h.symbol}"` or use Grep, Glob, Read tools to search manually.""")
  }
}

private[scalex] def renderSymbolList(r: CmdResult.SymbolList, ctx: CommandContext): Unit = {
  if (ctx.output.jsonOutput) {
    val items = if (r.truncate) r.symbols.take(ctx.output.limit) else r.symbols
    println(jArr(items.map(s => jsonSymbol(s, ctx.workspace))))
  } else {
    if (r.symbols.isEmpty) {
      if (r.emptyMessage.nonEmpty) println(r.emptyMessage)
    } else {
      println(r.header)
      if (r.truncate)
        renderShown(r.symbols, ctx.output.limit, "  ")(s => println(symbolFormatter(ctx.output)(s, ctx.workspace)))
      else r.symbols.foreach(s => println(symbolFormatter(ctx.output)(s, ctx.workspace)))
    }
  }
}

private[scalex] def renderStringList(r: CmdResult.StringList, ctx: CommandContext): Unit = {
  if (ctx.output.jsonOutput) {
    println(jStrArr(r.items.take(ctx.output.limit)))
  } else {
    if (r.items.isEmpty) {
      if (r.emptyMessage.nonEmpty) println(r.emptyMessage)
    } else {
      println(r.header)
      renderShown(r.items, ctx.output.limit, "  ")(f => println(s"  $f"))
    }
  }
}

private[scalex] def renderIndexStats(r: CmdResult.IndexStats, ctx: CommandContext): Unit = {
  if (ctx.output.jsonOutput) {
    println(
      s"""{"fileCount":${r.fileCount},"symbolCount":${r.symbolCount},"packageCount":${r.packageCount},"symbolsByKind":${jKindCounts(
          r.symbolsByKind
        )},"indexTimeMs":${r.indexTimeMs},"cachedLoad":${r.cachedLoad},"parsedCount":${r.parsedCount},"skippedCount":${r.skippedCount},"parseFailures":${r.parseFailures}}"""
    )
  } else {
    if (r.cachedLoad)
      println(s"Indexed ${r.fileCount} files (${r.skippedCount} cached, ${r.parsedCount} parsed) in ${r.indexTimeMs}ms")
    else
      println(s"Indexed ${r.fileCount} files, ${r.symbolCount} symbols in ${r.indexTimeMs}ms")
    println(s"Packages: ${r.packageCount}")
    println()
    println("Symbols by kind:")
    r.symbolsByKind.foreach { (kind, count) =>
      println(s"  ${kind.toString.padTo(10, ' ')} $count")
    }
    if (r.parseFailures > 0) {
      println(s"\n${r.parseFailures} files had parse errors:")
      if (ctx.output.verbose)
        r.parseFailedFiles.sorted.foreach(f => println(s"  $f"))
      else
        println("  Run with --verbose to see the list.")
    }
  }
}

private[scalex] def renderAstMatches(r: CmdResult.AstMatches, ctx: CommandContext): Unit = {
  if (ctx.output.jsonOutput) {
    println(jArr(r.results.map(s => jsonSymbol(s, ctx.workspace))))
  } else {
    if (r.results.isEmpty)
      println(s"No types matching AST pattern (${r.filters})")
    else {
      println(s"Types matching AST pattern (${r.filters}) — ${r.results.size} found:")
      r.results.foreach(s => println(formatSymbol(s, ctx.workspace)))
    }
  }
}

private[scalex] def renderNotFound(r: CmdResult.NotFound, ctx: CommandContext): Unit = {
  val suggestionsJson = jStrArr(r.hint.suggestions)
  if (ctx.output.jsonOutput) {
    r.hint.cmd match {
      case "hierarchy" =>
        // hierarchy never emits JSON for not-found case (matches original behavior)
        println(r.message)
        renderHint(r.hint)
      case "explain" | "package" | "api" => println(s"""{"error":"not found","suggestions":$suggestionsJson}""")
      case "imports" => println(s"""{"results":[],"timedOut":${r.hint.timedOut},"suggestions":$suggestionsJson}""")
      case "deps"    => println(s"""{"imports":[],"bodyReferences":[],"suggestions":$suggestionsJson}""")
      case _         => println(s"""{"results":[],"suggestions":$suggestionsJson}""")
    }
  } else {
    println(r.message)
    renderHint(r.hint)
  }
}

private[scalex] def renderSymbolSummary(r: CmdResult.SymbolSummary, ctx: CommandContext): Unit = {
  if (ctx.output.jsonOutput) {
    println(s"""{"file":${jStr(r.file)},"symbolsByKind":${jKindCounts(r.symbolsByKind)},"total":${r.total}}""")
  } else {
    val counts = r.symbolsByKind
      .map((k, count) =>
        s"$count ${k.label}${
            if (count > 1) { "s" }
            else { "" }
          }"
      )
      .mkString(", ")
    println(if (counts.nonEmpty) {
      s"${r.file}: $counts (${r.total} total)"
    } else { s"${r.file}: no symbols" })
  }
}

def renderError(message: String, jsonOutput: Boolean): Unit = {
  if (jsonOutput) { println(s"""{"error":${jStr(message)}}""") }
  else { Console.err.println(message) }
}

private[scalex] def renderParsedDiagram(r: CmdResult.ParsedDiagram, ctx: CommandContext): Unit = {
  if (ctx.output.jsonOutput) {
    val boxes = jArr(r.boxes.map(text => s"""{"text":${jStr(text)}}"""))
    val edges = jArr(r.edges.map { edge =>
      val label = edge.label.map(l => s""", "label":${jStr(l)}""").getOrElse("")
      s"""{"from":${jStr(edge.from)},"to":${jStr(edge.to)},"directed":${edge.arrowAtStart || edge.arrowAtEnd}$label}"""
    })
    println(s"""{"boxes":$boxes,"edges":$edges}""")
  } else {
    println(s"Boxes: ${r.boxes.mkString(", ")}\nEdges:")
    r.edges.foreach { edge =>
      val arrow = if (edge.arrowAtStart && edge.arrowAtEnd) { " <-> " }
      else if (edge.arrowAtEnd) { " -> " }
      else if (edge.arrowAtStart) { " <- " }
      else { " -- " }
      val label = edge.label.map(l => s" [$l]").getOrElse("")
      println(s"  ${edge.from}$arrow${edge.to}$label")
    }
  }
}
