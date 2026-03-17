import java.nio.file.Path
import scala.collection.mutable

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

// ── Package resolution (shared by package, api, summary) ────────────────────

def resolvePackage(pkg: String, ctx: CommandContext): Option[String] =
  val lower = pkg.toLowerCase
  def bestMatch(candidates: Iterable[String]): Option[String] =
    if candidates.isEmpty then None
    else Some(candidates.maxBy(p => ctx.idx.packageToSymbols.getOrElse(p, Nil).size))
  ctx.idx.packages.find(_.equalsIgnoreCase(pkg))
    .orElse(bestMatch(ctx.idx.packages.filter(_.toLowerCase.endsWith("." + lower))))
    .orElse(bestMatch(ctx.idx.packages.filter(_.toLowerCase.contains(lower))))

def mkPackageNotFound(pkg: String, ctx: CommandContext, cmd: String): NotFoundHint =
  val lower = pkg.toLowerCase
  val segments = lower.split("[.]").filter(_.nonEmpty)
  val pkgSuggestions = if segments.nonEmpty then
    ctx.idx.packages.filter { p =>
      val pl = p.toLowerCase
      segments.exists(seg => pl.contains(seg))
    }.toList.sortBy(p => -ctx.idx.packageToSymbols.getOrElse(p, Nil).size).take(5)
  else Nil
  NotFoundHint(pkg, ctx.idx.fileCount, ctx.idx.parseFailures, cmd, ctx.batchMode, false, pkgSuggestions)

// ── Shared filters ──────────────────────────────────────────────────────────

def filterSymbols(symbols: List[SymbolInfo], ctx: CommandContext): List[SymbolInfo] =
  var r = symbols
  ctx.kindFilter.foreach { k =>
    val kk = k.toLowerCase
    r = r.filter(_.kind.toString.toLowerCase == kk)
  }
  if ctx.noTests then r = r.filter(s => !isTestFile(s.file, ctx.workspace))
  ctx.pathFilter.foreach { p => r = r.filter(s => matchesPath(s.file, p, ctx.workspace)) }
  ctx.excludePath.foreach { p => r = r.filter(s => !matchesPath(s.file, p, ctx.workspace)) }
  r

def filterRefs(refs: List[Reference], ctx: CommandContext): List[Reference] =
  var r = refs
  if ctx.noTests then r = r.filter(ref => !isTestFile(ref.file, ctx.workspace))
  ctx.pathFilter.foreach { p => r = r.filter(ref => matchesPath(ref.file, p, ctx.workspace)) }
  ctx.excludePath.foreach { p => r = r.filter(ref => !matchesPath(ref.file, p, ctx.workspace)) }
  r

// ── Inherited member collection (shared by members + explain) ──────────────

private val inheritableKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)

def collectInheritedMembers(sym: SymbolInfo, ctx: CommandContext): (
  inherited: List[(parentName: String, parentFile: Option[Path], parentPackage: String, members: List[MemberInfo])],
  parentMemberKeys: Set[(name: String, kind: SymbolKind)]
) = {
  if !ctx.inherited then return (inherited = Nil, parentMemberKeys = Set.empty)
  val visited = mutable.HashSet.empty[String]
  visited += sym.name.toLowerCase
  val ownMembers = extractMembers(sym.file, sym.name).map(m => (name = m.name, kind = m.kind)).toSet
  val result = mutable.ListBuffer.empty[(parentName: String, parentFile: Option[Path], parentPackage: String, members: List[MemberInfo])]
  val allParentKeys = mutable.HashSet.empty[(name: String, kind: SymbolKind)]

  def walk(parentNames: List[String]): Unit = {
    parentNames.foreach { pName =>
      if !visited.contains(pName.toLowerCase) then {
        visited += pName.toLowerCase
        val parentDefs = ctx.idx.findDefinition(pName).filter(s => inheritableKinds.contains(s.kind))
        parentDefs.headOption.foreach { pd =>
          val parentMembers = extractMembers(pd.file, pd.name)
          parentMembers.foreach(m => allParentKeys += ((name = m.name, kind = m.kind)))
          val filtered = parentMembers.filterNot(m => ownMembers.contains((name = m.name, kind = m.kind)))
          if filtered.nonEmpty then result += ((parentName = pd.name, parentFile = Some(pd.file), parentPackage = pd.packageName, members = filtered))
          walk(pd.parents)
        }
      }
    }
  }

  walk(sym.parents)
  (inherited = result.toList, parentMemberKeys = allParentKeys.toSet)
}
