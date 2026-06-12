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
              HierarchyTree(HierarchyNode(parentName, None), Nil, Nil)
            case Some(pd) =>
              val grandParents = walkUp(pd.name, newVisited, depth + 1)
              HierarchyTree(HierarchyNode(pd.name, Some(pd)), grandParents, Nil)
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
        val node = HierarchyNode(s.name, Some(s))
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
    val parents = if goUp then walkUp(sym.name, Set.empty, 0) else Nil
    val children = if goDown then walkDown(sym.name, Set.empty, 0) else Nil
    HierarchyTree(HierarchyNode(sym.name, Some(sym)), parents, children)
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
                     limit: Int): List[SymbolInfo] = {
  val keep = pathPredicate(noTests, pathFilter, excludePath, workspace)
  val candidates = idx.symbols.filter { s =>
    typeKinds.contains(s.kind) && keep(s.file) &&
    extendsTrait.forall(t => s.parents.exists(_.equalsIgnoreCase(t)))
  }

  val buf = mutable.ListBuffer.empty[SymbolInfo]
  val iter = candidates.iterator
  while iter.hasNext && buf.size < limit do {
    val s = iter.next()
    // The expensive per-file parses run only for candidates that pass the cheap filters
    val ok = hasMethod.forall(m => extractMembers(s.file, s.name).exists(_.name == m)) &&
      bodyContains.forall(p => extractBody(s.file, s.name, None).exists(_.sourceText.contains(p)))
    if ok then buf += s
  }
  buf.toList
}
