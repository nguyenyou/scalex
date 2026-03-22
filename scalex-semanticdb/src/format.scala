// ── Shared helpers ──────────────────────────────────────────────────────────

def filterByKind(symbols: List[SemSymbol], kindFilter: Option[String]): List[SemSymbol] =
  kindFilter match
    case None => symbols
    case Some(k) =>
      val lower = k.toLowerCase
      symbols.filter(_.kind.toString.toLowerCase == lower)

def isAccessor(s: SemSymbol): Boolean =
  (s.isVal || s.isVar) && (s.kind == SemKind.Field || s.kind == SemKind.Method)

private val smartProtobufMethods = Set(
  "copy", "toByteArray", "writeTo", "serializedSize",
  "parseFrom", "mergeFrom", "of", "defaultInstance",
)

private val smartFunctionalMethods = Set(
  "map", "flatMap", "traverse", "fold", "foldLeft", "foldRight", "foreach",
)

def isInfraNoise(s: SemSymbol): Boolean =
  val uri = s.sourceUri
  // Generated code (protobuf, codegen)
  (uri.startsWith("out/") || uri.contains("compileScalaPB.dest") || uri.contains("compilePB.dest")) ||
  // Protobuf boilerplate methods in generated files
  (smartProtobufMethods.contains(s.displayName) && uri.contains("compileScalaPB")) ||
  // Functional plumbing
  smartFunctionalMethods.contains(s.displayName)

def filterByExclude(symbols: List[SemSymbol], patterns: List[String]): List[SemSymbol] =
  if patterns.isEmpty then symbols
  else symbols.filterNot(s => patterns.exists(p => s.fqn.contains(p) || s.sourceUri.contains(p)))

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
      entries.foreach { entry =>
        println(s"  [${entry.count}] ${entry.sym.kind.toString.toLowerCase} ${entry.sym.displayName} (${entry.sym.fqn})")
      }
      if total > entries.size then
        println(s"... and ${total - entries.size} more")

    case SemCmdResult.Stats(fc, sc, oc, ms, cached) =>
      println(s"Files:        $fc")
      println(s"Symbols:      $sc")
      println(s"Occurrences:  $oc")
      println(s"Build time:   ${ms}ms${if cached then " (cached)" else ""}")

    case SemCmdResult.Batch(results) =>
      results.foreach { entry =>
        println(s"--- ${entry.command} ---")
        renderText(entry.result, ctx)
        println()
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
      val items = entries.map { entry =>
        s"""{"symbol":${jsonStr(entry.sym.fqn)},"name":${jsonStr(entry.sym.displayName)},"kind":${jsonStr(entry.sym.kind.toString)},"count":${entry.count}}"""
      }
      println(s"""{"header":${jsonStr(header)},"total":$total,"related":[${items.mkString(",")}]}""")

    case SemCmdResult.Stats(fc, sc, oc, ms, cached) =>
      println(s"""{"files":$fc,"symbols":$sc,"occurrences":$oc,"buildTimeMs":$ms,"cached":$cached}""")

    case SemCmdResult.Batch(results) =>
      val items = results.map { entry =>
        val buf = java.io.ByteArrayOutputStream()
        Console.withOut(buf) { Console.withErr(java.io.ByteArrayOutputStream()) { renderJson(entry.result, ctx) } }
        s"""{"command":${jsonStr(entry.command)},"result":${buf.toString("UTF-8").trim}}"""
      }
      println(s"""{"batch":[${items.mkString(",")}]}""")

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
