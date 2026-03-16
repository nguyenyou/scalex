//> using scala 3.8.2
//> using dep org.scalameta::scalameta:4.15.2
//> using dep com.google.guava:guava:33.5.0-jre

import scala.meta.*
import scala.collection.mutable
import java.nio.file.{Files, Path}
import java.io.{BufferedReader, BufferedInputStream, BufferedOutputStream,
  DataInputStream, DataOutputStream, InputStreamReader}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*
import com.google.common.hash.{BloomFilter, Funnels}

val ScalexVersion = "1.11.0"

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
    aliases: Map[String, String] = Map.empty
)

enum RefCategory:
  case Definition, ExtendedBy, ImportedBy, UsedAsType, Comment, Usage

case class CategorizedRef(ref: Reference, category: RefCategory)

enum Confidence:
  case High, Medium, Low

// ── Git ─────────────────────────────────────────────────────────────────────

def gitLsFiles(workspace: Path): List[GitFile] =
  val pb = ProcessBuilder("git", "ls-files", "--stage")
  pb.directory(workspace.toFile)
  pb.redirectErrorStream(true)
  val proc = pb.start()
  val reader = BufferedReader(InputStreamReader(proc.getInputStream))
  val files = reader.lines().iterator().asScala.flatMap { line =>
    val tabIdx = line.indexOf('\t')
    if tabIdx < 0 then None
    else
      val parts = line.substring(0, tabIdx).split("\\s+")
      val path = line.substring(tabIdx + 1)
      if parts.length >= 2 && path.endsWith(".scala") then
        Some(GitFile(workspace.resolve(path), parts(1)))
      else None
  }.toList
  proc.waitFor()
  files

// ── Symbol extraction + bloom filter ────────────────────────────────────────

def buildBloomFilterFromSource(source: String): BloomFilter[CharSequence] =
  val expected = math.max(500, source.length / 15)
  val bloom = BloomFilter.create(Funnels.unencodedCharsFunnel(), expected, 0.01)
  var i = 0
  val len = source.length
  while i < len do
    if source(i).isLetter || source(i) == '_' then
      val start = i
      while i < len && (source(i).isLetterOrDigit || source(i) == '_') do i += 1
      val word = source.substring(start, i)
      if word.length >= 2 then bloom.put(word)
    else
      i += 1
  bloom

private def extractParents(templ: Template): List[String] =
  templ.inits.flatMap { init =>
    init.tpe match
      case Type.Name(name) => Some(name)
      case Type.Select(_, Type.Name(name)) => Some(name)
      case Type.Apply.After_4_6_0(Type.Name(name), _) => Some(name)
      case Type.Apply.After_4_6_0(Type.Select(_, Type.Name(name)), _) => Some(name)
      case _ => None
  }

private def buildSignature(name: String, kind: String, parents: List[String], tparams: List[String] = Nil): String =
  val tps = if tparams.nonEmpty then tparams.mkString("[", ", ", "]") else ""
  val ext = if parents.nonEmpty then s" extends ${parents.mkString(" with ")}" else ""
  s"$kind $name$tps$ext"

private def extractAnnotations(mods: List[Mod]): List[String] =
  mods.collect { case Mod.Annot(init) =>
    init.tpe match
      case Type.Name(name) => name
      case Type.Select(_, Type.Name(name)) => name
      case _ => init.tpe.toString()
  }

private def extractImports(tree: Tree): (List[String], Map[String, String]) =
  val buf = mutable.ListBuffer.empty[String]
  val aliases = mutable.Map.empty[String, String]
  def visit(t: Tree): Unit =
    t match
      case i: Import =>
        buf += i.toString()
        i.importers.foreach { importer =>
          importer.importees.foreach {
            case r: Importee.Rename => aliases(r.name.value) = r.rename.value
            case _ =>
          }
        }
      case _ =>
    t.children.foreach(visit)
  visit(tree)
  (buf.toList, aliases.toMap)

def extractSymbols(file: Path): (List[SymbolInfo], BloomFilter[CharSequence], List[String], Map[String, String]) =
  val source = try Files.readString(file) catch
    case _: Exception =>
      val bloom = BloomFilter.create(Funnels.unencodedCharsFunnel(), 500, 0.01)
      return (Nil, bloom, Nil, Map.empty)

  val bloom = buildBloomFilterFromSource(source)

  val input = Input.VirtualFile(file.toString, source)
  val tree = try
    given scala.meta.Dialect = scala.meta.dialects.Scala3
    input.parse[Source].get
  catch
    case _: Exception =>
      try
        given scala.meta.Dialect = scala.meta.dialects.Scala213
        input.parse[Source].get
      catch
        case _: Exception => return (Nil, bloom, Nil, Map.empty)

  val pkg = tree.children.collectFirst { case p: Pkg => p.ref.toString() }.getOrElse("")
  val (imports, aliases) = extractImports(tree)
  val buf = mutable.ListBuffer.empty[SymbolInfo]

  def visit(t: Tree): Unit = t match
    case d: Defn.Class =>
      val parents = extractParents(d.templ)
      val tparams = d.tparamClause.values.map(_.name.value)
      val sig = buildSignature(d.name.value, "class", parents, tparams)
      val annots = extractAnnotations(d.mods)
      buf += SymbolInfo(d.name.value, SymbolKind.Class, file, d.pos.startLine + 1, pkg, parents, sig, annots)
    case d: Defn.Trait =>
      val parents = extractParents(d.templ)
      val tparams = d.tparamClause.values.map(_.name.value)
      val sig = buildSignature(d.name.value, "trait", parents, tparams)
      val annots = extractAnnotations(d.mods)
      buf += SymbolInfo(d.name.value, SymbolKind.Trait, file, d.pos.startLine + 1, pkg, parents, sig, annots)
    case d: Defn.Object =>
      val parents = extractParents(d.templ)
      val sig = buildSignature(d.name.value, "object", parents)
      val annots = extractAnnotations(d.mods)
      buf += SymbolInfo(d.name.value, SymbolKind.Object, file, d.pos.startLine + 1, pkg, parents, sig, annots)
    case d: Defn.Enum =>
      val parents = extractParents(d.templ)
      val tparams = d.tparamClause.values.map(_.name.value)
      val sig = buildSignature(d.name.value, "enum", parents, tparams)
      val annots = extractAnnotations(d.mods)
      buf += SymbolInfo(d.name.value, SymbolKind.Enum, file, d.pos.startLine + 1, pkg, parents, sig, annots)
    case d: Defn.Given =>
      if d.name.value.nonEmpty then
        val annots = extractAnnotations(d.mods)
        buf += SymbolInfo(d.name.value, SymbolKind.Given, file, d.pos.startLine + 1, pkg, Nil, s"given ${d.name.value}", annots)
    case d: Defn.GivenAlias =>
      if d.name.value.nonEmpty then
        val sig = s"given ${d.name.value}: ${d.decltpe.toString()}"
        val annots = extractAnnotations(d.mods)
        buf += SymbolInfo(d.name.value, SymbolKind.Given, file, d.pos.startLine + 1, pkg, Nil, sig, annots)
    case d: Defn.Type =>
      val sig = s"type ${d.name.value} = ${d.body.toString().take(60)}"
      val annots = extractAnnotations(d.mods)
      buf += SymbolInfo(d.name.value, SymbolKind.Type, file, d.pos.startLine + 1, pkg, Nil, sig, annots)
    case d: Defn.Def =>
      val params = d.paramClauses.map(_.values.map(p => s"${p.name.value}: ${p.decltpe.map(_.toString()).getOrElse("?")}").mkString(", ")).mkString("(", ")(", ")")
      val ret = d.decltpe.map(t => s": ${t.toString()}").getOrElse("")
      val sig = s"def ${d.name.value}$params$ret"
      val annots = extractAnnotations(d.mods)
      buf += SymbolInfo(d.name.value, SymbolKind.Def, file, d.pos.startLine + 1, pkg, Nil, sig, annots)
    case d: Defn.Val =>
      val annots = extractAnnotations(d.mods)
      d.pats.foreach {
        case Pat.Var(name) =>
          val tpe = d.decltpe.map(t => s": ${t.toString()}").getOrElse("")
          buf += SymbolInfo(name.value, SymbolKind.Val, file, d.pos.startLine + 1, pkg, Nil, s"val ${name.value}$tpe", annots)
        case _ =>
      }
    case d: Defn.ExtensionGroup =>
      val recv = d.paramClauses.headOption.flatMap(_.values.headOption).map(p =>
        s"(${p.name.value}: ${p.decltpe.map(_.toString()).getOrElse("?")})"
      ).getOrElse("")
      buf += SymbolInfo("<extension>", SymbolKind.Extension, file, d.pos.startLine + 1, pkg, Nil, s"extension $recv")
    case _ =>

  def traverse(t: Tree): Unit =
    visit(t)
    t.children.foreach(traverse)

  traverse(tree)
  (buf.toList, bloom, imports, aliases)

// ── Source parsing helper ────────────────────────────────────────────────────

def parseFile(path: Path): Option[Source] =
  val source = try Files.readString(path) catch
    case _: Exception => return None
  val input = Input.VirtualFile(path.toString, source)
  try
    given scala.meta.Dialect = scala.meta.dialects.Scala3
    Some(input.parse[Source].get)
  catch
    case _: Exception =>
      try
        given scala.meta.Dialect = scala.meta.dialects.Scala213
        Some(input.parse[Source].get)
      catch
        case _: Exception => None

// ── Member extraction ───────────────────────────────────────────────────────

case class MemberInfo(name: String, kind: SymbolKind, line: Int, signature: String = "", annotations: List[String] = Nil)

case class BodyInfo(ownerName: String, symbolName: String, sourceText: String, startLine: Int, endLine: Int)

case class HierarchyNode(name: String, kind: Option[SymbolKind], file: Option[Path], line: Option[Int], packageName: String, isExternal: Boolean)
case class HierarchyTree(root: HierarchyNode, parents: List[HierarchyTree], children: List[HierarchyTree])

case class OverrideInfo(file: Path, line: Int, enclosingClass: String, enclosingKind: SymbolKind, signature: String, packageName: String)

case class ScopeInfo(name: String, kind: String, line: Int)

case class DiffSymbol(name: String, kind: SymbolKind, file: String, line: Int, packageName: String, signature: String)

def extractMembers(file: Path, symbolName: String): List[MemberInfo] =
  parseFile(file) match
    case None => Nil
    case Some(tree) =>
      val buf = mutable.ListBuffer.empty[MemberInfo]

      def extractFromTemplate(templ: Template): Unit =
        templ.stats.foreach {
          case d: Defn.Def =>
            val params = d.paramClauses.map(_.values.map(p => s"${p.name.value}: ${p.decltpe.map(_.toString()).getOrElse("?")}").mkString(", ")).mkString("(", ")(", ")")
            val ret = d.decltpe.map(t => s": ${t.toString()}").getOrElse("")
            val annots = extractAnnotations(d.mods)
            buf += MemberInfo(d.name.value, SymbolKind.Def, d.pos.startLine + 1, s"def ${d.name.value}$params$ret", annots)
          case d: Defn.Val =>
            val annots = extractAnnotations(d.mods)
            d.pats.foreach {
              case Pat.Var(name) =>
                val tpe = d.decltpe.map(t => s": ${t.toString()}").getOrElse("")
                buf += MemberInfo(name.value, SymbolKind.Val, d.pos.startLine + 1, s"val ${name.value}$tpe", annots)
              case _ =>
            }
          case d: Defn.Var =>
            val annots = extractAnnotations(d.mods)
            d.pats.foreach {
              case Pat.Var(name) =>
                val tpe = d.decltpe.map(t => s": ${t.toString()}").getOrElse("")
                buf += MemberInfo(name.value, SymbolKind.Var, d.pos.startLine + 1, s"var ${name.value}$tpe", annots)
              case _ =>
            }
          case d: Defn.Type =>
            val annots = extractAnnotations(d.mods)
            buf += MemberInfo(d.name.value, SymbolKind.Type, d.pos.startLine + 1, s"type ${d.name.value} = ${d.body.toString().take(60)}", annots)
          case d: Decl.Def =>
            val params = d.paramClauses.map(_.values.map(p => s"${p.name.value}: ${p.decltpe.map(_.toString()).getOrElse("?")}").mkString(", ")).mkString("(", ")(", ")")
            val ret = s": ${d.decltpe.toString()}"
            val annots = extractAnnotations(d.mods)
            buf += MemberInfo(d.name.value, SymbolKind.Def, d.pos.startLine + 1, s"def ${d.name.value}$params$ret", annots)
          case d: Decl.Val =>
            val annots = extractAnnotations(d.mods)
            d.pats.foreach {
              case p: Pat.Var =>
                val tpe = s": ${d.decltpe.toString()}"
                buf += MemberInfo(p.name.value, SymbolKind.Val, d.pos.startLine + 1, s"val ${p.name.value}$tpe", annots)
              case _ =>
            }
          case d: Decl.Type =>
            val annots = extractAnnotations(d.mods)
            buf += MemberInfo(d.name.value, SymbolKind.Type, d.pos.startLine + 1, s"type ${d.name.value}", annots)
          case d: Defn.Class =>
            val annots = extractAnnotations(d.mods)
            buf += MemberInfo(d.name.value, SymbolKind.Class, d.pos.startLine + 1, s"class ${d.name.value}", annots)
          case d: Defn.Trait =>
            val annots = extractAnnotations(d.mods)
            buf += MemberInfo(d.name.value, SymbolKind.Trait, d.pos.startLine + 1, s"trait ${d.name.value}", annots)
          case d: Defn.Object =>
            val annots = extractAnnotations(d.mods)
            buf += MemberInfo(d.name.value, SymbolKind.Object, d.pos.startLine + 1, s"object ${d.name.value}", annots)
          case d: Defn.Enum =>
            val annots = extractAnnotations(d.mods)
            buf += MemberInfo(d.name.value, SymbolKind.Enum, d.pos.startLine + 1, s"enum ${d.name.value}", annots)
          case _ =>
        }

      def findAndExtract(t: Tree): Unit = t match
        case d: Defn.Class if d.name.value == symbolName => extractFromTemplate(d.templ)
        case d: Defn.Trait if d.name.value == symbolName => extractFromTemplate(d.templ)
        case d: Defn.Object if d.name.value == symbolName => extractFromTemplate(d.templ)
        case d: Defn.Enum if d.name.value == symbolName => extractFromTemplate(d.templ)
        case _ => t.children.foreach(findAndExtract)

      findAndExtract(tree)
      buf.toList

// ── Scaladoc extraction ─────────────────────────────────────────────────────

