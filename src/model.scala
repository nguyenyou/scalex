import java.nio.file.Path
import com.google.common.hash.BloomFilter

val ScalexVersion = "1.13.0"

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
    signature: String = "",
    annotations: List[String] = Nil
)

case class Reference(file: Path, line: Int, contextLine: String, aliasInfo: Option[String] = None)
case class GitFile(path: Path, oid: String)

case class IndexedFile(
    relativePath: String,
    oid: String,
    symbols: List[SymbolInfo],
    identifierBloom: BloomFilter[CharSequence],
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

case class MemberInfo(name: String, kind: SymbolKind, line: Int, signature: String = "", annotations: List[String] = Nil)

case class BodyInfo(ownerName: String, symbolName: String, sourceText: String, startLine: Int, endLine: Int)

case class HierarchyNode(name: String, kind: Option[SymbolKind], file: Option[Path], line: Option[Int], packageName: String, isExternal: Boolean)
case class HierarchyTree(root: HierarchyNode, parents: List[HierarchyTree], children: List[HierarchyTree])

case class OverrideInfo(file: Path, line: Int, enclosingClass: String, enclosingKind: SymbolKind, signature: String, packageName: String)

case class ScopeInfo(name: String, kind: String, line: Int)

case class DiffSymbol(name: String, kind: SymbolKind, file: String, line: Int, packageName: String, signature: String)

case class TestCaseInfo(name: String, line: Int, suiteName: String, suiteFile: Path)
case class TestSuiteInfo(name: String, file: Path, line: Int, tests: List[TestCaseInfo])

// ── Dependency extraction types ─────────────────────────────────────────────

case class DepInfo(name: String, kind: String, file: Option[Path], line: Option[Int], packageName: String)

// ── AST pattern matching types ──────────────────────────────────────────────

case class AstPatternMatch(name: String, kind: SymbolKind, file: Path, line: Int, packageName: String, signature: String)

// ── Command context ────────────────────────────────────────────────────────

case class CommandContext(
  idx: WorkspaceIndex, workspace: Path,
  limit: Int = 20, verbose: Boolean = false, jsonOutput: Boolean = false, batchMode: Boolean = false,
  kindFilter: Option[String] = None, noTests: Boolean = false, pathFilter: Option[String] = None,
  contextLines: Int = 0, categorize: Boolean = true, categoryFilter: Option[String] = None,
  grepPatterns: List[String] = Nil, countOnly: Boolean = false,
  searchMode: Option[String] = None, definitionsOnly: Boolean = false,
  inOwner: Option[String] = None, ofTrait: Option[String] = None,
  implLimit: Int = 5, goUp: Boolean = true, goDown: Boolean = true,
  inherited: Boolean = false, architecture: Boolean = false,
  hasMethodFilter: Option[String] = None, extendsFilter: Option[String] = None,
  bodyContainsFilter: Option[String] = None,
):
  val fmt: (SymbolInfo, Path) => String = if verbose then formatSymbolVerbose else formatSymbol
  val jRef: Reference => String =
    if contextLines > 0 then r => jsonRefWithContext(r, workspace, contextLines)
    else r => jsonRef(r, workspace)
  val fmtRef: Reference => String =
    if contextLines > 0 then r => formatRefWithContext(r, workspace, contextLines)
    else r => formatRef(r, workspace)
