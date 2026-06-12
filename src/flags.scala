import java.nio.file.Path
import clibase.{Flag, FlagRegistry, Flags}

// ── Scalex flag declarations ────────────────────────────────────────────────
//
// Each flag is declared exactly once: spellings, value shape, default, and help
// text. The parser and the help `Options:` section both derive from
// `scalexFlags`, so adding a flag here is the only parser-side change needed.
// Registry order = help display order.

val WorkspaceFlag = Flag.string("-w", "--workspace")("PATH", "Set workspace path (default: current directory)")
val LimitFlag = Flag.int("--limit")("N", default = 20, "Max results (default: 20, 0 = unlimited)")
val OffsetFlag = Flag.int("--offset")("N", default = 0, "Members: skip first N results for pagination (default: 0)")
val KindFlag = Flag.string("--kind")("K", "Filter by kind: class, trait, object, def, val, type, enum, given, extension")
val VerboseFlag = Flag.boolean("--verbose")("Show signatures and extends clauses")
val CategorizeFlag = Flag.boolean("--categorize", "-c")("Group refs by category (default; kept for backwards compatibility)")
val FlatFlag = Flag.boolean("--flat")("Refs: flat list instead of categorized (overrides default)")
val DefinitionsOnlyFlag = Flag.boolean("--definitions-only")("Search: only return class/trait/object/enum definitions")
val CategoryFlag = Flag.string("--category")("CAT", "Refs: filter to a single category (Definition/ExtendedBy/ImportedBy/UsedAsType/Usage/Comment)")
val NoTestsFlag = Flag.boolean("--no-tests")("Exclude test files (test/, tests/, testing/, bench-*, *Spec.scala, etc.)")
val IncludeTestsFlag = Flag.boolean("--include-tests")("Override --no-tests default for overview command")
val PathFlag = Flag.string("--path")("PREFIX", "Restrict results to files under PREFIX (e.g. compiler/src/)", _.stripPrefix("/"))
val ExcludePathFlag = Flag.string("--exclude-path")("PREFIX", "Exclude files under PREFIX (e.g. --exclude-path sbt-test/)", _.stripPrefix("/"))
val ContextLinesFlag = Flag.int("-C")("N", default = 0, "Show N context lines around each reference (refs, grep, body)")
val GrepPatternFlag = Flag.repeated("-e")("PATTERN", "Grep: additional pattern (combine multiple with |); repeatable")
val CountFlag = Flag.boolean("--count")("Grep/refs: show counts only, no full results")
val TopFlag = Flag.intOption("--top")("N", "Refs: rank top N files by reference count")
val ExactFlag = Flag.boolean("--exact")("Search: only exact name matches")
val PrefixFlag = Flag.boolean("--prefix")("Search: only exact + prefix matches")
val JsonFlag = Flag.boolean("--json")("Output results as JSON (structured output for programmatic use)")
val VersionFlag = Flag.boolean("--version")("Print version and exit")
val InFlag = Flag.string("--in")("OWNER", "Body/grep: restrict to members of the given enclosing type")
val EachMethodFlag = Flag.boolean("--each-method")("Grep: with --in, report which methods match (per-method grep)")
val OfFlag = Flag.string("--of")("TRAIT", "Overrides: restrict to implementations of the given trait")
val BodyFlag = Flag.boolean("--body", "--with-bodies")("Members/overrides/explain: inline method bodies into output")
val MaxLinesFlag = Flag.int("--max-lines")("N", default = 0, "Members/overrides/explain: only inline bodies ≤ N lines (0 = unlimited)")
val ImportsFlag = Flag.boolean("--imports")("Body: prepend file's import block to output")
val ShallowFlag = Flag.boolean("--shallow")("Explain: skip implementations and import refs (definition + members only)")
val NoDocFlag = Flag.boolean("--no-doc")("Explain: suppress Scaladoc section")
val ImplLimitFlag = Flag.int("--impl-limit")("N", default = 5, "Explain: max implementations to show (default: 5)")
val MembersLimitFlag = Flag.int("--members-limit")("N", default = 10, "Explain: max members to show per type (default: 10)")
val ExpandFlag = Flag.optionalInt("--expand")("N", default = 0, presentValue = 1, "Explain: recursively expand implementations N levels deep")
val UpFlag = Flag.boolean("--up")("Hierarchy: show only parents (default: both)")
val DownFlag = Flag.boolean("--down")("Hierarchy: show only children (default: both)")
val DepthFlag = Flag.int("--depth")("N", default = -1, "Hierarchy/deps: max tree depth (hierarchy default: 5, no cap; deps default: 1, max: 5)")
val InheritedFlag = Flag.boolean("--inherited")("Members/explain: include inherited members from parent types")
val BriefFlag = Flag.boolean("--brief")("Members: names only; Explain: definition + top 3 members only")
val SummaryFlag = Flag.boolean("--summary")("Symbols: show grouped counts by kind instead of full listing")
val StrictFlag = Flag.boolean("--strict")("Refs/imports: treat _ and $ as word characters (no boundary matches)")
val RelatedFlag = Flag.boolean("--related")("Explain: show project-defined types referenced in member signatures")
val ExplainFlag = Flag.boolean("--explain")("Package: brief explain per type (definition + top 3 members + impl count)")
val ArchitectureFlag = Flag.boolean("--architecture")("Overview: show package dependency graph and hub types")
val ConciseFlag = Flag.boolean("--concise")("Overview: fixed-size summary (~60 lines) with top packages, hub types, dep stats")
val FocusPackageFlag = Flag.string("--focus-package")("PKG", "Overview: scope dependency graph to a single package")
val HasMethodFlag = Flag.string("--has-method")("NAME", "AST pattern: match types that have a method with NAME")
val ExtendsFlag = Flag.string("--extends")("TRAIT", "AST pattern: match types that extend TRAIT")
val BodyContainsFlag = Flag.string("--body-contains")("PAT", "AST pattern: match types whose body contains PAT")
val UsedByFlag = Flag.string("--used-by")("PKG", "API: filter importers to only those from PKG")
val ReturnsFlag = Flag.string("--returns")("TYPE", "Search: filter to symbols whose signature returns TYPE")
val TakesFlag = Flag.string("--takes")("TYPE", "Search: filter to symbols whose signature takes TYPE")
val MaxOutputFlag = Flag.int("--max-output")("N", default = 0, "Truncate output at N characters (0 = unlimited); works on all commands")
val InPackageFlag = Flag.string("--in-package")("PKG", "Filter results to files whose package matches PKG prefix")
val TimingsFlag = Flag.boolean("--timings")("Print per-phase timing breakdown to stderr")

