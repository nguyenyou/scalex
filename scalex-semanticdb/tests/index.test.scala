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
    index2.build(Some(semanticdbDir.toString))
    assert(index2.cachedLoad, "second load should come from cache")
    assertEquals(index2.symbolCount, symCountBefore)
    assertEquals(index2.occurrenceCount, occCountBefore)
  }
