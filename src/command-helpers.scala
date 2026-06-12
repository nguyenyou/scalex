import java.nio.file.Path
import scala.collection.mutable

// ── Command helpers ─────────────────────────────────────────────────────────

/** Standard command preamble: the first positional arg is required; without it
  * the command answers with its usage line. */
def requireArg(args: List[String], usage: String)(f: String => CmdResult): CmdResult =
  args.headOption match {
    case None => CmdResult.UsageError(usage)
    case Some(arg) => f(arg)
  }

def hasRegexHint(pattern: String): Boolean =
  pattern.contains("\\|")

// ── Dotted-name helpers ──────────────────────────────────────────────────────

/** "Outer.Inner.member" → "member"; names without a dot pass through unchanged. */
def simpleNameOf(s: String): String =
  if s.contains(".") then s.substring(s.lastIndexOf('.') + 1) else s

/** Split "Owner.member" at the last dot. None when there is no usable split
  * (no dot, or a leading dot like ".foo"). */
def splitOwnerMember(s: String): Option[(owner: String, member: String)] = {
  val lastDot = s.lastIndexOf('.')
  if lastDot <= 0 then None
  else Some((owner = s.substring(0, lastDot), member = s.substring(lastDot + 1)))
}

def fixPosixRegex(pattern: String): (pattern: String, wasFixed: Boolean) =
  // Only \| needs unconditional conversion: POSIX alternation vs Java literal pipe.
  // \( and \) mean "literal paren" in both POSIX and Java — leave them alone.
  if hasRegexHint(pattern) then
    val fixed = pattern.replace("\\|", "|")
    try
      java.util.regex.Pattern.compile(fixed)
      (pattern = fixed, wasFixed = true)
    catch
      case _: java.util.regex.PatternSyntaxException =>
        (pattern = java.util.regex.Pattern.quote(fixed), wasFixed = true)
  else
    try
      java.util.regex.Pattern.compile(pattern)
      (pattern = pattern, wasFixed = false)
    catch
      case _: java.util.regex.PatternSyntaxException =>
        (pattern = java.util.regex.Pattern.quote(pattern), wasFixed = true)

// ── Suggestions for not-found ────────────────────────────────────────────────

def mkNotFoundWithSuggestions(symbol: String, ctx: CommandContext, cmd: String): NotFoundHint =
  var results = ctx.idx.search(symbol)
  if ctx.noTests then results = results.filter(s => !isTestFile(s.file, ctx.workspace))
  val suggestions = results.take(5).map { s =>
    s"${s.kind.toString.toLowerCase} ${s.name} (${s.packageName})"
  }
  NotFoundHint(symbol, ctx.idx.fileCount, ctx.idx.parseFailures, cmd, ctx.batchMode,
    symbol.contains("/") || symbol.startsWith("."), suggestions)

/** Build owner-scoped suggestions ranked by similarity to `symbol`. */
def mkOwnerScopedSuggestions(symbol: String, owner: String, ctx: CommandContext): List[String] =
  val ownerDefs = filterSymbols(findTypeDefs(owner, ctx), ctx.copy(kindFilter = None))
  val members = ownerDefs.headOption.toList.flatMap(s => extractMembers(s.file, s.name, Some(s.kind)))
  // Rank by similarity: exact > prefix > contains > rest
  val lower = symbol.toLowerCase
  val exact = mutable.ListBuffer.empty[MemberInfo]
  val prefix = mutable.ListBuffer.empty[MemberInfo]
  val contains = mutable.ListBuffer.empty[MemberInfo]
  val rest = mutable.ListBuffer.empty[MemberInfo]
  members.foreach { m =>
    nameMatchTier(lower, m.name) match {
      case Some(MatchTier.Exact)    => exact += m
      case Some(MatchTier.Prefix)   => prefix += m
      case Some(MatchTier.Contains) => contains += m
      case _                        => rest += m
    }
  }
  (exact.toList ++ prefix.toList ++ contains.toList ++ rest.toList).take(5).map { m =>
    s"${m.kind.label} ${m.name} in $owner"
  }

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

/** Resolve a package name and run `f` on it, or produce the standard not-found result. */
def withResolvedPackage(pkg: String, ctx: CommandContext, cmd: String)(f: String => CmdResult): CmdResult = {
  resolvePackage(pkg, ctx) match {
    case None =>
      CmdResult.NotFound(
        s"""Package "$pkg" not found""",
        mkPackageNotFound(pkg, ctx, cmd))
    case Some(resolvedPkg) => f(resolvedPkg)
  }
}

// ── Shared filters ──────────────────────────────────────────────────────────

/** The --no-tests / --path / --exclude-path predicate for `ctx`, or None when
  * no file-level filters are active (callers skip the pass entirely). */
private def ctxPathPredicate(ctx: CommandContext): Option[Path => Boolean] =
  if ctx.noTests || ctx.pathFilter.isDefined || ctx.excludePath.isDefined then
    Some(pathPredicate(ctx.noTests, ctx.pathFilter, ctx.excludePath, ctx.workspace))
  else None

