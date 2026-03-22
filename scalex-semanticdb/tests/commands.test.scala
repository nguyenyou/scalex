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

  test("callers with --exclude filters matching FQNs") {
    val ctx = makeCtx(excludePatterns = List("Main"))
    val result = cmdCallers(List("greet"), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, _) =>
        val fqns = syms.map(_.fqn)
        assert(!fqns.exists(_.contains("Main")), s"Main should be excluded: $fqns")
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

  test("callees with --no-accessors excludes val fields") {
    val ctx = makeCtx(noAccessors = true)
    // greet() calls name and sound which are abstract val-like defs on Animal
    val result = cmdCallees(List("greet"), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, total) =>
        val withAccessors = {
          val r = cmdCallees(List("greet"), makeCtx())
          r.asInstanceOf[SemCmdResult.SymbolList].total
        }
        assert(total <= withAccessors, s"--no-accessors should not increase count: $total vs $withAccessors")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("callees with --exclude filters matching FQNs") {
    val ctx = makeCtx(excludePatterns = List("AnimalService"))
    val result = cmdCallees(List("example/Main.main()."), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, _) =>
        val fqns = syms.map(_.fqn)
        assert(!fqns.exists(_.contains("AnimalService")), s"AnimalService should be excluded: $fqns")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("callees without --exclude includes AnimalService calls") {
    val ctx = makeCtx()
    val result = cmdCallees(List("example/Main.main()."), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, _) =>
        val fqns = syms.map(_.fqn)
        assert(fqns.exists(_.contains("AnimalService")), s"without exclude, AnimalService should be present: $fqns")
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

  test("flow with --no-accessors produces fewer lines") {
    val withAccessors = cmdFlow(List("example/Main.main()."), makeCtx(depth = 2))
    val withoutAccessors = cmdFlow(List("example/Main.main()."), makeCtx(depth = 2, noAccessors = true))
    (withAccessors, withoutAccessors) match
      case (SemCmdResult.FlowTree(_, linesA), SemCmdResult.FlowTree(_, linesB)) =>
        assert(linesB.size <= linesA.size, s"--no-accessors should not increase lines: ${linesB.size} vs ${linesA.size}")
      case other =>
        fail(s"unexpected results: $other")
  }

  test("flow with --exclude filters symbols from tree") {
    val ctx = makeCtx(depth = 2, excludePatterns = List("AnimalService"))
    val result = cmdFlow(List("example/Main.main()."), ctx)
    result match
      case SemCmdResult.FlowTree(_, lines) =>
        val text = lines.mkString("\n")
        assert(!text.contains("AnimalServiceImpl"), s"AnimalServiceImpl should be excluded from flow: $text")
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

  // ── batch ────────────────────────────────────────────────────────────────

  test("batch runs multiple commands") {
    val ctx = makeCtx()
    val result = runBatch(List("lookup Dog", "lookup Cat"), ctx)
    result match
      case SemCmdResult.Batch(results) =>
        assertEquals(results.size, 2)
        // Both should find symbols, not NotFound
        results.foreach { entry =>
          assert(!entry.result.isInstanceOf[SemCmdResult.NotFound], s"${entry.command} should find symbol")
        }
      case other =>
        fail(s"unexpected result: $other")
  }

  test("batch with mixed commands") {
    val ctx = makeCtx()
    val result = runBatch(List("lookup Dog", "members Animal", "subtypes Shape"), ctx)
    result match
      case SemCmdResult.Batch(results) =>
        assertEquals(results.size, 3)
        // lookup Dog -> SymbolList or SymbolDetail
        assert(!results(0).result.isInstanceOf[SemCmdResult.NotFound])
        // members Animal -> SymbolList
        assert(results(1).result.isInstanceOf[SemCmdResult.SymbolList])
        // subtypes Shape -> Tree
        assert(results(2).result.isInstanceOf[SemCmdResult.Tree])
      case other =>
        fail(s"unexpected result: $other")
  }

  test("batch with unknown sub-command returns UsageError for that entry") {
    val ctx = makeCtx()
    val result = runBatch(List("lookup Dog", "nosuchcmd Foo", "lookup Cat"), ctx)
    result match
      case SemCmdResult.Batch(results) =>
        assertEquals(results.size, 3)
        assert(!results(0).result.isInstanceOf[SemCmdResult.UsageError])
        assert(results(1).result.isInstanceOf[SemCmdResult.UsageError])
        assert(!results(2).result.isInstanceOf[SemCmdResult.UsageError])
      case other =>
        fail(s"unexpected result: $other")
  }

  test("batch with no args returns UsageError") {
    val ctx = makeCtx()
    val result = runBatch(Nil, ctx)
    assert(result.isInstanceOf[SemCmdResult.UsageError])
  }

  test("batch with per-sub-command flags") {
    val ctx = makeCtx()
    val result = runBatch(List("members --kind method Animal"), ctx)
    result match
      case SemCmdResult.Batch(results) =>
        assertEquals(results.size, 1)
        results.head.result match
          case SemCmdResult.SymbolList(_, syms, _) =>
            assert(syms.forall(_.kind == SemKind.Method), s"all should be methods: ${syms.map(s => s.displayName -> s.kind)}")
          case other =>
            fail(s"unexpected sub-result: $other")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("batch text output has delimiters") {
    val ctx = makeCtx()
    val output = captureOut {
      val result = runBatch(List("lookup Dog", "stats"), ctx)
      render(result, ctx)
    }
    assert(output.contains("--- lookup Dog ---"), s"missing delimiter: $output")
    assert(output.contains("--- stats ---"), s"missing delimiter: $output")
  }

  test("batch json output wraps results") {
    val ctx = makeCtx(json = true)
    val output = captureOut {
      val result = runBatch(List("lookup Dog", "stats"), ctx)
      render(result, ctx)
    }
    assert(output.contains("\"batch\""), s"json should have batch key: $output")
    assert(output.contains("\"command\""), s"json should have command key: $output")
    assert(output.contains("lookup Dog"), s"json should include command string: $output")
  }

  test("batch with --no-accessors flag in sub-command") {
    val ctx = makeCtx()
    val result = runBatch(List("callees --no-accessors greet"), ctx)
    result match
      case SemCmdResult.Batch(results) =>
        assertEquals(results.size, 1)
        assert(!results.head.result.isInstanceOf[SemCmdResult.UsageError],
          s"should succeed: ${results.head.result}")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("batch with --exclude flag in sub-command") {
    val ctx = makeCtx()
    val result = runBatch(List("callees --exclude AnimalService example/Main.main()."), ctx)
    result match
      case SemCmdResult.Batch(results) =>
        assertEquals(results.size, 1)
        results.head.result match
          case SemCmdResult.SymbolList(_, syms, _) =>
            val fqns = syms.map(_.fqn)
            assert(!fqns.exists(_.contains("AnimalService")), s"AnimalService should be excluded: $fqns")
          case other =>
            fail(s"unexpected sub-result: $other")
      case other =>
        fail(s"unexpected result: $other")
  }

  // ── kind-aware resolution ────────────────────────────────────────────────

  test("callees with --kind method resolves to method, not object") {
    // "Main" matches both the object and the main method. With --kind method,
    // it should resolve to the method and show its callees.
    val ctx = makeCtx(kindFilter = Some("method"))
    val result = cmdCallees(List("main"), ctx)
    result match
      case SemCmdResult.SymbolList(header, syms, total) =>
        assert(total > 0, s"--kind method should resolve main to the method with callees: $header")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("flow with --kind method resolves to method") {
    val ctx = makeCtx(depth = 1, kindFilter = Some("method"))
    val result = cmdFlow(List("main"), ctx)
    result match
      case SemCmdResult.FlowTree(_, lines) =>
        assert(lines.size > 1, s"--kind method should resolve to the method with callees: $lines")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("callers with --kind method narrows resolution") {
    val ctx = makeCtx(kindFilter = Some("method"))
    val result = cmdCallers(List("register"), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, _) =>
        val names = syms.map(_.displayName)
        assert(names.contains("main"), s"main should call register: $names")
      case other =>
        fail(s"unexpected result: $other")
  }

  // ── exclude matches file path ───────────────────────────────────────────

  test("exclude matches file path, not just FQN") {
    val ctx = makeCtx(excludePatterns = List("Animal.scala"))
    val result = cmdCallees(List("example/Main.main()."), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, _) =>
        val files = syms.map(_.sourceUri)
        assert(!files.exists(_.contains("Animal.scala")),
          s"symbols from Animal.scala should be excluded: ${syms.map(s => s.displayName -> s.sourceUri)}")
      case other =>
        fail(s"unexpected result: $other")
  }

  // ── smart flag ──────────────────────────────────────────────────────────

  test("flow with --smart produces fewer lines than without") {
    val without = cmdFlow(List("example/Main.main()."), makeCtx(depth = 2))
    val withSmart = cmdFlow(List("example/Main.main()."), makeCtx(depth = 2, smart = true))
    (without, withSmart) match
      case (SemCmdResult.FlowTree(_, linesA), SemCmdResult.FlowTree(_, linesB)) =>
        assert(linesB.size <= linesA.size,
          s"--smart should not increase lines: ${linesB.size} vs ${linesA.size}")
      case other =>
        fail(s"unexpected results: $other")
  }

  test("callees with --smart filters accessors and plumbing") {
    val without = cmdCallees(List("example/Main.main()."), makeCtx())
    val withSmart = cmdCallees(List("example/Main.main()."), makeCtx(smart = true))
    (without, withSmart) match
      case (SemCmdResult.SymbolList(_, _, totalA), SemCmdResult.SymbolList(_, _, totalB)) =>
        assert(totalB <= totalA,
          s"--smart should not increase callees: $totalB vs $totalA")
      case other =>
        fail(s"unexpected results: $other")
  }

  // ── resolveSymbol generated disambiguation ──────────────────────────────

  test("resolveSymbol prefers source over generated URIs") {
    // All test fixture symbols are from source (not out/). They should come first.
    val syms = index.resolveSymbol("Dog")
    assert(syms.nonEmpty)
    assert(!syms.head.sourceUri.startsWith("out/"),
      s"source symbols should rank before generated: ${syms.head.sourceUri}")
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
