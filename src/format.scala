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