def extractScaladoc(file: Path, targetLine: Int): Option[String] =
  val lines = try Files.readAllLines(file).asScala.toArray catch
    case _: Exception => return None
  // targetLine is 1-indexed, array is 0-indexed
  var i = targetLine - 2 // line before the symbol
  // skip blank lines between doc and symbol
  while i >= 0 && lines(i).trim.isEmpty do i -= 1
  if i < 0 then return None
  // Check if this line ends a scaladoc
  val endLine = i
  if lines(endLine).trim == "*/" || lines(endLine).trim.endsWith("*/") then
    // Multi-line or single-line: find the opening /**
    while i >= 0 && !lines(i).trim.startsWith("/**") do i -= 1
    if i >= 0 then Some((i to endLine).map(lines(_)).mkString("\n"))
    else None
  else if lines(endLine).trim.startsWith("/**") && lines(endLine).trim.endsWith("*/") then
    // Single-line /** brief */
    Some(lines(endLine))
  else None

// ── Body extraction ─────────────────────────────────────────────────────────

def extractBody(file: Path, symbolName: String, ownerName: Option[String]): List[BodyInfo] = {
  val lines = try Files.readAllLines(file).asScala.toArray catch
    case _: Exception => return Nil
  parseFile(file) match
    case None => Nil
    case Some(tree) =>
      val buf = mutable.ListBuffer.empty[BodyInfo]

      def extractFromTree(t: Tree, currentOwner: String): Unit = {
        t match
          case d: Defn.Def if d.name.value == symbolName =>
            if ownerName.isEmpty || ownerName.contains(currentOwner) then
              val sl = d.pos.startLine
              val el = d.pos.endLine
              val body = (sl to el).map(lines(_)).mkString("\n")
              buf += BodyInfo(currentOwner, d.name.value, body, sl + 1, el + 1)
          case d: Defn.Val =>
            d.pats.foreach {
              case Pat.Var(name) if name.value == symbolName =>
                if ownerName.isEmpty || ownerName.contains(currentOwner) then
                  val sl = d.pos.startLine
                  val el = d.pos.endLine
                  val body = (sl to el).map(lines(_)).mkString("\n")
                  buf += BodyInfo(currentOwner, name.value, body, sl + 1, el + 1)
              case _ =>
            }
          case d: Defn.Var =>
            d.pats.foreach {
              case Pat.Var(name) if name.value == symbolName =>
                if ownerName.isEmpty || ownerName.contains(currentOwner) then
                  val sl = d.pos.startLine
                  val el = d.pos.endLine
                  val body = (sl to el).map(lines(_)).mkString("\n")
                  buf += BodyInfo(currentOwner, name.value, body, sl + 1, el + 1)
              case _ =>
            }
          case d: Defn.Type if d.name.value == symbolName =>
            if ownerName.isEmpty || ownerName.contains(currentOwner) then
              val sl = d.pos.startLine
              val el = d.pos.endLine
              val body = (sl to el).map(lines(_)).mkString("\n")
              buf += BodyInfo(currentOwner, d.name.value, body, sl + 1, el + 1)
          case d: Defn.Class =>
            if d.name.value == symbolName && (ownerName.isEmpty || ownerName.contains(currentOwner)) then
              val sl = d.pos.startLine
              val el = d.pos.endLine
              val body = (sl to el).map(lines(_)).mkString("\n")
              buf += BodyInfo(currentOwner, d.name.value, body, sl + 1, el + 1)
            d.templ.stats.foreach(s => extractFromTree(s, d.name.value))
          case d: Defn.Trait =>
            if d.name.value == symbolName && (ownerName.isEmpty || ownerName.contains(currentOwner)) then
              val sl = d.pos.startLine
              val el = d.pos.endLine
              val body = (sl to el).map(lines(_)).mkString("\n")
              buf += BodyInfo(currentOwner, d.name.value, body, sl + 1, el + 1)
            d.templ.stats.foreach(s => extractFromTree(s, d.name.value))
          case d: Defn.Object =>
            if d.name.value == symbolName && (ownerName.isEmpty || ownerName.contains(currentOwner)) then
              val sl = d.pos.startLine
              val el = d.pos.endLine
              val body = (sl to el).map(lines(_)).mkString("\n")
              buf += BodyInfo(currentOwner, d.name.value, body, sl + 1, el + 1)
            d.templ.stats.foreach(s => extractFromTree(s, d.name.value))
          case d: Defn.Enum =>
            if d.name.value == symbolName && (ownerName.isEmpty || ownerName.contains(currentOwner)) then
              val sl = d.pos.startLine
              val el = d.pos.endLine
              val body = (sl to el).map(lines(_)).mkString("\n")
              buf += BodyInfo(currentOwner, d.name.value, body, sl + 1, el + 1)
            d.templ.stats.foreach(s => extractFromTree(s, d.name.value))
          case p: Pkg =>
            p.stats.foreach(s => extractFromTree(s, currentOwner))
          case _ =>
      }

      tree.children.foreach(c => extractFromTree(c, ""))
      buf.toList
}

// ── Hierarchy building ──────────────────────────────────────────────────────

def buildHierarchy(idx: WorkspaceIndex, symbolName: String, goUp: Boolean, goDown: Boolean, workspace: Path): Option[HierarchyTree] = {
  val defs = idx.findDefinition(symbolName)
  if defs.isEmpty then return None

  val sym = defs.head
  val rootNode = HierarchyNode(sym.name, Some(sym.kind), Some(sym.file), Some(sym.line), sym.packageName, isExternal = false)

  def walkUp(name: String, visited: Set[String]): List[HierarchyTree] = {
    if visited.contains(name.toLowerCase) then return Nil
    val newVisited = visited + name.toLowerCase
    val defs = idx.findDefinition(name)
    if defs.isEmpty then Nil
    else {
      val s = defs.head
      s.parents.map { parentName =>
        val parentDefs = idx.findDefinition(parentName)
        if parentDefs.isEmpty then {
          val extNode = HierarchyNode(parentName, None, None, None, "", isExternal = true)
          HierarchyTree(extNode, Nil, Nil)
        } else {
          val pd = parentDefs.head
          val pNode = HierarchyNode(pd.name, Some(pd.kind), Some(pd.file), Some(pd.line), pd.packageName, isExternal = false)
          val grandParents = walkUp(pd.name, newVisited)
          HierarchyTree(pNode, grandParents, Nil)
        }
      }
    }
  }

  def walkDown(name: String, visited: Set[String]): List[HierarchyTree] = {
    if visited.contains(name.toLowerCase) then return Nil
    val newVisited = visited + name.toLowerCase
    val impls = idx.findImplementations(name)
    impls.map { s =>
      val node = HierarchyNode(s.name, Some(s.kind), Some(s.file), Some(s.line), s.packageName, isExternal = false)
      val grandChildren = walkDown(s.name, newVisited)
      HierarchyTree(node, Nil, grandChildren)
    }
  }

  val parents = if goUp then walkUp(sym.name, Set(sym.name.toLowerCase)) else Nil
  val children = if goDown then walkDown(sym.name, Set(sym.name.toLowerCase)) else Nil
  Some(HierarchyTree(rootNode, parents, children))
}

// ── Override finding ────────────────────────────────────────────────────────

def findOverrides(idx: WorkspaceIndex, methodName: String, ofTrait: Option[String], limit: Int): List[OverrideInfo] = {
  val buf = mutable.ListBuffer.empty[OverrideInfo]
  val typeKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)

  ofTrait match
    case Some(traitName) =>
      val impls = idx.findImplementations(traitName)
      impls.foreach { s =>
        if typeKinds.contains(s.kind) then {
          val members = extractMembers(s.file, s.name)
          members.filter(_.name == methodName).foreach { m =>
            buf += OverrideInfo(s.file, m.line, s.name, s.kind, m.signature, s.packageName)
          }
        }
      }
    case None =>
      // Find the method's defining type first
      val methodDefs = idx.findDefinition(methodName).filter(s => s.kind == SymbolKind.Def || s.kind == SymbolKind.Val)
      // Also search all types that have a member with this name
      val allTypes = idx.symbols.filter(s => typeKinds.contains(s.kind))
      val iter = allTypes.iterator
      while iter.hasNext && buf.size < limit do {
        val s = iter.next()
        val members = extractMembers(s.file, s.name)
        members.filter(_.name == methodName).foreach { m =>
          buf += OverrideInfo(s.file, m.line, s.name, s.kind, m.signature, s.packageName)
        }
      }

  buf.toList
}

// ── Scope extraction (context) ──────────────────────────────────────────────

def extractScopes(file: Path, targetLine: Int): List[ScopeInfo] = {
  parseFile(file) match
    case None => Nil
    case Some(tree) =>
      val buf = mutable.ListBuffer.empty[ScopeInfo]

      def visit(t: Tree): Unit = {
        val startLine = t.pos.startLine + 1
        val endLine = t.pos.endLine + 1
        if targetLine >= startLine && targetLine <= endLine then {
          t match
            case d: Pkg =>
              buf += ScopeInfo(d.ref.toString(), "package", startLine)
            case d: Defn.Class =>
              buf += ScopeInfo(d.name.value, "class", startLine)
            case d: Defn.Trait =>
              buf += ScopeInfo(d.name.value, "trait", startLine)
            case d: Defn.Object =>
              buf += ScopeInfo(d.name.value, "object", startLine)
            case d: Defn.Enum =>
              buf += ScopeInfo(d.name.value, "enum", startLine)
            case d: Defn.Def =>
              buf += ScopeInfo(d.name.value, "def", startLine)
            case d: Defn.Val =>
              d.pats.foreach {
                case Pat.Var(name) => buf += ScopeInfo(name.value, "val", startLine)
                case _ =>
              }
            case d: Defn.ExtensionGroup =>
              buf += ScopeInfo("<extension>", "extension", startLine)
            case _ =>
          t.children.foreach(visit)
        }
      }

      visit(tree)
      buf.toList
}

// ── Dependency extraction ───────────────────────────────────────────────────

case class DepInfo(name: String, kind: String, file: Option[Path], line: Option[Int], packageName: String)

def extractDeps(idx: WorkspaceIndex, symbolName: String, workspace: Path): (List[DepInfo], List[DepInfo]) = {
  val defs = idx.findDefinition(symbolName)
  if defs.isEmpty then return (Nil, Nil)

  val sym = defs.head
  val importDeps = mutable.ListBuffer.empty[DepInfo]
  val bodyDeps = mutable.ListBuffer.empty[DepInfo]
  val seenNames = mutable.HashSet.empty[String]

  parseFile(sym.file) match
    case None => (Nil, Nil)
    case Some(tree) =>
      // Find the target symbol's AST node and extract info
      def findNode(t: Tree): Option[Tree] = {
        t match
          case d: Defn.Class if d.name.value == symbolName => Some(d)
          case d: Defn.Trait if d.name.value == symbolName => Some(d)
          case d: Defn.Object if d.name.value == symbolName => Some(d)
          case d: Defn.Enum if d.name.value == symbolName => Some(d)
          case d: Defn.Def if d.name.value == symbolName => Some(d)
          case _ =>
            var result: Option[Tree] = None
            t.children.foreach { c =>
              if result.isEmpty then result = findNode(c)
            }
            result
      }

      // Collect imports at the file level
      def collectImports(t: Tree): Unit = {
        t match
          case i: Import =>
            i.importers.foreach { importer =>
              importer.importees.foreach {
                case importee: Importee.Name =>
                  val iname = importee.name.value
                  if !seenNames.contains(iname) then {
                    seenNames += iname
                    val found = idx.findDefinition(iname)
                    if found.nonEmpty then {
                      val f = found.head
                      importDeps += DepInfo(f.name, f.kind.toString.toLowerCase, Some(f.file), Some(f.line), f.packageName)
                    }
                  }
                case importee: Importee.Rename =>
                  val iname = importee.name.value
                  if !seenNames.contains(iname) then {
                    seenNames += iname
                    val found = idx.findDefinition(iname)
                    if found.nonEmpty then {
                      val f = found.head
                      importDeps += DepInfo(f.name, f.kind.toString.toLowerCase, Some(f.file), Some(f.line), f.packageName)
                    }
                  }
                case _ =>
              }
            }
          case _ =>
        t.children.foreach(collectImports)
      }
      collectImports(tree)

      // Collect type/term references in the symbol's body
      findNode(tree).foreach { node =>
        def collectRefs(t: Tree): Unit = {
          t match
            case Type.Name(name) if name != symbolName && !seenNames.contains(name) =>
              seenNames += name
              val found = idx.findDefinition(name)
              if found.nonEmpty then {
                val f = found.head
                bodyDeps += DepInfo(f.name, f.kind.toString.toLowerCase, Some(f.file), Some(f.line), f.packageName)
              }
            case Term.Name(name) if name != symbolName && !seenNames.contains(name) =>
              seenNames += name
              val found = idx.findDefinition(name)
              if found.nonEmpty then {
                val f = found.head
                bodyDeps += DepInfo(f.name, f.kind.toString.toLowerCase, Some(f.file), Some(f.line), f.packageName)
              }
            case _ =>
          t.children.foreach(collectRefs)
        }
        collectRefs(node)
      }

      (importDeps.toList, bodyDeps.toList)
}

// ── Diff extraction ─────────────────────────────────────────────────────────

def runGitDiff(workspace: Path, ref: String): List[String] = {
  val pb = ProcessBuilder("git", "diff", "--name-only", ref)
  pb.directory(workspace.toFile)
  pb.redirectErrorStream(true)
  val proc = pb.start()
  val reader = BufferedReader(InputStreamReader(proc.getInputStream))
  val files = reader.lines().iterator().asScala.filter(_.endsWith(".scala")).toList
  proc.waitFor()
  files
}

def gitShowFile(workspace: Path, ref: String, relPath: String): Option[String] = {
  try {
    val pb = ProcessBuilder("git", "show", s"$ref:$relPath")
    pb.directory(workspace.toFile)
    pb.redirectErrorStream(false)
    val proc = pb.start()
    val reader = BufferedReader(InputStreamReader(proc.getInputStream))
    val content = reader.lines().iterator().asScala.mkString("\n")
    val exitCode = proc.waitFor()
    if exitCode == 0 then Some(content) else None
  } catch {
    case _: Exception => None
  }
}

