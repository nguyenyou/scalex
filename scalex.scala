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

val ScalexVersion = "1.3.0"

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
    signature: String = ""
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
      val path = line.substring(tabIdx + 1)
      if path.endsWith(".scala") then
        // Format: "<mode> <oid> <stage>\t<path>" — extract OID between first and second space
        val sp1 = line.indexOf(' ')
        if sp1 >= 0 then
          val sp2 = line.indexOf(' ', sp1 + 1)
          if sp2 > sp1 then Some(GitFile(workspace.resolve(path), line.substring(sp1 + 1, sp2)))
          else None
        else None
      else None
  }.toList
  proc.waitFor()
  files

// ── Symbol extraction + bloom filter ────────────────────────────────────────

// Lightweight CharSequence view — avoids allocating a new String per identifier
private class SubSeq(val s: String, val start: Int, val end: Int) extends CharSequence:
  def length(): Int = end - start
  def charAt(index: Int): Char = s.charAt(start + index)
  def subSequence(s2: Int, e2: Int): CharSequence = SubSeq(s, start + s2, start + e2)

def buildBloomFilterFromSource(source: String): BloomFilter[CharSequence] =
  val expected = math.max(500, source.length / 15)
  val bloom = BloomFilter.create(Funnels.unencodedCharsFunnel(), expected, 0.01)
  var i = 0
  val len = source.length
  while i < len do
    if source(i).isLetter || source(i) == '_' then
      val start = i
      while i < len && (source(i).isLetterOrDigit || source(i) == '_') do i += 1
      if i - start >= 2 then bloom.put(SubSeq(source, start, i))
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
  val buf = mutable.ListBuffer.empty[SymbolInfo]
  val importBuf = mutable.ListBuffer.empty[String]
  val aliases = mutable.Map.empty[String, String]

  // Single-pass: extract symbols AND imports in one traversal
  def visit(t: Tree): Unit = {
    t match
      case i: Import =>
        importBuf += i.toString()
        i.importers.foreach { importer =>
          importer.importees.foreach {
            case r: Importee.Rename => aliases(r.name.value) = r.rename.value
            case _ =>
          }
        }
      case d: Defn.Class =>
        val parents = extractParents(d.templ)
        val tparams = d.tparamClause.values.map(_.name.value)
        val sig = buildSignature(d.name.value, "class", parents, tparams)
        buf += SymbolInfo(d.name.value, SymbolKind.Class, file, d.pos.startLine + 1, pkg, parents, sig)
      case d: Defn.Trait =>
        val parents = extractParents(d.templ)
        val tparams = d.tparamClause.values.map(_.name.value)
        val sig = buildSignature(d.name.value, "trait", parents, tparams)
        buf += SymbolInfo(d.name.value, SymbolKind.Trait, file, d.pos.startLine + 1, pkg, parents, sig)
      case d: Defn.Object =>
        val parents = extractParents(d.templ)
        val sig = buildSignature(d.name.value, "object", parents)
        buf += SymbolInfo(d.name.value, SymbolKind.Object, file, d.pos.startLine + 1, pkg, parents, sig)
      case d: Defn.Enum =>
        val parents = extractParents(d.templ)
        val tparams = d.tparamClause.values.map(_.name.value)
        val sig = buildSignature(d.name.value, "enum", parents, tparams)
        buf += SymbolInfo(d.name.value, SymbolKind.Enum, file, d.pos.startLine + 1, pkg, parents, sig)
      case d: Defn.Given =>
        if d.name.value.nonEmpty then
          buf += SymbolInfo(d.name.value, SymbolKind.Given, file, d.pos.startLine + 1, pkg, Nil, s"given ${d.name.value}")
      case d: Defn.GivenAlias =>
        if d.name.value.nonEmpty then
          val sig = s"given ${d.name.value}: ${d.decltpe.toString()}"
          buf += SymbolInfo(d.name.value, SymbolKind.Given, file, d.pos.startLine + 1, pkg, Nil, sig)
      case d: Defn.Type =>
        val sig = s"type ${d.name.value} = ${d.body.toString().take(60)}"
        buf += SymbolInfo(d.name.value, SymbolKind.Type, file, d.pos.startLine + 1, pkg, Nil, sig)
      case d: Defn.Def =>
        val params = d.paramClauses.map(_.values.map(p => s"${p.name.value}: ${p.decltpe.map(_.toString()).getOrElse("?")}").mkString(", ")).mkString("(", ")(", ")")
        val ret = d.decltpe.map(t => s": ${t.toString()}").getOrElse("")
        val sig = s"def ${d.name.value}$params$ret"
        buf += SymbolInfo(d.name.value, SymbolKind.Def, file, d.pos.startLine + 1, pkg, Nil, sig)
      case d: Defn.Val =>
        d.pats.foreach {
          case Pat.Var(name) =>
            val tpe = d.decltpe.map(t => s": ${t.toString()}").getOrElse("")
            buf += SymbolInfo(name.value, SymbolKind.Val, file, d.pos.startLine + 1, pkg, Nil, s"val ${name.value}$tpe")
          case _ =>
        }
      case d: Defn.ExtensionGroup =>
        val recv = d.paramClauses.headOption.flatMap(_.values.headOption).map(p =>
          s"(${p.name.value}: ${p.decltpe.map(_.toString()).getOrElse("?")})"
        ).getOrElse("")
        buf += SymbolInfo("<extension>", SymbolKind.Extension, file, d.pos.startLine + 1, pkg, Nil, s"extension $recv")
      case _ =>
    t.children.foreach(visit)
  }

  visit(tree)
  (buf.toList, bloom, importBuf.toList, aliases.toMap)

