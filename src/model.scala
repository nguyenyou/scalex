import java.nio.file.Path
import com.google.common.hash.BloomFilter
import java.util.concurrent.ConcurrentLinkedQueue as CLQ

val ScalexVersion = "1.22.0"

// ── Timings ────────────────────────────────────────────────────────────────

object Timings:
  var enabled: Boolean = false
  private val entries = CLQ[(String, Long)]()

  inline def phase[A](name: String)(body: => A): A =
    if !enabled then body
    else
      val t0 = System.nanoTime()
      val result = body
      entries.add((name, System.nanoTime() - t0))
      result

  def report(): Unit =
    if !enabled then return
    import scala.jdk.CollectionConverters.*
    val items = entries.asScala.toList
    entries.clear()
    if items.isEmpty then return
    val total = items.map(_._2).sum
    System.err.println("Timings:")
    items.foreach { (name, nanos) =>
      val ms = nanos / 1_000_000.0
      val pct = if total > 0 then (nanos * 100.0 / total).round else 0
      System.err.println(f"  $name%-22s $ms%8.1f ms  ($pct%2d%%)")
    }
    val totalMs = total / 1_000_000.0
    System.err.println(f"  ${"total"}%-22s $totalMs%8.1f ms")

  def reset(): Unit = entries.clear()

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

case class CategorizedRef(ref: Reference, category: RefCategory)

enum Confidence:
  case High, Medium, Low

// ── Member / body / hierarchy types ─────────────────────────────────────────

case class MemberInfo(name: String, kind: SymbolKind, line: Int, signature: String = "", annotations: List[String] = Nil, isOverride: Boolean = false)

case class BodyInfo(ownerName: String, symbolName: String, sourceText: String, startLine: Int, endLine: Int)

case class HierarchyNode(name: String, kind: Option[SymbolKind], file: Option[Path], line: Option[Int], packageName: String, isExternal: Boolean)
case class HierarchyTree(root: HierarchyNode, parents: List[HierarchyTree], children: List[HierarchyTree], truncatedChildren: Int = 0)

case class OverrideInfo(file: Path, line: Int, enclosingClass: String, enclosingKind: SymbolKind, signature: String, packageName: String)

case class ScopeInfo(name: String, kind: String, line: Int)

case class DiffSymbol(name: String, kind: SymbolKind, file: String, line: Int, packageName: String, signature: String)

case class TestCaseInfo(name: String, line: Int, suiteName: String, suiteFile: Path)
case class TestSuiteInfo(name: String, file: Path, line: Int, tests: List[TestCaseInfo])

// ── Dependency extraction types ─────────────────────────────────────────────

case class DepInfo(name: String, kind: String, file: Option[Path], line: Option[Int], packageName: String, depth: Int = 0)

// ── AST pattern matching types ──────────────────────────────────────────────

case class AstPatternMatch(name: String, kind: SymbolKind, file: Path, line: Int, packageName: String, signature: String)

case class ExplainedImpl(sym: SymbolInfo, members: List[MemberInfo], subImpls: List[ExplainedImpl] = Nil)

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
):
  val fmt: (SymbolInfo, Path) => String = if verbose then formatSymbolVerbose else formatSymbol
  val jRef: Reference => String =
    if contextLines > 0 then r => jsonRefWithContext(r, workspace, contextLines)
    else r => jsonRef(r, workspace)
  val fmtRef: Reference => String =
    if contextLines > 0 then r => formatRefWithContext(r, workspace, contextLines)
    else r => formatRef(r, workspace)

// ── CmdResult types ────────────────────────────────────────────────────────

case class NotFoundHint(symbol: String, fileCount: Int, parseFailures: Int, cmd: String, batchMode: Boolean, looksLikePath: Boolean, suggestions: List[String] = Nil)

case class MemberSectionData(
  file: Path, ownerKind: SymbolKind, packageName: String, line: Int,
  ownMembers: List[MemberInfo],
  inherited: List[(parentName: String, parentFile: Option[Path], parentPackage: String, members: List[MemberInfo])],
  companion: Option[(sym: SymbolInfo, members: List[MemberInfo])] = None
)

case class DocEntryData(sym: SymbolInfo, doc: Option[String])

