import scala.meta.*
import scala.collection.mutable
import scala.util.Using
import java.nio.file.Path
import java.io.{BufferedReader, InputStreamReader}
import scala.jdk.CollectionConverters.*

// ── Hierarchy building ──────────────────────────────────────────────────────

def buildHierarchy(idx: WorkspaceIndex, symbolName: String, goUp: Boolean, goDown: Boolean, maxDepth: Int, workspace: Path): Option[HierarchyTree] = {
  def walkUp(name: String, visited: Set[String], depth: Int): List[HierarchyTree] = {
    if depth >= maxDepth || visited.contains(name.toLowerCase) then Nil
    else {
      val newVisited = visited + name.toLowerCase
      idx.findDefinition(name).headOption.toList.flatMap { s =>
        s.parents.map { parentName =>
          idx.findDefinition(parentName).headOption match {
            case None =>
              val extNode = HierarchyNode(parentName, None, None, None, "", isExternal = true)
              HierarchyTree(extNode, Nil, Nil)
            case Some(pd) =>
              val pNode = HierarchyNode(pd.name, Some(pd.kind), Some(pd.file), Some(pd.line), pd.packageName, isExternal = false)
              val grandParents = walkUp(pd.name, newVisited, depth + 1)
              HierarchyTree(pNode, grandParents, Nil)
          }
        }
      }
    }
  }

  def walkDown(name: String, visited: Set[String], depth: Int): List[HierarchyTree] = {
    if depth >= maxDepth || visited.contains(name.toLowerCase) then Nil
    else {
      val newVisited = visited + name.toLowerCase
      idx.findImplementations(name).map { s =>
        val node = HierarchyNode(s.name, Some(s.kind), Some(s.file), Some(s.line), s.packageName, isExternal = false)
        if depth + 1 >= maxDepth then {
          val truncated = idx.findImplementations(s.name).size
          HierarchyTree(node, Nil, Nil, truncatedChildren = truncated)
        } else {
          val grandChildren = walkDown(s.name, newVisited, depth + 1)
          HierarchyTree(node, Nil, grandChildren)
        }
      }
    }
  }

  idx.findDefinition(symbolName).headOption.map { sym =>
    val rootNode = HierarchyNode(sym.name, Some(sym.kind), Some(sym.file), Some(sym.line), sym.packageName, isExternal = false)
    val parents = if goUp then walkUp(sym.name, Set.empty, 0) else Nil
    val children = if goDown then walkDown(sym.name, Set.empty, 0) else Nil
    HierarchyTree(rootNode, parents, children)
  }
}

// ── Override finding ────────────────────────────────────────────────────────

def findOverrides(idx: WorkspaceIndex, methodName: String, ofTrait: Option[String], limit: Int): List[OverrideInfo] = {
  val buf = mutable.ListBuffer.empty[OverrideInfo]

  def addMatchingMembers(s: SymbolInfo): Unit =
    extractMembers(s.file, s.name).filter(_.name == methodName).foreach { m =>
      buf += OverrideInfo(s.file, m.line, s.name, s.kind, m.signature, s.packageName)
    }

  ofTrait match
    case Some(traitName) =>
      idx.findImplementations(traitName).filter(s => typeKinds.contains(s.kind)).foreach(addMatchingMembers)
    case None =>
      // No declaring trait given: scan all types for a member with this name, capped by limit
      val allTypes = idx.symbols.filter(s => typeKinds.contains(s.kind))
      val iter = allTypes.iterator
      while iter.hasNext && buf.size < limit do addMatchingMembers(iter.next())

  buf.toList
}

// ── Dependency extraction ───────────────────────────────────────────────────

