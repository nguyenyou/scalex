import java.nio.file.Path
import scala.collection.mutable.ListBuffer
import scala.meta.internal.semanticdb.{
  Locator, TextDocuments, TextDocument,
  SymbolInformation => SdbInfo, SymbolOccurrence => SdbOcc,
  Diagnostic => SdbDiag, Signature => SdbSig,
  ClassSignature, MethodSignature, TypeSignature, ValueSignature,
  Type => SdbType, TypeRef, SingleType, ThisType, SuperType,
  IntersectionType, UnionType, WithType, AnnotatedType,
  UniversalType, ExistentialType, StructuralType, ByNameType, RepeatedType,
  ConstantType, MatchType, LambdaType,
  Print, Scope,
}
import scala.meta.internal.metap.PrinterSymtab
import scala.meta.metap.Format
import scala.meta.internal.semanticdb.Scala._

// ── SemanticDB protobuf → model conversion ─────────────────────────────────

object Parser:

  /** Load all .semanticdb files from the given root directories and convert to IndexedDocuments. */
  def loadDocuments(roots: List[Path]): List[IndexedDocument] =
    val docs = ListBuffer.empty[IndexedDocument]
    Locator(roots) { (path, textDocuments) =>
      textDocuments.documents.foreach { doc =>
        docs += convertDocument(doc)
      }
    }
    docs.toList

  /** Convert a single TextDocument to our IndexedDocument model. */
  def convertDocument(doc: TextDocument): IndexedDocument =
    val symtab = PrinterSymtab.fromTextDocument(doc)
    val symbols = doc.symbols.iterator.map(info => convertSymbol(info, doc.uri, symtab)).toList
    val occurrences = doc.occurrences.iterator.map(occ => convertOccurrence(occ, doc.uri)).toList
    val diagnostics = doc.diagnostics.iterator.map(diag => convertDiagnostic(diag, doc.uri)).toList
    IndexedDocument(
      uri = doc.uri,
      md5 = doc.md5,
      symbols = symbols,
      occurrences = occurrences,
      diagnostics = diagnostics,
    )

  // ── Symbol conversion ──────────────────────────────────────────────────

  private def convertSymbol(info: SdbInfo, uri: String, symtab: PrinterSymtab): SemSymbol =
    val owner =
      try info.symbol.owner
      catch case _: Exception => ""

    val parents = extractParentSymbols(info.signature)
    val sig = prettySignature(info, symtab)

    SemSymbol(
      fqn = info.symbol,
      displayName = info.displayName,
      kind = SemKind.fromSdb(info),
      properties = info.properties,
      owner = owner,
      sourceUri = uri,
      signature = sig,
      parents = parents,
      overriddenSymbols = info.overriddenSymbols.toList,
      annotations = info.annotations.map(a => extractTypeSymbol(a.tpe)).toList,
    )

  /** Pretty-print a signature using scalameta's Print utility. */
  private def prettySignature(info: SdbInfo, symtab: PrinterSymtab): String =
    if !info.signature.isDefined then ""
    else
      try Print.info(Format.Detailed, info, symtab)
      catch case _: Exception => info.displayName

  // ── Type extraction helpers ────────────────────────────────────────────

  /** Extract parent type symbols from a ClassSignature's parents list. */
  def extractParentSymbols(sig: SdbSig): List[String] =
    sig match
      case ClassSignature(_, parents, _, _) =>
        parents.flatMap(extractTypeSymbolOpt).toList
      case _ => Nil

  /** Extract the primary type symbol from a Type (e.g. TypeRef.symbol). */
  def extractTypeSymbol(tpe: SdbType): String =
    extractTypeSymbolOpt(tpe).getOrElse("")

  def extractTypeSymbolOpt(tpe: SdbType): Option[String] =
    tpe match
      case TypeRef(_, symbol, _) if symbol.nonEmpty => Some(symbol)
      case SingleType(_, symbol) if symbol.nonEmpty => Some(symbol)
      case ThisType(symbol) if symbol.nonEmpty => Some(symbol)
      case AnnotatedType(_, underlying) => extractTypeSymbolOpt(underlying)
      case _ => None

  /** Extract return type from a method/value signature as a pretty string. */
  def extractReturnTypeString(info: SdbInfo, symtab: PrinterSymtab): Option[String] =
    info.signature match
      case MethodSignature(_, _, returnType, _) if returnType.isDefined =>
        Some(prettyType(returnType, symtab))
      case ValueSignature(tpe) if tpe.isDefined =>
        Some(prettyType(tpe, symtab))
      case _ => None

  /** Pretty-print a type. */
  def prettyType(tpe: SdbType, symtab: PrinterSymtab): String =
    try Print.tpe(Format.Detailed, tpe, symtab)
    catch case _: Exception => extractTypeSymbol(tpe)

  // ── Occurrence conversion ──────────────────────────────────────────────

  private def convertOccurrence(occ: SdbOcc, uri: String): SemOccurrence =
    val range = occ.range match
      case Some(r) => SemRange(r.startLine, r.startCharacter, r.endLine, r.endCharacter)
      case None    => SemRange(0, 0, 0, 0)
    SemOccurrence(
      file = uri,
      range = range,
      symbol = occ.symbol,
      role = OccRole.fromSdb(occ.role),
    )

  // ── Diagnostic conversion ──────────────────────────────────────────────

  private def convertDiagnostic(diag: SdbDiag, uri: String): SemDiagnostic =
    val range = diag.range match
      case Some(r) => SemRange(r.startLine, r.startCharacter, r.endLine, r.endCharacter)
      case None    => SemRange(0, 0, 0, 0)
    val severity = diag.severity match
      case SdbDiag.Severity.ERROR       => "error"
      case SdbDiag.Severity.WARNING     => "warning"
      case SdbDiag.Severity.INFORMATION => "info"
      case SdbDiag.Severity.HINT        => "hint"
      case _                            => "unknown"
    SemDiagnostic(
      file = uri,
      range = range,
      severity = severity,
      message = diag.message,
    )