case class OverviewData(
  fileCount: Int, symbolCount: Int, packageCount: Int,
  symbolsByKind: List[(kind: SymbolKind, count: Int)],
  topPackages: List[(pkg: String, count: Int)],
  mostExtended: List[(name: String, count: Int, signature: String)],
  pkgDeps: Map[String, Set[String]],
  hubTypes: List[(name: String, score: Int, signature: String)],
  hasArchitecture: Boolean,
  focusPackage: Option[String] = None
)

case class TestCaseResult(name: String, line: Int, body: Option[BodyInfo])
case class TestSuiteResult(name: String, file: Path, line: Int, tests: List[TestCaseResult])

enum CmdResult:
  case SymbolList(header: String, symbols: List[SymbolInfo], total: Int, emptyMessage: String = "", truncate: Boolean = true)
  case RefList(header: String, refs: List[Reference], timedOut: Boolean, hint: Option[String] = None, useContext: Boolean = true, emptyMessage: String = "", stderrHint: Option[String] = None)
  case CategorizedRefs(symbol: String, grouped: Map[RefCategory, List[Reference]], targetPkgs: Set[String], timedOut: Boolean, stderrHint: Option[String] = None)
  case FlatRefs(symbol: String, refs: List[Reference], targetPkgs: Set[String], timedOut: Boolean)
  case StringList(header: String, items: List[String], total: Int, emptyMessage: String = "")
  case IndexStats(fileCount: Int, symbolCount: Int, packageCount: Int, symbolsByKind: List[(kind: SymbolKind, count: Int)], indexTimeMs: Long, cachedLoad: Boolean, parsedCount: Int, skippedCount: Int, parseFailures: Int, parseFailedFiles: List[String])
  case MemberSections(symbol: String, sections: List[MemberSectionData])
  case DocEntries(symbol: String, entries: List[DocEntryData])
  case Overview(data: OverviewData)
  case SourceBlocks(symbol: String, blocks: List[(file: Path, body: BodyInfo)])
  case TestSuites(suites: List[TestSuiteResult], showBody: Boolean, emptyMessage: String = "No test suites found")
  case CoverageReport(symbol: String, totalRefs: Int, testRefs: List[Reference], testFiles: List[String])
  case HierarchyResult(symbol: String, tree: HierarchyTree)
  case OverrideList(header: String, results: List[OverrideInfo])
  case Explanation(sym: SymbolInfo, doc: Option[String], members: List[MemberInfo], impls: List[SymbolInfo], importRefs: List[Reference],
    companion: Option[(sym: SymbolInfo, members: List[MemberInfo])] = None,
    expandedImpls: List[ExplainedImpl] = Nil,
    otherMatches: List[String] = Nil, totalImpls: Int = 0,
    inherited: List[(parentName: String, parentFile: Option[Path], parentPackage: String, members: List[MemberInfo])] = Nil)
  case Dependencies(symbol: String, importDeps: List[DepInfo], bodyDeps: List[DepInfo])
  case Scopes(file: Path, line: Int, scopes: List[ScopeInfo])
  case SymbolDiff(ref: String, filesChanged: Int, added: List[DiffSymbol], removed: List[DiffSymbol], modified: List[(before: DiffSymbol, after: DiffSymbol)])
  case AstMatches(filters: String, results: List[AstPatternMatch])
  case GrepCount(matches: Int, files: Int, timedOut: Boolean, hint: Option[String] = None, stderrHint: Option[String] = None)
  case Packages(packages: List[String])
  case PackageSymbols(pkg: String, symbols: List[SymbolInfo])
  case PackageSummary(pkg: String, subPackages: List[(subPkg: String, count: Int)], totalSymbols: Int)
  case ApiSurface(pkg: String, symbols: List[(symbol: SymbolInfo, importerCount: Int)], totalInPackage: Int, internalOnly: List[String])
  case RefsTop(symbol: String, fileRanking: List[(file: Path, count: Int)], total: Int, timedOut: Boolean)
  case RefsSummary(symbol: String, categoryCounts: List[(category: RefCategory, count: Int)], total: Int, timedOut: Boolean)
  case Entrypoints(entries: List[EntrypointInfo], total: Int)
  case NotFound(message: String, hint: NotFoundHint)
  case UsageError(message: String)