def extractDeps(idx: WorkspaceIndex, symbolName: String, workspace: Path, maxDepth: Int = 1): (importDeps: List[DepInfo], bodyDeps: List[DepInfo]) = {
  val allImportDeps = mutable.ListBuffer.empty[DepInfo]
  val allBodyDeps = mutable.ListBuffer.empty[DepInfo]
  val visited = mutable.HashSet.empty[String]

  def extractSingle(name: String, depth: Int): Unit = {
    if depth < maxDepth && !visited.contains(name.toLowerCase) then {
      visited += name.toLowerCase
      idx.findDefinition(name).headOption.filterNot(sym => isJavaFile(sym.file)).foreach { sym =>
        val seenNames = mutable.HashSet.empty[String]
        seenNames += name // avoid self-reference

        // Record a dependency on `depName` (resolved via the index) into `sink`
        def addDep(depName: String, sink: mutable.ListBuffer[DepInfo]): Unit = {
          if !seenNames.contains(depName) then {
            seenNames += depName
            idx.findDefinition(depName).headOption.foreach { f =>
              if !visited.contains(f.name.toLowerCase) then
                sink += DepInfo(f.name, f.kind.toString.toLowerCase, Some(f.file), Some(f.line), f.packageName, depth)
            }
          }
        }

        parseFile(sym.file).foreach { tree =>
          def findNode(t: Tree): Option[Tree] = {
            t match
              case d: Defn.Class if d.name.value == name => Some(d)
              case d: Defn.Trait if d.name.value == name => Some(d)
              case d: Defn.Object if d.name.value == name => Some(d)
              case d: Defn.Enum if d.name.value == name => Some(d)
              case d: Defn.Def if d.name.value == name => Some(d)
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
                    case importee: Importee.Name => addDep(importee.name.value, allImportDeps)
                    case importee: Importee.Rename => addDep(importee.name.value, allImportDeps)
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
                case Type.Name(typeName) if typeName != name => addDep(typeName, allBodyDeps)
                case Term.Name(termName) if termName != name => addDep(termName, allBodyDeps)
                case _ =>
              t.children.foreach(collectRefs)
            }
            collectRefs(node)
          }

          // Recurse into discovered deps at the next depth level
          if depth + 1 < maxDepth then {
            val newDeps = (allImportDeps.toList ++ allBodyDeps.toList)
              .filter(_.depth == depth)
              .map(_.name)
              .distinct
            newDeps.foreach(dep => extractSingle(dep, depth + 1))
          }
        }
      }
    }
  }

  extractSingle(symbolName, 0)
  (allImportDeps.toList, allBodyDeps.toList)
}

// ── Diff extraction ─────────────────────────────────────────────────────────

def runGitDiff(workspace: Path, ref: String): List[String] =
  runGitLines(workspace, "diff", "--name-only", ref) { lines =>
    lines.filter(f => f.endsWith(".scala") || f.endsWith(".java")).toList
  }.getOrElse(Nil)

def gitShowFile(workspace: Path, ref: String, relPath: String): Option[String] = {
  try {
    val pb = ProcessBuilder("git", "show", s"$ref:$relPath")
    pb.directory(workspace.toFile)
    pb.redirectErrorStream(false)
    val proc = pb.start()
    val content = Using.resource(BufferedReader(InputStreamReader(proc.getInputStream))) { reader =>
      reader.lines().iterator().asScala.mkString("\n")
    }
    val exitCode = proc.waitFor()
    if exitCode == 0 then Some(content) else None
  } catch {
    case _: java.io.IOException => None
  }
}

def extractSymbolsFromSource(source: String, filePath: String): List[DiffSymbol] =
  parseSource(source, filePath) match {
    case None => Nil
    case Some(tree) =>
      val (rawSymbols, pkg) = extractRawSymbols(tree)
      rawSymbols.map(r => DiffSymbol(r.name, r.kind, filePath, r.line, pkg, r.signature))
  }

// ── AST pattern search ──────────────────────────────────────────────────────

def astPatternSearch(idx: WorkspaceIndex, workspace: Path,
                     hasMethod: Option[String], extendsTrait: Option[String],
                     bodyContains: Option[String], noTests: Boolean,
                     pathFilter: Option[String], excludePath: Option[String] = None,
                     limit: Int): List[AstPatternMatch] = {
  val keep = pathPredicate(noTests, pathFilter, excludePath, workspace)
  var candidates = idx.symbols.filter(s => typeKinds.contains(s.kind) && keep(s.file))

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
