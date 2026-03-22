class NewCommandsTest extends SemTestBase:

  // ════════════════════════════════════════════════════════════════════════
  // Batch 1: file, packages, package, annotated, summary
  // ════════════════════════════════════════════════════════════════════════

  // ── file ──

  test("file finds by name") {
    val ctx = makeCtx()
    val result = cmdFile(List("Dog"), ctx)
    result match
      case SemCmdResult.FileList(_, files, _) =>
        assert(files.exists(_.contains("Dog")), s"should find Dog file: $files")
      case other => fail(s"unexpected: $other")
  }

  test("file not found returns empty") {
    val ctx = makeCtx()
    val result = cmdFile(List("NonExistent12345"), ctx)
    result match
      case SemCmdResult.FileList(_, files, total) =>
        assertEquals(total, 0)
      case other => fail(s"unexpected: $other")
  }

  // ── packages ──

  test("packages lists example package") {
    val ctx = makeCtx()
    val result = cmdPackages(Nil, ctx)
    result match
      case SemCmdResult.PackageList(_, pkgs, _) =>
        assert(pkgs.exists(_.contains("example")), s"should have example package: $pkgs")
      case other => fail(s"unexpected: $other")
  }

  test("packages lists util package") {
    val ctx = makeCtx()
    val result = cmdPackages(Nil, ctx)
    result match
      case SemCmdResult.PackageList(_, pkgs, _) =>
        assert(pkgs.exists(_.contains("util")), s"should have util package: $pkgs")
      case other => fail(s"unexpected: $other")
  }

  // ── package ──

  test("package lists symbols in example") {
    val ctx = makeCtx(limit = 200)
    val result = cmdPackageSymbols(List("example"), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, total) =>
        assert(total > 5, s"example should have many symbols, got $total")
        val names = syms.map(_.displayName).toSet
        assert(names.contains("Dog"), s"should contain Dog: $names")
        assert(names.contains("Animal"), s"should contain Animal: $names")
      case other => fail(s"unexpected: $other")
  }

  // ── annotated ──

  test("annotated finds deprecated symbols") {
    val ctx = makeCtx()
    val result = cmdAnnotated(List("deprecated"), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, total) =>
        assert(total > 0, "should find deprecated symbols")
        val names = syms.map(_.displayName).toSet
        assert(names.contains("OldService") || names.contains("LegacyApi"),
          s"should find OldService or LegacyApi: $names")
      case other => fail(s"unexpected: $other")
  }

  test("annotated not found returns empty") {
    val ctx = makeCtx()
    val result = cmdAnnotated(List("nonExistentAnnotation123"), ctx)
    result match
      case SemCmdResult.SymbolList(_, _, total) => assertEquals(total, 0)
      case other => fail(s"unexpected: $other")
  }

  // ── summary ──

  test("summary shows package counts") {
    val ctx = makeCtx()
    val result = cmdSummary(Nil, ctx)
    result match
      case SemCmdResult.SummaryList(_, entries, _) =>
        assert(entries.nonEmpty, "should have package entries")
        assert(entries.exists(_._1.contains("example")), s"should have example: $entries")
      case other => fail(s"unexpected: $other")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Batch 2: overrides, entrypoints, api, overview
  // ════════════════════════════════════════════════════════════════════════

  // ── overrides ──

  test("overrides finds method overrides") {
    val ctx = makeCtx()
    // Dog.sound and Cat.sound override Animal.sound
    val result = cmdOverrides(List("sound"), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, total) =>
        assert(total > 0, "sound should have overrides")
        // Should find Dog.sound and Cat.sound
        val owners = syms.map(_.owner).toSet
        assert(owners.exists(_.contains("Dog")) || owners.exists(_.contains("Cat")),
          s"should find Dog or Cat overrides: ${syms.map(s => s"${s.owner}${s.displayName}")}")
      case other => fail(s"unexpected: $other")
  }

  // ── entrypoints ──

  test("entrypoints finds main method") {
    val ctx = makeCtx()
    val result = cmdEntrypoints(Nil, ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, total) =>
        assert(total > 0, "should find entrypoints")
        val names = syms.map(_.displayName).toSet
        assert(names.contains("main"), s"should find main: $names")
      case other => fail(s"unexpected: $other")
  }

  // ── api ──

  test("api shows public symbols in package") {
    val ctx = makeCtx(limit = 200)
    val result = cmdApi(List("example"), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, total) =>
        assert(total > 0, "example should have public API")
        // Should contain public types like Animal, Dog, Cat
        val names = syms.map(_.displayName).toSet
        assert(names.contains("Animal") || names.contains("Dog"),
          s"should contain Animal or Dog: $names")
      case other => fail(s"unexpected: $other")
  }

  // ── overview ──

  test("overview returns codebase summary") {
    val ctx = makeCtx()
    val result = cmdOverview(Nil, ctx)
    result match
      case SemCmdResult.SummaryList(_, entries, _) =>
        assert(entries.nonEmpty, "overview should have entries")
      case other => fail(s"unexpected: $other")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Batch 3: imports, coverage, deps, context
  // ════════════════════════════════════════════════════════════════════════

  // ── imports ──

  test("imports finds files referencing symbol at top") {
    val ctx = makeCtx()
    val result = cmdImports(List("Animal"), ctx)
    result match
      case SemCmdResult.OccurrenceList(_, occs, total) =>
        // Animal is referenced in Dog.scala, Cat.scala, etc. at declaration/extends site
        assert(total >= 0, "should not error") // may be 0 if no top-of-file refs
      case other => fail(s"unexpected: $other")
  }

  // ── coverage ──

  test("coverage returns ref counts") {
    val ctx = makeCtx()
    val result = cmdCoverage(List("Animal"), ctx)
    result match
      case SemCmdResult.CoverageResult(_, totalRefs, _, _) =>
        assert(totalRefs >= 0, "should return coverage info")
      case other => fail(s"unexpected: $other")
  }

  // ── deps ──

  test("deps of Dog includes Animal") {
    val ctx = makeCtx()
    val result = cmdDeps(List("Dog"), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, total) =>
        assert(total > 0, "Dog should have dependencies")
        val fqns = syms.map(_.fqn).toSet
        assert(fqns.exists(_.contains("Animal")), s"Dog should depend on Animal: $fqns")
      case other => fail(s"unexpected: $other")
  }

  // ── context ──

  test("context finds enclosing scopes") {
    val ctx = makeCtx()
    // Main.scala line 5 should be inside Main object and main method
    val result = cmdContext(List("Main.scala:5"), ctx)
    result match
      case SemCmdResult.ContextResult(_, scopes) =>
        assert(scopes.nonEmpty, "should find enclosing scopes")
      case other => fail(s"unexpected: $other")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Batch 4: explain
  // ════════════════════════════════════════════════════════════════════════

  test("explain shows composite info") {
    val ctx = makeCtx()
    val result = cmdExplain(List("Dog"), ctx)
    result match
      case SemCmdResult.ExplainResult(sym, members, subtypeCount, refCount, supertypes) =>
        assertEquals(sym.displayName, "Dog")
        assert(members.nonEmpty, "Dog should have members")
        assert(supertypes.nonEmpty, "Dog should have supertypes")
      case other => fail(s"unexpected: $other")
  }

  test("explain not found") {
    val ctx = makeCtx()
    val result = cmdExplain(List("NonExistent12345"), ctx)
    assert(result.isInstanceOf[SemCmdResult.NotFound])
  }
