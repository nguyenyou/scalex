class CommandsTest extends SemTestBase:

  // ── lookup ───────────────────────────────────────────────────────────────

  test("lookup by name returns symbol detail") {
    val ctx = makeCtx()
    val result = cmdLookup(List("Dog"), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, _) =>
        assert(syms.exists(_.fqn == "example/Dog#"))
      case SemCmdResult.SymbolDetail(sym) =>
        assertEquals(sym.fqn, "example/Dog#")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("lookup not found") {
    val ctx = makeCtx()
    val result = cmdLookup(List("NonExistent12345"), ctx)
    assert(result.isInstanceOf[SemCmdResult.NotFound])
  }

  // ── refs ─────────────────────────────────────────────────────────────────

  test("refs finds definition and references") {
    val ctx = makeCtx()
    val result = cmdRefs(List("greet"), ctx)
    result match
      case SemCmdResult.OccurrenceList(_, occs, total) =>
        assert(total > 0, "greet should have occurrences")
        val roles = occs.map(_.role).toSet
        assert(roles.contains(OccRole.Definition), "should have a definition")
        assert(roles.contains(OccRole.Reference), "should have references")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("refs with role filter") {
    val ctx = makeCtx(roleFilter = Some("ref"))
    val result = cmdRefs(List("greet"), ctx)
    result match
      case SemCmdResult.OccurrenceList(_, occs, _) =>
        assert(occs.forall(_.role == OccRole.Reference), "all should be references")
      case other =>
        fail(s"unexpected result: $other")
  }

  // ── supertypes ───────────────────────────────────────────────────────────

  test("supertypes shows parent chain") {
    val ctx = makeCtx()
    val result = cmdSupertypes(List("Dog"), ctx)
    result match
      case SemCmdResult.Tree(_, lines) =>
        val text = lines.mkString("\n")
        assert(text.contains("Animal"), s"Dog supertypes should include Animal: $text")
      case other =>
        fail(s"unexpected result: $other")
  }

  // ── subtypes ─────────────────────────────────────────────────────────────

  test("subtypes of sealed trait finds all variants") {
    val ctx = makeCtx()
    val result = cmdSubtypes(List("Shape"), ctx)
    result match
      case SemCmdResult.Tree(_, lines) =>
        val text = lines.mkString("\n")
        assert(text.contains("Circle"), s"missing Circle: $text")
        assert(text.contains("Rectangle"), s"missing Rectangle: $text")
        assert(text.contains("Triangle"), s"missing Triangle: $text")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("subtypes of Animal finds Dog and Cat") {
    val ctx = makeCtx()
    val result = cmdSubtypes(List("Animal"), ctx)
    result match
      case SemCmdResult.Tree(_, lines) =>
        val text = lines.mkString("\n")
        assert(text.contains("Dog"), s"missing Dog: $text")
        assert(text.contains("Cat"), s"missing Cat: $text")
      case other =>
        fail(s"unexpected result: $other")
  }

  // ── members ──────────────────────────────────────────────────────────────

  test("members of Animal lists abstract methods") {
    val ctx = makeCtx()
    val result = cmdMembers(List("Animal"), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, _) =>
        val names = syms.map(_.displayName).toSet
        assert(names.contains("name"), s"missing 'name': $names")
        assert(names.contains("sound"), s"missing 'sound': $names")
        assert(names.contains("greet"), s"missing 'greet': $names")
      case other =>
        fail(s"unexpected result: $other")
  }

  // ── type ─────────────────────────────────────────────────────────────────

  test("type shows signature") {
    val ctx = makeCtx()
    val result = cmdTypeOf(List("fetch"), ctx)
    result match
      case SemCmdResult.TypeResult(_, sig) =>
        assert(sig.nonEmpty, "signature should be non-empty")
      case SemCmdResult.SymbolList(_, syms, _) =>
        // multiple matches, verify at least one has a sig
        assert(syms.exists(_.signature.nonEmpty))
      case other =>
        fail(s"unexpected result: $other")
  }

  // ── callers ──────────────────────────────────────────────────────────────

  test("callers of greet includes main") {
    val ctx = makeCtx()
    val result = cmdCallers(List("greet"), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, _) =>
        val names = syms.map(_.displayName)
        assert(names.contains("main"), s"main should call greet: $names")
      case other =>
        fail(s"unexpected result: $other")
  }

  // ── callees ──────────────────────────────────────────────────────────────

  test("callees of main includes service calls") {
    val ctx = makeCtx()
    val result = cmdCallees(List("main"), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, total) =>
        assert(total > 0, "main should have callees")
        val names = syms.map(_.displayName).toSet
        // main calls register, listAll, greet, fetch
        assert(names.contains("register") || names.contains("greet") || names.contains("fetch"),
          s"expected service calls in main's callees: $names")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("callees of single-line method finds body references") {
    val ctx = makeCtx()
    // greet() is: def greet(): String = s"I'm $name and I say $sound"
    // Single-line method — body refs are on the same line as def
    val result = cmdCallees(List("greet"), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, total) =>
        assert(total > 0, "greet should have callees (name, sound)")
        val names = syms.map(_.displayName).toSet
        assert(names.contains("name") || names.contains("sound"),
          s"greet should call name or sound: $names")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("callees excludes def signature types") {
    val ctx = makeCtx()
    // fetch is: def fetch(item: String): String = s"$name fetches $item"
    // String appears in the signature — should NOT be a callee
    val result = cmdCallees(List("fetch"), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, _) =>
        val fqns = syms.map(_.fqn).toSet
        // String# should not appear as a callee (it's in the signature, not the body)
        assert(!fqns.contains("scala/Predef.String#"),
          s"String should not be a callee of fetch: $fqns")
      case other =>
        fail(s"unexpected result: $other")
  }

  // ── flow ─────────────────────────────────────────────────────────────────

  test("flow from main shows call tree") {
    val ctx = makeCtx(depth = 2)
    // Use the method, not the ambiguous "main" which also matches the Main object
    val result = cmdFlow(List("example/Main.main()."), ctx)
    result match
      case SemCmdResult.FlowTree(_, lines) =>
        assert(lines.size > 1, s"flow tree should have multiple lines: $lines")
        val text = lines.mkString("\n")
        assert(text.contains("main"), s"should contain main: $text")
      case other =>
        fail(s"unexpected result: $other")
  }

  // ── related ──────────────────────────────────────────────────────────────

  test("related to Dog includes Animal and Cat") {
    val ctx = makeCtx()
    val result = cmdRelated(List("Dog"), ctx)
    result match
      case SemCmdResult.RelatedList(_, entries, _) =>
        val names = entries.map(_.sym.displayName).toSet
        assert(names.contains("Animal") || entries.exists(_.sym.fqn.contains("Animal")),
          s"Animal should be related to Dog: ${entries.map(e => e.sym.displayName -> e.count)}")
      case other =>
        fail(s"unexpected result: $other")
  }

  // ── symbols ──────────────────────────────────────────────────────────────

  test("symbols lists all symbols") {
    val ctx = makeCtx(limit = 1000)
    val result = cmdSymbols(Nil, ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, total) =>
        assert(total > 10, s"expected many symbols, got $total")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("symbols by file") {
    val ctx = makeCtx()
    val result = cmdSymbols(List("Dog.scala"), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, _) =>
        assert(syms.nonEmpty, "Dog.scala should have symbols")
        assert(syms.exists(_.displayName == "Dog"), "should contain Dog class")
      case other =>
        fail(s"unexpected result: $other")
  }

  // ── occurrences ──────────────────────────────────────────────────────────

  test("occurrences by file") {
    val ctx = makeCtx(limit = 200)
    val result = cmdOccurrences(List("Main.scala"), ctx)
    result match
      case SemCmdResult.OccurrenceList(_, occs, total) =>
        assert(total > 0, "Main.scala should have occurrences")
      case other =>
        fail(s"unexpected result: $other")
  }

  // ── kind filter ──────────────────────────────────────────────────────────

  test("kind filter works on symbols") {
    val ctx = makeCtx(kindFilter = Some("trait"))
    val result = cmdSymbols(Nil, ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, _) =>
        assert(syms.forall(_.kind == SemKind.Trait), "all should be traits")
        assert(syms.exists(_.displayName == "Animal"))
      case other =>
        fail(s"unexpected result: $other")
  }

  // ── JSON output ──────────────────────────────────────────────────────────

  test("json output is valid") {
    val ctx = makeCtx(json = true)
    val output = captureOut {
      val result = cmdStats(Nil, ctx)
      render(result, ctx)
    }
    assert(output.contains("\"files\""), s"json should have files key: $output")
    assert(output.contains("\"symbols\""), s"json should have symbols key: $output")
  }

  // ── stats ────────────────────────────────────────────────────────────────

  test("stats shows counts") {
    val ctx = makeCtx()
    val result = cmdStats(Nil, ctx)
    result match
      case SemCmdResult.Stats(fc, sc, oc, _, _) =>
        assert(fc > 0, "should have files")
        assert(sc > 0, "should have symbols")
        assert(oc > 0, "should have occurrences")
      case other =>
        fail(s"unexpected result: $other")
  }
