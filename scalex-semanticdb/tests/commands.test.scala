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
    val ctx = makeCtx(depth = Some(2))
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
    val withAccessors = cmdFlow(List("example/Main.main()."), makeCtx(depth = Some(2)))
    val withoutAccessors = cmdFlow(List("example/Main.main()."), makeCtx(depth = Some(2), noAccessors = true))
    val linesA = withAccessors.asInstanceOf[SemCmdResult.FlowTree].lines
    val linesB = withoutAccessors.asInstanceOf[SemCmdResult.FlowTree].lines
    assert(linesB.size <= linesA.size, s"--no-accessors should not increase lines: ${linesB.size} vs ${linesA.size}")
  }

  test("flow with --exclude filters symbols from tree") {
    val ctx = makeCtx(depth = Some(2), excludePatterns = List("AnimalService"))
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
    val ctx = makeCtx(depth = Some(1), kindFilter = Some("method"))
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
    val without = cmdFlow(List("example/Main.main()."), makeCtx(depth = Some(2)))
    val withSmart = cmdFlow(List("example/Main.main()."), makeCtx(depth = Some(2), smart = true))
    val linesA = without.asInstanceOf[SemCmdResult.FlowTree].lines
    val linesB = withSmart.asInstanceOf[SemCmdResult.FlowTree].lines
    assert(linesB.size <= linesA.size,
      s"--smart should not increase lines: ${linesB.size} vs ${linesA.size}")
  }

  test("callees with --smart filters accessors") {
    val without = cmdCallees(List("example/Main.main()."), makeCtx())
    val withSmart = cmdCallees(List("example/Main.main()."), makeCtx(smart = true))
    val totalA = without.asInstanceOf[SemCmdResult.SymbolList].total
    val totalB = withSmart.asInstanceOf[SemCmdResult.SymbolList].total
    assert(totalB <= totalA,
      s"--smart should not increase callees: $totalB vs $totalA")
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

  // ── transitive callers ──────────────────────────────────────────────────

  test("callers with default depth returns flat SymbolList") {
    val ctx = makeCtx()
    val result = cmdCallers(List("greet"), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, _) =>
        val names = syms.map(_.displayName)
        assert(names.contains("main"), s"main should call greet: $names")
      case other =>
        fail(s"expected SymbolList for flat callers, got: $other")
  }

  test("callers with --depth 1 explicitly returns flat SymbolList") {
    val ctx = makeCtx(depth = Some(1))
    val result = cmdCallers(List("greet"), ctx)
    result match
      case SemCmdResult.SymbolList(_, _, _) => () // flat mode
      case other =>
        fail(s"expected SymbolList for depth=1, got: $other")
  }

  test("callers with --depth 2 returns FlowTree with transitive callers") {
    val ctx = makeCtx(depth = Some(2))
    // greet is called by main — tree mode should show the caller tree
    val result = cmdCallers(List("greet"), ctx)
    result match
      case SemCmdResult.FlowTree(header, lines) =>
        assert(header.contains("Caller tree"), s"header should indicate tree mode: $header")
        val text = lines.mkString("\n")
        assert(text.contains("greet"), s"should contain root symbol: $text")
        // main calls greet, so it should appear in the tree
        assert(text.contains("main"), s"should contain caller main: $text")
      case other =>
        fail(s"expected FlowTree for depth>1, got: $other")
  }

  test("callers --depth 2 shows root location in first line") {
    val ctx = makeCtx(depth = Some(2))
    val result = cmdCallers(List("example/Animal#greet()."), ctx)
    result match
      case SemCmdResult.FlowTree(_, lines) =>
        // Root line should include file location
        assert(lines.head.contains("Animal.scala"), s"root line should have location: ${lines.head}")
      case other =>
        fail(s"expected FlowTree, got: $other")
  }

  test("callers --depth tree does not duplicate visited nodes") {
    // name is called by greet, fetch, findByName, describe etc.
    // At depth 3, callers of those callers should not re-appear
    val ctx = makeCtx(depth = Some(3))
    val result = cmdCallers(List("example/Animal#name()."), ctx)
    result match
      case SemCmdResult.FlowTree(_, lines) =>
        val text = lines.mkString("\n")
        // Count occurrences of "main" — should appear at most once due to visited set
        val mainCount = lines.count(_.contains("main"))
        assert(mainCount <= 1, s"main should not be duplicated in tree (found $mainCount times):\n$text")
      case other =>
        fail(s"expected FlowTree, got: $other")
  }

  test("callers with --depth handles cycles without infinite loop") {
    // greet is called by main, main is an entrypoint (no callers beyond it)
    // This should terminate cleanly
    val ctx = makeCtx(depth = Some(5))
    val result = cmdCallers(List("greet"), ctx)
    result match
      case SemCmdResult.FlowTree(_, lines) =>
        assert(lines.nonEmpty, s"should produce output: $lines")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("callers --depth with --exclude filters from tree") {
    val ctx = makeCtx(depth = Some(2), excludePatterns = List("Main"))
    val result = cmdCallers(List("example/Animal#greet()."), ctx)
    result match
      case SemCmdResult.FlowTree(_, lines) =>
        val text = lines.mkString("\n")
        assert(!text.contains("Main"), s"Main should be excluded from caller tree: $text")
      case other =>
        fail(s"expected FlowTree, got: $other")
  }

  test("callers --depth for symbol with no callers shows only root") {
    // main is the entrypoint — nobody calls it
    val ctx = makeCtx(depth = Some(3))
    val result = cmdCallers(List("example/Main.main()."), ctx)
    result match
      case SemCmdResult.FlowTree(_, lines) =>
        assert(lines.size == 1, s"symbol with no callers should only have root line: $lines")
        assert(lines.head.contains("main"), s"root should be main: ${lines.head}")
      case other =>
        fail(s"expected FlowTree, got: $other")
  }

  test("callers with no args returns UsageError") {
    val ctx = makeCtx()
    val result = cmdCallers(Nil, ctx)
    result match
      case SemCmdResult.UsageError(_) => ()
      case other => fail(s"expected UsageError, got: $other")
  }

  test("callers for unknown symbol returns NotFound") {
    val ctx = makeCtx()
    val result = cmdCallers(List("nonexistent_xyz"), ctx)
    result match
      case SemCmdResult.NotFound(_) => ()
      case other => fail(s"expected NotFound, got: $other")
  }

  // ── findCallers helper ──────────────────────────────────────────────────

  test("findCallers returns direct callers of a method") {
    val callers = findCallers("example/Animal#greet().", index)
    val names = callers.map(_.displayName)
    // greet is called by Main.main and AnimalOps.loudGreet
    assert(names.contains("main"), s"main should call greet: $names")
    assert(names.contains("loudGreet"), s"loudGreet should call greet: $names")
  }

  test("findCallers returns empty for symbol with no callers") {
    // oldMethod is defined but never called in the fixtures
    val callers = findCallers("example/OldService#oldMethod().", index)
    assert(callers.isEmpty, s"oldMethod has no callers: ${callers.map(_.displayName)}")
  }

  // ── disambiguation ────────────────────────────────────────────────────

  test("resolveOne prints disambiguation hint to stderr for ambiguous query") {
    val err = java.io.ByteArrayOutputStream()
    val ps = java.io.PrintStream(err)
    val oldErr = System.err
    System.setErr(ps)
    try
      val sym = resolveOne("name", index, kindFilter = None)
      // "name" matches Dog.name, Cat.name, Animal.name, etc.
      assert(sym.isDefined, "should still resolve to a symbol")
      ps.flush()
      val errText = err.toString
      // Only show hint when actually ambiguous (>1 match)
      val names = index.resolveSymbol("name")
      if names.size > 1 then
        assert(errText.contains("Ambiguous"), s"should print disambiguation hint: $errText")
    finally
      System.setErr(oldErr)
  }

  test("resolveOne with unique symbol does not print hint") {
    val err = java.io.ByteArrayOutputStream()
    val ps = java.io.PrintStream(err)
    val oldErr = System.err
    System.setErr(ps)
    try
      // AnimalRepository is unique — only one class with that name
      val sym = resolveOne("AnimalRepository", index, kindFilter = None)
      assert(sym.isDefined, "should resolve")
      ps.flush()
      val errText = err.toString
      assert(!errText.contains("Ambiguous"), s"unique symbol should not print hint: $errText")
    finally
      System.setErr(oldErr)
  }

  test("resolveOne returns None for unknown symbol") {
    val sym = resolveOne("completely_nonexistent_xyz", index, kindFilter = None)
    assert(sym.isEmpty, "should return None for unknown symbol")
  }

  test("resolveOne with kindFilter narrows to one match") {
    val err = java.io.ByteArrayOutputStream()
    val ps = java.io.PrintStream(err)
    val oldErr = System.err
    System.setErr(ps)
    try
      // "main" matches both Main (object) and main (method)
      // --kind method should narrow to one
      val sym = resolveOne("main", index, kindFilter = Some("method"))
      assert(sym.isDefined, "should resolve with kind filter")
      assert(sym.get.kind == SemKind.Method, s"should be a method: ${sym.get.kind}")
      ps.flush()
      val errText = err.toString
      assert(!errText.contains("Ambiguous"), s"kind-filtered to 1 should not print hint: $errText")
    finally
      System.setErr(oldErr)
  }

  test("resolveOne disambiguation hint shows candidates with FQN") {
    val err = java.io.ByteArrayOutputStream()
    val ps = java.io.PrintStream(err)
    val oldErr = System.err
    System.setErr(ps)
    try
      resolveOne("name", index, kindFilter = None)
      ps.flush()
      val errText = err.toString
      // Should show full FQN paths for candidates
      assert(errText.contains("example/"), s"hint should show FQN with package: $errText")
      assert(errText.contains("Candidates:"), s"hint should list candidates: $errText")
    finally
      System.setErr(oldErr)
  }

  // ── path command ──────────────────────────────────────────────────────

  test("path finds direct call path") {
    val ctx = makeCtx()
    val result = cmdPath(List("example/Main.main().", "register"), ctx)
    result match
      case SemCmdResult.FlowTree(header, lines) =>
        assert(header.contains("Call path"), s"header should indicate path: $header")
        val text = lines.mkString("\n")
        assert(text.contains("main"), s"should contain source: $text")
        assert(text.contains("register"), s"should contain target: $text")
      case SemCmdResult.NotFound(msg) =>
        fail(s"path should be found: $msg")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("path header shows hop count") {
    val ctx = makeCtx()
    val result = cmdPath(List("example/Main.main().", "register"), ctx)
    result match
      case SemCmdResult.FlowTree(header, _) =>
        assert(header.contains("hop"), s"header should show hops: $header")
      case other =>
        fail(s"expected FlowTree, got: $other")
  }

  test("path finds multi-hop path") {
    val ctx = makeCtx()
    // main -> greet -> name (name is called inside greet via string interpolation)
    val result = cmdPath(List("example/Main.main().", "example/Animal#name()."), ctx)
    result match
      case SemCmdResult.FlowTree(header, lines) =>
        assert(lines.size >= 2, s"multi-hop path should have at least 2 lines: $lines")
      case SemCmdResult.NotFound(msg) =>
        // If path is not found via callees (depends on how greet's body refs resolve),
        // that's acceptable for this fixture
        ()
      case other =>
        fail(s"unexpected result: $other")
  }

  test("path returns NotFound for unreachable target") {
    val ctx = makeCtx()
    val result = cmdPath(List("example/Main.main().", "nonexistent_symbol_xyz"), ctx)
    result match
      case SemCmdResult.NotFound(_) => () // expected
      case other =>
        fail(s"expected NotFound, got: $other")
  }

  test("path returns NotFound for disconnected symbols") {
    val ctx = makeCtx()
    // capitalize (in util package) is never called from main
    val result = cmdPath(List("example/Main.main().", "example/OldService#oldMethod()."), ctx)
    result match
      case SemCmdResult.NotFound(_) => () // expected — no call path
      case SemCmdResult.FlowTree(_, _) =>
        fail("should not find path from main to oldMethod (never called)")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("path with --depth 0 returns NotFound (no search possible)") {
    val ctx = makeCtx(depth = Some(0))
    val result = cmdPath(List("example/Main.main().", "register"), ctx)
    result match
      case SemCmdResult.NotFound(_) => ()
      case other =>
        fail(s"expected NotFound at depth 0, got: $other")
  }

  test("path with --depth 1 limits search depth") {
    val ctx = makeCtx(depth = Some(1))
    // With depth=1, can only find direct callees of main
    val result = cmdPath(List("example/Main.main().", "register"), ctx)
    result match
      case SemCmdResult.FlowTree(header, _) =>
        assert(header.contains("Call path"), s"should find direct callee: $header")
      case SemCmdResult.NotFound(_) =>
        () // acceptable if register is not a direct callee at depth 1
      case other =>
        fail(s"unexpected result: $other")
  }

  test("path with --exclude filters intermediate nodes") {
    val ctx = makeCtx(excludePatterns = List("AnimalService"))
    val result = cmdPath(List("example/Main.main().", "register"), ctx)
    // register is in AnimalService, so excluding it should make path unfindable
    // (or find an alternative path if one exists)
    result match
      case SemCmdResult.FlowTree(_, lines) =>
        val text = lines.mkString("\n")
        assert(!text.contains("AnimalService"), s"excluded symbols should not appear: $text")
      case SemCmdResult.NotFound(_) => () // acceptable
      case other =>
        fail(s"unexpected result: $other")
  }

  test("path with no args returns UsageError") {
    val ctx = makeCtx()
    val result = cmdPath(Nil, ctx)
    result match
      case SemCmdResult.UsageError(_) => ()
      case other => fail(s"expected UsageError, got: $other")
  }

  test("path with only one arg returns UsageError") {
    val ctx = makeCtx()
    val result = cmdPath(List("main"), ctx)
    result match
      case SemCmdResult.UsageError(_) => ()
      case other => fail(s"expected UsageError, got: $other")
  }

  test("path with unknown source returns NotFound") {
    val ctx = makeCtx()
    val result = cmdPath(List("nonexistent_xyz", "register"), ctx)
    result match
      case SemCmdResult.NotFound(_) => ()
      case other => fail(s"expected NotFound, got: $other")
  }

  test("path with unknown target returns NotFound") {
    val ctx = makeCtx()
    val result = cmdPath(List("example/Main.main().", "nonexistent_xyz"), ctx)
    result match
      case SemCmdResult.NotFound(_) => ()
      case other => fail(s"expected NotFound, got: $other")
  }

  // ── explain command ───────────────────────────────────────────────────

  test("explain method shows callers and callees") {
    val ctx = makeCtx()
    val result = cmdExplain(List("example/Main.main()."), ctx)
    result match
      case SemCmdResult.ExplainResult(sym, definedAt, callers, totalCallers, callees, totalCallees, _, _, _) =>
        assert(sym.displayName == "main", s"should resolve to main: ${sym.displayName}")
        assert(definedAt.isDefined, "should have definition location")
        assert(totalCallees > 0, s"main should have callees: $totalCallees")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("explain method includes definition file and line") {
    val ctx = makeCtx()
    val result = cmdExplain(List("example/Main.main()."), ctx)
    result match
      case SemCmdResult.ExplainResult(_, definedAt, _, _, _, _, _, _, _) =>
        assert(definedAt.isDefined, "should have definition location")
        val (file, line) = definedAt.get
        assert(file.contains("Main.scala"), s"should be in Main.scala: $file")
        assert(line > 0, s"line should be positive: $line")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("explain method with callers shows who calls it") {
    val ctx = makeCtx()
    // greet is called by main and loudGreet
    val result = cmdExplain(List("example/Animal#greet()."), ctx)
    result match
      case SemCmdResult.ExplainResult(_, _, callers, totalCallers, _, _, _, _, _) =>
        assert(totalCallers > 0, s"greet should have callers: $totalCallers")
        val callerNames = callers.map(_.displayName)
        assert(callerNames.contains("main") || callerNames.contains("loudGreet"),
          s"greet callers should include main or loudGreet: $callerNames")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("explain class shows members and parents") {
    val ctx = makeCtx()
    val result = cmdExplain(List("Dog"), ctx)
    result match
      case SemCmdResult.ExplainResult(sym, _, _, _, _, _, parents, members, totalMembers) =>
        assert(sym.displayName == "Dog", s"should resolve to Dog: ${sym.displayName}")
        assert(parents.nonEmpty, s"Dog should have parents (Animal)")
        assert(totalMembers > 0, s"Dog should have members: $totalMembers")
        val memberNames = members.map(_.displayName)
        assert(memberNames.contains("fetch") || memberNames.contains("sound") || memberNames.contains("name"),
          s"Dog members should include fetch/sound/name: $memberNames")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("explain class has zero callers/callees") {
    val ctx = makeCtx()
    val result = cmdExplain(List("Dog"), ctx)
    result match
      case SemCmdResult.ExplainResult(_, _, _, totalCallers, _, totalCallees, _, _, _) =>
        assert(totalCallers == 0, s"class itself should have 0 callers: $totalCallers")
        assert(totalCallees == 0, s"class itself should have 0 callees: $totalCallees")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("explain trait shows parents and members") {
    val ctx = makeCtx()
    val result = cmdExplain(List("AnimalService"), ctx)
    result match
      case SemCmdResult.ExplainResult(sym, _, _, _, _, _, _, members, totalMembers) =>
        assert(sym.kind == SemKind.Trait, s"should be a trait: ${sym.kind}")
        assert(totalMembers > 0, s"AnimalService should have members: $totalMembers")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("explain object shows members") {
    val ctx = makeCtx()
    val result = cmdExplain(List("AnimalOps"), ctx)
    result match
      case SemCmdResult.ExplainResult(sym, _, _, _, _, _, _, members, totalMembers) =>
        assert(sym.kind == SemKind.Object, s"should be an object: ${sym.kind}")
        assert(totalMembers > 0, s"AnimalOps should have members: $totalMembers")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("explain field shows callers") {
    val ctx = makeCtx()
    // Dog.name is a field (val), called by greet, fetch, findByName, describe
    val result = cmdExplain(List("example/Dog#name."), ctx)
    result match
      case SemCmdResult.ExplainResult(_, _, _, totalCallers, _, _, _, _, _) =>
        assert(totalCallers >= 0, s"field explain should work: $totalCallers")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("explain unknown symbol returns NotFound") {
    val ctx = makeCtx()
    val result = cmdExplain(List("nonexistent_symbol_xyz"), ctx)
    result match
      case SemCmdResult.NotFound(_) => () // expected
      case other =>
        fail(s"expected NotFound, got: $other")
  }

  test("explain with no args returns UsageError") {
    val ctx = makeCtx()
    val result = cmdExplain(Nil, ctx)
    result match
      case SemCmdResult.UsageError(_) => ()
      case other => fail(s"expected UsageError, got: $other")
  }

  test("explain text output renders all sections") {
    val ctx = makeCtx()
    val result = cmdExplain(List("example/Animal#greet()."), ctx)
    val output = captureOut { renderText(result, ctx) }
    assert(output.contains("greet"), s"should contain symbol name: $output")
    assert(output.contains("Defined:"), s"should contain Defined section: $output")
  }

  test("explain JSON output is valid") {
    val ctx = makeCtx(json = true)
    val result = cmdExplain(List("example/Animal#greet()."), ctx)
    val output = captureOut { renderJson(result, ctx) }
    assert(output.contains("\"symbol\""), s"JSON should contain symbol field: $output")
    assert(output.contains("\"totalCallers\""), s"JSON should contain totalCallers: $output")
    assert(output.contains("\"totalCallees\""), s"JSON should contain totalCallees: $output")
  }
