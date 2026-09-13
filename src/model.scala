package scalex

import java.nio.file.Path
import com.google.common.hash.BloomFilter

val ScalexVersion = "1.41.0"

// ── Data types ──────────────────────────────────────────────────────────────

enum SymbolKind(val id: Byte) {
  case Class extends SymbolKind(0)
  case Trait extends SymbolKind(1)
  case Object extends SymbolKind(2)
  case Def extends SymbolKind(3)
  case Val extends SymbolKind(4)
  case Var extends SymbolKind(5)
  case Type extends SymbolKind(6)
  case Enum extends SymbolKind(7)
  case Given extends SymbolKind(8)
  case Extension extends SymbolKind(9)
  case Package extends SymbolKind(10)

  /** Lowercase display name used in all text and JSON output. */
  def label: String = toString.toLowerCase
}

object SymbolKind {
  private val byId: Array[SymbolKind] = values.sortBy(_.id)
  def fromId(id: Byte): SymbolKind = byId(id)
}

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

enum RefCategory {
  case Definition, ExtendedBy, ImportedBy, UsedAsType, Comment, Usage
}

/** Display order for reference categories (differs from declaration order). */
val refCategoryOrder: List[RefCategory] = List(
  RefCategory.Definition,
  RefCategory.ExtendedBy,
  RefCategory.ImportedBy,
  RefCategory.UsedAsType,
  RefCategory.Usage,
  RefCategory.Comment
)

enum Confidence {
  case High, Medium, Low

  def label: String = s"$this confidence"

  /** Parenthetical shown next to the label in categorized refs output. */
  def explanation: String = this match {
    case Confidence.High   => "import-matched"
    case Confidence.Medium => "wildcard import"
    case Confidence.Low    => "no matching import"
  }
}

// ── Member / body / hierarchy types ─────────────────────────────────────────

case class MemberInfo(
    name: String,
    kind: SymbolKind,
    line: Int,
    signature: String = "",
    annotations: List[String] = Nil,
    isOverride: Boolean = false,
    body: Option[BodyInfo] = None
)

/** Members grouped under the parent type they are inherited from. */
type InheritedGroup = (parentName: String, parentFile: Option[Path], parentPackage: String, members: List[MemberInfo])

case class BodyInfo(
    ownerName: String,
    symbolName: String,
    sourceText: String,
    startLine: Int,
    endLine: Int,
    isAbstract: Boolean = false
)

/** A node in a hierarchy tree: the resolved symbol, or just a name when the type is defined outside the workspace
  * (`sym` empty = external).
  */
case class HierarchyNode(name: String, sym: Option[SymbolInfo]) {
  def isExternal: Boolean = sym.isEmpty
  def packageName: String = sym.map(_.packageName).getOrElse("")
}
case class HierarchyTree(
    root: HierarchyNode,
    parents: List[HierarchyTree],
    children: List[HierarchyTree],
    truncatedChildren: Int = 0
)

case class OverrideInfo(
    file: Path,
    line: Int,
    enclosingClass: String,
    enclosingKind: SymbolKind,
    signature: String,
    packageName: String,
    body: Option[BodyInfo] = None
)

case class ScopeInfo(name: String, kind: String, line: Int)

case class DiffSymbol(
    name: String,
    kind: SymbolKind,
    file: String,
    line: Int,
    packageName: String,
    signature: String,
    contentHash: String = "",
    owner: List[String] = Nil
)

case class TestCaseInfo(name: String, line: Int)
case class TestSuiteInfo(name: String, file: Path, line: Int, tests: List[TestCaseInfo], dynamicSites: Int = 0)

// ── Dependency extraction types ─────────────────────────────────────────────

case class DepInfo(
    name: String,
    kind: String,
    file: Option[Path],
    line: Option[Int],
    packageName: String,
    depth: Int = 0
)

case class MethodGrepMatch(
    member: MemberInfo,
    file: Path,
    matchCount: Int,
    matchLines: List[(lineNum: Int, text: String)] = Nil
)

case class ExplainedImpl(sym: SymbolInfo, members: List[MemberInfo], subImpls: List[ExplainedImpl] = Nil)

case class PackageExplainedEntry(sym: SymbolInfo, members: List[MemberInfo], implCount: Int)

// ── Entrypoint types ───────────────────────────────────────────────────────

case class EntrypointInfo(sym: SymbolInfo, category: EntrypointCategory, memberLine: Option[Int] = None)

enum EntrypointCategory {
  case MainAnnotation, MainMethod, ExtendsApp, TestSuite
}
