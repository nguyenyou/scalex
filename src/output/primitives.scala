package scalex.output

import scalex.*
import scalex.extraction.*

import java.nio.file.Path

private[scalex] def symbolFormatter(options: OutputOptions): (SymbolInfo, Path) => String = {
  if (options.verbose) { formatSymbolVerbose }
  else { formatSymbol }
}

private[scalex] def referenceJsonFormatter(ctx: CommandContext): Reference => String = {
  if (ctx.output.contextLines > 0) { r => jsonRefWithContext(r, ctx.workspace, ctx.output.contextLines) }
  else { r => jsonRef(r, ctx.workspace) }
}

private[scalex] def referenceTextFormatter(ctx: CommandContext): Reference => String = {
  if (ctx.output.contextLines > 0) { r => formatRefWithContext(r, ctx.workspace, ctx.output.contextLines) }
  else { r => formatRef(r, ctx.workspace) }
}

// ── Formatting ──────────────────────────────────────────────────────────────

def jsonEscape(s: String): String = {
  val sb = StringBuilder(s.length + 8)
  var i = 0
  while (i < s.length) {
    s.charAt(i) match {
      case '"'           => sb.append("\\\"")
      case '\\'          => sb.append("\\\\")
      case '\n'          => sb.append("\\n")
      case '\r'          => sb.append("\\r")
      case '\t'          => sb.append("\\t")
      case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
      case c             => sb.append(c)
    }
    i += 1
  }
  sb.toString
}

// JSON emission helpers — every string value goes through jsonEscape exactly once.

/** Quoted, escaped JSON string value. */
def jStr(s: String): String = s"\"${jsonEscape(s)}\""

/** JSON array from already-rendered element strings. */
def jArr(items: Iterable[String]): String = items.mkString("[", ",", "]")

/** JSON array of escaped strings. */
def jStrArr(items: Iterable[String]): String = jArr(items.map(jStr))

/** Escaped string value or null. */
def jOpt(o: Option[String]): String = o.map(jStr).getOrElse("null")

def jsonSymbol(s: SymbolInfo, workspace: Path): String = {
  val rel = workspace.relativize(s.file).toString
  s"""{"name":${jStr(s.name)},"kind":${jStr(s.kind.label)},"file":${jStr(rel)},"line":${s.line},"package":${jStr(
      s.packageName
    )},"parents":${jStrArr(s.parents)},"typeParamParents":${jStrArr(s.typeParamParents)},"signature":${jStr(
      s.signature
    )},"annotations":${jStrArr(s.annotations)}}"""
}

def jsonRef(r: Reference, workspace: Path): String = {
  val rel = workspace.relativize(r.file).toString
  s"""{"file":${jStr(rel)},"line":${r.line},"context":${jStr(r.contextLine)},"alias":${jOpt(r.aliasInfo)}}"""
}

def jsonRefWithContext(r: Reference, workspace: Path, contextN: Int): String = {
  val rel = workspace.relativize(r.file).toString
  val lines = readSourceLines(r.file).getOrElse(Array.empty[String])
  val total = lines.length
  val startLine = math.max(1, r.line - contextN)
  val endLine = math.min(total, r.line + contextN)
  val ctxLines = jArr((startLine to endLine).map { i =>
    s"""{"line":$i,"content":${jStr(lines(i - 1))},"match":${i == r.line}}"""
  })
  s"""{"file":${jStr(rel)},"line":${r.line},"context":${jStr(r.contextLine)},"alias":${jOpt(
      r.aliasInfo
    )},"contextLines":$ctxLines}"""
}

// Text formatting helpers

/** " (pkg)" suffix, or empty when the package is unknown. */
def pkgSuffix(packageName: String): String =
  if (packageName.nonEmpty) s" ($packageName)" else ""

/** One source line with a left-aligned number gutter: "42   | text". */
private[scalex] def numberedLine(lineNum: Int, content: String): String =
  s"${lineNum.toString.padTo(4, ' ')} | $content"

/** Print up to `limit` items, then a "... and N more" footer at `indent`. */
private[scalex] def renderShown[A](items: List[A], limit: Int, indent: String, footerSuffix: String = "")(
    printOne: A => Unit
): Unit = {
  items.take(limit).foreach(printOne)
  if (items.size > limit) println(s"$indent... and ${items.size - limit} more$footerSuffix")
}

def formatSymbol(s: SymbolInfo, workspace: Path): String = {
  val rel = workspace.relativize(s.file)
  s"  ${s.kind.label.padTo(9, ' ')} ${s.name}${pkgSuffix(s.packageName)} — $rel:${s.line}"
}

def formatSymbolVerbose(s: SymbolInfo, workspace: Path): String = {
  val rel = workspace.relativize(s.file)
  val sig = if (s.signature.nonEmpty) s"\n             ${s.signature}" else ""
  s"  ${s.kind.label.padTo(9, ' ')} ${s.name}${pkgSuffix(s.packageName)} — $rel:${s.line}$sig"
}

def formatRef(r: Reference, workspace: Path): String = {
  val rel = workspace.relativize(r.file)
  val alias = r.aliasInfo.map(a => s" [$a]").getOrElse("")
  s"  $rel:${r.line} — ${r.contextLine}$alias"
}

def formatRefWithContext(r: Reference, workspace: Path, contextN: Int): String = {
  val rel = workspace.relativize(r.file)
  val alias = r.aliasInfo.map(a => s" [$a]").getOrElse("")
  val header = s"  $rel:${r.line}$alias"
  readSourceLines(r.file) match {
    case None        => s"$header\n    > ${r.contextLine}"
    case Some(lines) =>
      val total = lines.length
      val startLine = math.max(1, r.line - contextN)
      val endLine = math.min(total, r.line + contextN)
      val buf = StringBuilder(header)
      var i = startLine
      while (i <= endLine) {
        val marker = if (i == r.line) ">" else " "
        buf.append(s"\n    $marker ${numberedLine(i, lines(i - 1))}")
        i += 1
      }
      buf.toString
  }
}

/** `{"class":12,"def":3,...}` JSON object from kind counts — shared by overview, index stats, and the symbols --summary
  * command.
  */
def jKindCounts(counts: List[(kind: SymbolKind, count: Int)]): String =
  counts.map((k, c) => s""""${k.label}":$c""").mkString("{", ",", "}")
