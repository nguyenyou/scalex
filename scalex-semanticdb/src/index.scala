import scala.collection.mutable
import java.nio.file.{Files, Path}
import java.io.{BufferedInputStream, BufferedOutputStream, DataInputStream, DataOutputStream}

// ── Binary persistence ──────────────────────────────────────────────────────

object SemPersistence:
  private val MAGIC = 0x53454D44 // "SEMD"
  private val VERSION: Byte = 2

  def indexPath(workspace: Path): Path =
    workspace.resolve(".scalex").resolve("semanticdb.bin")

  def save(workspace: Path, documents: List[IndexedDocument]): Unit =
    val dir = workspace.resolve(".scalex")
    if !Files.exists(dir) then Files.createDirectories(dir)

    val stringTable = mutable.LinkedHashMap.empty[String, Int]
    def intern(s: String): Int =
      stringTable.getOrElseUpdate(s, stringTable.size)

    // Pre-populate string table
    documents.foreach { doc =>
      intern(doc.uri)
      intern(doc.md5)
      doc.symbols.foreach { s =>
        intern(s.fqn); intern(s.displayName); intern(s.owner)
        intern(s.sourceUri); intern(s.signature)
        s.parents.foreach(intern)
        s.overriddenSymbols.foreach(intern)
        s.annotations.foreach(intern)
      }
      doc.occurrences.foreach { o =>
        intern(o.file); intern(o.symbol)
      }
    }

    val out = DataOutputStream(BufferedOutputStream(Files.newOutputStream(indexPath(workspace)), 1 << 16))
    try
      out.writeInt(MAGIC)
      out.writeByte(VERSION)

      // String table
      val strings = stringTable.keys.toArray
      out.writeInt(strings.length)
      strings.foreach(out.writeUTF)

      // Documents
      out.writeInt(documents.size)
      documents.foreach { doc =>
        out.writeInt(intern(doc.uri))
        out.writeInt(intern(doc.md5))

        // Symbols
        out.writeInt(doc.symbols.size)
        doc.symbols.foreach { s =>
          out.writeInt(intern(s.fqn))
          out.writeInt(intern(s.displayName))
          out.writeByte(s.kind.id)
          out.writeInt(s.properties)
          out.writeInt(intern(s.owner))
          out.writeInt(intern(s.sourceUri))
          out.writeInt(intern(s.signature))
          out.writeShort(s.parents.size)
          s.parents.foreach(p => out.writeInt(intern(p)))
          out.writeShort(s.overriddenSymbols.size)
          s.overriddenSymbols.foreach(p => out.writeInt(intern(p)))
          out.writeShort(s.annotations.size)
          s.annotations.foreach(a => out.writeInt(intern(a)))
        }

        // Occurrences
        out.writeInt(doc.occurrences.size)
        doc.occurrences.foreach { o =>
          out.writeInt(intern(o.file))
          out.writeInt(o.range.startLine)
          out.writeShort(o.range.startChar)
          out.writeInt(o.range.endLine)
          out.writeShort(o.range.endChar)
          out.writeInt(intern(o.symbol))
          out.writeByte(o.role.id)
        }

      }
    finally out.close()

  def load(workspace: Path): Option[List[IndexedDocument]] =
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

        val docCount = in.readInt()
        val docs = List.newBuilder[IndexedDocument]

        var di = 0
        while di < docCount do
          val uri = strings(in.readInt())
          val md5 = strings(in.readInt())

          // Symbols
          val symCount = in.readInt()
          val syms = List.newBuilder[SemSymbol]
          var si = 0
          while si < symCount do
            val fqn = strings(in.readInt())
            val displayName = strings(in.readInt())
            val kind = SemKind.fromId(in.readByte())
            val properties = in.readInt()
            val owner = strings(in.readInt())
            val sourceUri = strings(in.readInt())
            val signature = strings(in.readInt())
            val parentCount = in.readUnsignedShort()
            val parents = (0 until parentCount).map(_ => strings(in.readInt())).toList
            val overriddenCount = in.readUnsignedShort()
            val overridden = (0 until overriddenCount).map(_ => strings(in.readInt())).toList
            val annotCount = in.readUnsignedShort()
            val annots = (0 until annotCount).map(_ => strings(in.readInt())).toList
            syms += SemSymbol(fqn, displayName, kind, properties, owner, sourceUri, signature, parents, overridden, annots)
            si += 1

          // Occurrences
          val occCount = in.readInt()
          val occs = List.newBuilder[SemOccurrence]
          var oi = 0
          while oi < occCount do
            val file = strings(in.readInt())
            val sl = in.readInt(); val sc = in.readUnsignedShort()
            val el = in.readInt(); val ec = in.readUnsignedShort()
            val symbol = strings(in.readInt())
            val role = OccRole.fromId(in.readByte())
            occs += SemOccurrence(file, SemRange(sl, sc, el, ec), symbol, role)
            oi += 1

          docs += IndexedDocument(uri, md5, syms.result(), occs.result())
          di += 1

        Some(docs.result())
      finally in.close()
    catch
      case _: Exception =>
        System.err.println("scalex-semanticdb: index load failed, rebuilding")
        None

