import java.nio.file.Path
import com.google.common.hash.BloomFilter

val ScalexVersion = "1.40.0"

// ── Data types ──────────────────────────────────────────────────────────────

enum SymbolKind(val id: Byte):
  case Class     extends SymbolKind(0)
  case Trait     extends SymbolKind(1)
  case Object    extends SymbolKind(2)
  case Def       extends SymbolKind(3)
  case Val       extends SymbolKind(4)
  case Var       extends SymbolKind(5)
  case Type      extends SymbolKind(6)
  case Enum      extends SymbolKind(7)
  case Given     extends SymbolKind(8)
  case Extension extends SymbolKind(9)
  case Package   extends SymbolKind(10)

  /** Lowercase display name used in all text and JSON output. */
  def label: String = toString.toLowerCase

object SymbolKind:
  private val byId: Array[SymbolKind] = values.sortBy(_.id)
  def fromId(id: Byte): SymbolKind = byId(id)

case class SymbolInfo(
    name: String,
    kind: SymbolKind,
    file: Path,
    line: Int,
    packageName: String,
    parents: List[String] = Nil,
    typeParamParents: List[String] = Nil,
    signature: String = "",
    annotations: List[String] = Nil
)

case class Reference(file: Path, line: Int, contextLine: String, aliasInfo: Option[String] = None)
case class GitFile(path: Path, oid: String)

case class IndexedFile(
    relativePath: String,
    oid: String,
    symbols: List[SymbolInfo],
    identifierBloom: Option[BloomFilter[CharSequence]],
    imports: List[String] = Nil,
    aliases: Map[String, String] = Map.empty,
    parseFailed: Boolean = false
)

enum RefCategory:
  case Definition, ExtendedBy, ImportedBy, UsedAsType, Comment, Usage

/** Display order for reference categories (differs from declaration order). */
val refCategoryOrder: List[RefCategory] = List(
  RefCategory.Definition, RefCategory.ExtendedBy, RefCategory.ImportedBy,
  RefCategory.UsedAsType, RefCategory.Usage, RefCategory.Comment)

enum Confidence:
  case High, Medium, Low

  def label: String = s"$this confidence"

  /** Parenthetical shown next to the label in categorized refs output. */
  def explanation: String = this match {
    case Confidence.High   => "import-matched"
    case Confidence.Medium => "wildcard import"
    case Confidence.Low    => "no matching import"
  }

// ── Member / body / hierarchy types ─────────────────────────────────────────

case class MemberInfo(name: String, kind: SymbolKind, line: Int, signature: String = "", annotations: List[String] = Nil, isOverride: Boolean = false, body: Option[BodyInfo] = None)

/** Members grouped under the parent type they are inherited from. */
type InheritedGroup = (parentName: String, parentFile: Option[Path], parentPackage: String, members: List[MemberInfo])

case class BodyInfo(ownerName: String, symbolName: String, sourceText: String, startLine: Int, endLine: Int, isAbstract: Boolean = false)

/** A node in a hierarchy tree: the resolved symbol, or just a name when the
  * type is defined outside the workspace (`sym` empty = external). */
case class HierarchyNode(name: String, sym: Option[SymbolInfo]) {
  def isExternal: Boolean = sym.isEmpty
  def packageName: String = sym.map(_.packageName).getOrElse("")
}
case class HierarchyTree(root: HierarchyNode, parents: List[HierarchyTree], children: List[HierarchyTree], truncatedChildren: Int = 0)

case class OverrideInfo(file: Path, line: Int, enclosingClass: String, enclosingKind: SymbolKind, signature: String, packageName: String, body: Option[BodyInfo] = None)

case class ScopeInfo(name: String, kind: String, line: Int)

case class DiffSymbol(name: String, kind: SymbolKind, file: String, line: Int, packageName: String, signature: String)

case class TestCaseInfo(name: String, line: Int)
case class TestSuiteInfo(name: String, file: Path, line: Int, tests: List[TestCaseInfo], dynamicSites: Int = 0)

// ── Dependency extraction types ─────────────────────────────────────────────

case class DepInfo(name: String, kind: String, file: Option[Path], line: Option[Int], packageName: String, depth: Int = 0)

