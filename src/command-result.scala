package scalex

import java.nio.file.Path

// ── CmdResult types ────────────────────────────────────────────────────────

case class NotFoundHint(
    symbol: String,
    fileCount: Int,
    parseFailures: Int,
    cmd: String,
    batchMode: Boolean,
    looksLikePath: Boolean,
    suggestions: List[String] = Nil,
    timedOut: Boolean = false
)

case class MemberSectionData(
    file: Path,
    ownerKind: SymbolKind,
    packageName: String,
    line: Int,
    ownMembers: List[MemberInfo],
    inherited: List[InheritedGroup],
    companion: Option[(sym: SymbolInfo, members: List[MemberInfo])] = None
)

case class DocEntryData(sym: SymbolInfo, doc: Option[String])

case class OverviewData(
    fileCount: Int,
    symbolCount: Int,
    packageCount: Int,
    symbolsByKind: List[(kind: SymbolKind, count: Int)],
    topPackages: List[(pkg: String, count: Int)],
    mostExtended: List[(name: String, count: Int, signature: String)],
    pkgDeps: Map[String, Set[String]],
    hasArchitecture: Boolean,
    focusPackage: Option[String] = None
)

case class TestCaseResult(name: String, line: Int, body: Option[BodyInfo])
case class TestSuiteResult(name: String, file: Path, line: Int, tests: List[TestCaseResult])

case class DiagramLink(from: String, to: String, arrowAtStart: Boolean, arrowAtEnd: Boolean, label: Option[String])

enum CmdResult {
  case SymbolList(header: String, symbols: List[SymbolInfo], emptyMessage: String = "", truncate: Boolean = true)
  case RefList(
      header: String,
      refs: List[Reference],
      timedOut: Boolean,
      correctedPattern: Option[String] = None,
      useContext: Boolean = true,
      emptyMessage: String = "",
      stderrHint: Option[String] = None
  )
  case CategorizedRefs(
      symbol: String,
      grouped: Map[RefCategory, List[Reference]],
      targetPkgs: Set[String],
      timedOut: Boolean,
      stderrHint: Option[String] = None
  )
  case FlatRefs(symbol: String, refs: List[Reference], targetPkgs: Set[String], timedOut: Boolean)
  case StringList(header: String, items: List[String], emptyMessage: String = "")
  case IndexStats(
      fileCount: Int,
      symbolCount: Int,
      packageCount: Int,
      symbolsByKind: List[(kind: SymbolKind, count: Int)],
      indexTimeMs: Long,
      cachedLoad: Boolean,
      parsedCount: Int,
      skippedCount: Int,
      parseFailures: Int,
      parseFailedFiles: List[String]
  )
  case MemberSections(symbol: String, sections: List[MemberSectionData])
  case DocEntries(symbol: String, entries: List[DocEntryData])
  case Overview(data: OverviewData)
  case SourceBlocks(
      symbol: String,
      blocks: List[(file: Path, body: BodyInfo)],
      contextLines: Int = 0,
      showImports: Boolean = false
  )
  case TestSuites(suites: List[TestSuiteResult], showBody: Boolean, emptyMessage: String = "No test suites found")
  case TestCount(suites: Int, tests: Int, dynamicSites: Int)
  case CoverageReport(
      symbol: String,
      totalRefs: Int,
      testRefs: List[Reference],
      testFiles: List[String],
      hint: Option[NotFoundHint] = None
  )
  case HierarchyResult(symbol: String, tree: HierarchyTree)
  case OverrideList(header: String, results: List[OverrideInfo])
  case Explanation(
      sym: SymbolInfo,
      doc: Option[String],
      members: List[MemberInfo],
      impls: List[SymbolInfo],
      importRefs: List[Reference],
      companion: Option[(sym: SymbolInfo, members: List[MemberInfo])] = None,
      expandedImpls: List[ExplainedImpl] = Nil,
      otherMatches: List[String] = Nil,
      totalImpls: Int = 0,
      inherited: List[InheritedGroup] = Nil,
      relatedTypes: List[SymbolInfo] = Nil
  )
  case Dependencies(symbol: String, importDeps: List[DepInfo], bodyDeps: List[DepInfo])
  case Scopes(file: Path, line: Int, scopes: List[ScopeInfo])
  case SymbolDiff(
      ref: String,
      filesChanged: Int,
      added: List[DiffSymbol],
      removed: List[DiffSymbol],
      modified: List[(before: DiffSymbol, after: DiffSymbol)]
  )
  case AstMatches(filters: String, results: List[SymbolInfo])
  case GrepCount(
      matches: Int,
      files: Int,
      timedOut: Boolean,
      correctedPattern: Option[String] = None,
      stderrHint: Option[String] = None
  )
  case GrepByMethod(
      pattern: String,
      owner: String,
      methods: List[MethodGrepMatch],
      correctedPattern: Option[String] = None,
      stderrHint: Option[String] = None,
      timedOut: Boolean = false
  )
  case Packages(packages: List[String])
  case PackageSymbols(pkg: String, symbols: List[SymbolInfo])
  case PackageExplained(pkg: String, entries: List[PackageExplainedEntry], totalSymbols: Int, totalTypes: Int)
  case PackageSummary(pkg: String, subPackages: List[(subPkg: String, count: Int)], totalSymbols: Int)
  case ApiSurface(
      pkg: String,
      symbols: List[(symbol: SymbolInfo, importerCount: Int)],
      totalInPackage: Int,
      internalOnly: List[String]
  )
  case RefsTop(symbol: String, fileRanking: List[(file: Path, count: Int)], total: Int, timedOut: Boolean)
  case RefsSummary(
      symbol: String,
      categoryCounts: List[(category: RefCategory, count: Int)],
      total: Int,
      timedOut: Boolean
  )
  case Entrypoints(entries: List[EntrypointInfo], total: Int)
  case GraphOutput(text: String)
  case ParsedDiagram(boxes: List[String], edges: List[DiagramLink])
  case WithDiagnostics(result: CmdResult, messages: List[String])
  case SymbolSummary(file: String, symbolsByKind: List[(kind: SymbolKind, count: Int)], total: Int)
  case Failure(message: String)
  case NotFound(message: String, hint: NotFoundHint)
  case UsageError(message: String)
}