def extractSymbolsFromSource(source: String, filePath: String): List[DiffSymbol] = {
  val input = Input.VirtualFile(filePath, source)
  val tree = try {
    given scala.meta.Dialect = scala.meta.dialects.Scala3
    input.parse[Source].get
  } catch {
    case _: Exception =>
      try {
        given scala.meta.Dialect = scala.meta.dialects.Scala213
        input.parse[Source].get
      } catch {
        case _: Exception => return Nil
      }
  }

  val pkg = tree.children.collectFirst { case p: Pkg => p.ref.toString() }.getOrElse("")
  val buf = mutable.ListBuffer.empty[DiffSymbol]

  def visit(t: Tree): Unit = {
    t match
      case d: Defn.Class =>
        buf += DiffSymbol(d.name.value, SymbolKind.Class, filePath, d.pos.startLine + 1, pkg, s"class ${d.name.value}")
      case d: Defn.Trait =>
        buf += DiffSymbol(d.name.value, SymbolKind.Trait, filePath, d.pos.startLine + 1, pkg, s"trait ${d.name.value}")
      case d: Defn.Object =>
        buf += DiffSymbol(d.name.value, SymbolKind.Object, filePath, d.pos.startLine + 1, pkg, s"object ${d.name.value}")
      case d: Defn.Enum =>
        buf += DiffSymbol(d.name.value, SymbolKind.Enum, filePath, d.pos.startLine + 1, pkg, s"enum ${d.name.value}")
      case d: Defn.Def =>
        buf += DiffSymbol(d.name.value, SymbolKind.Def, filePath, d.pos.startLine + 1, pkg, s"def ${d.name.value}")
      case d: Defn.Val =>
        d.pats.foreach {
          case Pat.Var(name) =>
            buf += DiffSymbol(name.value, SymbolKind.Val, filePath, d.pos.startLine + 1, pkg, s"val ${name.value}")
          case _ =>
        }
      case d: Defn.Type =>
        buf += DiffSymbol(d.name.value, SymbolKind.Type, filePath, d.pos.startLine + 1, pkg, s"type ${d.name.value}")
      case _ =>
  }

  def traverse(t: Tree): Unit = {
    visit(t)
    t.children.foreach(traverse)
  }

  traverse(tree)
  buf.toList
}

// ── AST pattern matching ────────────────────────────────────────────────────

case class AstPatternMatch(name: String, kind: SymbolKind, file: Path, line: Int, packageName: String, signature: String)

def astPatternSearch(idx: WorkspaceIndex, workspace: Path,
                     hasMethod: Option[String], extendsTrait: Option[String],
                     bodyContains: Option[String], noTests: Boolean,
                     pathFilter: Option[String], limit: Int): List[AstPatternMatch] = {
  val typeKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
  var candidates = idx.symbols.filter(s => typeKinds.contains(s.kind))
  if noTests then candidates = candidates.filter(s => !isTestFile(s.file, workspace))
  pathFilter.foreach { p => candidates = candidates.filter(s => matchesPath(s.file, p, workspace)) }

  // Filter by extends
  extendsTrait.foreach { traitName =>
    candidates = candidates.filter(_.parents.exists(_.equalsIgnoreCase(traitName)))
  }

  val buf = mutable.ListBuffer.empty[AstPatternMatch]

  val iter = candidates.iterator
  while iter.hasNext && buf.size < limit do {
    val s = iter.next()
    var matches = true

    // Filter by has-method
    hasMethod.foreach { methodName =>
      val members = extractMembers(s.file, s.name)
      if !members.exists(_.name == methodName) then matches = false
    }

    // Filter by body-contains
    bodyContains.foreach { pattern =>
      if matches then {
        val bodies = extractBody(s.file, s.name, None)
        if !bodies.exists(_.sourceText.contains(pattern)) then matches = false
      }
    }

    if matches then {
      buf += AstPatternMatch(s.name, s.kind, s.file, s.line, s.packageName, s.signature)
    }
  }
  buf.toList
}

// ── Binary persistence ──────────────────────────────────────────────────────

object IndexPersistence:
  private val MAGIC = 0x53584458
  private val VERSION: Byte = 5

  def indexPath(workspace: Path): Path = workspace.resolve(".scalex").resolve("index.bin")

  def save(workspace: Path, files: List[IndexedFile]): Unit =
    val dir = workspace.resolve(".scalex")
    if !Files.exists(dir) then Files.createDirectories(dir)

    val stringTable = mutable.LinkedHashMap.empty[String, Int]
    def intern(s: String): Int =
      stringTable.getOrElseUpdate(s, stringTable.size)

    files.foreach { f =>
      intern(f.relativePath)
      intern(f.oid)
      f.symbols.foreach { s =>
        intern(s.name)
        intern(s.packageName)
        intern(s.signature)
        s.parents.foreach(intern)
        s.annotations.foreach(intern)
      }
      f.imports.foreach(intern)
      f.aliases.foreach { (k, v) => intern(k); intern(v) }
    }

    val out = DataOutputStream(BufferedOutputStream(Files.newOutputStream(indexPath(workspace)), 1 << 16))
    try
      out.writeInt(MAGIC)
      out.writeByte(VERSION)

      val strings = stringTable.keys.toArray
      out.writeInt(strings.length)
      strings.foreach(out.writeUTF)

      out.writeInt(files.size)
      files.foreach { f =>
        out.writeInt(intern(f.relativePath))
        out.writeInt(intern(f.oid))

        out.writeShort(f.symbols.size)
        f.symbols.foreach { s =>
          out.writeInt(intern(s.name))
          out.writeByte(s.kind.id)
          out.writeInt(s.line)
          out.writeInt(intern(s.packageName))
          out.writeInt(intern(s.signature))
          out.writeShort(s.parents.size)
          s.parents.foreach(p => out.writeInt(intern(p)))
          out.writeShort(s.annotations.size)
          s.annotations.foreach(a => out.writeInt(intern(a)))
        }

        // Imports
        out.writeShort(f.imports.size)
        f.imports.foreach(i => out.writeInt(intern(i)))

        // Aliases
        out.writeShort(f.aliases.size)
        f.aliases.foreach { (k, v) =>
          out.writeInt(intern(k))
          out.writeInt(intern(v))
        }

        // Bloom filter
        val bloomBytes = java.io.ByteArrayOutputStream()
        f.identifierBloom.writeTo(bloomBytes)
        val ba = bloomBytes.toByteArray
        out.writeInt(ba.length)
        out.write(ba)
      }
    finally out.close()

  def load(workspace: Path, loadBlooms: Boolean = true): Option[Map[String, IndexedFile]] =
    val p = indexPath(workspace)
    if !Files.exists(p) then return None

    try
      val in = DataInputStream(BufferedInputStream(Files.newInputStream(p), 1 << 16))
      try
        val magic = in.readInt()
        if magic != MAGIC then return None
        val version = in.readByte()
        if version != VERSION then return None

        val strCount = in.readInt()
        val strings = Array.fill(strCount)(in.readUTF())

        val fileCount = in.readInt()
        val result = mutable.HashMap.empty[String, IndexedFile]

        var fi = 0
        while fi < fileCount do
          val relPath = strings(in.readInt())
          val oid = strings(in.readInt())

          val symCount = in.readShort()
          val syms = List.newBuilder[SymbolInfo]
          var si = 0
          while si < symCount do
            val name = strings(in.readInt())
            val kind = SymbolKind.fromId(in.readByte())
            val line = in.readInt()
            val pkg = strings(in.readInt())
            val sig = strings(in.readInt())
            val parentCount = in.readShort()
            val parents = (0 until parentCount).map(_ => strings(in.readInt())).toList
            val annotCount = in.readShort()
            val annots = (0 until annotCount).map(_ => strings(in.readInt())).toList
            syms += SymbolInfo(name, kind, workspace.resolve(relPath), line, pkg, parents, sig, annots)
            si += 1

          // Imports
          val importCount = in.readShort()
          val imports = (0 until importCount).map(_ => strings(in.readInt())).toList

          // Aliases
          val aliasCount = in.readShort()
          val aliases = (0 until aliasCount).map { _ =>
            val k = strings(in.readInt())
            val v = strings(in.readInt())
            k -> v
          }.toMap

          // Bloom filter
          val bloomLen = in.readInt()
          val bloom = if loadBlooms then
            val bloomBytes = new Array[Byte](bloomLen)
            in.readFully(bloomBytes)
            BloomFilter.readFrom(
              java.io.ByteArrayInputStream(bloomBytes),
              Funnels.unencodedCharsFunnel()
            )
          else
            in.skipBytes(bloomLen)
            null

          result(relPath) = IndexedFile(relPath, oid, syms.result(), bloom, imports, aliases)
          fi += 1

        Some(result.toMap)
      finally in.close()
    catch
      case _: Exception => None

// ── Workspace index ─────────────────────────────────────────────────────────