case class MethodGrepMatch(member: MemberInfo, file: Path, matchCount: Int, matchLines: List[(lineNum: Int, text: String)] = Nil)

case class ExplainedImpl(sym: SymbolInfo, members: List[MemberInfo], subImpls: List[ExplainedImpl] = Nil)

case class PackageExplainedEntry(sym: SymbolInfo, members: List[MemberInfo], implCount: Int)

// ── Entrypoint types ───────────────────────────────────────────────────────

case class EntrypointInfo(sym: SymbolInfo, category: EntrypointCategory, memberLine: Option[Int] = None)

enum EntrypointCategory:
  case MainAnnotation, MainMethod, ExtendsApp, TestSuite

// ── Command context ────────────────────────────────────────────────────────

case class CommandContext(
  idx: WorkspaceIndex, workspace: Path,
  limit: Int = 20, verbose: Boolean = false, jsonOutput: Boolean = false, batchMode: Boolean = false,
  kindFilter: Option[String] = None, noTests: Boolean = false, pathFilter: Option[String] = None,
  contextLines: Int = 0, categorize: Boolean = true, categoryFilter: Option[String] = None,
  grepPatterns: List[String] = Nil, countOnly: Boolean = false, topN: Option[Int] = None,
  searchMode: Option[String] = None, definitionsOnly: Boolean = false,
  inOwner: Option[String] = None, ofTrait: Option[String] = None,
  implLimit: Int = 5, goUp: Boolean = true, goDown: Boolean = true, maxDepth: Int = -1,
  inherited: Boolean = false, architecture: Boolean = false,
  brief: Boolean = false, strict: Boolean = false,
  focusPackage: Option[String] = None,
  hasMethodFilter: Option[String] = None, extendsFilter: Option[String] = None,
  bodyContainsFilter: Option[String] = None,
  expandDepth: Int = 0,
  membersLimit: Int = 10,
  usedByFilter: Option[String] = None,
  returnsFilter: Option[String] = None,
  takesFilter: Option[String] = None,
  shallow: Boolean = false,
  noDoc: Boolean = false,
  excludePath: Option[String] = None,
  summaryMode: Boolean = false,
  withBody: Boolean = false, maxBodyLines: Int = 0,
  showImports: Boolean = false,
  offset: Int = 0,
  related: Boolean = false,
  explainMode: Boolean = false,
  concise: Boolean = false,
  maxOutput: Int = 0,
  inPackageFilter: Option[String] = None,
  eachMethod: Boolean = false,
):
  val fmt: (SymbolInfo, Path) => String = if verbose then formatSymbolVerbose else formatSymbol
  val jRef: Reference => String =
    if contextLines > 0 then r => jsonRefWithContext(r, workspace, contextLines)
    else r => jsonRef(r, workspace)
  val fmtRef: Reference => String =
    if contextLines > 0 then r => formatRefWithContext(r, workspace, contextLines)
    else r => formatRef(r, workspace)

// ── CmdResult types ────────────────────────────────────────────────────────

case class NotFoundHint(symbol: String, fileCount: Int, parseFailures: Int, cmd: String, batchMode: Boolean, looksLikePath: Boolean, suggestions: List[String] = Nil, timedOut: Boolean = false)

case class MemberSectionData(
  file: Path, ownerKind: SymbolKind, packageName: String, line: Int,
  ownMembers: List[MemberInfo],
  inherited: List[InheritedGroup],
  companion: Option[(sym: SymbolInfo, members: List[MemberInfo])] = None
)

case class DocEntryData(sym: SymbolInfo, doc: Option[String])

case class OverviewData(
  fileCount: Int, symbolCount: Int, packageCount: Int,
  symbolsByKind: List[(kind: SymbolKind, count: Int)],
  topPackages: List[(pkg: String, count: Int)],
  mostExtended: List[(name: String, count: Int, signature: String)],
  pkgDeps: Map[String, Set[String]],
  hasArchitecture: Boolean,
  focusPackage: Option[String] = None
)

case class TestCaseResult(name: String, line: Int, body: Option[BodyInfo])
case class TestSuiteResult(name: String, file: Path, line: Int, tests: List[TestCaseResult])

