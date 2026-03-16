import java.nio.file.Path

// ── Command helpers ─────────────────────────────────────────────────────────

def hasRegexHint(pattern: String): Boolean =
  pattern.contains("\\|") || pattern.contains("\\(") || pattern.contains("\\)")

def fixPosixRegex(pattern: String): (pattern: String, wasFixed: Boolean) =
  val fixed = pattern.replace("\\|", "|").replace("\\(", "(").replace("\\)", ")")
  (fixed, fixed != pattern)

// ── Suggestions for not-found ────────────────────────────────────────────────

def mkNotFoundWithSuggestions(symbol: String, ctx: CommandContext, cmd: String): NotFoundHint =
  var results = ctx.idx.search(symbol)
  if ctx.noTests then results = results.filter(s => !isTestFile(s.file, ctx.workspace))
  val suggestions = results.take(5).map { s =>
    s"${s.kind.toString.toLowerCase} ${s.name} (${s.packageName})"
  }
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
