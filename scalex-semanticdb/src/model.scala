import java.nio.file.Path
import scala.meta.internal.{semanticdb => sdb}
import scala.meta.internal.semanticdb.XtensionSemanticdbSymbolInformation

val ScalexSdbVersion = "0.2.0"

// ── Enums ──────────────────────────────────────────────────────────────────

enum SemKind(val id: Byte):
  case Class       extends SemKind(0)
  case Trait       extends SemKind(1)
  case Object      extends SemKind(2)
  case Method      extends SemKind(3)
  case Field       extends SemKind(4)
  case Type        extends SemKind(5)
  case Package     extends SemKind(6)
  case PackageObj  extends SemKind(7)
  case Constructor extends SemKind(8)
  case Parameter   extends SemKind(9)
  case TypeParam   extends SemKind(10)
  case Macro       extends SemKind(11)
  case Interface   extends SemKind(12)
  case Local       extends SemKind(13)
  case Unknown     extends SemKind(14)

object SemKind:
  private val byId: Array[SemKind] = values.sortBy(_.id)
  def fromId(id: Byte): SemKind =
    if id >= 0 && id < byId.length then byId(id) else Unknown

  def fromSdb(info: sdb.SymbolInformation): SemKind =
    import sdb.SymbolInformation.Kind.*
    info.kind match
      case LOCAL            => Local
      case FIELD            => Field
      case METHOD           => Method
      case CONSTRUCTOR      => Constructor
      case MACRO            => Macro
      case TYPE             => Type
      case PARAMETER        => Parameter
      case SELF_PARAMETER   => Parameter
      case TYPE_PARAMETER   => TypeParam
      case OBJECT           => Object
      case PACKAGE          => Package
      case PACKAGE_OBJECT   => PackageObj
      case CLASS            => Class
      case TRAIT             => Trait
      case INTERFACE        => Interface
      case _                => Unknown

enum OccRole(val id: Byte):
  case Definition extends OccRole(0)
  case Reference  extends OccRole(1)
  case Unknown    extends OccRole(2)

object OccRole:
  def fromId(id: Byte): OccRole =
    if id == 0 then Definition else if id == 1 then Reference else Unknown

  def fromSdb(role: sdb.SymbolOccurrence.Role): OccRole =
    role match
      case sdb.SymbolOccurrence.Role.DEFINITION => Definition
      case sdb.SymbolOccurrence.Role.REFERENCE  => Reference
      case _                                     => Unknown

// ── Core data types ────────────────────────────────────────────────────────

case class SemRange(startLine: Int, startChar: Int, endLine: Int, endChar: Int):
  override def toString: String = s"$startLine:$startChar..$endLine:$endChar"

case class SemSymbol(
  fqn: String,
  displayName: String,
  kind: SemKind,
  properties: Int,
  owner: String,
  sourceUri: String,
  signature: String,
  parents: List[String],
  overriddenSymbols: List[String],
  annotations: List[String],
):
  // Property flag helpers (bitmask values from semanticdb.proto)
  def isAbstract: Boolean    = (properties & 0x4) != 0
  def isFinal: Boolean       = (properties & 0x8) != 0
  def isSealed: Boolean      = (properties & 0x10) != 0
  def isImplicit: Boolean    = (properties & 0x20) != 0
  def isLazy: Boolean        = (properties & 0x40) != 0
  def isCase: Boolean        = (properties & 0x80) != 0
  def isVal: Boolean         = (properties & 0x400) != 0
  def isVar: Boolean         = (properties & 0x800) != 0
  def isStatic: Boolean      = (properties & 0x1000) != 0
  def isEnum: Boolean        = (properties & 0x4000) != 0
  def isGiven: Boolean       = (properties & 0x10000) != 0
  def isInline: Boolean      = (properties & 0x20000) != 0
  def isOpen: Boolean        = (properties & 0x40000) != 0
  def isOpaque: Boolean      = (properties & 0x200000) != 0
  def isOverride: Boolean    = (properties & 0x400000) != 0

  def propertyNames: List[String] =
    val buf = List.newBuilder[String]
    if isAbstract then buf += "abstract"
    if isFinal then buf += "final"
    if isSealed then buf += "sealed"
    if isImplicit then buf += "implicit"
    if isLazy then buf += "lazy"
    if isCase then buf += "case"
    if isVal then buf += "val"
    if isVar then buf += "var"
    if isStatic then buf += "static"
    if isEnum then buf += "enum"
    if isGiven then buf += "given"
    if isInline then buf += "inline"
    if isOpen then buf += "open"
    if isOpaque then buf += "opaque"
    if isOverride then buf += "override"
    buf.result()

case class SemOccurrence(
  file: String,
  range: SemRange,
  symbol: String,
  role: OccRole,
)

case class IndexedDocument(
  uri: String,
  md5: String,
  symbols: List[SemSymbol],
  occurrences: List[SemOccurrence],
)

// ── Result types ───────────────────────────────────────────────────────────

enum SemCmdResult:
  case SymbolDetail(sym: SemSymbol)
  case SymbolList(header: String, symbols: List[SemSymbol], total: Int)
  case OccurrenceList(header: String, occs: List[SemOccurrence], total: Int)
  case TypeResult(symbol: String, typeString: String)
  case Tree(header: String, lines: List[String])
  case FlowTree(header: String, lines: List[String])
  case RelatedList(header: String, entries: List[(sym: SemSymbol, count: Int)], total: Int)
  case Stats(fileCount: Int, symbolCount: Int, occurrenceCount: Int, buildTimeMs: Long, cached: Boolean)
  case Batch(results: List[(command: String, result: SemCmdResult)])
  case NotFound(message: String)
  case UsageError(message: String)

// ── Command context ────────────────────────────────────────────────────────

case class SemCommandContext(
  index: SemIndex,
  workspace: Path,
  limit: Int = 50,
  verbose: Boolean = false,
  jsonOutput: Boolean = false,
  kindFilter: Option[String] = None,
  roleFilter: Option[String] = None,
  depth: Int = 3,
  timingsEnabled: Boolean = false,
  noAccessors: Boolean = false,
  excludePatterns: List[String] = Nil,
  smart: Boolean = false,
)
