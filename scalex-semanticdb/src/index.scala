import scala.collection.mutable
import java.nio.file.{Files, Path}
import java.io.{BufferedInputStream, BufferedOutputStream, DataInputStream, DataOutputStream}
import scala.jdk.CollectionConverters.*

// ── Binary persistence ──────────────────────────────────────────────────────

object SemPersistence:
  private val MAGIC = 0x53454D44 // "SEMD"
  private val VERSION: Byte = 2

  def indexPath(workspace: Path): Path =
    workspace.resolve(".scalex").resolve("semanticdb.bin")

  private def dirsManifestPath(workspace: Path): Path =
    workspace.resolve(".scalex").resolve("semanticdb-dirs.txt")

  /** Save the list of semanticdb parent directories for cheap staleness checks. */
  def saveDirsManifest(workspace: Path, dirs: List[Path]): Unit =
    val dir = workspace.resolve(".scalex")
    if !Files.exists(dir) then Files.createDirectories(dir)
    Files.writeString(dirsManifestPath(workspace), dirs.map(_.toString).mkString("\n"))

  /** Load the dirs manifest. Returns None if missing or unreadable. */
  def loadDirsManifest(workspace: Path): Option[List[Path]] =
    val p = dirsManifestPath(workspace)
    if !Files.exists(p) then return None
    try
      val lines = Files.readString(p).split('\n').filter(_.nonEmpty).map(Path.of(_)).toList
      Some(lines)
    catch
      case _: Exception => None

  /** Check if any directory in the manifest has a newer mtime than the given threshold. */
  def anyDirNewerThan(dirs: List[Path], thresholdMs: Long): Boolean =
    dirs.exists { dir =>
      Files.isDirectory(dir) && Files.getLastModifiedTime(dir).toMillis > thresholdMs
    }

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

  /** Bytes per occurrence entry: 4(file) + 4(startLine) + 2(startChar) + 4(endLine) + 2(endChar) + 4(symbol) + 1(role) */
  private val BYTES_PER_OCCURRENCE = 21

  /** Load binary cache. When skipOccurrences=true, skip occurrence data entirely (~70% faster). */
  def load(workspace: Path, skipOccurrences: Boolean = false): Option[List[IndexedDocument]] =
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
          if skipOccurrences then
            in.skipBytes(occCount * BYTES_PER_OCCURRENCE)
            docs += IndexedDocument(uri, md5, syms.result(), Nil)
          else
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
  var parsedCount: Int = 0
  var skippedCount: Int = 0

  // ── Stats ──────────────────────────────────────────────────────────────

  def fileCount: Int = documents.size
  def symbolCount: Int = _symbolCount
  def occurrenceCount: Int = _occurrenceCount

  // ── Pre-built indexes ─────────────────────────────────────────────────
  // On cache hit: populated by LoadedIndex (string-table-indexed arrays, no hashing)
  // On rebuild: populated by buildIndexMaps (HashMap-based, single pass)

  private var _symbolCount: Int = 0
  private var _occurrenceCount: Int = 0
  private var _allSymbols: List[SemSymbol] = Nil
  var symbolByFqn: collection.Map[String, SemSymbol] = Map.empty
  var symbolsByName: collection.Map[String, List[SemSymbol]] = Map.empty
  var symbolsByFile: collection.Map[String, List[SemSymbol]] = Map.empty
  var occurrencesBySymbol: collection.Map[String, List[SemOccurrence]] = Map.empty
  var occurrencesByFile: collection.Map[String, List[SemOccurrence]] = Map.empty
  var subtypeIndex: collection.Map[String, List[String]] = Map.empty
  var memberIndex: collection.Map[String, List[SemSymbol]] = Map.empty
  var definitionRanges: collection.Map[String, (file: String, range: SemRange)] = Map.empty


  /** Build all index maps in a single pass over documents.
    * When occurrences were skipped during load (empty lists), occurrence maps are left as Map.empty. */
  private def buildIndexMaps(docs: List[IndexedDocument]): Unit =
    SemTimings.phase("build-indexes") {
      // Pre-compute sizes for HashMap pre-allocation
      var estSymbols = 0
      var estOccs = 0
      docs.foreach { d => estSymbols += d.symbols.size; estOccs += d.occurrences.size }

      val symByFqn = new java.util.HashMap[String, SemSymbol](estSymbols * 2)
      val symsByName = new java.util.HashMap[String, mutable.ListBuffer[SemSymbol]](estSymbols)
      val symsByFile = new java.util.HashMap[String, mutable.ListBuffer[SemSymbol]](docs.size * 2)
      val subIdx = new java.util.HashMap[String, mutable.ListBuffer[String]](estSymbols / 4)
      val memIdx = new java.util.HashMap[String, mutable.ListBuffer[SemSymbol]](estSymbols / 4)
      val allSyms = List.newBuilder[SemSymbol]
      var symCount = 0

      // Occurrence maps — only allocated when occurrences are present
      val hasOccs = estOccs > 0
      val occsBySym = if hasOccs then new java.util.HashMap[String, mutable.ListBuffer[SemOccurrence]](estSymbols) else null
      val occsByFile = if hasOccs then new java.util.HashMap[String, mutable.ListBuffer[SemOccurrence]](docs.size * 2) else null
      val defRanges = if hasOccs then new java.util.HashMap[String, (file: String, range: SemRange)](estSymbols) else null
      var occCount = 0

      docs.foreach { doc =>
        doc.symbols.foreach { s =>
          allSyms += s
          symCount += 1
          symByFqn.putIfAbsent(s.fqn, s)
          symsByName.computeIfAbsent(s.displayName.toLowerCase, _ => mutable.ListBuffer.empty) += s
          symsByFile.computeIfAbsent(s.sourceUri, _ => mutable.ListBuffer.empty) += s
          s.parents.foreach { p =>
            subIdx.computeIfAbsent(p, _ => mutable.ListBuffer.empty) += s.fqn
          }
          if s.owner.nonEmpty && s.kind != SemKind.Parameter && s.kind != SemKind.TypeParam then
            memIdx.computeIfAbsent(s.owner, _ => mutable.ListBuffer.empty) += s
        }
        if hasOccs then
          doc.occurrences.foreach { o =>
            occCount += 1
            occsBySym.computeIfAbsent(o.symbol, _ => mutable.ListBuffer.empty) += o
            occsByFile.computeIfAbsent(o.file, _ => mutable.ListBuffer.empty) += o
            if o.role == OccRole.Definition && !defRanges.containsKey(o.symbol) then
              defRanges.put(o.symbol, (file = o.file, range = o.range))
          }
      }

      _allSymbols = allSyms.result()
      _symbolCount = symCount
      _occurrenceCount = occCount
      symbolByFqn = symByFqn.asScala
      symbolsByName = convertListBufferMap(symsByName)
      symbolsByFile = convertListBufferMap(symsByFile)
      subtypeIndex = convertListBufferMapDistinct(subIdx)
      memberIndex = convertListBufferMap(memIdx)
      if hasOccs then
        occurrencesBySymbol = convertListBufferMap(occsBySym)
        occurrencesByFile = convertListBufferMap(occsByFile)
        definitionRanges = defRanges.asScala
    }

  /** Convert a Java HashMap[String, ListBuffer[A]] to a Scala Map[String, List[A]] by
    * replacing ListBuffer values with List in-place (single pass, no map copy). */
  private def convertListBufferMap[A](jmap: java.util.HashMap[String, mutable.ListBuffer[A]]): collection.Map[String, List[A]] =
    val result = new java.util.HashMap[String, List[A]](jmap.size * 2)
    val it = jmap.entrySet().iterator()
    while it.hasNext do
      val e = it.next()
      result.put(e.getKey, e.getValue.toList)
    result.asScala

  private def convertListBufferMapDistinct(jmap: java.util.HashMap[String, mutable.ListBuffer[String]]): collection.Map[String, List[String]] =
    val result = new java.util.HashMap[String, List[String]](jmap.size * 2)
    val it = jmap.entrySet().iterator()
    while it.hasNext do
      val e = it.next()
      result.put(e.getKey, e.getValue.distinct.toList)
    result.asScala

  // ── Query helpers ──────────────────────────────────────────────────────

  /** Resolve a user query to a symbol FQN. Tries: exact FQN, suffix match, display name.
    * Results are sorted: non-local before local, source before generated, by kind rank
    * (classes first), then shorter FQN first, then alphabetically. */
  def resolveSymbol(query: String): List[SemSymbol] =
    val raw =
      // 1. Exact FQN match
      symbolByFqn.get(query).map(List(_)).getOrElse {
        // 2. Suffix match on FQN (e.g. "List#map()." matches "scala/collection/immutable/List#map().")
        val suffixMatches = _allSymbols.filter(_.fqn.endsWith(query))
        if suffixMatches.nonEmpty then suffixMatches
        else
          // 3. Display name match (case-insensitive)
          val nameMatches = symbolsByName.getOrElse(query.toLowerCase, Nil)
          if nameMatches.nonEmpty then nameMatches
          else
            // 4. Partial display name match
            _allSymbols.filter(_.displayName.toLowerCase.contains(query.toLowerCase))
      }
    // Deterministic ordering: non-local before local, source before generated, then by kind rank, then shorter FQN first
    raw.sortBy(s => (isLocal = if s.kind == SemKind.Local then 1 else 0, isGenerated = if isGeneratedSource(s.sourceUri) then 1 else 0, kindRank = kindRank(s.kind), fqnLen = s.fqn.length, fqn = s.fqn))

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

  /** Build the index: load cache, check staleness cheaply, rebuild incrementally if needed.
    *
    * Staleness check uses a saved manifest of semanticdb directories (~5ms) instead of
    * a full file walk (~1s on large projects). Full discovery only runs when stale or on first build. */
  /** Build the index. When needOccurrences=false, skip loading occurrence data (~70% faster).
    * Symbol-only commands (lookup, members, subtypes, etc.) pass false. */
  def build(semanticdbPath: Option[String] = None, needOccurrences: Boolean = true): Unit =
    val start = System.currentTimeMillis()
    cachedLoad = false
    parsedCount = 0
    skippedCount = 0

    // 1. Try loading from cache first — read cache mtime before loading
    val cachePath = SemPersistence.indexPath(workspace)
    val cacheMtime = if Files.exists(cachePath) then Files.getLastModifiedTime(cachePath).toMillis else 0L

    val cached = SemTimings.phase("cache-load") {
      SemPersistence.load(workspace, skipOccurrences = !needOccurrences)
    }

    cached match
      case Some(cachedDocs) =>
        // 2. Cheap staleness check: stat directories from saved manifest (~5ms)
        val stale = SemTimings.phase("staleness-check") {
          SemPersistence.loadDirsManifest(workspace) match
            case Some(dirs) => SemPersistence.anyDirNewerThan(dirs, cacheMtime)
            case None       => true // no manifest → must do full discovery to create one
        }

        if !stale then
          documents = cachedDocs
          buildIndexMaps(documents)
          cachedLoad = true
          buildTimeMs = System.currentTimeMillis() - start
          return

        // 3. Stale — full discovery + incremental rebuild
        val (files, _, semanticdbDirs) = SemTimings.phase("discover") {
          semanticdbPath match
            case Some(p) =>
              val explicitPath = Path.of(p).toAbsolutePath.normalize
              val r = Discovery.discoverFromExplicitPath(explicitPath)
              (files = r.files, maxMtimeMs = r.maxMtimeMs, semanticdbDirs = List(explicitPath))
            case None => Discovery.discoverSemanticdbFiles(workspace)
        }

        if files.isEmpty then
          documents = Nil
          SemPersistence.save(workspace, Nil)
          buildTimeMs = System.currentTimeMillis() - start
          return

        incrementalRebuild(files, cachedDocs)
        buildIndexMaps(documents)
        SemPersistence.saveDirsManifest(workspace, semanticdbDirs)


      case None =>
        // Cache miss — full discovery + full rebuild
        val (files, _, semanticdbDirs) = SemTimings.phase("discover") {
          semanticdbPath match
            case Some(p) =>
              val explicitPath = Path.of(p).toAbsolutePath.normalize
              val r = Discovery.discoverFromExplicitPath(explicitPath)
              (files = r.files, maxMtimeMs = r.maxMtimeMs, semanticdbDirs = List(explicitPath))
            case None => Discovery.discoverSemanticdbFiles(workspace)
        }

        if files.isEmpty then
          documents = Nil
          buildTimeMs = System.currentTimeMillis() - start
          return

        val parsed = SemTimings.phase("parse-semanticdb") {
          Parser.loadDocuments(files)
        }
        parsedCount = parsed.size

        documents = SemTimings.phase("dedup-documents") {
          deduplicateDocuments(parsed)
        }
        buildIndexMaps(documents)

        SemTimings.phase("save-index") {
          SemPersistence.save(workspace, documents)
        }
        SemPersistence.saveDirsManifest(workspace, semanticdbDirs)

    buildTimeMs = System.currentTimeMillis() - start

  /** Force rebuild (ignores mtime staleness check, but still uses incremental MD5 comparison). */
  def rebuild(semanticdbPath: Option[String] = None): Unit =
    val start = System.currentTimeMillis()
    cachedLoad = false
    parsedCount = 0
    skippedCount = 0

    val (files, _, semanticdbDirs) = SemTimings.phase("discover") {
      semanticdbPath match
        case Some(p) =>
          val explicitPath = Path.of(p).toAbsolutePath.normalize
          val r = Discovery.discoverFromExplicitPath(explicitPath)
          (files = r.files, maxMtimeMs = r.maxMtimeMs, semanticdbDirs = List(explicitPath))
        case None => Discovery.discoverSemanticdbFiles(workspace)
    }

    if files.isEmpty then
      documents = Nil
      buildTimeMs = System.currentTimeMillis() - start
      return

    // Try incremental rebuild using cached data if available
    val cached = SemTimings.phase("cache-load") {
      SemPersistence.load(workspace)
    }

    cached match
      case Some(cachedDocs) =>
        incrementalRebuild(files, cachedDocs)
      case None =>
        val parsed = SemTimings.phase("parse-semanticdb") {
          Parser.loadDocuments(files)
        }
        parsedCount = parsed.size
        documents = SemTimings.phase("dedup-documents") {
          deduplicateDocuments(parsed)
        }
        SemTimings.phase("save-index") {
          SemPersistence.save(workspace, documents)
        }

    buildIndexMaps(documents)
    SemPersistence.saveDirsManifest(workspace, semanticdbDirs)
    buildTimeMs = System.currentTimeMillis() - start

  /** Incremental rebuild: decode raw protobuf, compare MD5s against cache, only convert changed docs. */
  private def incrementalRebuild(files: List[Path], cachedDocs: List[IndexedDocument]): Unit =
    // Build lookup from cached documents by URI → IndexedDocument
    val cachedByUri = SemTimings.phase("build-cache-map") {
      cachedDocs.map(d => d.uri -> d).toMap
    }

    // Decode raw protobuf (cheap — no signature printing)
    val rawDocs = SemTimings.phase("decode-semanticdb") {
      Parser.loadRawDocuments(files)
    }

    // Compare MD5s: reuse cached or convert new
    val merged = SemTimings.phase("md5-compare") {
      rawDocs.map { raw =>
        cachedByUri.get(raw.uri) match
          case Some(cached) if cached.md5 == raw.md5 =>
            skippedCount += 1
            cached
          case _ =>
            parsedCount += 1
            Parser.convertDocument(raw)
      }
    }

    documents = SemTimings.phase("dedup-documents") {
      deduplicateDocuments(merged)
    }

    SemTimings.phase("save-index") {
      SemPersistence.save(workspace, documents)
    }
