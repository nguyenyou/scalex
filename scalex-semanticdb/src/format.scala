// ── Shared helpers ──────────────────────────────────────────────────────────

def filterByKind(symbols: List[SemSymbol], kindFilter: Option[String]): List[SemSymbol] =
  kindFilter match
    case None => symbols
    case Some(k) =>
      val lower = k.toLowerCase
      symbols.filter(_.kind.toString.toLowerCase == lower)

def isAccessor(s: SemSymbol): Boolean =
  (s.isVal || s.isVar) && (s.kind == SemKind.Field || s.kind == SemKind.Method)

def isGeneratedSource(uri: String): Boolean =
  uri.startsWith("out/") || uri.contains("compileScalaPB.dest") || uri.contains("compilePB.dest")

def isInfraNoise(s: SemSymbol): Boolean =
  // Generated code (protobuf, codegen) — any symbol from a generated file
  isGeneratedSource(s.sourceUri)

def isTestSource(uri: String): Boolean =
  val lower = uri.toLowerCase
  lower.contains("/test/") || lower.contains("/tests/") ||
  lower.contains("/it/") || lower.contains("/spec/") ||
  lower.endsWith("test.scala") || lower.endsWith("spec.scala") ||
  lower.endsWith("suite.scala") || lower.endsWith("integ.scala")

/** Monadic/effect combinator names that are unambiguously plumbing noise.
  * Only includes names unlikely to appear as business-domain methods.
  * Common names like `map`, `filter`, `foreach`, `fold` are excluded
  * because user-defined domain methods often share those names. */
private val monadicCombinatorNames = Set(
  "flatMap", "traverse", "pure", "succeed", "attempt",
  "fromOption", "catchAll", "catchSome", "mapError", "tapError",
)

def isMonadicCombinator(s: SemSymbol): Boolean =
  monadicCombinatorNames.contains(s.displayName)

def filterByExclude(symbols: List[SemSymbol], patterns: List[String]): List[SemSymbol] =
  if patterns.isEmpty then symbols
  else symbols.filterNot(s => patterns.exists(p => s.fqn.contains(p) || s.sourceUri.contains(p)))

def filterByExcludePkg(symbols: List[SemSymbol], patterns: List[String]): List[SemSymbol] =
  if patterns.isEmpty then symbols
  else
    // Ensure each pattern ends with "/" to avoid prefix collisions (e.g. "com/example" matching "com/exampleother")
    val normalized = patterns.map(p => if p.endsWith("/") then p else p + "/")
    symbols.filterNot(s => normalized.exists(p => s.fqn.startsWith(p)))

/** Stdlib/trivial prefixes to filter out of call trees. */
private val trivialPrefixes = Set(
  "scala/", "java/lang/", "java/util/", "scala/Predef",
  "scala/collection/", "scala/runtime/",
)

def isTrivial(fqn: String): Boolean =
  trivialPrefixes.exists(fqn.startsWith)

/** Extract module prefix from a source URI for same-module detection.
  * Uses the first two path segments: "modules/billing/jvm/src/..." → "modules/billing/".
  * URIs with fewer than 2 segments return empty string (disables same-module filtering). */
def modulePrefix(uri: String): String =
  val parts = uri.split("/")
  if parts.length >= 2 then s"${parts(0)}/${parts(1)}/" else ""

/** Resolve a user query to a single symbol. When multiple match, prints a
  * disambiguation hint to stderr showing up to 5 candidates with FQN + kind.
  * Returns None only when no symbols match at all.
  * @param inScope optional scope to narrow by owner class, FQN, or source file */