val scalexFlags: FlagRegistry = FlagRegistry(List(
  WorkspaceFlag, LimitFlag, OffsetFlag, KindFlag, VerboseFlag, CategorizeFlag, FlatFlag,
  DefinitionsOnlyFlag, CategoryFlag, NoTestsFlag, IncludeTestsFlag, PathFlag, ExcludePathFlag,
  ContextLinesFlag, GrepPatternFlag, CountFlag, TopFlag, ExactFlag, PrefixFlag, JsonFlag,
  VersionFlag, InFlag, EachMethodFlag, OfFlag, BodyFlag, MaxLinesFlag, ImportsFlag, ShallowFlag,
  NoDocFlag, ImplLimitFlag, MembersLimitFlag, ExpandFlag, UpFlag, DownFlag, DepthFlag,
  InheritedFlag, BriefFlag, SummaryFlag, StrictFlag, RelatedFlag, ExplainFlag, ArchitectureFlag,
  ConciseFlag, FocusPackageFlag, HasMethodFlag, ExtendsFlag, BodyContainsFlag, UsedByFlag,
  ReturnsFlag, TakesFlag, MaxOutputFlag, InPackageFlag, TimingsFlag,
))

def parseFlags(argList: List[String]): Flags = Flags.parse(argList, scalexFlags)

/** The single place a CommandContext is built from parsed flags — derived values
  * (limit 0 → unlimited, --flat inversion, --exact/--prefix and --up/--down
  * pairing) are resolved here. */
def flagsToContext(f: Flags, idx: WorkspaceIndex, workspace: Path,
                   batchMode: Boolean = false, effectiveNoTests: Option[Boolean] = None): CommandContext = {
  val sawUp = f(UpFlag)
  val sawDown = f(DownFlag)
  CommandContext(
    idx = idx, workspace = workspace,
    limit = if f(LimitFlag) == 0 then Int.MaxValue else f(LimitFlag),
    verbose = f(VerboseFlag),
    jsonOutput = f(JsonFlag),
    batchMode = batchMode,
    kindFilter = f(KindFlag),
    noTests = effectiveNoTests.getOrElse(f(NoTestsFlag)),
    pathFilter = f(PathFlag),
    contextLines = f(ContextLinesFlag),
    categorize = !f(FlatFlag),
    categoryFilter = f(CategoryFlag),
    grepPatterns = f(GrepPatternFlag),
    countOnly = f(CountFlag),
    topN = f(TopFlag),
    searchMode = if f(ExactFlag) then Some("exact") else if f(PrefixFlag) then Some("prefix") else None,
    definitionsOnly = f(DefinitionsOnlyFlag),
    inOwner = f(InFlag),
    ofTrait = f(OfFlag),
    implLimit = f(ImplLimitFlag),
    goUp = !sawDown || sawUp,
    goDown = !sawUp || sawDown,
    maxDepth = f(DepthFlag),
    inherited = f(InheritedFlag),
    architecture = f(ArchitectureFlag),
    focusPackage = f(FocusPackageFlag),
    hasMethodFilter = f(HasMethodFlag),
    extendsFilter = f(ExtendsFlag),
    bodyContainsFilter = f(BodyContainsFlag),
    expandDepth = f(ExpandFlag),
    membersLimit = f(MembersLimitFlag),
    brief = f(BriefFlag),
    strict = f(StrictFlag),
    usedByFilter = f(UsedByFlag),
    returnsFilter = f(ReturnsFlag),
    takesFilter = f(TakesFlag),
    shallow = f(ShallowFlag),
    noDoc = f(NoDocFlag),
    excludePath = f(ExcludePathFlag),
    summaryMode = f(SummaryFlag),
    withBody = f(BodyFlag),
    maxBodyLines = f(MaxLinesFlag),
    showImports = f(ImportsFlag),
    offset = f(OffsetFlag),
    related = f(RelatedFlag),
    explainMode = f(ExplainFlag),
    concise = f(ConciseFlag),
    maxOutput = f(MaxOutputFlag),
    inPackageFilter = f(InPackageFlag),
    eachMethod = f(EachMethodFlag),
  )
}
