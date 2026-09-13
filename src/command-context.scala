package scalex

import scalex.index.*

import java.nio.file.Path

case class FiltersOptions(
    kindFilter: Option[String] = None,
    noTests: Boolean = false,
    pathFilter: Option[String] = None,
    excludePath: Option[String] = None,
    inPackageFilter: Option[String] = None
)

case class OutputOptions(
    limit: Int = 20,
    verbose: Boolean = false,
    jsonOutput: Boolean = false,
    batchMode: Boolean = false,
    contextLines: Int = 0,
    offset: Int = 0,
    maxOutput: Int = 0,
    countOnly: Boolean = false
)

case class SearchOptions(
    grepPatterns: List[String] = Nil,
    searchMode: Option[String] = None,
    definitionsOnly: Boolean = false,
    inOwner: Option[String] = None,
    returnsFilter: Option[String] = None,
    takesFilter: Option[String] = None,
    eachMethod: Boolean = false,
    strict: Boolean = false
)

case class ReferencesOptions(
    categorize: Boolean = true,
    categoryFilter: Option[String] = None,
    topN: Option[Int] = None
)

case class MembersOptions(
    implLimit: Int = 5,
    inherited: Boolean = false,
    brief: Boolean = false,
    expandDepth: Int = 0,
    membersLimit: Int = 10,
    shallow: Boolean = false,
    noDoc: Boolean = false,
    withBody: Boolean = false,
    maxBodyLines: Int = 0,
    showImports: Boolean = false,
    related: Boolean = false
)

case class HierarchyOptions(
    ofTrait: Option[String] = None,
    goUp: Boolean = true,
    goDown: Boolean = true,
    maxDepth: Int = -1
)

case class OverviewOptions(
    architecture: Boolean = false,
    focusPackage: Option[String] = None,
    usedByFilter: Option[String] = None,
    summaryMode: Boolean = false,
    explainMode: Boolean = false,
    concise: Boolean = false
)

case class AstOptions(
    hasMethodFilter: Option[String] = None,
    extendsFilter: Option[String] = None,
    bodyContainsFilter: Option[String] = None
)

case class CommandContext(
    idx: WorkspaceIndex,
    workspace: Path,
    filters: FiltersOptions = FiltersOptions(),
    output: OutputOptions = OutputOptions(),
    search: SearchOptions = SearchOptions(),
    references: ReferencesOptions = ReferencesOptions(),
    members: MembersOptions = MembersOptions(),
    hierarchy: HierarchyOptions = HierarchyOptions(),
    overview: OverviewOptions = OverviewOptions(),
    ast: AstOptions = AstOptions()
)
