class IndexTest extends SemTestBase:

  // ── Index stats ──────────────────────────────────────────────────────────

  test("index has files, symbols, occurrences") {
    assert(index.fileCount > 0, s"expected files, got ${index.fileCount}")
    assert(index.symbolCount > 0, s"expected symbols, got ${index.symbolCount}")
    assert(index.occurrenceCount > 0, s"expected occurrences, got ${index.occurrenceCount}")
  }

  // ── symbolByFqn ──────────────────────────────────────────────────────────

  test("symbolByFqn resolves known classes") {
    assert(index.symbolByFqn.contains("example/Dog#"), "Dog not found by FQN")
    assert(index.symbolByFqn.contains("example/Cat#"), "Cat not found by FQN")
    assert(index.symbolByFqn.contains("example/Animal#"), "Animal not found by FQN")
  }

  test("symbolByFqn has correct kind") {
    val dog = index.symbolByFqn("example/Dog#")
    assertEquals(dog.kind, SemKind.Class)
    assertEquals(dog.displayName, "Dog")

    val animal = index.symbolByFqn("example/Animal#")
    assertEquals(animal.kind, SemKind.Trait)
  }

  // ── symbolsByName ────────────────────────────────────────────────────────

  test("symbolsByName finds by display name") {
    val results = index.symbolsByName.getOrElse("dog", Nil)
    assert(results.nonEmpty, "expected to find 'dog' by name")
    assert(results.exists(_.fqn == "example/Dog#"))
  }

  // ── subtypeIndex ─────────────────────────────────────────────────────────

  test("subtypeIndex maps parent to children") {
    val animalSubs = index.subtypeIndex.getOrElse("example/Animal#", Nil)
    assert(animalSubs.contains("example/Dog#"), s"Dog not a subtype of Animal. Subs: $animalSubs")
    assert(animalSubs.contains("example/Cat#"), s"Cat not a subtype of Animal. Subs: $animalSubs")
  }

  test("subtypeIndex sealed trait has all variants") {
    val shapeSubs = index.subtypeIndex.getOrElse("example/Shape#", Nil)
    assert(shapeSubs.contains("example/Circle#"), "Circle not found")
    assert(shapeSubs.contains("example/Rectangle#"), "Rectangle not found")
    assert(shapeSubs.contains("example/Triangle#"), "Triangle not found")
  }

  // ── memberIndex ──────────────────────────────────────────────────────────

  test("memberIndex lists class members") {
    val dogMembers = index.memberIndex.getOrElse("example/Dog#", Nil)
    val memberNames = dogMembers.map(_.displayName).toSet
    assert(memberNames.contains("sound"), s"sound not in Dog members: $memberNames")
    assert(memberNames.contains("fetch"), s"fetch not in Dog members: $memberNames")
  }

  // ── occurrencesBySymbol ──────────────────────────────────────────────────

  test("occurrencesBySymbol finds references") {
    val greetOccs = index.occurrencesBySymbol.getOrElse("example/Animal#greet().", Nil)
    val defs = greetOccs.filter(_.role == OccRole.Definition)
    val refs = greetOccs.filter(_.role == OccRole.Reference)
    assert(defs.nonEmpty, "greet should have a definition")
    assert(refs.nonEmpty, "greet should have references (called in Main)")
  }

  // ── definitionRanges ─────────────────────────────────────────────────────

  test("definitionRanges contains known symbols") {
    assert(index.definitionRanges.contains("example/Dog#"), "Dog def range not found")
    assert(index.definitionRanges.contains("example/Main.main()."), "Main.main def range not found")
  }

  // ── resolveSymbol ────────────────────────────────────────────────────────

  test("resolveSymbol by exact FQN") {
    val result = index.resolveSymbol("example/Dog#")
    assertEquals(result.size, 1)
    assertEquals(result.head.displayName, "Dog")
  }

  test("resolveSymbol by display name") {
    val result = index.resolveSymbol("Dog")
    assert(result.nonEmpty, "should find Dog by display name")
    assert(result.exists(_.fqn == "example/Dog#"))
  }

  test("resolveSymbol by partial name") {
    val result = index.resolveSymbol("AnimalService")
    assert(result.nonEmpty, "should find AnimalService")
  }

  // ── Binary persistence ───────────────────────────────────────────────────

  test("binary persistence round-trip") {
    val symCountBefore = index.symbolCount
    val occCountBefore = index.occurrenceCount

    // Load from cache
    val index2 = SemIndex(workspace)
    index2.build()
    assert(index2.cachedLoad, "second load should come from cache")
    assertEquals(index2.symbolCount, symCountBefore)
    assertEquals(index2.occurrenceCount, occCountBefore)
  }

  // ── Incremental rebuild ─────────────────────────────────────────────────

  test("incremental rebuild reuses unchanged documents") {
    val totalFiles = index.fileCount

    // Touch the discovered semanticdb directory to trigger staleness detection.
    // The staleness check stats directories (from the saved manifest), not individual files.
    java.nio.file.Files.setLastModifiedTime(discoveredSdbDir, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10000))

    // Build should detect staleness and do incremental rebuild
    val index3 = SemIndex(workspace)
    index3.build()
    assert(!index3.cachedLoad, "should not be a pure cache hit after touching a file")
    // All documents should be reused (MD5s haven't changed, only mtime)
    assertEquals(index3.parsedCount, 0, "no docs should need re-conversion")
    assert(index3.skippedCount > 0, "should have reused documents from cache")
    assertEquals(index3.fileCount, totalFiles, "file count should be preserved after incremental rebuild")
    assertEquals(index3.symbolCount, index.symbolCount, "symbol count should be preserved")
  }

  // ── Regression tests for bugs fixed in #301 ────────────────────────────
  // Each test ensures a known-good cache state first to avoid cross-test contamination.

  // The actual directory that discovery saves in the manifest (out/META-INF/semanticdb/)
  private lazy val discoveredSdbDir: java.nio.file.Path =
    semanticdbDir.resolve("META-INF").resolve("semanticdb")

  private def ensureFreshCache(): Unit =
    val fresh = SemIndex(workspace)
    fresh.rebuild()
    // Ensure dir mtime is before cache mtime so staleness check returns fresh
    val cacheTime = java.nio.file.Files.getLastModifiedTime(SemPersistence.indexPath(workspace))
    java.nio.file.Files.setLastModifiedTime(discoveredSdbDir,
      java.nio.file.attribute.FileTime.fromMillis(cacheTime.toMillis - 1000))

  test("counts reset on each build call") {
    // Bug: parsedCount/skippedCount accumulated across calls if SemIndex reused
    ensureFreshCache()
    val idx = SemIndex(workspace)

    // First build: trigger stale rebuild so parsedCount/skippedCount are non-zero
    java.nio.file.Files.setLastModifiedTime(discoveredSdbDir, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 40000))
    idx.build()
    val firstSkipped = idx.skippedCount
    assert(firstSkipped > 0 || idx.parsedCount > 0, "first build should have done work (stale rebuild)")

    val firstParsed = idx.parsedCount

    // Second build on same instance: counts must be reset, not accumulated
    idx.build()
    assert(idx.parsedCount <= firstParsed,
      s"parsedCount should not accumulate: first=$firstParsed, second=${idx.parsedCount}")
    assert(idx.skippedCount <= firstSkipped,
      s"skippedCount should not accumulate: first=$firstSkipped, second=${idx.skippedCount}")
    ensureFreshCache()
  }

  test("rebuild on workspace without out/ produces empty index") {
    val emptyWorkspace = java.nio.file.Files.createTempDirectory("scalex-sdb-empty")
    val idx = SemIndex(emptyWorkspace)
    idx.rebuild()
    assertEquals(idx.fileCount, 0, "should have zero files when no out/ exists")
    // Cleanup
    java.nio.file.Files.deleteIfExists(emptyWorkspace.resolve(".scalex").resolve("semanticdb.bin"))
    java.nio.file.Files.deleteIfExists(emptyWorkspace.resolve(".scalex").resolve("semanticdb-dirs.txt"))
    java.nio.file.Files.deleteIfExists(emptyWorkspace.resolve(".scalex"))
    java.nio.file.Files.deleteIfExists(emptyWorkspace)
  }

  test("symbol-only load preserves occurrences in cache after incremental rebuild") {
    // Bug: build(needOccurrences=false) + stale → incrementalRebuild saved occurrence-stripped
    // docs to disk, permanently destroying occurrence data
    ensureFreshCache()
    val origOccCount = index.occurrenceCount
    assert(origOccCount > 0, "should have occurrences initially")

    // Touch dir to trigger staleness
    java.nio.file.Files.setLastModifiedTime(discoveredSdbDir, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 20000))

    // Build with needOccurrences=false (symbol-only command) — triggers incremental rebuild
    val idx = SemIndex(workspace)
    idx.build(needOccurrences = false)

    // Now load again WITH occurrences — they should still be in the cache
    val idx2 = SemIndex(workspace)
    idx2.build(needOccurrences = true)
    assertEquals(idx2.occurrenceCount, origOccCount,
      s"occurrences should be preserved in cache after symbol-only incremental rebuild (expected $origOccCount, got ${idx2.occurrenceCount})")
  }

  test("skipOccurrences load produces valid symbol data") {
    // Bug: DataInputStream.skipBytes may skip fewer bytes than requested, corrupting stream
    ensureFreshCache()
    val idx = SemIndex(workspace)
    idx.build(needOccurrences = false)

    // Symbol data should be intact even though occurrences were skipped
    assertEquals(idx.symbolCount, index.symbolCount, "symbol count should match full load")
    assertEquals(idx.fileCount, index.fileCount, "file count should match full load")
    assert(idx.symbolByFqn.contains("example/Dog#"), "Dog should be resolvable after skip-occ load")
    assert(idx.symbolByFqn.contains("example/Animal#"), "Animal should be resolvable after skip-occ load")
    // Occurrence maps should be empty (not loaded)
    assertEquals(idx.occurrenceCount, 0, "occurrenceCount should be 0 when occurrences skipped")
  }

  test("isStale returns true on missing or empty manifest") {
    // Bug: isStale returned false on missing/empty manifest, while build() returned true.
    // Daemon uses isStale() as its only staleness check — if it returns false on missing
    // manifest, the daemon silently serves empty/stale results forever after mill clean.
    val idx = SemIndex(workspace)

    // Case 1: missing manifest file
    val manifestPath = workspace.resolve(".scalex").resolve("semanticdb-dirs.txt")
    val hadManifest = java.nio.file.Files.exists(manifestPath)
    if hadManifest then java.nio.file.Files.delete(manifestPath)
    assert(idx.isStale(0L), "isStale should return true when manifest file is missing")
    if hadManifest then ensureFreshCache() // restore

    // Case 2: empty manifest file
    ensureFreshCache()
    java.nio.file.Files.writeString(manifestPath, "")
    assert(idx.isStale(0L), "isStale should return true when manifest is empty")

    ensureFreshCache()
  }

  test("empty dirs manifest triggers full discovery on next build") {
    // Bug: empty manifest made anyDirNewerThan always return false, never detecting new files
    ensureFreshCache()
    val manifestPath = workspace.resolve(".scalex").resolve("semanticdb-dirs.txt")
    java.nio.file.Files.writeString(manifestPath, "") // empty manifest

    val idx = SemIndex(workspace)
    idx.build()
    // Should NOT be a cache hit — empty manifest should trigger full discovery
    assert(!idx.cachedLoad, "empty manifest should trigger full discovery, not cache hit")
    assert(idx.fileCount > 0, "should discover files even with empty manifest")

    // Restore cache for subsequent tests
    ensureFreshCache()
  }