class WorkspaceIndex(val workspace: Path, val needBlooms: Boolean = true):
  var symbols: List[SymbolInfo] = Nil
  var filesByPath: Map[Path, List[SymbolInfo]] = Map.empty
  var symbolsByName: Map[String, List[SymbolInfo]] = Map.empty
  var packages: Set[String] = Set.empty
  var gitFiles: List[GitFile] = Nil
  private var indexedFiles: List[IndexedFile] = Nil
  var parentIndex: Map[String, List[SymbolInfo]] = Map.empty

  private var distinctSymbols: List[SymbolInfo] = Nil
  var packageToSymbols: Map[String, Set[String]] = Map.empty
  private var indexedByPath: Map[String, IndexedFile] = Map.empty
  private var aliasIndex: Map[String, List[(IndexedFile, String)]] = Map.empty
  private var annotationIndex: Map[String, List[SymbolInfo]] = Map.empty

  var fileCount: Int = 0
  var indexTimeMs: Long = 0
  var parsedCount: Int = 0
  var skippedCount: Int = 0
  var parseFailures: Int = 0
  var parseFailedFiles: List[String] = Nil
  var cachedLoad: Boolean = false

  def index(): Unit =
    val t0 = System.nanoTime()
    gitFiles = gitLsFiles(workspace)
    fileCount = gitFiles.size

    val cached = IndexPersistence.load(workspace, needBlooms)
    val result = mutable.ListBuffer.empty[IndexedFile]

    cached match
      case Some(cachedMap) =>
        cachedLoad = true
        val toParseQueue = ConcurrentLinkedQueue[IndexedFile]()
        val toParse = mutable.ListBuffer.empty[GitFile]

        gitFiles.foreach { gf =>
          val rel = workspace.relativize(gf.path).toString
          cachedMap.get(rel) match
            case Some(cf) if cf.oid == gf.oid =>
              result += cf
              skippedCount += 1
            case _ =>
              toParse += gf
        }

        toParse.asJava.parallelStream().forEach { gf =>
          val rel = workspace.relativize(gf.path).toString
          val (syms, bloom, imports, aliases) = extractSymbols(gf.path)
          toParseQueue.add(IndexedFile(rel, gf.oid, syms, bloom, imports, aliases))
        }
        result ++= toParseQueue.asScala
        parsedCount = toParse.size

      case None =>
        val queue = ConcurrentLinkedQueue[IndexedFile]()
        gitFiles.asJava.parallelStream().forEach { gf =>
          val rel = workspace.relativize(gf.path).toString
          val (syms, bloom, imports, aliases) = extractSymbols(gf.path)
          queue.add(IndexedFile(rel, gf.oid, syms, bloom, imports, aliases))
        }
        result ++= queue.asScala
        parsedCount = gitFiles.size

    indexedFiles = result.toList
    parseFailedFiles = indexedFiles.collect {
      case f if f.symbols.isEmpty && {
        val p = workspace.resolve(f.relativePath)
        try Files.size(p) > 0 catch case _: Exception => false
      } => f.relativePath
    }
    parseFailures = parseFailedFiles.size
    // Single-pass over symbols: build all symbol-level indexes
    val allSyms = mutable.ListBuffer.empty[SymbolInfo]
    val byPath = mutable.HashMap.empty[Path, mutable.ListBuffer[SymbolInfo]]
    val byName = mutable.HashMap.empty[String, mutable.ListBuffer[SymbolInfo]]
    val pkgs = mutable.HashSet.empty[String]
    val pIdx = mutable.HashMap.empty[String, mutable.ListBuffer[SymbolInfo]]
    val pkgToSyms = mutable.HashMap.empty[String, mutable.HashSet[String]]
    val distinctSeen = mutable.HashSet.empty[(String, Path, Int)]
    val distinctBuf = mutable.ListBuffer.empty[SymbolInfo]
    val aByAnnot = mutable.HashMap.empty[String, mutable.ListBuffer[SymbolInfo]]
    indexedFiles.foreach { f =>
      f.symbols.foreach { s =>
        allSyms += s
        byPath.getOrElseUpdate(s.file, mutable.ListBuffer.empty) += s
        byName.getOrElseUpdate(s.name.toLowerCase, mutable.ListBuffer.empty) += s
        if s.packageName.nonEmpty then pkgs += s.packageName
        s.parents.foreach { p =>
          pIdx.getOrElseUpdate(p.toLowerCase, mutable.ListBuffer.empty) += s
        }
        s.annotations.foreach { a =>
          aByAnnot.getOrElseUpdate(a.toLowerCase, mutable.ListBuffer.empty) += s
        }
        pkgToSyms.getOrElseUpdate(s.packageName, mutable.HashSet.empty) += s.name
        val key = (s.name, s.file, s.line)
        if distinctSeen.add(key) then distinctBuf += s
      }
    }
    symbols = allSyms.toList
    distinctSymbols = distinctBuf.toList
    filesByPath = byPath.map((k, v) => k -> v.toList).toMap
    symbolsByName = byName.map((k, v) => k -> v.toList).toMap
    packages = pkgs.toSet
    parentIndex = pIdx.map((k, v) => k -> v.toList).toMap
    annotationIndex = aByAnnot.map((k, v) => k -> v.toList).toMap
    packageToSymbols = pkgToSyms.map((k, v) => k -> v.toSet).toMap

    // Single-pass over indexedFiles: build file-level indexes
    val iByPath = mutable.HashMap.empty[String, IndexedFile]
    val aIdx = mutable.HashMap.empty[String, mutable.ListBuffer[(IndexedFile, String)]]
    indexedFiles.foreach { f =>
      iByPath(f.relativePath) = f
      f.aliases.foreach { (orig, alias) =>
        aIdx.getOrElseUpdate(orig, mutable.ListBuffer.empty) += ((f, alias))
      }
    }
    indexedByPath = iByPath.toMap
    aliasIndex = aIdx.map((k, v) => k -> v.toList).toMap
    indexTimeMs = (System.nanoTime() - t0) / 1_000_000

    if parsedCount > 0 then
      if !needBlooms then
        // Reload with blooms for newly parsed files that have null blooms
        indexedFiles = indexedFiles.map { f =>
          if f.identifierBloom == null then
            val source = try Files.readString(workspace.resolve(f.relativePath)) catch
              case _: Exception => ""
            f.copy(identifierBloom = buildBloomFilterFromSource(source))
          else f
        }
      IndexPersistence.save(workspace, indexedFiles)

  def findDefinition(name: String): List[SymbolInfo] =
    symbolsByName.getOrElse(name.toLowerCase, Nil)

  def findImplementations(name: String): List[SymbolInfo] =
    parentIndex.getOrElse(name.toLowerCase, Nil)

  def findAnnotated(annotation: String): List[SymbolInfo] =
    annotationIndex.getOrElse(annotation.toLowerCase, Nil)

  def grepFiles(pattern: String, noTests: Boolean, pathFilter: Option[String],
                timeoutMs: Long = defaultTimeoutMs): (List[Reference], Boolean) =
    val regex = try java.util.regex.Pattern.compile(pattern)
    catch
      case e: java.util.regex.PatternSyntaxException =>
        System.err.println(s"Invalid regex: ${e.getMessage}")
        return (Nil, false)
    var candidates = gitFiles
    if noTests then candidates = candidates.filter(gf => !isTestFile(gf.path, workspace))
    pathFilter.foreach { p => candidates = candidates.filter(gf => matchesPath(gf.path, p, workspace)) }
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    var grepTimedOut = false
    val results = ConcurrentLinkedQueue[Reference]()
    candidates.asJava.parallelStream().forEach { gf =>
      if System.nanoTime() < deadline then {
        try {
          val lines = Files.readAllLines(gf.path).asScala
          lines.zipWithIndex.foreach { case (line, idx) =>
            if System.nanoTime() < deadline then {
              if regex.matcher(line).find() then
                results.add(Reference(gf.path, idx + 1, line.trim))
            } else grepTimedOut = true
          }
        } catch { case _: Exception => () }
      } else grepTimedOut = true
    }
    (results.asScala.toList.sortBy(r => (workspace.relativize(r.file).toString, r.line)), grepTimedOut)

  def search(query: String): List[SymbolInfo] =
    val lower = query.toLowerCase
    val exact = mutable.ListBuffer.empty[SymbolInfo]
    val prefix = mutable.ListBuffer.empty[SymbolInfo]
    val contains = mutable.ListBuffer.empty[SymbolInfo]
    val fuzzy = mutable.ListBuffer.empty[SymbolInfo]

    distinctSymbols.foreach { s =>
      val n = s.name.toLowerCase
      if n == lower then exact += s
      else if n.startsWith(lower) then prefix += s
      else if n.contains(lower) then contains += s
      else if camelCaseMatch(lower, s.name) then fuzzy += s
    }
    exact.toList ++ prefix.toList ++ contains.toList ++ fuzzy.sortBy(_.name.length).toList

  def fileSymbols(path: String): List[SymbolInfo] =
    val resolved = if Path.of(path).isAbsolute then Path.of(path)
                   else workspace.resolve(path)
    filesByPath.getOrElse(resolved, Nil)

  def searchFiles(query: String): List[String] =
    val lower = query.toLowerCase
    val exact = mutable.ListBuffer.empty[String]
    val prefix = mutable.ListBuffer.empty[String]
    val contains = mutable.ListBuffer.empty[String]
    val fuzzy = mutable.ListBuffer.empty[String]

    indexedFiles.foreach { f =>
      val fileName = f.relativePath.substring(f.relativePath.lastIndexOf('/') + 1).stripSuffix(".scala")
      val n = fileName.toLowerCase
      if n == lower then exact += f.relativePath
      else if n.startsWith(lower) then prefix += f.relativePath
      else if n.contains(lower) then contains += f.relativePath
      else if camelCaseMatch(lower, fileName) then fuzzy += f.relativePath
    }
    exact.toList ++ prefix.toList ++ contains.toList ++ fuzzy.sortBy(_.length).toList

  private val defaultTimeoutMs = 20_000L
  var timedOut: Boolean = false

  def findReferences(name: String, timeoutMs: Long = defaultTimeoutMs): List[Reference] =
    val candidates = indexedFiles.filter(f => f.identifierBloom == null || f.identifierBloom.mightContain(name))
    val aliasFiles = aliasIndex.getOrElse(name, Nil)
    val candidateSet = candidates.map(_.relativePath).toSet
    val extraFiles = aliasFiles.collect {
      case (f, _) if !candidateSet.contains(f.relativePath) => f
    }
    val allCandidates = candidates ++ extraFiles
    val fileAliasMap = aliasFiles.map((f, alias) => f.relativePath -> alias).toMap

    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    timedOut = false
    val results = ConcurrentLinkedQueue[Reference]()
    val seen = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
    allCandidates.asJava.parallelStream().forEach { idxFile =>
      if System.nanoTime() < deadline then
        val path = workspace.resolve(idxFile.relativePath)
        val lines = try Files.readAllLines(path).asScala catch
          case _: Exception => Seq.empty
        val aliasName = fileAliasMap.get(idxFile.relativePath)
        lines.zipWithIndex.foreach {
          case (line, idx) if System.nanoTime() < deadline =>
            val key = s"${idxFile.relativePath}:${idx + 1}"
            if containsWord(line, name) && seen.add(key) then
              results.add(Reference(path, idx + 1, line.trim))
            else aliasName match
              case Some(alias) if containsWord(line, alias) && seen.add(key) =>
                results.add(Reference(path, idx + 1, line.trim, Some(s"via alias $alias")))
              case _ =>
          case _ =>
        }
      else timedOut = true
    }
    results.asScala.toList

  def categorizeReferences(name: String): Map[RefCategory, List[Reference]] =
    val refs = findReferences(name)
    refs.groupBy { r =>
      val line = r.contextLine
      if line.matches("""^\s*(trait|class|object|enum|given|type|def|val|var)\s+.*""") && containsWord(line, name) &&
         (line.contains(s"trait $name") || line.contains(s"class $name") || line.contains(s"object $name") ||
          line.contains(s"enum $name") || line.contains(s"type $name") ||
          line.matches(s""".*given\\s+\\w*$name.*""")) then
        RefCategory.Definition
      else if line.matches(""".*\b(extends|with)\b.*""") && containsWord(line, name) then
        RefCategory.ExtendedBy
      else if line.trim.startsWith("import ") then
        RefCategory.ImportedBy
      else if line.matches("""^\s*(//|/\*|\*).*""") then
        RefCategory.Comment
      else if line.matches(s""".*:\\s*$name.*""") || line.matches(s""".*\\[$name.*""") then
        RefCategory.UsedAsType
      else
        RefCategory.Usage
    }

  def findImports(name: String, timeoutMs: Long = defaultTimeoutMs): List[Reference] =
    val candidates = indexedFiles.filter(f => f.identifierBloom == null || f.identifierBloom.mightContain(name))
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    timedOut = false
    val results = ConcurrentLinkedQueue[Reference]()
    val resultPaths = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
    candidates.asJava.parallelStream().forEach { idxFile =>
      if System.nanoTime() < deadline then
        val path = workspace.resolve(idxFile.relativePath)
        val lines = try Files.readAllLines(path).asScala catch
          case _: Exception => Seq.empty
        lines.zipWithIndex.foreach {
          case (line, idx) if System.nanoTime() < deadline && line.trim.startsWith("import ") && containsWord(line, name) =>
            results.add(Reference(path, idx + 1, line.trim))
            resultPaths.add(s"${idxFile.relativePath}:${idx + 1}")
          case _ =>
        }
      else timedOut = true
    }

    // Also find wildcard imports that resolve to a package containing the target symbol
    val targetPkgs = symbolsByName.getOrElse(name.toLowerCase, Nil).map(_.packageName).toSet
    if targetPkgs.nonEmpty then
      for idxFile <- indexedFiles if System.nanoTime() < deadline do
        for imp <- idxFile.imports do
          val trimmed = imp.trim.stripPrefix("import ")
          if (trimmed.endsWith("._") || trimmed.endsWith(".*")) then
            val pkg = trimmed.dropRight(2)
            if targetPkgs.contains(pkg) then
              val path = workspace.resolve(idxFile.relativePath)
              try {
                val lines = Files.readAllLines(path).asScala
                lines.zipWithIndex.foreach { case (line, lineIdx) =>
                  if line.trim == imp.trim then
                    val key = s"${idxFile.relativePath}:${lineIdx + 1}"
                    if !resultPaths.contains(key) then
                      results.add(Reference(path, lineIdx + 1, line.trim))
                      resultPaths.add(key)
                }
              } catch { case _: Exception => () }

    results.asScala.toList

  private def filePackage(idxFile: IndexedFile): String =
    idxFile.symbols.headOption.map(_.packageName).getOrElse("")

  def resolveConfidence(ref: Reference, targetName: String, targetPackages: Set[String]): Confidence =
    val relPath = workspace.relativize(ref.file).toString
    indexedByPath.get(relPath) match
      case None => Confidence.Low
      case Some(idxFile) =>
        val filePkg = filePackage(idxFile)
        if targetPackages.contains(filePkg) then Confidence.High
        else
          val imports = idxFile.imports
          val hasExplicit = imports.exists { imp =>
            imp.contains(s".$targetName") || imp.contains(s"{$targetName") ||
            imp.contains(s", $targetName") || imp.contains(s"$targetName,")
          }
          val hasAliasMatch = idxFile.aliases.exists { (orig, alias) =>
            alias == targetName || orig == targetName
          }
          if hasExplicit || hasAliasMatch then Confidence.High
          else
            val hasWildcard = imports.exists { imp =>
              val trimmed = imp.trim.stripPrefix("import ")
              (trimmed.endsWith("._") || trimmed.endsWith(".*")) && {
                val pkg = trimmed.dropRight(2)
                targetPackages.contains(pkg)
              }
            }
            if hasWildcard then Confidence.Medium
            else Confidence.Low

  private def isSegmentStart(name: String, i: Int): Boolean =
    i == 0 || name(i).isUpper || (i > 0 && name(i - 1) == '_')

  private def camelCaseMatch(query: String, name: String): Boolean =
    if query.length < 2 then return false
    val qLower = query.toLowerCase
    val nLower = name.toLowerCase
    var qi = 0
    var ni = 0
    while qi < qLower.length && ni < nLower.length do
      if qLower(qi) == nLower(ni) then
        qi += 1
        ni += 1
      else
        ni += 1
        while ni < nLower.length && !isSegmentStart(name, ni) do ni += 1
    qi == qLower.length

  private def containsWord(line: String, word: String): Boolean =
    var i = line.indexOf(word)
    while i >= 0 do
      val before = i == 0 || !line(i - 1).isLetterOrDigit
      val after = i + word.length >= line.length || !line(i + word.length).isLetterOrDigit
      if before && after then return true
      i = line.indexOf(word, i + 1)
    false

// ── Filtering helpers ────────────────────────────────────────────────────────

def isTestFile(path: Path, workspace: Path): Boolean =
  val rel = workspace.relativize(path).toString
  rel.contains("/test/") || rel.contains("/tests/") || rel.contains("/testing/") ||
  rel.startsWith("bench-") || rel.contains("/bench-") ||
  rel.endsWith("Test.scala") || rel.endsWith("Spec.scala") || rel.endsWith("Suite.scala")

def matchesPath(file: Path, prefix: String, workspace: Path): Boolean =
  val rel = workspace.relativize(file).toString
  rel.startsWith(prefix)

// ── CLI ─────────────────────────────────────────────────────────────────────

def jsonEscape(s: String): String =
  val sb = new StringBuilder(s.length + 8)
  var i = 0
  while i < s.length do
    s.charAt(i) match
      case '"'  => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
      case c    => sb.append(c)
    i += 1
  sb.toString