// ── Timing helper ───────────────────────────────────────────────────────────

object SemTimings:
  private var enabled: Boolean = false
  private val phases = mutable.ListBuffer.empty[(name: String, ms: Long)]

  def enable(): Unit = enabled = true

  def phase[T](name: String)(body: => T): T =
    if !enabled then body
    else
      val start = System.nanoTime()
      val result = body
      val elapsed = (System.nanoTime() - start) / 1_000_000
      phases += ((name = name, ms = elapsed))
      result

  def report(): Unit =
    if enabled && phases.nonEmpty then
      System.err.println("── timings ──")
      phases.foreach { (name, ms) =>
        System.err.println(f"  $name%-30s ${ms}%6d ms")
      }
      System.err.println("─────────────")

// ── Workspace index ─────────────────────────────────────────────────────────

class SemIndex(val workspace: Path):
  private var documents: List[IndexedDocument] = Nil
  var buildTimeMs: Long = 0
  var cachedLoad: Boolean = false

  // ── Stats ──────────────────────────────────────────────────────────────

  def fileCount: Int = documents.size
  def symbolCount: Int = documents.iterator.map(_.symbols.size).sum
  def occurrenceCount: Int = documents.iterator.map(_.occurrences.size).sum

  // ── Primary indexes ────────────────────────────────────────────────────

  private lazy val allSymbols: List[SemSymbol] =
    SemTimings.phase("build-allSymbols") {
      documents.flatMap(_.symbols)
    }

  lazy val symbolByFqn: Map[String, SemSymbol] =
    SemTimings.phase("build-symbolByFqn") {
      val m = mutable.HashMap.empty[String, SemSymbol]
      allSymbols.foreach(s => m.getOrElseUpdate(s.fqn, s))
      m.toMap
    }

  lazy val symbolsByName: Map[String, List[SemSymbol]] =
    SemTimings.phase("build-symbolsByName") {
      allSymbols.groupBy(_.displayName.toLowerCase)
    }

  lazy val symbolsByFile: Map[String, List[SemSymbol]] =
    SemTimings.phase("build-symbolsByFile") {
      allSymbols.groupBy(_.sourceUri)
    }

  private lazy val allOccurrences: List[SemOccurrence] =
    SemTimings.phase("build-allOccurrences") {
      documents.flatMap(_.occurrences)
    }

  lazy val occurrencesBySymbol: Map[String, List[SemOccurrence]] =
    SemTimings.phase("build-occsBySymbol") {
      allOccurrences.groupBy(_.symbol)
    }

  lazy val occurrencesByFile: Map[String, List[SemOccurrence]] =
    SemTimings.phase("build-occsByFile") {
      allOccurrences.groupBy(_.file)
    }

  lazy val subtypeIndex: Map[String, List[String]] =
    SemTimings.phase("build-subtypeIndex") {
      val idx = mutable.HashMap.empty[String, mutable.ListBuffer[String]]
      allSymbols.foreach { sym =>
        sym.parents.foreach { parentFqn =>
          idx.getOrElseUpdate(parentFqn, mutable.ListBuffer.empty) += sym.fqn
        }
      }
      idx.map((k, v) => k -> v.distinct.toList).toMap
    }

  lazy val memberIndex: Map[String, List[SemSymbol]] =
    SemTimings.phase("build-memberIndex") {
      allSymbols
        .filter(s => s.owner.nonEmpty && s.kind != SemKind.Parameter && s.kind != SemKind.TypeParam)
        .groupBy(_.owner)
    }

  /** Map from symbol FQN → (file, definition range). Built from DEFINITION occurrences. */
  lazy val definitionRanges: Map[String, (file: String, range: SemRange)] =
    SemTimings.phase("build-definitionRanges") {
      val m = mutable.HashMap.empty[String, (file: String, range: SemRange)]
      allOccurrences.foreach { occ =>
        if occ.role == OccRole.Definition && !m.contains(occ.symbol) then
          m(occ.symbol) = (file = occ.file, range = occ.range)
      }
      m.toMap
    }

  // ── Query helpers ──────────────────────────────────────────────────────

  /** Resolve a user query to a symbol FQN. Tries: exact FQN, suffix match, display name.
    * Results are sorted: non-local before local, source before generated, by kind rank
    * (classes first), then shorter FQN first, then alphabetically. */
  def resolveSymbol(query: String): List[SemSymbol] =
    val raw =
      // 1. Exact FQN match
      symbolByFqn.get(query).map(List(_)).getOrElse {
        // 2. Suffix match on FQN (e.g. "List#map()." matches "scala/collection/immutable/List#map().")
        val suffixMatches = allSymbols.filter(_.fqn.endsWith(query))
        if suffixMatches.nonEmpty then suffixMatches
        else
          // 3. Display name match (case-insensitive)
          val nameMatches = symbolsByName.getOrElse(query.toLowerCase, Nil)
          if nameMatches.nonEmpty then nameMatches
          else
            // 4. Partial display name match
            allSymbols.filter(_.displayName.toLowerCase.contains(query.toLowerCase))
      }
    // Deterministic ordering: non-local before local, source before generated, then by kind rank, then shorter FQN first
    raw.sortBy(s => (isLocal = if s.kind == SemKind.Local then 1 else 0, isGenerated = if isGenerated(s.sourceUri) then 1 else 0, kindRank = kindRank(s.kind), fqnLen = s.fqn.length, fqn = s.fqn))

  private def isGenerated(uri: String): Boolean =
    uri.startsWith("out/") || uri.contains("compileScalaPB.dest") || uri.contains("compilePB.dest")

  private def kindRank(k: SemKind): Int = k match
    case SemKind.Class       => 0
    case SemKind.Trait       => 1
    case SemKind.Object      => 2
    case SemKind.Interface   => 3
    case SemKind.Type        => 4
    case SemKind.Method      => 5
    case SemKind.Field       => 6
    case SemKind.Constructor => 7
    case _                   => 8

  // ── Build ──────────────────────────────────────────────────────────────

  /** Mill generated-source markers in URIs. Files with these are copies of real sources. */
  private val generatedSourceMarkers = List(
    "jsSharedSources.dest/", "jvmSharedSources.dest/",
    "nativeSharedSources.dest/",
  )

  /** Deduplicate documents where a generated copy duplicates a real source file.
    * Extracts the "source identity" from each URI (package path + filename) and
    * when two documents share the same identity, keeps the non-generated one. */
  private def deduplicateDocuments(docs: List[IndexedDocument]): List[IndexedDocument] =
    // Group by source identity: extract the meaningful suffix from the URI
    // e.g. "out/modules/foo/js/jsSharedSources.dest/com/example/Foo.scala" → "com/example/Foo.scala"
    // e.g. "modules/foo/shared/src/com/example/Foo.scala" → "com/example/Foo.scala"
    def sourceIdentity(uri: String): String =
      // For generated sources, extract path after the marker
      val genMatch = generatedSourceMarkers.collectFirst {
        case marker if uri.contains(marker) =>
          uri.substring(uri.indexOf(marker) + marker.length)
      }
      genMatch.getOrElse {
        // For real sources, extract path after last "src/" segment
        val srcIdx = uri.lastIndexOf("src/")
        if srcIdx >= 0 then uri.substring(srcIdx + 4)
        else uri
      }

    val grouped = docs.groupBy(d => sourceIdentity(d.uri))
    grouped.values.map { group =>
      if group.size == 1 then group.head
      else
        // Prefer non-generated source
        group.find(d => !generatedSourceMarkers.exists(d.uri.contains))
          .getOrElse(group.head)
    }.toList

  /** Build the index: discover .semanticdb files, parse, and persist. */
  def build(semanticdbPath: Option[String] = None): Unit =
    val start = System.currentTimeMillis()

    // Try loading from cache first
    SemPersistence.load(workspace) match
      case Some(cached) =>
        documents = cached
        cachedLoad = true
        buildTimeMs = System.currentTimeMillis() - start
        return
      case None => ()

    // Discover and parse
    val files = SemTimings.phase("discover") {
      semanticdbPath match
        case Some(p) => Discovery.discoverFromExplicitPath(Path.of(p))
        case None    => Discovery.discoverSemanticdbFiles(workspace)
    }

    if files.isEmpty then
      documents = Nil
      buildTimeMs = System.currentTimeMillis() - start
      return

    val parsed = SemTimings.phase("parse-semanticdb") {
      Parser.loadDocuments(files)
    }

    documents = SemTimings.phase("dedup-documents") {
      deduplicateDocuments(parsed)
    }

    // Persist
    SemTimings.phase("save-index") {
      SemPersistence.save(workspace, documents)
    }

    buildTimeMs = System.currentTimeMillis() - start

  /** Force rebuild (ignores cache). */
  def rebuild(semanticdbPath: Option[String] = None): Unit =
    val start = System.currentTimeMillis()

    val files = SemTimings.phase("discover") {
      semanticdbPath match
        case Some(p) => Discovery.discoverFromExplicitPath(Path.of(p))
        case None    => Discovery.discoverSemanticdbFiles(workspace)
    }

    if files.isEmpty then
      documents = Nil
      buildTimeMs = System.currentTimeMillis() - start
      return

    val parsed = SemTimings.phase("parse-semanticdb") {
      Parser.loadDocuments(files)
    }

    documents = SemTimings.phase("dedup-documents") {
      deduplicateDocuments(parsed)
    }

    SemTimings.phase("save-index") {
      SemPersistence.save(workspace, documents)
    }

    cachedLoad = false
    buildTimeMs = System.currentTimeMillis() - start
