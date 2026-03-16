import scala.meta.*
import scala.collection.mutable
import java.nio.file.{Files, Path}
import java.io.{BufferedReader, InputStreamReader}
import scala.jdk.CollectionConverters.*

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

  val parents = if goUp then walkUp(sym.name, Set.empty) else Nil
  val children = if goDown then walkDown(sym.name, Set.empty) else Nil
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

// ── Dependency extraction ───────────────────────────────────────────────────

def extractDeps(idx: WorkspaceIndex, symbolName: String, workspace: Path): (importDeps: List[DepInfo], bodyDeps: List[DepInfo]) = {
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

// ── AST pattern search ──────────────────────────────────────────────────────

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