def filterSymbols(symbols: List[SymbolInfo], ctx: CommandContext): List[SymbolInfo] =
  var r = symbols
  ctx.kindFilter.foreach { k =>
    val kk = k.toLowerCase
    r = r.filter(_.kind.label == kk)
  }
  ctxPathPredicate(ctx).foreach { keep => r = r.filter(s => keep(s.file)) }
  ctx.inPackageFilter.foreach { pkg => r = symbolsInPackage(pkg, r) }
  r

def filterRefs(refs: List[Reference], ctx: CommandContext): List[Reference] =
  var r = refs
  ctxPathPredicate(ctx).foreach { keep => r = r.filter(ref => keep(ref.file)) }
  ctx.inPackageFilter.foreach { pkg =>
    val prefix = pkg + "."
    r = r.filter { ref =>
      val relPath = ctx.workspace.relativize(ref.file).toString
      ctx.idx.filePackageByPath(relPath) match
        case Some(filePkg) => filePkg == pkg || filePkg.startsWith(prefix)
        case None => true // keep refs from files with unknown package (e.g. parse failures)
    }
  }
  r

// ── Shared constants ─────────────────────────────────────────────────────────

val typeKinds: Set[SymbolKind] = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)

/** Definitions of `name` that are types (class/trait/object/enum). */
def findTypeDefs(name: String, ctx: CommandContext): List[SymbolInfo] =
  ctx.idx.findDefinition(name).filter(s => typeKinds.contains(s.kind))

// ── Stdlib package detection ─────────────────────────────────────────────────

/** True for java/javax/scala standard-library packages. Expects lowercase. */
def isStdlibPackage(pkg: String): Boolean =
  pkg.startsWith("java.") || pkg.startsWith("javax.") || pkg.startsWith("scala.") ||
    pkg == "java" || pkg == "javax" || pkg == "scala"

/** Ranking weight that deprioritizes stdlib packages: java/javax (2), scala (1),
  * project code (0). Expects lowercase. */
def stdlibPkgRank(pkg: String): Int =
  if pkg.startsWith("java.") || pkg.startsWith("javax.") || pkg == "java" || pkg == "javax" then 2
  else if pkg.startsWith("scala.") || pkg == "scala" then 1
  else 0

// ── Shared output fragments ──────────────────────────────────────────────────

def timedOutSuffix(timedOut: Boolean): String =
  if timedOut then " (timed out — partial results)" else ""

// ── Kind grouping ────────────────────────────────────────────────────────────

def countByKind(symbols: List[SymbolInfo]): List[(kind: SymbolKind, count: Int)] =
  symbols.groupBy(_.kind).toList.sortBy(-_._2.size).map((k, v) => (kind = k, count = v.size))

// ── Inherited member collection (shared by members + explain) ──────────────

def collectInheritedMembers(sym: SymbolInfo, ctx: CommandContext): (
  inherited: List[(parentName: String, parentFile: Option[Path], parentPackage: String, members: List[MemberInfo])],
  parentMemberKeys: Set[(name: String, kind: SymbolKind)]
) = {
  if !ctx.inherited then (inherited = Nil, parentMemberKeys = Set.empty)
  else collectInheritedMembersImpl(sym, ctx)
}