def jsonSymbol(s: SymbolInfo, workspace: Path): String =
  val rel = jsonEscape(workspace.relativize(s.file).toString)
  val parents = s.parents.map(p => s""""${jsonEscape(p)}"""").mkString("[", ",", "]")
  val annots = s.annotations.map(a => s""""${jsonEscape(a)}"""").mkString("[", ",", "]")
  s"""{"name":"${jsonEscape(s.name)}","kind":"${s.kind.toString.toLowerCase}","file":"$rel","line":${s.line},"package":"${jsonEscape(s.packageName)}","parents":$parents,"signature":"${jsonEscape(s.signature)}","annotations":$annots}"""

def jsonRef(r: Reference, workspace: Path): String =
  val rel = jsonEscape(workspace.relativize(r.file).toString)
  val alias = r.aliasInfo.map(a => s""""${jsonEscape(a)}"""").getOrElse("null")
  s"""{"file":"$rel","line":${r.line},"context":"${jsonEscape(r.contextLine)}","alias":$alias}"""

def jsonRefWithContext(r: Reference, workspace: Path, contextN: Int): String =
  val rel = jsonEscape(workspace.relativize(r.file).toString)
  val alias = r.aliasInfo.map(a => s""""${jsonEscape(a)}"""").getOrElse("null")
  val lines = try java.nio.file.Files.readAllLines(r.file).asScala catch
    case _: Exception => Seq.empty
  val total = lines.size
  val startLine = math.max(1, r.line - contextN)
  val endLine = math.min(total, r.line + contextN)
  val ctxLines = (startLine to endLine).map { i =>
    s"""{"line":$i,"content":"${jsonEscape(lines(i - 1))}","match":${i == r.line}}"""
  }.mkString("[", ",", "]")
  s"""{"file":"$rel","line":${r.line},"context":"${jsonEscape(r.contextLine)}","alias":$alias,"contextLines":$ctxLines}"""

def formatSymbol(s: SymbolInfo, workspace: Path): String =
  val rel = workspace.relativize(s.file)
  val pkg = if s.packageName.nonEmpty then s" (${s.packageName})" else ""
  s"  ${s.kind.toString.toLowerCase.padTo(9, ' ')} ${s.name}$pkg — $rel:${s.line}"

def formatSymbolVerbose(s: SymbolInfo, workspace: Path): String =
  val rel = workspace.relativize(s.file)
  val pkg = if s.packageName.nonEmpty then s" (${s.packageName})" else ""
  val sig = if s.signature.nonEmpty then s"\n             ${s.signature}" else ""
  s"  ${s.kind.toString.toLowerCase.padTo(9, ' ')} ${s.name}$pkg — $rel:${s.line}$sig"

def formatRef(r: Reference, workspace: Path): String =
  val rel = workspace.relativize(r.file)
  val alias = r.aliasInfo.map(a => s" [$a]").getOrElse("")
  s"  $rel:${r.line} — ${r.contextLine}$alias"

def formatRefWithContext(r: Reference, workspace: Path, contextN: Int): String =
  val rel = workspace.relativize(r.file)
  val alias = r.aliasInfo.map(a => s" [$a]").getOrElse("")
  val header = s"  $rel:${r.line}$alias"
  val lines = try java.nio.file.Files.readAllLines(r.file).asScala catch
    case _: Exception => return s"$header\n    > ${r.contextLine}"
  val total = lines.size
  val startLine = math.max(1, r.line - contextN)
  val endLine = math.min(total, r.line + contextN)
  val buf = new StringBuilder(header)
  var i = startLine
  while i <= endLine do
    val lineContent = lines(i - 1)
    val marker = if i == r.line then ">" else " "
    val lineNum = i.toString.reverse.padTo(4, ' ').reverse
    buf.append(s"\n    $marker $lineNum | $lineContent")
    i += 1
  buf.toString

def printNotFoundHint(symbol: String, idx: WorkspaceIndex, cmd: String, batchMode: Boolean = false): Unit =
  if batchMode then
    println(s"  not found (0 matches in ${idx.fileCount} files)")
  else
    if symbol.contains("/") || symbol.startsWith(".") then
      println(s"  Note: \"$symbol\" looks like a path. Did you mean: scalex $cmd -w <workspace> $symbol?")
    println(s"  Hint: scalex indexes ${idx.fileCount} git-tracked .scala files.")
    if idx.parseFailures > 0 then
      println(s"  ${idx.parseFailures} files had parse errors (run `scalex index --verbose` to list them).")
    println(s"  Fallback: use Grep, Glob, or Read tools to search manually.")

def hasRegexHint(pattern: String): Boolean =
  pattern.contains("\\|") || pattern.contains("\\(") || pattern.contains("\\)")

def fixPosixRegex(pattern: String): (String, Boolean) =
  val fixed = pattern.replace("\\|", "|").replace("\\(", "(").replace("\\)", ")")
  (fixed, fixed != pattern)

def resolveWorkspace(path: String): Path =
  val p = Path.of(path).toAbsolutePath.normalize
  if Files.isDirectory(p) then p else p.getParent

def parseWorkspaceAndArg(rest: List[String]): Option[(Path, String)] =
  rest match
    case a :: Nil => Some((resolveWorkspace("."), a))
    case ws :: a :: _ => Some((resolveWorkspace(ws), a))
    case _ => None

def runCommand(cmd: String, rest: List[String], idx: WorkspaceIndex, workspace: Path,
               limit: Int, kindFilter: Option[String], verbose: Boolean, categorize: Boolean,
               noTests: Boolean, pathFilter: Option[String], contextLines: Int,
               jsonOutput: Boolean, grepPatterns: List[String] = Nil,
               countOnly: Boolean = false, batchMode: Boolean = false,
               searchMode: Option[String] = None, definitionsOnly: Boolean = false,
               categoryFilter: Option[String] = None,
               inOwner: Option[String] = None, ofTrait: Option[String] = None,
               implLimit: Int = 5, goUp: Boolean = true, goDown: Boolean = true,
               inherited: Boolean = false, architecture: Boolean = false,
               hasMethodFilter: Option[String] = None, extendsFilter: Option[String] = None,
               bodyContainsFilter: Option[String] = None): Unit =
  val fmt = if verbose then formatSymbolVerbose else formatSymbol
  val jRef: Reference => String =
    if contextLines > 0 then r => jsonRefWithContext(r, workspace, contextLines)
    else r => jsonRef(r, workspace)
  cmd match
    case "index" =>
      if jsonOutput then
        val byKind = idx.symbols.groupBy(_.kind).toList.sortBy(-_._2.size)
          .map((k, v) => s""""${k.toString.toLowerCase}":${v.size}""").mkString(",")
        println(s"""{"fileCount":${idx.fileCount},"symbolCount":${idx.symbols.size},"packageCount":${idx.packages.size},"symbolsByKind":{$byKind},"indexTimeMs":${idx.indexTimeMs},"cachedLoad":${idx.cachedLoad},"parsedCount":${idx.parsedCount},"skippedCount":${idx.skippedCount},"parseFailures":${idx.parseFailures}}""")
      else
        if idx.cachedLoad then
          println(s"Indexed ${idx.fileCount} files (${idx.skippedCount} cached, ${idx.parsedCount} parsed) in ${idx.indexTimeMs}ms")
        else
          println(s"Indexed ${idx.fileCount} files, ${idx.symbols.size} symbols in ${idx.indexTimeMs}ms")
        println(s"Packages: ${idx.packages.size}")
        println()
        println("Symbols by kind:")
        idx.symbols.groupBy(_.kind).toList.sortBy(-_._2.size).foreach { (kind, syms) =>
          println(s"  ${kind.toString.padTo(10, ' ')} ${syms.size}")
        }
        if idx.parseFailures > 0 then
          println(s"\n${idx.parseFailures} files had parse errors:")
          if verbose then
            idx.parseFailedFiles.sorted.foreach(f => println(s"  $f"))
          else
            println("  Run with --verbose to see the list.")

    case "search" =>
      rest.headOption match
        case None => println("Usage: scalex search <query>")
        case Some(query) =>
          var results = idx.search(query)
          searchMode.foreach {
            case "exact" =>
              val lower = query.toLowerCase
              results = results.filter(_.name.toLowerCase == lower)
            case "prefix" =>
              val lower = query.toLowerCase
              results = results.filter(_.name.toLowerCase.startsWith(lower))
            case _ => ()
          }
          if definitionsOnly then
            val defKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
            results = results.filter(s => defKinds.contains(s.kind))
          kindFilter.foreach { k =>
            val kk = k.toLowerCase
            results = results.filter(_.kind.toString.toLowerCase == kk)
          }
          if noTests then results = results.filter(s => !isTestFile(s.file, workspace))
          pathFilter.foreach { p => results = results.filter(s => matchesPath(s.file, p, workspace)) }
          if jsonOutput then
            val arr = results.take(limit).map(s => jsonSymbol(s, workspace)).mkString("[", ",", "]")
            println(arr)
          else
            if results.isEmpty then
              println(s"Found 0 symbols matching \"$query\"")
              printNotFoundHint(query, idx, "search", batchMode)
            else
              println(s"Found ${results.size} symbols matching \"$query\":")
              results.take(limit).foreach(s => println(fmt(s, workspace)))
              if results.size > limit then println(s"  ... and ${results.size - limit} more")

    case "def" =>
      rest.headOption match
        case None => println("Usage: scalex def <symbol>")
        case Some(symbol) =>
          var results = idx.findDefinition(symbol)
          kindFilter.foreach { k =>
            val kk = k.toLowerCase
            results = results.filter(_.kind.toString.toLowerCase == kk)
          }
          if noTests then results = results.filter(s => !isTestFile(s.file, workspace))
          pathFilter.foreach { p => results = results.filter(s => matchesPath(s.file, p, workspace)) }
          // Rank: class/trait/object/enum > type/given > def/val/var, non-test > test, shorter path first
          results = results.sortBy { s =>
            val kindRank = s.kind match
              case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Object | SymbolKind.Enum => 0
              case SymbolKind.Type | SymbolKind.Given => 1
              case _ => 2
            val testRank = if isTestFile(s.file, workspace) then 1 else 0
            val pathLen = workspace.relativize(s.file).toString.length
            (kindRank, testRank, pathLen)
          }
          if jsonOutput then
            val arr = results.take(limit).map(s => jsonSymbol(s, workspace)).mkString("[", ",", "]")
            println(arr)
          else
            if results.isEmpty then
              println(s"Definition of \"$symbol\": not found")
              printNotFoundHint(symbol, idx, "def", batchMode)
            else
              println(s"Definition of \"$symbol\":")
              results.take(limit).foreach(s => println(fmt(s, workspace)))
              if results.size > limit then println(s"  ... and ${results.size - limit} more")

    case "impl" =>
      rest.headOption match
        case None => println("Usage: scalex impl <trait>")
        case Some(symbol) =>
          var results = idx.findImplementations(symbol)
          kindFilter.foreach { k =>
            val kk = k.toLowerCase
            results = results.filter(_.kind.toString.toLowerCase == kk)
          }
          if noTests then results = results.filter(s => !isTestFile(s.file, workspace))
          pathFilter.foreach { p => results = results.filter(s => matchesPath(s.file, p, workspace)) }
          if jsonOutput then
            val arr = results.take(limit).map(s => jsonSymbol(s, workspace)).mkString("[", ",", "]")
            println(arr)
          else
            if results.isEmpty then
              println(s"No implementations of \"$symbol\" found")
              printNotFoundHint(symbol, idx, "impl", batchMode)
            else
              println(s"Implementations of \"$symbol\" — ${results.size} found:")
              results.take(limit).foreach(s => println(fmt(s, workspace)))
              if results.size > limit then println(s"  ... and ${results.size - limit} more")

    case "refs" =>
      rest.headOption match
        case None => println("Usage: scalex refs <symbol>")
        case Some(symbol) =>
          val fmtRef: Reference => String =
            if contextLines > 0 then r => formatRefWithContext(r, workspace, contextLines)
            else r => formatRef(r, workspace)
          val targetPkgs = idx.symbolsByName.getOrElse(symbol.toLowerCase, Nil).map(_.packageName).toSet
          def filterRefs(refs: List[Reference]): List[Reference] =
            var r = refs
            if noTests then r = r.filter(ref => !isTestFile(ref.file, workspace))
            pathFilter.foreach { p => r = r.filter(ref => matchesPath(ref.file, p, workspace)) }
            r
          def filterByCategory(grouped: Map[RefCategory, List[Reference]]): Map[RefCategory, List[Reference]] =
            categoryFilter match
              case Some(catName) =>
                val validCats = RefCategory.values.map(_.toString.toLowerCase).toSet
                val lower = catName.toLowerCase
                if !validCats.contains(lower) then
                  System.err.println(s"Unknown category: $catName. Valid: ${RefCategory.values.map(_.toString).mkString(", ")}")
                  Map.empty
                else
                  grouped.filter((cat, _) => cat.toString.toLowerCase == lower)
              case None => grouped
          if jsonOutput then
            if categorize then
              val grouped = filterByCategory(idx.categorizeReferences(symbol).map((cat, refs) => (cat, filterRefs(refs))))
              val entries = grouped.map { (cat, refs) =>
                val arr = refs.take(limit).map(jRef).mkString("[", ",", "]")
                s""""${cat.toString}":$arr"""
              }.mkString(",")
              println(s"""{"categories":{$entries},"timedOut":${idx.timedOut}}""")
            else
              val results = filterRefs(idx.findReferences(symbol))
              val arr = results.take(limit).map(jRef).mkString("[", ",", "]")
              println(s"""{"results":$arr,"timedOut":${idx.timedOut}}""")
          else
            if categorize then
              val grouped = filterByCategory(idx.categorizeReferences(symbol).map((cat, refs) => (cat, filterRefs(refs))))
              val total = grouped.values.map(_.size).sum
              val suffix = if idx.timedOut then " (timed out — partial results)" else ""
              println(s"References to \"$symbol\" — $total found:$suffix")
              val confidenceOrder = List(Confidence.High, Confidence.Medium, Confidence.Low)
              confidenceOrder.foreach { conf =>
                val catRefs = grouped.flatMap { (cat, refs) =>
                  refs.map(r => (cat, r, idx.resolveConfidence(r, symbol, targetPkgs)))
                }.filter(_._3 == conf).toList
                if catRefs.nonEmpty then
                  val label = conf match
                    case Confidence.High   => "High confidence (import-matched)"
                    case Confidence.Medium => "Medium confidence (wildcard import)"
                    case Confidence.Low    => "Low confidence (no matching import)"
                  println(s"\n  $label:")
                  val byCat = catRefs.groupBy(_._1)
                  val order = List(RefCategory.Definition, RefCategory.ExtendedBy, RefCategory.ImportedBy,
                                   RefCategory.UsedAsType, RefCategory.Usage, RefCategory.Comment)
                  order.foreach { cat =>
                    byCat.get(cat).filter(_.nonEmpty).foreach { entries =>
                      println(s"\n    ${cat.toString}:")
                      entries.take(limit).foreach((_, r, _) => println(s"    ${fmtRef(r)}"))
                      if entries.size > limit then println(s"      ... and ${entries.size - limit} more")
                    }
                  }
              }
            else
              val results = filterRefs(idx.findReferences(symbol))
              val suffix = if idx.timedOut then " (timed out — partial results)" else ""
              println(s"References to \"$symbol\" — ${results.size} found:$suffix")
              val annotated = results.map(r => (r, idx.resolveConfidence(r, symbol, targetPkgs)))
              val sorted = annotated.sortBy { case (_, c) => c.ordinal }
              var lastConf: Option[Confidence] = None
              var shown = 0
              sorted.foreach { case (r, conf) =>
                if shown < limit then
                  if !lastConf.contains(conf) then
                    val label = conf match
                      case Confidence.High   => "High confidence"
                      case Confidence.Medium => "Medium confidence"
                      case Confidence.Low    => "Low confidence"
                    println(s"\n  [$label]")
                    lastConf = Some(conf)
                  println(fmtRef(r))
                  shown += 1
              }
              if results.size > limit then println(s"  ... and ${results.size - limit} more")

    case "imports" =>
      rest.headOption match
        case None => println("Usage: scalex imports <symbol>")
        case Some(symbol) =>
          var results = idx.findImports(symbol)
          if noTests then results = results.filter(r => !isTestFile(r.file, workspace))
          pathFilter.foreach { p => results = results.filter(r => matchesPath(r.file, p, workspace)) }
          if jsonOutput then
            val arr = results.take(limit).map(r => jsonRef(r, workspace)).mkString("[", ",", "]")
            println(s"""{"results":$arr,"timedOut":${idx.timedOut}}""")
          else
            if results.isEmpty then
              println(s"No imports of \"$symbol\" found")
              printNotFoundHint(symbol, idx, "imports", batchMode)
            else
              val suffix = if idx.timedOut then " (timed out — partial results)" else ""
              println(s"Imports of \"$symbol\" — ${results.size} found:$suffix")
              results.take(limit).foreach(r => println(formatRef(r, workspace)))
              if results.size > limit then println(s"  ... and ${results.size - limit} more")

    case "symbols" =>
      rest.headOption match
        case None => println("Usage: scalex symbols <file>")
        case Some(file) =>
          val results = idx.fileSymbols(file)
          if jsonOutput then
            val arr = results.map(s => jsonSymbol(s, workspace)).mkString("[", ",", "]")
            println(arr)
          else
            if results.isEmpty then println(s"No symbols found in $file")
            else
              println(s"Symbols in $file:")
              results.foreach(s => println(fmt(s, workspace)))

    case "file" =>
      rest.headOption match
        case None => println("Usage: scalex file <query>")
        case Some(query) =>
          val results = idx.searchFiles(query)
          if jsonOutput then
            val arr = results.take(limit).map(f => s""""${jsonEscape(f)}"""").mkString("[", ",", "]")
            println(arr)
          else
            if results.isEmpty then
              println(s"Found 0 files matching \"$query\"")
              println(s"  Hint: scalex indexes ${idx.fileCount} git-tracked .scala files.")
            else
              println(s"Found ${results.size} files matching \"$query\":")
              results.take(limit).foreach(f => println(s"  $f"))
              if results.size > limit then println(s"  ... and ${results.size - limit} more")

    case "packages" =>
      if jsonOutput then
        val arr = idx.packages.toList.sorted.map(p => s""""${jsonEscape(p)}"""").mkString("[", ",", "]")
        println(arr)
      else
        println(s"Packages (${idx.packages.size}):")
        idx.packages.toList.sorted.foreach(p => println(s"  $p"))

    case "annotated" =>
      rest.headOption match
        case None => println("Usage: scalex annotated <annotation>")
        case Some(query) =>
          val annot = query.stripPrefix("@")
          var results = idx.findAnnotated(annot)
          kindFilter.foreach { k =>
            val kk = k.toLowerCase
            results = results.filter(_.kind.toString.toLowerCase == kk)
          }
          if noTests then results = results.filter(s => !isTestFile(s.file, workspace))
          pathFilter.foreach { p => results = results.filter(s => matchesPath(s.file, p, workspace)) }
          if jsonOutput then
            val arr = results.take(limit).map(s => jsonSymbol(s, workspace)).mkString("[", ",", "]")
            println(arr)
          else
            if results.isEmpty then
              println(s"No symbols with @$annot annotation found")
            else
              println(s"Symbols annotated with @$annot — ${results.size} found:")
              results.take(limit).foreach(s => println(fmt(s, workspace)))
              if results.size > limit then println(s"  ... and ${results.size - limit} more")

    case "grep" =>
      val patternOpt = if grepPatterns.nonEmpty then Some(grepPatterns.mkString("|"))
                       else rest.headOption
      patternOpt match
        case None => println("Usage: scalex grep <pattern>")
        case Some(rawPattern) =>
          val (pattern, wasFixed) = fixPosixRegex(rawPattern)
          if wasFixed then
            System.err.println(s"  Note: auto-corrected POSIX regex to Java regex: \"$rawPattern\" → \"$pattern\"")
          val (results, grepTimedOut) = idx.grepFiles(pattern, noTests, pathFilter)
          if countOnly then
            val fileCount = results.map(_.file).distinct.size
            val hint = if wasFixed then s""","corrected":"$pattern"""" else ""
            if jsonOutput then println(s"""{"matches":${results.size},"files":$fileCount,"timedOut":$grepTimedOut$hint}""")
            else
              val suffix = if grepTimedOut then " (timed out — partial results)" else ""
              println(s"${results.size} matches across $fileCount files$suffix")
          else if jsonOutput then
            val arr = results.take(limit).map(jRef).mkString("[", ",", "]")
            val hint = if wasFixed then s""","corrected":"$pattern"""" else ""
            println(s"""{"results":$arr,"timedOut":$grepTimedOut$hint}""")
          else
            val suffix = if grepTimedOut then " (timed out — partial results)" else ""
            if results.isEmpty then
              println(s"No matches for \"$pattern\"$suffix")
            else
              val fmtRef: Reference => String =
                if contextLines > 0 then r => formatRefWithContext(r, workspace, contextLines)
                else r => formatRef(r, workspace)
              println(s"Matches for \"$pattern\" — ${results.size} found:$suffix")
              results.take(limit).foreach(r => println(fmtRef(r)))
              if results.size > limit then println(s"  ... and ${results.size - limit} more")

    case "members" =>
      rest.headOption match
        case None => println("Usage: scalex members <Symbol>")
        case Some(symbol) =>
          val typeKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
          var defs = idx.findDefinition(symbol).filter(s => typeKinds.contains(s.kind))
          if noTests then defs = defs.filter(s => !isTestFile(s.file, workspace))
          pathFilter.foreach { p => defs = defs.filter(s => matchesPath(s.file, p, workspace)) }
          kindFilter.foreach { k =>
            val kk = k.toLowerCase
            defs = defs.filter(_.kind.toString.toLowerCase == kk)
          }

          // Collect inherited members if --inherited is set
          def collectInherited(sym: SymbolInfo): List[(String, List[MemberInfo])] = {
            if !inherited then return Nil
            val visited = mutable.HashSet.empty[String]
            visited += sym.name.toLowerCase
            val ownMembers = extractMembers(sym.file, sym.name).map(m => (m.name, m.kind)).toSet
            val result = mutable.ListBuffer.empty[(String, List[MemberInfo])]

            def walk(parentNames: List[String]): Unit = {
              parentNames.foreach { pName =>
                if !visited.contains(pName.toLowerCase) then {
                  visited += pName.toLowerCase
                  val parentDefs = idx.findDefinition(pName).filter(s => typeKinds.contains(s.kind))
                  parentDefs.headOption.foreach { pd =>
                    val parentMembers = extractMembers(pd.file, pd.name)
                    val filtered = parentMembers.filterNot(m => ownMembers.contains((m.name, m.kind)))
                    if filtered.nonEmpty then result += ((pd.name, filtered))
                    walk(pd.parents)
                  }
                }
              }
            }

            walk(sym.parents)
            result.toList
          }

          if jsonOutput then
            val allMembers = defs.flatMap { s =>
              val ownMembers = extractMembers(s.file, symbol).map { m =>
                val rel = jsonEscape(workspace.relativize(s.file).toString)
                s"""{"name":"${jsonEscape(m.name)}","kind":"${m.kind.toString.toLowerCase}","line":${m.line},"signature":"${jsonEscape(m.signature)}","file":"$rel","owner":"${jsonEscape(symbol)}","ownerKind":"${s.kind.toString.toLowerCase}","package":"${jsonEscape(s.packageName)}","inherited":false}"""
              }
              val inheritedMembers = collectInherited(s).flatMap { case (parentName, members) =>
                val parentDef = idx.findDefinition(parentName).headOption
                members.map { m =>
                  val rel = parentDef.map(pd => jsonEscape(workspace.relativize(pd.file).toString)).getOrElse("")
                  s"""{"name":"${jsonEscape(m.name)}","kind":"${m.kind.toString.toLowerCase}","line":${m.line},"signature":"${jsonEscape(m.signature)}","file":"$rel","owner":"${jsonEscape(parentName)}","ownerKind":"inherited","package":"${parentDef.map(_.packageName).getOrElse("")}","inherited":true}"""
                }
              }
              ownMembers ++ inheritedMembers
            }
            println(allMembers.take(limit).mkString("[", ",", "]"))
          else
            if defs.isEmpty then
              println(s"No class/trait/object/enum \"$symbol\" found")
              printNotFoundHint(symbol, idx, "members", batchMode)
            else
              defs.foreach { s =>
                val rel = workspace.relativize(s.file)
                val pkg = if s.packageName.nonEmpty then s" (${s.packageName})" else ""
                val members = extractMembers(s.file, symbol)
                println(s"Members of ${s.kind.toString.toLowerCase} $symbol$pkg — $rel:${s.line}:")
                if members.isEmpty then println("  (no members)")
                else
                  println(s"  Defined in $symbol:")
                  members.take(limit).foreach { m =>
                    if verbose then
                      println(s"    ${m.kind.toString.toLowerCase.padTo(5, ' ')} ${m.signature.padTo(50, ' ')} :${m.line}")
                    else
                      println(s"    ${m.kind.toString.toLowerCase.padTo(5, ' ')} ${m.name.padTo(30, ' ')} :${m.line}")
                  }
                  if members.size > limit then println(s"    ... and ${members.size - limit} more")
                val inheritedGroups = collectInherited(s)
                inheritedGroups.foreach { case (parentName, pMembers) =>
                  println(s"  Inherited from $parentName:")
                  pMembers.take(limit).foreach { m =>
                    if verbose then
                      println(s"    ${m.kind.toString.toLowerCase.padTo(5, ' ')} ${m.signature.padTo(50, ' ')} :${m.line}")
                    else
                      println(s"    ${m.kind.toString.toLowerCase.padTo(5, ' ')} ${m.name.padTo(30, ' ')} :${m.line}")
                  }
                  if pMembers.size > limit then println(s"    ... and ${pMembers.size - limit} more")
                }
              }

    case "doc" =>
      rest.headOption match
        case None => println("Usage: scalex doc <Symbol>")
        case Some(symbol) =>
          var defs = idx.findDefinition(symbol)
          if noTests then defs = defs.filter(s => !isTestFile(s.file, workspace))
          pathFilter.foreach { p => defs = defs.filter(s => matchesPath(s.file, p, workspace)) }
          kindFilter.foreach { k =>
            val kk = k.toLowerCase
            defs = defs.filter(_.kind.toString.toLowerCase == kk)
          }
          if jsonOutput then
            val entries = defs.take(limit).map { s =>
              val rel = jsonEscape(workspace.relativize(s.file).toString)
              val doc = extractScaladoc(s.file, s.line).map(d => s""""${jsonEscape(d)}"""").getOrElse("null")
              s"""{"name":"${jsonEscape(s.name)}","kind":"${s.kind.toString.toLowerCase}","file":"$rel","line":${s.line},"package":"${jsonEscape(s.packageName)}","doc":$doc}"""
            }
            println(entries.mkString("[", ",", "]"))
          else
            if defs.isEmpty then
              println(s"Definition of \"$symbol\": not found")
              printNotFoundHint(symbol, idx, "doc", batchMode)
            else
              defs.take(limit).foreach { s =>
                val rel = workspace.relativize(s.file)
                val pkg = if s.packageName.nonEmpty then s" (${s.packageName})" else ""
                println(s"${s.kind.toString.toLowerCase} $symbol$pkg — $rel:${s.line}:")
                extractScaladoc(s.file, s.line) match
                  case Some(doc) => println(doc)
                  case None => println("  (no scaladoc)")
                println()
              }

    case "overview" =>
      val symbolsByKind = idx.symbols.groupBy(_.kind).toList.sortBy(-_._2.size)
      val topPackages = idx.packageToSymbols.toList.sortBy(-_._2.size).take(limit)
      val mostExtended = idx.parentIndex.toList.sortBy(-_._2.size).take(limit)

      // Architecture: compute package dependency graph from imports
      val archPkgDeps: Map[String, Set[String]] = if architecture then {
        val deps = mutable.HashMap.empty[String, mutable.HashSet[String]]
        idx.symbols.groupBy(_.file).foreach { (file, syms) =>
          val filePkg = syms.headOption.map(_.packageName).getOrElse("")
          if filePkg.nonEmpty then {
            parseFile(file).foreach { tree =>
              val (imports, _) = extractImports(tree)
              imports.foreach { imp =>
                val trimmed = imp.trim.stripPrefix("import ")
                // Extract package from import: "com.example.Foo" → "com.example"
                val lastDot = trimmed.lastIndexOf('.')
                if lastDot > 0 then {
                  val importPkg = trimmed.substring(0, lastDot)
                  // Only track cross-package dependencies
                  if importPkg != filePkg && idx.packages.contains(importPkg) then {
                    deps.getOrElseUpdate(filePkg, mutable.HashSet.empty) += importPkg
                  }
                }
              }
            }
          }
        }
        deps.map((k, v) => k -> v.toSet).toMap
      } else Map.empty

      // Architecture: hub types (most-referenced + most-extended)
      val hubTypes: List[(String, Int)] = if architecture then {
        val refCounts = mutable.HashMap.empty[String, Int]
        idx.parentIndex.foreach { (name, impls) =>
          refCounts(name) = refCounts.getOrElse(name, 0) + impls.size
        }
        refCounts.toList.sortBy(-_._2).take(limit)
      } else Nil

      if jsonOutput then
        val kindJson = symbolsByKind.map((k, v) => s""""${k.toString.toLowerCase}":${v.size}""").mkString("{", ",", "}")
        val pkgJson = topPackages.map((p, s) => s"""{"package":"${jsonEscape(p)}","count":${s.size}}""").mkString("[", ",", "]")
        val extJson = mostExtended.map((p, s) => s"""{"name":"${jsonEscape(p)}","implementations":${s.size}}""").mkString("[", ",", "]")
        if architecture then
          val depsJson = archPkgDeps.map { (pkg, deps) =>
            val dArr = deps.map(d => s""""${jsonEscape(d)}"""").mkString("[", ",", "]")
            s""""${jsonEscape(pkg)}":$dArr"""
          }.mkString("{", ",", "}")
          val hubJson = hubTypes.map((n, c) => s"""{"name":"${jsonEscape(n)}","score":$c}""").mkString("[", ",", "]")
          println(s"""{"fileCount":${idx.fileCount},"symbolCount":${idx.symbols.size},"packageCount":${idx.packages.size},"symbolsByKind":$kindJson,"topPackages":$pkgJson,"mostExtended":$extJson,"packageDependencies":$depsJson,"hubTypes":$hubJson}""")
        else
          println(s"""{"fileCount":${idx.fileCount},"symbolCount":${idx.symbols.size},"packageCount":${idx.packages.size},"symbolsByKind":$kindJson,"topPackages":$pkgJson,"mostExtended":$extJson}""")
      else
        println(s"Project overview (${idx.fileCount} files, ${idx.symbols.size} symbols):\n")
        println("Symbols by kind:")
        symbolsByKind.foreach { (kind, syms) =>
          println(s"  ${kind.toString.padTo(10, ' ')} ${syms.size}")
        }
        println(s"\nTop packages (by symbol count):")
        topPackages.foreach { (pkg, syms) =>
          println(s"  ${pkg.padTo(50, ' ')} ${syms.size}")
        }
        println(s"\nMost extended (by implementation count):")
        mostExtended.foreach { (name, impls) =>
          println(s"  ${name.padTo(30, ' ')} ${impls.size} impl")
        }
        if architecture then
          println(s"\nPackage dependencies:")
          if archPkgDeps.isEmpty then println("  (no cross-package dependencies found)")
          else archPkgDeps.toList.sortBy(_._1).foreach { (pkg, deps) =>
            println(s"  $pkg → ${deps.toList.sorted.mkString(", ")}")
          }
          println(s"\nHub types (by extension count):")
          if hubTypes.isEmpty then println("  (none)")
          else hubTypes.foreach { (name, count) =>
            println(s"  ${name.padTo(30, ' ')} $count references")
          }

    case "body" =>
      rest.headOption match
        case None => println("Usage: scalex body <symbol> [--in <owner>]")
        case Some(symbol) =>
          // Find files containing the symbol
          var defs = idx.findDefinition(symbol)
          if noTests then defs = defs.filter(s => !isTestFile(s.file, workspace))
          pathFilter.foreach { p => defs = defs.filter(s => matchesPath(s.file, p, workspace)) }
          // Also look in type definitions for member bodies
          val typeKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
          val filesToSearch = if defs.nonEmpty then {
            defs.map(_.file).distinct
          } else {
            // If not found directly, search all files for member bodies
            inOwner match
              case Some(owner) =>
                idx.findDefinition(owner).filter(s => typeKinds.contains(s.kind)).map(_.file).distinct
              case None =>
                idx.symbols.filter(s => typeKinds.contains(s.kind)).map(_.file).distinct
          }
          // Collect (file, body) pairs
          val resultsWithFiles = filesToSearch.flatMap { f =>
            extractBody(f, symbol, inOwner).map(b => (f, b))
          }
          if jsonOutput then
            val arr = resultsWithFiles.take(limit).map { case (file, b) =>
              val rel = jsonEscape(workspace.relativize(file).toString)
              s"""{"name":"${jsonEscape(b.symbolName)}","owner":"${jsonEscape(b.ownerName)}","file":"$rel","startLine":${b.startLine},"endLine":${b.endLine},"body":"${jsonEscape(b.sourceText)}"}"""
            }.mkString("[", ",", "]")
            println(arr)
          else
            if resultsWithFiles.isEmpty then
              println(s"No body found for \"$symbol\"")
              printNotFoundHint(symbol, idx, "body", batchMode)
            else
              resultsWithFiles.take(limit).foreach { case (file, b) =>
                val ownerStr = if b.ownerName.nonEmpty then s" — ${b.ownerName}" else ""
                val rel = workspace.relativize(file)
                println(s"Body of ${b.symbolName}$ownerStr — $rel:${b.startLine}:")
                val bodyLines = b.sourceText.split("\n")
                bodyLines.zipWithIndex.foreach { case (line, i) =>
                  println(s"  ${(b.startLine + i).toString.padTo(4, ' ')} | $line")
                }
                println()
              }

    case "hierarchy" =>
      rest.headOption match
        case None => println("Usage: scalex hierarchy <symbol> [--up] [--down]")
        case Some(symbol) =>
          buildHierarchy(idx, symbol, goUp, goDown, workspace) match
            case None =>
              println(s"No definition of \"$symbol\" found")
              printNotFoundHint(symbol, idx, "hierarchy", batchMode)
            case Some(tree) =>
              def nodeJson(n: HierarchyNode): String = {
                val file = n.file.map(f => s""""${jsonEscape(workspace.relativize(f).toString)}"""").getOrElse("null")
                val kind = n.kind.map(k => s""""${k.toString.toLowerCase}"""").getOrElse("null")
                val line = n.line.map(_.toString).getOrElse("null")
                s"""{"name":"${jsonEscape(n.name)}","kind":$kind,"file":$file,"line":$line,"package":"${jsonEscape(n.packageName)}","isExternal":${n.isExternal}}"""
              }
              def treeJson(t: HierarchyTree): String = {
                val ps = t.parents.map(treeJson).mkString("[", ",", "]")
                val cs = t.children.map(treeJson).mkString("[", ",", "]")
                s"""{"node":${nodeJson(t.root)},"parents":$ps,"children":$cs}"""
              }
              if jsonOutput then
                println(treeJson(tree))
              else
                val rootNode = tree.root
                val pkg = if rootNode.packageName.nonEmpty then s" (${rootNode.packageName})" else ""
                val kind = rootNode.kind.map(_.toString.toLowerCase).getOrElse("unknown")
                val loc = rootNode.file.map(f => s" — ${workspace.relativize(f)}:${rootNode.line.getOrElse(0)}").getOrElse("")
                println(s"Hierarchy of $kind ${rootNode.name}$pkg$loc:")
                if goUp then
                  println("  Parents:")
                  def printParents(parents: List[HierarchyTree], indent: String): Unit = {
                    parents.zipWithIndex.foreach { case (pt, i) =>
                      val isLast = i == parents.size - 1
                      val prefix = if isLast then s"$indent└── " else s"$indent├── "
                      val nextIndent = if isLast then s"$indent    " else s"$indent│   "
                      val n = pt.root
                      val npkg = if n.packageName.nonEmpty then s" (${n.packageName})" else ""
                      val nkind = n.kind.map(_.toString.toLowerCase + " ").getOrElse("")
                      val nloc = if n.isExternal then " [external]"
                                 else n.file.map(f => s" — ${workspace.relativize(f)}:${n.line.getOrElse(0)}").getOrElse("")
                      println(s"$prefix$nkind${n.name}$npkg$nloc")
                      printParents(pt.parents, nextIndent)
                    }
                  }
                  if tree.parents.isEmpty then println("    (none)")
                  else printParents(tree.parents, "    ")
                if goDown then
                  println("  Children:")
                  def printChildren(children: List[HierarchyTree], indent: String): Unit = {
                    children.zipWithIndex.foreach { case (ct, i) =>
                      val isLast = i == children.size - 1
                      val prefix = if isLast then s"$indent└── " else s"$indent├── "
                      val nextIndent = if isLast then s"$indent    " else s"$indent│   "
                      val n = ct.root
                      val npkg = if n.packageName.nonEmpty then s" (${n.packageName})" else ""
                      val nkind = n.kind.map(_.toString.toLowerCase + " ").getOrElse("")
                      val nloc = n.file.map(f => s" — ${workspace.relativize(f)}:${n.line.getOrElse(0)}").getOrElse("")
                      println(s"$prefix$nkind${n.name}$npkg$nloc")
                      printChildren(ct.children, nextIndent)
                    }
                  }
                  if tree.children.isEmpty then println("    (none)")
                  else printChildren(tree.children, "    ")

    case "overrides" =>
      rest.headOption match
        case None => println("Usage: scalex overrides <method> [--of <trait>]")
        case Some(methodName) =>
          val results = findOverrides(idx, methodName, ofTrait, limit)
          if jsonOutput then
            val arr = results.map { o =>
              val rel = jsonEscape(workspace.relativize(o.file).toString)
              s"""{"enclosingClass":"${jsonEscape(o.enclosingClass)}","enclosingKind":"${o.enclosingKind.toString.toLowerCase}","file":"$rel","line":${o.line},"signature":"${jsonEscape(o.signature)}","package":"${jsonEscape(o.packageName)}"}"""
            }.mkString("[", ",", "]")
            println(arr)
          else
            if results.isEmpty then
              val ofStr = ofTrait.map(t => s" of $t").getOrElse("")
              println(s"No overrides of \"$methodName\"$ofStr found")
              printNotFoundHint(methodName, idx, "overrides", batchMode)
            else
              val ofStr = ofTrait.map(t => s" (in implementations of $t)").getOrElse("")
              println(s"Overrides of $methodName$ofStr — ${results.size} found:")
              results.foreach { o =>
                val rel = workspace.relativize(o.file)
                val pkg = if o.packageName.nonEmpty then s" (${o.packageName})" else ""
                println(s"  ${o.enclosingClass}$pkg — $rel:${o.line}")
                println(s"    ${o.signature}")
              }

    case "explain" =>
      rest.headOption match
        case None => println("Usage: scalex explain <symbol>")
        case Some(symbol) =>
          var defs = idx.findDefinition(symbol)
          if noTests then defs = defs.filter(s => !isTestFile(s.file, workspace))
          pathFilter.foreach { p => defs = defs.filter(s => matchesPath(s.file, p, workspace)) }
          if defs.isEmpty then
            if jsonOutput then println("""{"error":"not found"}""")
            else
              println(s"No definition of \"$symbol\" found")
              printNotFoundHint(symbol, idx, "explain", batchMode)
          else
            val sym = defs.head
            val rel = workspace.relativize(sym.file)
            val pkg = if sym.packageName.nonEmpty then s" (${sym.packageName})" else ""

            // Scaladoc
            val doc = extractScaladoc(sym.file, sym.line)

            // Members (for types)
            val typeKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
            val members = if typeKinds.contains(sym.kind) then extractMembers(sym.file, symbol).take(10) else Nil

            // Implementations
            val impls = idx.findImplementations(symbol).take(implLimit)

            // Import count
            val importCount = idx.findImports(symbol, timeoutMs = 3000).size

            if jsonOutput then
              val docJson = doc.map(d => s""""${jsonEscape(d)}"""").getOrElse("null")
              val membersJson = members.map { m =>
                s"""{"name":"${jsonEscape(m.name)}","kind":"${m.kind.toString.toLowerCase}","line":${m.line},"signature":"${jsonEscape(m.signature)}"}"""
              }.mkString("[", ",", "]")
              val implsJson = impls.map(s => jsonSymbol(s, workspace)).mkString("[", ",", "]")
              println(s"""{"definition":${jsonSymbol(sym, workspace)},"doc":$docJson,"members":$membersJson,"implementations":$implsJson,"importCount":$importCount}""")
            else
              println(s"Explanation of ${sym.kind.toString.toLowerCase} $symbol$pkg:\n")
              println(s"  Definition: $rel:${sym.line}")
              println(s"  Signature: ${sym.signature}")
              if sym.parents.nonEmpty then println(s"  Extends: ${sym.parents.mkString(", ")}")
              println()
              doc match
                case Some(d) =>
                  println("  Scaladoc:")
                  d.split("\n").foreach(l => println(s"    $l"))
                  println()
                case None =>
                  println("  Scaladoc: (none)\n")
              if members.nonEmpty then
                println(s"  Members (top ${members.size}):")
                members.foreach(m => println(s"    ${m.kind.toString.toLowerCase.padTo(5, ' ')} ${m.name}"))
                println()
              if impls.nonEmpty then
                println(s"  Implementations (top ${impls.size}):")
                impls.foreach(s => println(formatSymbol(s, workspace)))
                println()
              println(s"  Imported by: $importCount files")

    case "deps" =>
      rest.headOption match
        case None => println("Usage: scalex deps <symbol>")
        case Some(symbol) =>
          val (importDeps, bodyDeps) = extractDeps(idx, symbol, workspace)
          if jsonOutput then
            val iArr = importDeps.map { d =>
              val file = d.file.map(f => s""""${jsonEscape(workspace.relativize(f).toString)}"""").getOrElse("null")
              val line = d.line.map(_.toString).getOrElse("null")
              s"""{"name":"${jsonEscape(d.name)}","kind":"${jsonEscape(d.kind)}","file":$file,"line":$line,"package":"${jsonEscape(d.packageName)}"}"""
            }.mkString("[", ",", "]")
            val bArr = bodyDeps.map { d =>
              val file = d.file.map(f => s""""${jsonEscape(workspace.relativize(f).toString)}"""").getOrElse("null")
              val line = d.line.map(_.toString).getOrElse("null")
              s"""{"name":"${jsonEscape(d.name)}","kind":"${jsonEscape(d.kind)}","file":$file,"line":$line,"package":"${jsonEscape(d.packageName)}"}"""
            }.mkString("[", ",", "]")
            println(s"""{"imports":$iArr,"bodyReferences":$bArr}""")
          else
            if importDeps.isEmpty && bodyDeps.isEmpty then
              println(s"No dependencies found for \"$symbol\"")
              printNotFoundHint(symbol, idx, "deps", batchMode)
            else
              println(s"Dependencies of \"$symbol\":")
              if importDeps.nonEmpty then
                println(s"\n  Imports:")
                importDeps.take(limit).foreach { d =>
                  val loc = d.file.map(f => s" — ${workspace.relativize(f)}:${d.line.getOrElse(0)}").getOrElse("")
                  println(s"    ${d.kind.padTo(9, ' ')} ${d.name}$loc")
                }
                if importDeps.size > limit then println(s"    ... and ${importDeps.size - limit} more")
              if bodyDeps.nonEmpty then
                println(s"\n  Body references:")
                bodyDeps.take(limit).foreach { d =>
                  val loc = d.file.map(f => s" — ${workspace.relativize(f)}:${d.line.getOrElse(0)}").getOrElse("")
                  println(s"    ${d.kind.padTo(9, ' ')} ${d.name}$loc")
                }
                if bodyDeps.size > limit then println(s"    ... and ${bodyDeps.size - limit} more")

    case "context" =>
      rest.headOption match
        case None => println("Usage: scalex context <file:line>")
        case Some(arg) =>
          val parts = arg.split(":")
          if parts.length < 2 then
            println("Usage: scalex context <file:line> (e.g. src/Main.scala:42)")
          else
            val filePath = parts.dropRight(1).mkString(":")
            val lineNum = parts.last.toIntOption
            lineNum match
              case None => println(s"Invalid line number: ${parts.last}")
              case Some(line) =>
                val resolved = if Path.of(filePath).isAbsolute then Path.of(filePath) else workspace.resolve(filePath)
                val scopes = extractScopes(resolved, line)
                if jsonOutput then
                  val arr = scopes.map { s =>
                    s"""{"name":"${jsonEscape(s.name)}","kind":"${jsonEscape(s.kind)}","line":${s.line}}"""
                  }.mkString("[", ",", "]")
                  val rel = jsonEscape(workspace.relativize(resolved).toString)
                  println(s"""{"file":"$rel","line":$line,"scopes":$arr}""")
                else
                  val rel = workspace.relativize(resolved)
                  println(s"Context at $rel:$line:")
                  if scopes.isEmpty then println("  (no enclosing scopes found)")
                  else
                    scopes.foreach { s =>
                      println(s"  ${s.kind.padTo(9, ' ')} ${s.name} (line ${s.line})")
                    }

    case "diff" =>
      rest.headOption match
        case None => println("Usage: scalex diff <git-ref> (e.g. scalex diff HEAD~1)")
        case Some(ref) =>
          val changedFiles = runGitDiff(workspace, ref)
          if changedFiles.isEmpty then
            if jsonOutput then println("""{"added":[],"removed":[],"modified":[]}""")
            else println(s"No Scala files changed compared to $ref")
          else
            val added = mutable.ListBuffer.empty[DiffSymbol]
            val removed = mutable.ListBuffer.empty[DiffSymbol]
            val modified = mutable.ListBuffer.empty[(DiffSymbol, DiffSymbol)]

            changedFiles.take(limit * 5).foreach { relPath =>
              val currentPath = workspace.resolve(relPath)
              val currentSource = try Some(Files.readString(currentPath)) catch { case _: Exception => None }
              val oldSource = gitShowFile(workspace, ref, relPath)

              val currentSyms = currentSource.map(s => extractSymbolsFromSource(s, relPath)).getOrElse(Nil)
              val oldSyms = oldSource.map(s => extractSymbolsFromSource(s, relPath)).getOrElse(Nil)

              val currentByKey = currentSyms.map(s => (s.name, s.kind) -> s).toMap
              val oldByKey = oldSyms.map(s => (s.name, s.kind) -> s).toMap

              // Added: in current but not in old
              currentByKey.foreach { case (key, sym) =>
                if !oldByKey.contains(key) then added += sym
              }
              // Removed: in old but not in current
              oldByKey.foreach { case (key, sym) =>
                if !currentByKey.contains(key) then removed += sym
              }
              // Modified: in both but signature changed
              currentByKey.foreach { case (key, cSym) =>
                oldByKey.get(key).foreach { oSym =>
                  if cSym.signature != oSym.signature || cSym.line != oSym.line then
                    modified += ((oSym, cSym))
                }
              }
            }

            if jsonOutput then
              def diffSymJson(s: DiffSymbol): String =
                s"""{"name":"${jsonEscape(s.name)}","kind":"${s.kind.toString.toLowerCase}","file":"${jsonEscape(s.file)}","line":${s.line},"package":"${jsonEscape(s.packageName)}","signature":"${jsonEscape(s.signature)}"}"""
              val addedJson = added.take(limit).map(diffSymJson).mkString("[", ",", "]")
              val removedJson = removed.take(limit).map(diffSymJson).mkString("[", ",", "]")
              val modifiedJson = modified.take(limit).map { case (o, n) =>
                s"""{"old":${diffSymJson(o)},"new":${diffSymJson(n)}}"""
              }.mkString("[", ",", "]")
              println(s"""{"ref":"${jsonEscape(ref)}","filesChanged":${changedFiles.size},"added":$addedJson,"removed":$removedJson,"modified":$modifiedJson}""")
            else
              println(s"Symbol changes compared to $ref (${changedFiles.size} files changed):")
              if added.nonEmpty then
                println(s"\n  Added (${added.size}):")
                added.take(limit).foreach { s =>
                  println(s"    + ${s.kind.toString.toLowerCase.padTo(9, ' ')} ${s.name} — ${s.file}:${s.line}")
                }
                if added.size > limit then println(s"    ... and ${added.size - limit} more")
              if removed.nonEmpty then
                println(s"\n  Removed (${removed.size}):")
                removed.take(limit).foreach { s =>
                  println(s"    - ${s.kind.toString.toLowerCase.padTo(9, ' ')} ${s.name} — ${s.file}:${s.line}")
                }
                if removed.size > limit then println(s"    ... and ${removed.size - limit} more")
              if modified.nonEmpty then
                println(s"\n  Modified (${modified.size}):")
                modified.take(limit).foreach { case (o, n) =>
                  println(s"    ~ ${n.kind.toString.toLowerCase.padTo(9, ' ')} ${n.name} — ${n.file}:${n.line}")
                }
                if modified.size > limit then println(s"    ... and ${modified.size - limit} more")
              if added.isEmpty && removed.isEmpty && modified.isEmpty then
                println("  No symbol-level changes detected")

    case "ast-pattern" =>
      val results = astPatternSearch(idx, workspace, hasMethodFilter, extendsFilter, bodyContainsFilter, noTests, pathFilter, limit)
      if jsonOutput then
        val arr = results.map { m =>
          val rel = jsonEscape(workspace.relativize(m.file).toString)
          s"""{"name":"${jsonEscape(m.name)}","kind":"${m.kind.toString.toLowerCase}","file":"$rel","line":${m.line},"package":"${jsonEscape(m.packageName)}","signature":"${jsonEscape(m.signature)}"}"""
        }.mkString("[", ",", "]")
        println(arr)
      else
        val filters = List(
          hasMethodFilter.map(m => s"has-method=$m"),
          extendsFilter.map(e => s"extends=$e"),
          bodyContainsFilter.map(b => s"body-contains=\"$b\"")
        ).flatten.mkString(", ")
        if results.isEmpty then
          println(s"No types matching AST pattern ($filters)")
        else
          println(s"Types matching AST pattern ($filters) — ${results.size} found:")
          results.foreach { m =>
            val rel = workspace.relativize(m.file)
            val pkg = if m.packageName.nonEmpty then s" (${m.packageName})" else ""
            println(s"  ${m.kind.toString.toLowerCase.padTo(9, ' ')} ${m.name}$pkg — $rel:${m.line}")
          }

    case other =>
      println(s"Unknown command: $other")

@main def main(args: String*): Unit =
  val argList = args.toList

  if argList.contains("--version") then
    println(ScalexVersion)
    return

  val limit = argList.indexOf("--limit") match
    case -1 => 20
    case i => argList.lift(i + 1).flatMap(_.toIntOption).getOrElse(20)
  val kindFilter = argList.indexOf("--kind") match
    case -1 => None
    case i => argList.lift(i + 1)
  val verbose = argList.contains("--verbose")
  val categorize = !argList.contains("--flat")
  val noTests = argList.contains("--no-tests")
  val pathFilter: Option[String] = argList.indexOf("--path") match
    case -1 => None
    case i => argList.lift(i + 1).map(p => p.stripPrefix("/"))
  val contextLines: Int = argList.indexOf("-C") match
    case -1 => 0
    case i => argList.lift(i + 1).flatMap(_.toIntOption).getOrElse(0)
  val jsonOutput = argList.contains("--json")
  val countOnly = argList.contains("--count")
  val searchMode: Option[String] =
    if argList.contains("--exact") then Some("exact")
    else if argList.contains("--prefix") then Some("prefix")
    else None
  val definitionsOnly = argList.contains("--definitions-only")
  val categoryFilter: Option[String] = argList.indexOf("--category") match
    case -1 => None
    case i => argList.lift(i + 1)
  val grepPatterns: List[String] = argList.zipWithIndex.collect {
    case ("-e", i) if argList.lift(i + 1).exists(a => !a.startsWith("-")) => argList(i + 1)
  }
  val explicitWorkspace: Option[String] =
    val longIdx = argList.indexOf("--workspace")
    val shortIdx = argList.indexOf("-w")
    val idx = if longIdx >= 0 then longIdx else shortIdx
    if idx >= 0 then argList.lift(idx + 1) else None

  // New flags for new commands
  val inOwner: Option[String] = argList.indexOf("--in") match
    case -1 => None
    case i => argList.lift(i + 1)
  val ofTrait: Option[String] = argList.indexOf("--of") match
    case -1 => None
    case i => argList.lift(i + 1)
  val implLimit: Int = argList.indexOf("--impl-limit") match
    case -1 => 5
    case i => argList.lift(i + 1).flatMap(_.toIntOption).getOrElse(5)
  val goUp = !argList.contains("--down") || argList.contains("--up")
  val goDown = !argList.contains("--up") || argList.contains("--down")
  val inherited = argList.contains("--inherited")
  val architecture = argList.contains("--architecture")
  val hasMethodFilter: Option[String] = argList.indexOf("--has-method") match
    case -1 => None
    case i => argList.lift(i + 1)
  val extendsFilter: Option[String] = argList.indexOf("--extends") match
    case -1 => None
    case i => argList.lift(i + 1)
  val bodyContainsFilter: Option[String] = argList.indexOf("--body-contains") match
    case -1 => None
    case i => argList.lift(i + 1)

  val flagsWithArgs = Set("--limit", "--kind", "--workspace", "-w", "--path", "-C", "-e", "--category",
                           "--in", "--of", "--impl-limit", "--has-method", "--extends", "--body-contains")
  val cleanArgs = argList.filterNot(a => a.startsWith("--") || a == "-w" || a == "-C" || a == "-e" || a == "-c" || a == "--flat" || {
    val prev = argList.indexOf(a) - 1
    prev >= 0 && flagsWithArgs.contains(argList(prev))
  })

  cleanArgs match
    case Nil | List("help") =>
      println("""Scalex — Scala code intelligence for AI agents
        |
        |Commands:
        |  scalex search <query>           Search symbols by name          (aka: find symbol)
        |  scalex def <symbol>             Where is this symbol defined?   (aka: find definition)
        |  scalex impl <trait>             Who extends this trait/class?   (aka: find implementations)
        |  scalex refs <symbol>            Who uses this symbol?           (aka: find references)
        |  scalex imports <symbol>         Who imports this symbol?        (aka: import graph)
        |  scalex members <symbol>         What's inside this class/trait? (aka: list members)
        |  scalex doc <symbol>             Show scaladoc for a symbol      (aka: show docs)
        |  scalex overview                 Codebase summary                (aka: project overview)
        |  scalex symbols <file>           What's defined in this file?    (aka: file symbols)
        |  scalex file <query>             Search files by name            (aka: find file)
        |  scalex annotated <annotation>   Find symbols with annotation    (aka: find annotated)
        |  scalex grep <pattern>           Regex search in file contents   (aka: content search)
        |  scalex packages                 What packages exist?            (aka: list packages)
        |  scalex index                    Rebuild the index               (aka: reindex)
        |  scalex batch                    Run multiple queries at once    (aka: batch mode)
        |  scalex body <symbol>            Extract method/val/class body   (aka: show source)
        |  scalex hierarchy <symbol>       Full inheritance tree           (aka: type hierarchy)
        |  scalex overrides <method>       Find override implementations   (aka: find overrides)
        |  scalex explain <symbol>         Composite one-shot summary      (aka: explain symbol)
        |  scalex deps <symbol>            Show symbol dependencies        (aka: dependency graph)
        |  scalex context <file:line>      Show enclosing scopes at line   (aka: scope chain)
        |  scalex diff <git-ref>           Symbol-level diff vs git ref    (aka: symbol diff)
        |  scalex ast-pattern              Structural AST search           (aka: pattern search)
        |
        |Options:
        |  -w, --workspace PATH  Set workspace path (default: current directory)
        |  --limit N             Max results (default: 20)
        |  --kind K              Filter by kind: class, trait, object, def, val, type, enum, given, extension
        |  --verbose             Show signatures and extends clauses
        |  --categorize, -c      Group refs by category (default; kept for backwards compatibility)
        |  --flat                Refs: flat list instead of categorized (overrides default)
        |  --definitions-only    Search: only return class/trait/object/enum definitions
        |  --category CAT        Refs: filter to a single category (Definition/ExtendedBy/ImportedBy/UsedAsType/Usage/Comment)
        |  --no-tests            Exclude test files (test/, tests/, testing/, bench-*, *Spec.scala, etc.)
        |  --path PREFIX         Restrict results to files under PREFIX (e.g. compiler/src/)
        |  -C N                  Show N context lines around each reference (refs, grep)
        |  -e PATTERN            Grep: additional pattern (combine multiple with |); repeatable
        |  --count               Grep: show match/file count only, no full results
        |  --exact               Search: only exact name matches
        |  --prefix              Search: only exact + prefix matches
        |  --json                Output results as JSON (structured output for programmatic use)
        |  --version             Print version and exit
        |  --in OWNER            Body: restrict to members of the given enclosing type
        |  --of TRAIT            Overrides: restrict to implementations of the given trait
        |  --impl-limit N        Explain: max implementations to show (default: 5)
        |  --up                  Hierarchy: show only parents (default: both)
        |  --down                Hierarchy: show only children (default: both)
        |  --inherited           Members: include inherited members from parent types
        |  --architecture        Overview: show package dependency graph and hub types
        |  --has-method NAME     AST pattern: match types that have a method with NAME
        |  --extends TRAIT       AST pattern: match types that extend TRAIT
        |  --body-contains PAT   AST pattern: match types whose body contains PAT
        |
        |All commands accept an optional [workspace] positional arg or -w flag (default: current directory).
        |First run indexes the project (~3s for 14k files). Subsequent runs use cache (~300ms).
        |""".stripMargin)

    case "batch" :: rest =>
      val workspace = resolveWorkspace(explicitWorkspace.orElse(rest.headOption).getOrElse("."))
      val idx = WorkspaceIndex(workspace, needBlooms = true)
      idx.index()
      val reader = BufferedReader(InputStreamReader(System.in))
      var line = reader.readLine()
      while line != null do
        val parts = line.trim.split("\\s+").toList
        if parts.nonEmpty && parts.head.nonEmpty then
          val batchCmd = parts.head
          val batchRest = parts.tail
          println(s">>> $line")
          runCommand(batchCmd, batchRest, idx, workspace, limit, kindFilter, verbose, categorize, noTests, pathFilter, contextLines, jsonOutput, grepPatterns, countOnly, batchMode = true, searchMode, definitionsOnly, categoryFilter,
                     inOwner = inOwner, ofTrait = ofTrait, implLimit = implLimit, goUp = goUp, goDown = goDown, inherited = inherited, architecture = architecture,
                     hasMethodFilter = hasMethodFilter, extendsFilter = extendsFilter, bodyContainsFilter = bodyContainsFilter)
          println()
        line = reader.readLine()

    case cmd :: rest =>
      val (workspace, cmdRest) = explicitWorkspace match
        case Some(ws) =>
          (resolveWorkspace(ws), rest)
        case None =>
          cmd match
            case "index" | "packages" | "overview" | "ast-pattern" =>
              (resolveWorkspace(rest.headOption.getOrElse(".")), rest)
            case _ =>
              rest match
                case arg :: Nil => (resolveWorkspace("."), List(arg))
                case ws :: arg :: tail => (resolveWorkspace(ws), arg :: tail)
                case Nil => (resolveWorkspace("."), Nil)

      val bloomCmds = Set("refs", "imports")
      val idx = WorkspaceIndex(workspace, needBlooms = bloomCmds.contains(cmd))
      idx.index()
      runCommand(cmd, cmdRest, idx, workspace, limit, kindFilter, verbose, categorize, noTests, pathFilter, contextLines, jsonOutput, grepPatterns, countOnly, searchMode = searchMode, definitionsOnly = definitionsOnly, categoryFilter = categoryFilter,
                 inOwner = inOwner, ofTrait = ofTrait, implLimit = implLimit, goUp = goUp, goDown = goDown, inherited = inherited, architecture = architecture,
                 hasMethodFilter = hasMethodFilter, extendsFilter = extendsFilter, bodyContainsFilter = bodyContainsFilter)