// ── Binary persistence ──────────────────────────────────────────────────────

object IndexPersistence:
  private val MAGIC = 0x53584458
  private val VERSION: Byte = 4

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
            syms += SymbolInfo(name, kind, workspace.resolve(relPath), line, pkg, parents, sig)
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
  private var parentIndex: Map[String, List[SymbolInfo]] = Map.empty

  private var distinctSymbols: List[SymbolInfo] = Nil
  private var packageToSymbols: Map[String, Set[String]] = Map.empty
  private var indexedByPath: Map[String, IndexedFile] = Map.empty
  private var aliasIndex: Map[String, List[(IndexedFile, String)]] = Map.empty

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
    indexedFiles.foreach { f =>
      f.symbols.foreach { s =>
        allSyms += s
        byPath.getOrElseUpdate(s.file, mutable.ListBuffer.empty) += s
        byName.getOrElseUpdate(s.name.toLowerCase, mutable.ListBuffer.empty) += s
        if s.packageName.nonEmpty then pkgs += s.packageName
        s.parents.foreach { p =>
          pIdx.getOrElseUpdate(p.toLowerCase, mutable.ListBuffer.empty) += s
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
    val seen = java.util.concurrent.ConcurrentHashMap.newKeySet[Long]()
    allCandidates.asJava.parallelStream().forEach { idxFile =>
      if System.nanoTime() < deadline then {
        val path = workspace.resolve(idxFile.relativePath)
        val lines = try Files.readAllLines(path) catch
          case _: Exception => java.util.Collections.emptyList[String]()
        val aliasName = fileAliasMap.get(idxFile.relativePath)
        val fileHash = idxFile.relativePath.hashCode.toLong << 32
        var idx = 0
        val sz = lines.size()
        while idx < sz && System.nanoTime() < deadline do {
          val line = lines.get(idx)
          val lineNum = idx + 1
          val key = fileHash | lineNum.toLong
          if containsWord(line, name) && seen.add(key) then
            results.add(Reference(path, lineNum, line.trim))
          else aliasName match
            case Some(alias) if containsWord(line, alias) && seen.add(key) =>
              results.add(Reference(path, lineNum, line.trim, Some(s"via alias $alias")))
            case _ =>
          idx += 1
        }
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
    val resultPaths = java.util.concurrent.ConcurrentHashMap.newKeySet[Long]()
    candidates.asJava.parallelStream().forEach { idxFile =>
      if System.nanoTime() < deadline then {
        val path = workspace.resolve(idxFile.relativePath)
        val lines = try Files.readAllLines(path) catch
          case _: Exception => java.util.Collections.emptyList[String]()
        val fileHash = idxFile.relativePath.hashCode.toLong << 32
        var idx = 0
        val sz = lines.size()
        while idx < sz && System.nanoTime() < deadline do {
          val line = lines.get(idx)
          if line.trim.startsWith("import ") && containsWord(line, name) then
            results.add(Reference(path, idx + 1, line.trim))
            resultPaths.add(fileHash | (idx + 1).toLong)
          idx += 1
        }
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
                val lines = Files.readAllLines(path)
                val fileHash = idxFile.relativePath.hashCode.toLong << 32
                var lineIdx = 0
                val sz = lines.size()
                while lineIdx < sz do {
                  if lines.get(lineIdx).trim == imp.trim then
                    val key = fileHash | (lineIdx + 1).toLong
                    if !resultPaths.contains(key) then
                      results.add(Reference(path, lineIdx + 1, lines.get(lineIdx).trim))
                      resultPaths.add(key)
                  lineIdx += 1
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

// ── CLI ─────────────────────────────────────────────────────────────────────

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

def printNotFoundHint(symbol: String, idx: WorkspaceIndex, cmd: String): Unit =
  println(s"  Hint: scalex indexes ${idx.fileCount} git-tracked .scala files.")
  if idx.parseFailures > 0 then
    println(s"  ${idx.parseFailures} files had parse errors (run `scalex index --verbose` to list them).")
  println(s"  Fallback: use Grep, Glob, or Read tools to search manually.")

def resolveWorkspace(path: String): Path =
  val p = Path.of(path).toAbsolutePath.normalize
  if Files.isDirectory(p) then p else p.getParent

def parseWorkspaceAndArg(rest: List[String]): Option[(Path, String)] =
  rest match
    case a :: Nil => Some((resolveWorkspace("."), a))
    case ws :: a :: _ => Some((resolveWorkspace(ws), a))
    case _ => None

def runCommand(cmd: String, rest: List[String], idx: WorkspaceIndex, workspace: Path,
               limit: Int, kindFilter: Option[String], verbose: Boolean, categorize: Boolean): Unit =
  val fmt = if verbose then formatSymbolVerbose else formatSymbol
  cmd match
    case "index" =>
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
          kindFilter.foreach { k =>
            val kk = k.toLowerCase
            results = results.filter(_.kind.toString.toLowerCase == kk)
          }
          if results.isEmpty then
            println(s"Found 0 symbols matching \"$query\"")
            printNotFoundHint(query, idx, "search")
          else
            println(s"Found ${results.size} symbols matching \"$query\":")
            results.take(limit).foreach(s => println(fmt(s, workspace)))
            if results.size > limit then println(s"  ... and ${results.size - limit} more")

    case "def" =>
      rest.headOption match
        case None => println("Usage: scalex def <symbol>")
        case Some(symbol) =>
          val results = idx.findDefinition(symbol)
          if results.isEmpty then
            println(s"Definition of \"$symbol\": not found")
            printNotFoundHint(symbol, idx, "def")
          else
            println(s"Definition of \"$symbol\":")
            results.foreach(s => println(fmt(s, workspace)))

    case "impl" =>
      rest.headOption match
        case None => println("Usage: scalex impl <trait>")
        case Some(symbol) =>
          val results = idx.findImplementations(symbol)
          if results.isEmpty then
            println(s"No implementations of \"$symbol\" found")
            printNotFoundHint(symbol, idx, "impl")
          else
            println(s"Implementations of \"$symbol\" — ${results.size} found:")
            results.take(limit).foreach(s => println(fmt(s, workspace)))
            if results.size > limit then println(s"  ... and ${results.size - limit} more")

    case "refs" =>
      rest.headOption match
        case None => println("Usage: scalex refs <symbol>")
        case Some(symbol) =>
          val targetPkgs = idx.symbolsByName.getOrElse(symbol.toLowerCase, Nil).map(_.packageName).toSet
          if categorize then
            val grouped = idx.categorizeReferences(symbol)
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
                    entries.take(limit).foreach((_, r, _) => println(s"    ${formatRef(r, workspace)}"))
                    if entries.size > limit then println(s"      ... and ${entries.size - limit} more")
                  }
                }
            }
          else
            val results = idx.findReferences(symbol)
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
                println(formatRef(r, workspace))
                shown += 1
            }
            if results.size > limit then println(s"  ... and ${results.size - limit} more")

    case "imports" =>
      rest.headOption match
        case None => println("Usage: scalex imports <symbol>")
        case Some(symbol) =>
          val results = idx.findImports(symbol)
          if results.isEmpty then
            println(s"No imports of \"$symbol\" found")
            printNotFoundHint(symbol, idx, "imports")
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
          if results.isEmpty then println(s"No symbols found in $file")
          else
            println(s"Symbols in $file:")
            results.foreach(s => println(fmt(s, workspace)))

    case "file" =>
      rest.headOption match
        case None => println("Usage: scalex file <query>")
        case Some(query) =>
          val results = idx.searchFiles(query)
          if results.isEmpty then
            println(s"Found 0 files matching \"$query\"")
            println(s"  Hint: scalex indexes ${idx.fileCount} git-tracked .scala files.")
          else
            println(s"Found ${results.size} files matching \"$query\":")
            results.take(limit).foreach(f => println(s"  $f"))
            if results.size > limit then println(s"  ... and ${results.size - limit} more")

    case "packages" =>
      println(s"Packages (${idx.packages.size}):")
      idx.packages.toList.sorted.foreach(p => println(s"  $p"))

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
  val categorize = argList.contains("--categorize")

  val cleanArgs = argList.filterNot(a => a.startsWith("--") || {
    val prev = argList.indexOf(a) - 1
    prev >= 0 && (argList(prev) == "--limit" || argList(prev) == "--kind")
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
        |  scalex symbols <file>           What's defined in this file?    (aka: file symbols)
        |  scalex file <query>            Search files by name            (aka: find file)
        |  scalex packages                 What packages exist?            (aka: list packages)
        |  scalex index                    Rebuild the index               (aka: reindex)
        |  scalex batch                    Run multiple queries at once    (aka: batch mode)
        |
        |Options:
        |  --limit N       Max results (default: 20)
        |  --kind K        Filter by kind: class, trait, object, def, val, type, enum, given, extension
        |  --verbose       Show signatures and extends clauses
        |  --categorize    Group refs by: definition, extends, import, type usage, comment
        |  --version       Print version and exit
        |
        |All commands accept an optional [workspace] path (default: current directory).
        |First run indexes the project (~3s for 14k files). Subsequent runs use cache (~300ms).
        |""".stripMargin)

    case "batch" :: rest =>
      val workspace = resolveWorkspace(rest.headOption.getOrElse("."))
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
          runCommand(batchCmd, batchRest, idx, workspace, limit, kindFilter, verbose, categorize)
          println()
        line = reader.readLine()

    case cmd :: rest =>
      val (workspace, cmdRest) = cmd match
        case "index" | "packages" =>
          (resolveWorkspace(rest.headOption.getOrElse(".")), rest)
        case _ =>
          rest match
            case arg :: Nil => (resolveWorkspace("."), List(arg))
            case ws :: arg :: tail => (resolveWorkspace(ws), arg :: tail)
            case Nil => (resolveWorkspace("."), Nil)

      val bloomCmds = Set("refs", "imports")
      val idx = WorkspaceIndex(workspace, needBlooms = bloomCmds.contains(cmd))
      idx.index()
      runCommand(cmd, cmdRest, idx, workspace, limit, kindFilter, verbose, categorize)
