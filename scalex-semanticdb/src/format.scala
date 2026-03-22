// ── Shared helpers ──────────────────────────────────────────────────────────

def filterByKind(symbols: List[SemSymbol], kindFilter: Option[String]): List[SemSymbol] =
  kindFilter match
    case None => symbols
    case Some(k) =>
      val lower = k.toLowerCase
      symbols.filter(_.kind.toString.toLowerCase == lower)

// ── Output rendering ───────────────────────────────────────────────────────

def render(result: SemCmdResult, ctx: SemCommandContext): Unit =
  if ctx.jsonOutput then renderJson(result, ctx)
  else renderText(result, ctx)

def renderText(result: SemCmdResult, ctx: SemCommandContext): Unit =
  result match
    case SemCmdResult.SymbolDetail(sym) =>
      println(formatSymbolDetail(sym, ctx.verbose))

    case SemCmdResult.SymbolList(header, symbols, total) =>
      println(header)
      symbols.foreach { s =>
        if ctx.verbose then println(formatSymbolDetail(s, true))
        else println(formatSymbolLine(s))
      }
      if total > symbols.size then
        println(s"... and ${total - symbols.size} more (use --limit 0 for all)")

    case SemCmdResult.OccurrenceList(header, occs, total) =>
      println(header)
      occs.foreach { o =>
        val role = if o.role == OccRole.Definition then "<=" else "=>"
        println(s"  [${o.range}] $role ${o.symbol}")
        println(s"    ${o.file}:${o.range.startLine + 1}")
      }
      if total > occs.size then
        println(s"... and ${total - occs.size} more")

    case SemCmdResult.TypeResult(symbol, typeString) =>
      println(s"$symbol: $typeString")

    case SemCmdResult.Tree(header, lines) =>
      println(header)
      lines.foreach(println)

    case SemCmdResult.FlowTree(header, lines) =>
      println(header)
      lines.foreach(println)

    case SemCmdResult.RelatedList(header, entries, total) =>
      println(header)
      entries.foreach { (s, count) =>
        println(s"  [$count] ${s.kind.toString.toLowerCase} ${s.displayName} (${s.fqn})")
      }
      if total > entries.size then
        println(s"... and ${total - entries.size} more")

    case SemCmdResult.Stats(fc, sc, oc, ms, cached) =>
      println(s"Files:        $fc")
      println(s"Symbols:      $sc")
      println(s"Occurrences:  $oc")
      println(s"Build time:   ${ms}ms${if cached then " (cached)" else ""}")

    case SemCmdResult.PackageList(header, pkgs, total) =>
      println(header)
      pkgs.foreach(p => println(s"  $p"))
      if total > pkgs.size then println(s"... and ${total - pkgs.size} more")

    case SemCmdResult.SummaryList(header, entries, total) =>
      println(header)
      entries.foreach { (pkg, count) => println(f"  $count%5d  $pkg") }
      if total > entries.size then println(s"... and ${total - entries.size} more")

    case SemCmdResult.FileList(header, files, total) =>
      println(header)
      files.foreach(f => println(s"  $f"))
      if total > files.size then println(s"... and ${total - files.size} more")

    case SemCmdResult.ExplainResult(sym, members, subtypeCount, refCount, supertypes) =>
      println(formatSymbolDetail(sym, true))
      if supertypes.nonEmpty then
        println(s"  supertypes: ${supertypes.size}")
        supertypes.foreach(p => println(s"    $p"))
      println(s"  members: ${members.size}")
      members.foreach(m => println(s"    ${m.kind.toString.toLowerCase} ${m.displayName}"))
      println(s"  subtypes: $subtypeCount")
      println(s"  references: $refCount")

    case SemCmdResult.CoverageResult(symbol, totalRefs, testRefs, testFiles) =>
      println(s"$symbol: $totalRefs total refs, $testRefs in test files")
      testFiles.foreach(f => println(s"  $f"))

    case SemCmdResult.ContextResult(header, scopes) =>
      println(header)
      scopes.zipWithIndex.foreach { (s, i) =>
        val indent = "  " * (i + 1)
        println(s"$indent${s.kind.toString.toLowerCase} ${s.displayName} (${s.sourceUri})")
      }

    case SemCmdResult.NotFound(msg) =>
      println(s"Not found: $msg")

    case SemCmdResult.UsageError(msg) =>
      System.err.println(msg)

def formatSymbolLine(s: SemSymbol): String =
  val props = s.propertyNames
  val propsStr = if props.isEmpty then "" else s" [${props.mkString(", ")}]"
  s"  ${s.kind.toString.toLowerCase} ${s.displayName}$propsStr (${s.sourceUri})"

def formatSymbolDetail(s: SemSymbol, verbose: Boolean): String =
  val sb = StringBuilder()
  val props = s.propertyNames
  val propsStr = if props.isEmpty then "" else s" [${props.mkString(", ")}]"
  sb.append(s"${s.kind.toString.toLowerCase} ${s.displayName}$propsStr\n")
  sb.append(s"  fqn: ${s.fqn}\n")
  sb.append(s"  file: ${s.sourceUri}\n")
  if s.signature.nonEmpty then
    sb.append(s"  signature: ${s.signature}\n")
  if s.parents.nonEmpty then
    sb.append(s"  parents: ${s.parents.mkString(", ")}\n")
  if verbose then
    if s.overriddenSymbols.nonEmpty then
      sb.append(s"  overrides: ${s.overriddenSymbols.mkString(", ")}\n")
    if s.annotations.nonEmpty then
      sb.append(s"  annotations: ${s.annotations.mkString(", ")}\n")
    sb.append(s"  owner: ${s.owner}\n")
  sb.result()