private def collectInheritedMembersImpl(sym: SymbolInfo, ctx: CommandContext): (
  inherited: List[(parentName: String, parentFile: Option[Path], parentPackage: String, members: List[MemberInfo])],
  parentMemberKeys: Set[(name: String, kind: SymbolKind)]
) = {
  val visited = mutable.HashSet.empty[String]
  visited += sym.name.toLowerCase
  val ownMembers = extractMembers(sym.file, sym.name, Some(sym.kind)).map(m => (name = m.name, kind = m.kind)).toSet
  val result = mutable.ListBuffer.empty[(parentName: String, parentFile: Option[Path], parentPackage: String, members: List[MemberInfo])]
  val allParentKeys = mutable.HashSet.empty[(name: String, kind: SymbolKind)]

  def walk(parentNames: List[String]): Unit = {
    parentNames.foreach { pName =>
      if !visited.contains(pName.toLowerCase) then {
        visited += pName.toLowerCase
        val parentDefs = findTypeDefs(pName, ctx)
        parentDefs.headOption.foreach { pd =>
          val parentMembers = extractMembers(pd.file, pd.name, Some(pd.kind))
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

// ── Body enrichment (shared by members, overrides, explain) ─────────────────

/** Extract the body of `name` (optionally scoped to `owner`), kept only when it
  * fits within `maxBodyLines` (<= 0 means no limit). */
def bodyWithinLimit(file: Path, name: String, owner: Option[String], maxBodyLines: Int): Option[BodyInfo] =
  extractBody(file, name, owner).headOption
    .filter(b => maxBodyLines <= 0 || (b.endLine - b.startLine + 1) <= maxBodyLines)

def enrichMemberWithBody(m: MemberInfo, file: Path, ownerName: String, maxBodyLines: Int): MemberInfo =
  bodyWithinLimit(file, m.name, Some(ownerName), maxBodyLines) match {
    case Some(b) => m.copy(body = Some(b))
    case None => m
  }

/** Decorate raw members: mark inherited overrides (--inherited) and attach bodies (--with-body). */
def decorateMembers(raw: List[MemberInfo], parentKeys: Set[(name: String, kind: SymbolKind)],
                    file: Path, ownerName: String, ctx: CommandContext): List[MemberInfo] =
  raw.map { m =>
    val m2 = if ctx.inherited && parentKeys.contains((name = m.name, kind = m.kind)) then m.copy(isOverride = true) else m
    if ctx.withBody then enrichMemberWithBody(m2, file, ownerName, ctx.maxBodyLines) else m2
  }

// ── Ranking / sorting ────────────────────────────────────────────────────────

def rankSymbols(symbols: List[SymbolInfo], workspace: Path): List[SymbolInfo] =
  symbols.sortBy { s =>
    val kindRank = s.kind match
      case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Object | SymbolKind.Enum => 0
      case SymbolKind.Type | SymbolKind.Given => 1
      case _ => 2
    val testRank = if isTestFile(s.file, workspace) then 1 else 0
    val pathLen = workspace.relativize(s.file).toString.length
    (kindRank = kindRank, testRank = testRank, stdlibRank = stdlibPkgRank(s.packageName.toLowerCase), pathLen = pathLen)
  }

def memberKindRank(m: MemberInfo): Int = m.kind match
  case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Object | SymbolKind.Enum => 0
  case SymbolKind.Def => 1
  case SymbolKind.Val | SymbolKind.Var => 2
  case SymbolKind.Type => 3
  case _ => 4

// ── Companion lookup ─────────────────────────────────────────────────────────

def findCompanion(sym: SymbolInfo, symbol: String, defs: List[SymbolInfo]): Option[(sym: SymbolInfo, members: List[MemberInfo])] =
  val companionKinds: Set[SymbolKind] = sym.kind match
    case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Enum => Set(SymbolKind.Object)
    case SymbolKind.Object => Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Enum)
    case _ => Set.empty
  if companionKinds.isEmpty then None
  else
    defs.find(d => companionKinds.contains(d.kind) && d.packageName == sym.packageName && d.file == sym.file)
      .map { compSym =>
        val compMembers = extractMembers(compSym.file, symbol, Some(compSym.kind))
        (sym = compSym, members = compMembers)
      }

// ── Related types extraction ─────────────────────────────────────────

// Scala predef auto-imports: always in scope, almost never redefined by projects.
// Extracted type names from signatures are unqualified — for these universal names,
// the reference is virtually always to the stdlib version even if a project happens
// to define a namesake (e.g. com.ui.Option). Library types like Task, Stream, IO
// are intentionally excluded: projects commonly define their own.
private val predefTypeNames: Set[String] = Set(
  "option", "list", "map", "set", "seq", "vector", "array", "either",
  "some", "none", "nil", "iterable", "iterator", "tuple",
  "boolean", "string", "int", "long", "double", "float", "byte", "short",
  "char", "unit", "nothing", "any", "anyref", "anyval",
)

private val typeNamePattern = """\b[A-Z][A-Za-z0-9]+\b""".r

/** True if `lowerName` is unlikely to refer to a project-defined type:
  * either it's a Scala predef auto-import, not in the index at all (unindexed
  * stdlib type), or all definitions live in stdlib packages. */
private def isStdlibType(lowerName: String, symbolsByName: Map[String, List[SymbolInfo]]): Boolean =
  predefTypeNames.contains(lowerName) || {
    symbolsByName.get(lowerName) match
      case None => true // not in index → unindexed stdlib type
      case Some(syms) => syms.forall(s => isStdlibPackage(s.packageName.toLowerCase))
  }

def extractRelatedTypes(members: List[MemberInfo], sym: SymbolInfo, idx: WorkspaceIndex): List[SymbolInfo] =
  val selfLower = sym.name.toLowerCase
  val seen = mutable.HashSet.empty[String]
  seen += selfLower
  val result = mutable.ListBuffer.empty[SymbolInfo]
  // Extract type names from member signatures
  val typeNames = mutable.HashSet.empty[String]
  members.foreach { m =>
    typeNamePattern.findAllIn(m.signature).foreach(typeNames += _)
  }
  // Also extract from parent names
  sym.parents.foreach(typeNames += _)
  // Cross-reference with index — skip names that are only defined in stdlib packages
  typeNames.foreach { name =>
    val lower = name.toLowerCase
    if !seen.contains(lower) then
      seen += lower
      if !isStdlibType(lower, idx.symbolsByName) then
        idx.symbolsByName.get(lower) match
          case Some(syms) =>
            syms.find(s => typeKinds.contains(s.kind)).foreach { s =>
              result += s
            }
          case None => ()
  }
  result.toList.sortBy(_.name).take(10)

// ── Package helpers ──────────────────────────────────────────────────────────

def symbolsInPackage(pkg: String, symbols: List[SymbolInfo]): List[SymbolInfo] =
  val prefix = pkg + "."
  symbols.filter(s => s.packageName == pkg || s.packageName.startsWith(prefix))