enum CmdResult:
  case SymbolList(header: String, symbols: List[SymbolInfo], emptyMessage: String = "", truncate: Boolean = true)
  case RefList(header: String, refs: List[Reference], timedOut: Boolean, hint: Option[String] = None, useContext: Boolean = true, emptyMessage: String = "", stderrHint: Option[String] = None)
  case CategorizedRefs(symbol: String, grouped: Map[RefCategory, List[Reference]], targetPkgs: Set[String], timedOut: Boolean, stderrHint: Option[String] = None)
  case FlatRefs(symbol: String, refs: List[Reference], targetPkgs: Set[String], timedOut: Boolean)
  case StringList(header: String, items: List[String], emptyMessage: String = "")
  case IndexStats(fileCount: Int, symbolCount: Int, packageCount: Int, symbolsByKind: List[(kind: SymbolKind, count: Int)], indexTimeMs: Long, cachedLoad: Boolean, parsedCount: Int, skippedCount: Int, parseFailures: Int, parseFailedFiles: List[String])
  case MemberSections(symbol: String, sections: List[MemberSectionData])
  case DocEntries(symbol: String, entries: List[DocEntryData])
  case Overview(data: OverviewData)
  case SourceBlocks(symbol: String, blocks: List[(file: Path, body: BodyInfo)], contextLines: Int = 0, showImports: Boolean = false)
  case TestSuites(suites: List[TestSuiteResult], showBody: Boolean, emptyMessage: String = "No test suites found")
  case TestCount(suites: Int, tests: Int, dynamicSites: Int)
  case CoverageReport(symbol: String, totalRefs: Int, testRefs: List[Reference], testFiles: List[String], hint: Option[NotFoundHint] = None)
  case HierarchyResult(symbol: String, tree: HierarchyTree)
  case OverrideList(header: String, results: List[OverrideInfo])
  case Explanation(sym: SymbolInfo, doc: Option[String], members: List[MemberInfo], impls: List[SymbolInfo], importRefs: List[Reference],
    companion: Option[(sym: SymbolInfo, members: List[MemberInfo])] = None,
    expandedImpls: List[ExplainedImpl] = Nil,
    otherMatches: List[String] = Nil, totalImpls: Int = 0,
    inherited: List[InheritedGroup] = Nil,
    relatedTypes: List[SymbolInfo] = Nil)
  case Dependencies(symbol: String, importDeps: List[DepInfo], bodyDeps: List[DepInfo])
  case Scopes(file: Path, line: Int, scopes: List[ScopeInfo])
  case SymbolDiff(ref: String, filesChanged: Int, added: List[DiffSymbol], removed: List[DiffSymbol], modified: List[(before: DiffSymbol, after: DiffSymbol)])
  case AstMatches(filters: String, results: List[SymbolInfo])
  case GrepCount(matches: Int, files: Int, timedOut: Boolean, hint: Option[String] = None, stderrHint: Option[String] = None)
  case GrepByMethod(pattern: String, owner: String, methods: List[MethodGrepMatch], hint: Option[String] = None, stderrHint: Option[String] = None, timedOut: Boolean = false)
  case Packages(packages: List[String])
  case PackageSymbols(pkg: String, symbols: List[SymbolInfo])
  case PackageExplained(pkg: String, entries: List[PackageExplainedEntry], totalSymbols: Int, totalTypes: Int)
  case PackageSummary(pkg: String, subPackages: List[(subPkg: String, count: Int)], totalSymbols: Int)
  case ApiSurface(pkg: String, symbols: List[(symbol: SymbolInfo, importerCount: Int)], totalInPackage: Int, internalOnly: List[String])
  case RefsTop(symbol: String, fileRanking: List[(file: Path, count: Int)], total: Int, timedOut: Boolean)
  case RefsSummary(symbol: String, categoryCounts: List[(category: RefCategory, count: Int)], total: Int, timedOut: Boolean)
  case Entrypoints(entries: List[EntrypointInfo], total: Int)
  case GraphOutput(text: String)
  case NotFound(message: String, hint: NotFoundHint)
  case UsageError(message: String)
  /** The command printed its own output; the renderer does nothing. */
  case Silent