// ── JSON rendering ─────────────────────────────────────────────────────────

def renderJson(result: SemCmdResult, ctx: SemCommandContext): Unit =
  result match
    case SemCmdResult.SymbolDetail(sym) =>
      println(symbolToJson(sym))

    case SemCmdResult.SymbolList(header, symbols, total) =>
      println(s"""{"header":${jsonStr(header)},"total":$total,"symbols":[${symbols.map(symbolToJson).mkString(",")}]}""")

    case SemCmdResult.OccurrenceList(header, occs, total) =>
      val items = occs.map { o =>
        s"""{"file":${jsonStr(o.file)},"line":${o.range.startLine + 1},"symbol":${jsonStr(o.symbol)},"role":${jsonStr(o.role.toString)}}"""
      }
      println(s"""{"header":${jsonStr(header)},"total":$total,"occurrences":[${items.mkString(",")}]}""")

    case SemCmdResult.TypeResult(symbol, typeString) =>
      println(s"""{"symbol":${jsonStr(symbol)},"type":${jsonStr(typeString)}}""")

    case SemCmdResult.Tree(header, lines) =>
      println(s"""{"header":${jsonStr(header)},"lines":[${lines.map(jsonStr).mkString(",")}]}""")

    case SemCmdResult.FlowTree(header, lines) =>
      println(s"""{"header":${jsonStr(header)},"lines":[${lines.map(jsonStr).mkString(",")}]}""")

    case SemCmdResult.RelatedList(header, entries, total) =>
      val items = entries.map { (s, count) =>
        s"""{"symbol":${jsonStr(s.fqn)},"name":${jsonStr(s.displayName)},"kind":${jsonStr(s.kind.toString)},"count":$count}"""
      }
      println(s"""{"header":${jsonStr(header)},"total":$total,"related":[${items.mkString(",")}]}""")

    case SemCmdResult.Stats(fc, sc, oc, ms, cached) =>
      println(s"""{"files":$fc,"symbols":$sc,"occurrences":$oc,"buildTimeMs":$ms,"cached":$cached}""")

    case SemCmdResult.PackageList(header, pkgs, total) =>
      println(s"""{"header":${jsonStr(header)},"total":$total,"packages":[${pkgs.map(jsonStr).mkString(",")}]}""")

    case SemCmdResult.SummaryList(header, entries, total) =>
      val items = entries.map { (pkg, count) => s"""{"package":${jsonStr(pkg)},"count":$count}""" }
      println(s"""{"header":${jsonStr(header)},"total":$total,"summary":[${items.mkString(",")}]}""")

    case SemCmdResult.FileList(header, files, total) =>
      println(s"""{"header":${jsonStr(header)},"total":$total,"files":[${files.map(jsonStr).mkString(",")}]}""")

    case SemCmdResult.ExplainResult(sym, members, subtypeCount, refCount, supertypes) =>
      val ms = members.map(symbolToJson).mkString(",")
      val sp = supertypes.map(jsonStr).mkString(",")
      println(s"""{"symbol":${symbolToJson(sym)},"members":[${ms}],"subtypeCount":$subtypeCount,"refCount":$refCount,"supertypes":[$sp]}""")

    case SemCmdResult.CoverageResult(symbol, totalRefs, testRefs, testFiles) =>
      val fs = testFiles.map(jsonStr).mkString(",")
      println(s"""{"symbol":${jsonStr(symbol)},"totalRefs":$totalRefs,"testRefs":$testRefs,"testFiles":[$fs]}""")

    case SemCmdResult.ContextResult(header, scopes) =>
      val ss = scopes.map(symbolToJson).mkString(",")
      println(s"""{"header":${jsonStr(header)},"scopes":[$ss]}""")

    case SemCmdResult.NotFound(msg) =>
      println(s"""{"error":"not_found","message":${jsonStr(msg)}}""")

    case SemCmdResult.UsageError(msg) =>
      println(s"""{"error":"usage","message":${jsonStr(msg)}}""")

private def symbolToJson(s: SemSymbol): String =
  val parents = s.parents.map(jsonStr).mkString(",")
  val overridden = s.overriddenSymbols.map(jsonStr).mkString(",")
  val annots = s.annotations.map(jsonStr).mkString(",")
  s"""{"fqn":${jsonStr(s.fqn)},"name":${jsonStr(s.displayName)},"kind":${jsonStr(s.kind.toString)},"file":${jsonStr(s.sourceUri)},"signature":${jsonStr(s.signature)},"parents":[$parents],"overriddenSymbols":[$overridden],"annotations":[$annots]}"""

private def jsonStr(s: String): String =
  val sb = StringBuilder("\"")
  s.foreach {
    case '"'  => sb.append("\\\"")
    case '\\' => sb.append("\\\\")
    case '\n' => sb.append("\\n")
    case '\r' => sb.append("\\r")
    case '\t' => sb.append("\\t")
    case c if c < ' ' => sb.append(f"\\u${c.toInt}%04x")
    case c    => sb.append(c)
  }
  sb.append('"')
  sb.result()