def resolveOne(query: String, index: SemIndex, kindFilter: Option[String], inScope: Option[String] = None): Option[SemSymbol] =
  val symbols = index.resolveSymbol(query)
  if symbols.isEmpty then return None
  val filtered = filterByKind(symbols, kindFilter)
  val candidates = if filtered.nonEmpty then filtered else symbols
  // Apply --in scope filter
  val scoped = inScope match
    case Some(scope) =>
      val scopeLower = scope.toLowerCase
      val scopeFqnLower = scope.replace(".", "/").toLowerCase
      val matched = candidates.filter { s =>
        s.owner.toLowerCase.contains(scopeLower) ||
        s.fqn.toLowerCase.contains(scopeFqnLower) ||
        s.sourceUri.toLowerCase.contains(scopeLower)
      }
      if matched.nonEmpty then matched
      else
        System.err.println(s"Warning: --in '$scope' matched no candidates, falling back to unscoped resolution")
        candidates
    case None => candidates
  if scoped.size > 1 then
    System.err.println(s"Ambiguous: ${scoped.size} symbols match '$query'. Using ${scoped.head.fqn}")
    System.err.println(s"  Disambiguate with FQN or --kind. Candidates:")
    scoped.take(5).foreach { s =>
      System.err.println(s"    ${s.kind.toString.toLowerCase} ${s.fqn}")
    }
    if scoped.size > 5 then
      System.err.println(s"    ... and ${scoped.size - 5} more")
  Some(scoped.head)

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

    case SemCmdResult.ExplainResult(sym, definedAt, callers, totalCallers, callees, totalCallees, parents, members, totalMembers) =>
      val props = sym.propertyNames
      val propsStr = if props.isEmpty then "" else s" [${props.mkString(", ")}]"
      println(s"${sym.kind.toString.toLowerCase} ${sym.displayName}$propsStr")
      if sym.signature.nonEmpty then println(s"  Type: ${sym.signature}")
      definedAt.foreach { (file, line) => println(s"  Defined: $file:$line") }
      if parents.nonEmpty then
        val resolved = parents.flatMap(p => ctx.index.symbolByFqn.get(p).map(_.displayName))
        if resolved.nonEmpty then println(s"  Extends: ${resolved.mkString(", ")}")
      if totalMembers > 0 then
        val names = members.map(_.displayName).mkString(", ")
        val more = if totalMembers > members.size then s" ($totalMembers total)" else ""
        println(s"  Members: $names$more")
      if totalCallers > 0 then
        val names = callers.map(_.displayName).mkString(", ")
        val more = if totalCallers > callers.size then s" ($totalCallers total)" else ""
        println(s"  Called by: $names$more")
      if totalCallees > 0 then
        val names = callees.map(_.displayName).mkString(", ")
        val more = if totalCallees > callees.size then s" ($totalCallees total)" else ""
        println(s"  Calls: $names$more")

    case SemCmdResult.Stats(fc, sc, oc, ms, cached, parsed, skipped) =>
      println(s"Files:        $fc")
      println(s"Symbols:      $sc")
      println(s"Occurrences:  $oc")
      val detail =
        if cached then " (cached)"
        else if skipped > 0 then s" (incremental: $parsed parsed, $skipped reused)"
        else ""
      println(s"Build time:   ${ms}ms$detail")

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
  // For method/field/constructor members, show [object] vs [class/trait] based on FQN convention.
  // Use lastIndexOf to avoid matching displayName in package path (e.g. "com/example/bar/Foo#bar().")
  val memberOf =
    if s.kind == SemKind.Method || s.kind == SemKind.Field || s.kind == SemKind.Constructor then
      val nameStart = s.fqn.lastIndexOf(s.displayName)
      if nameStart > 0 then
        s.fqn.charAt(nameStart - 1) match
          case '#' => " [class/trait]"
          case '.' => " [object]"
          case _   => ""
      else ""
    else ""
  s"  ${s.kind.toString.toLowerCase} ${s.displayName}$propsStr$memberOf (${s.sourceUri})"

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

    case SemCmdResult.ExplainResult(sym, definedAt, callers, totalCallers, callees, totalCallees, parents, members, totalMembers) =>
      val locJson = definedAt.map((f, l) => s""","file":${jsonStr(f)},"line":$l""").getOrElse("")
      val callerJson = callers.map(s => s"""{"fqn":${jsonStr(s.fqn)},"name":${jsonStr(s.displayName)}}""").mkString(",")
      val calleeJson = callees.map(s => s"""{"fqn":${jsonStr(s.fqn)},"name":${jsonStr(s.displayName)}}""").mkString(",")
      val memberJson = members.map(s => s"""{"fqn":${jsonStr(s.fqn)},"name":${jsonStr(s.displayName)},"kind":${jsonStr(s.kind.toString)}}""").mkString(",")
      val parentsJson = parents.map(jsonStr).mkString(",")
      println(s"""{"symbol":${symbolToJson(sym)}$locJson,"callers":[$callerJson],"totalCallers":$totalCallers,"callees":[$calleeJson],"totalCallees":$totalCallees,"parents":[$parentsJson],"members":[$memberJson],"totalMembers":$totalMembers}""")

    case SemCmdResult.Stats(fc, sc, oc, ms, cached, parsed, skipped) =>
      println(s"""{"files":$fc,"symbols":$sc,"occurrences":$oc,"buildTimeMs":$ms,"cached":$cached,"parsedCount":$parsed,"skippedCount":$skipped}""")

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
