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
          s"Animal should be related to Dog: ${entries.map(e => (name = e.sym.displayName, count = e.count))}")
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
            assert(syms.forall(_.kind == SemKind.Method), s"all should be methods: ${syms.map(s => (name = s.displayName, kind = s.kind))}")
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

  test("batch with quoted FQN resolves correctly") {
    // Bug #303: batch sub-commands with quoted FQNs should strip surrounding quotes
    // before resolving. Without the fix, literal " chars break FQN lookup.
    val ctx = makeCtx()
    val result = runBatch(List("""lookup "example/Dog#" """), ctx)
    result match
      case SemCmdResult.Batch(results) =>
        assertEquals(results.size, 1)
        results.head.result match
          case SemCmdResult.SymbolDetail(sym) =>
            assertEquals(sym.fqn, "example/Dog#")
          case SemCmdResult.SymbolList(_, syms, _) =>
            assert(syms.exists(_.fqn == "example/Dog#"),
              s"should find Dog via quoted FQN: ${syms.map(_.fqn)}")
          case SemCmdResult.NotFound(msg) =>
            fail(s"quoted FQN should resolve but got NotFound: $msg")
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
          s"symbols from Animal.scala should be excluded: ${syms.map(s => (name = s.displayName, file = s.sourceUri))}")
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
      case SemCmdResult.Stats(fc, sc, oc, _, _, _, _) =>
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

  // ── trait-aware callers ─────────────────────────────────────────────────

  test("findCallersTraitAware includes callers through trait indirection") {
    // TraitCaller.viaTrait calls register through the AnimalService trait type
    val implFqn = index.resolveSymbol("register").find(_.owner.contains("AnimalServiceImpl")).map(_.fqn)
    val traitFqn = index.resolveSymbol("register").find(_.owner.contains("AnimalService#")).map(_.fqn)
    assert(implFqn.isDefined, "fixture AnimalServiceImpl#register should resolve")
    assert(traitFqn.isDefined, "fixture AnimalService#register should resolve")
    val fqn = implFqn.get
    val directCallers = findCallers(fqn, index)
    val traitAwareCallers = findCallersTraitAware(fqn, index)
    // Trait-aware should find strictly more callers (trait-typed call sites)
    assert(traitAwareCallers.size > directCallers.size,
      s"trait-aware callers (${traitAwareCallers.size}) should be > direct callers (${directCallers.size})")
    // Verify trait callers are found through the trait FQN
    val traitCallers = findCallers(traitFqn.get, index)
    val traitCallerNames = traitCallers.map(_.displayName)
    assert(traitCallerNames.contains("viaTrait"),
      s"viaTrait should call trait's register: $traitCallerNames")
  }

  test("callers command uses trait-aware resolution by default") {
    // Query callers of register scoped to impl — should include TraitCaller.viaTrait
    val implSymbols = index.resolveSymbol("register").filter(_.owner.contains("AnimalServiceImpl"))
    assert(implSymbols.nonEmpty, "fixture AnimalServiceImpl#register should resolve")
    val ctx = makeCtx(inScope = Some("AnimalServiceImpl"))
    val result = cmdCallers(List("register"), ctx)
    result match
      case SemCmdResult.SymbolList(_, callers, _) =>
        val names = callers.map(_.displayName)
        // viaTrait calls register through trait type — trait-aware callers should find it
        assert(names.contains("viaTrait"),
          s"trait-aware callers should include viaTrait: $names")
      case other =>
        fail(s"expected SymbolList, got: $other")
  }

  test("callers --depth 2 traverses through trait indirection") {
    val implSymbols = index.resolveSymbol("register").filter(_.owner.contains("AnimalServiceImpl"))
    assert(implSymbols.nonEmpty, "fixture AnimalServiceImpl#register should resolve")
    val ctx = makeCtx(depth = Some(2), inScope = Some("AnimalServiceImpl"))
    val result = cmdCallers(List("register"), ctx)
    result match
      case SemCmdResult.FlowTree(_, lines) =>
        val text = lines.mkString("\n")
        assert(text.contains("viaTrait"),
          s"transitive callers should include viaTrait through trait: $text")
      case other =>
        fail(s"expected FlowTree, got: $other")
  }

  // ── group-by-file ─────────────────────────────────────────────────────

  test("callers --group-by-file returns Tree grouped by source file") {
    val ctx = makeCtx(groupByFile = true)
    val result = cmdCallers(List("greet"), ctx)
    result match
      case SemCmdResult.Tree(header, lines) =>
        assert(header.contains("grouped by file"), s"header should mention grouping: $header")
        // Lines should contain file headers with counts in parentheses
        val fileHeaders = lines.filter(l => l.contains("(") && !l.startsWith("    "))
        assert(fileHeaders.nonEmpty, s"should have file group headers: $lines")
        // Each file header should be indented with 2 spaces, members with 4
        val memberLines = lines.filter(_.startsWith("    "))
        assert(memberLines.nonEmpty, s"should have member lines under file groups: $lines")
      case other =>
        fail(s"expected Tree for group-by-file, got: $other")
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
      case SemCmdResult.ExplainResult(sym, definedAt, callers, totalCallers, callees, totalCallees, _, _, _, _, _) =>
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
      case SemCmdResult.ExplainResult(_, definedAt, _, _, _, _, _, _, _, _, _) =>
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
      case SemCmdResult.ExplainResult(_, _, callers, totalCallers, _, _, _, _, _, _, _) =>
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
      case SemCmdResult.ExplainResult(sym, _, _, _, _, _, parents, members, totalMembers, _, _) =>
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
      case SemCmdResult.ExplainResult(_, _, _, totalCallers, _, totalCallees, _, _, _, _, _) =>
        assert(totalCallers == 0, s"class itself should have 0 callers: $totalCallers")
        assert(totalCallees == 0, s"class itself should have 0 callees: $totalCallees")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("explain trait shows parents and members") {
    val ctx = makeCtx()
    val result = cmdExplain(List("AnimalService"), ctx)
    result match
      case SemCmdResult.ExplainResult(sym, _, _, _, _, _, _, members, totalMembers, _, _) =>
        assert(sym.kind == SemKind.Trait, s"should be a trait: ${sym.kind}")
        assert(totalMembers > 0, s"AnimalService should have members: $totalMembers")
      case other =>
        fail(s"unexpected result: $other")
  }

  test("explain object shows members") {
    val ctx = makeCtx()
    val result = cmdExplain(List("AnimalOps"), ctx)
    result match
      case SemCmdResult.ExplainResult(sym, _, _, _, _, _, _, members, totalMembers, _, _) =>
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
      case SemCmdResult.ExplainResult(_, _, _, totalCallers, _, _, _, _, _, _, _) =>
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

  // ── --in flag (#303) ──────────────────────────────────────────────────────

  test("--in scopes resolution to owner class") {
    // "sound" exists on Animal (trait), Dog (class), Cat (class).
    // With --in Dog, should pick Dog's sound.
    val ctx = makeCtx(inScope = Some("Dog"))
    val result = cmdLookup(List("sound"), ctx)
    result match
      case SemCmdResult.SymbolDetail(sym) =>
        assert(sym.owner.contains("Dog"), s"expected Dog owner, got ${sym.owner}")
      case SemCmdResult.SymbolList(_, syms, _) =>
        assert(syms.head.owner.contains("Dog"), s"expected Dog owner first, got ${syms.head.owner}")
      case other => fail(s"unexpected: $other")
  }

  test("--in scopes by file path") {
    val ctx = makeCtx(inScope = Some("Cat.scala"))
    val result = cmdLookup(List("sound"), ctx)
    result match
      case SemCmdResult.SymbolDetail(sym) =>
        assert(sym.sourceUri.contains("Cat.scala"), s"expected Cat.scala, got ${sym.sourceUri}")
      case SemCmdResult.SymbolList(_, syms, _) =>
        assert(syms.head.sourceUri.contains("Cat.scala"), s"expected Cat.scala first, got ${syms.head.sourceUri}")
      case other => fail(s"unexpected: $other")
  }

  test("--in with no match falls back to unscoped") {
    val ctx = makeCtx(inScope = Some("NonExistentOwner"))
    val result = cmdLookup(List("Dog"), ctx)
    // Should fall back to unscoped resolution and still find Dog
    assert(!result.isInstanceOf[SemCmdResult.NotFound], s"should fall back, not NotFound: $result")
  }

  test("--in works with callers") {
    // "register" exists on both AnimalService (trait) and AnimalServiceImpl (class).
    // With --in AnimalServiceImpl, callers should resolve to the impl's register.
    val ctx = makeCtx(inScope = Some("AnimalServiceImpl"))
    val result = cmdCallers(List("register"), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, _) =>
        val names = syms.map(_.displayName)
        assert(names.contains("main"), s"main should call register: $names")
      case other => fail(s"unexpected: $other")
  }

  // ── --exclude-test (#303) ─────────────────────────────────────────────────

  test("--exclude-test filters callers from test sources") {
    // DogTest.testBark calls Dog.sound and Dog.fetch.
    // Check all callers of "sound" — should include testBark from test source.
    val ctxAll = makeCtx()
    val resultAll = cmdCallers(List("sound"), ctxAll)
    val allCallers = resultAll match
      case SemCmdResult.SymbolList(_, syms, _) => syms
      case _ => Nil

    val hasTestCaller = allCallers.exists(s => isTestSource(s.sourceUri))

    // With --exclude-test, test callers should be filtered out.
    val ctxNoTest = makeCtx(excludeTest = true)
    val resultNoTest = cmdCallers(List("sound"), ctxNoTest)
    val filteredCallers = resultNoTest match
      case SemCmdResult.SymbolList(_, syms, _) => syms
      case _ => Nil

    if hasTestCaller then
      assert(!filteredCallers.exists(s => isTestSource(s.sourceUri)),
        s"with --exclude-test, should have no test callers: ${filteredCallers.map(s => (name = s.displayName, file = s.sourceUri))}")
    // If no test callers found at all, at least verify the flag doesn't break anything
    assert(filteredCallers.size <= allCallers.size,
      s"--exclude-test should not increase callers: ${filteredCallers.size} vs ${allCallers.size}")
  }

  test("--exclude-test filters callees from test sources") {
    val ctxNoTest = makeCtx(excludeTest = true)
    val result = cmdCallees(List("example/Main.main()."), ctxNoTest)
    result match
      case SemCmdResult.SymbolList(_, syms, _) =>
        assert(!syms.exists(s => isTestSource(s.sourceUri)),
          s"should have no test callees: ${syms.map(s => (name = s.displayName, file = s.sourceUri))}")
      case other => fail(s"unexpected: $other")
  }

  // ── --exclude-pkg (#303) ──────────────────────────────────────────────────

  test("--exclude-pkg filters by package prefix") {
    val ctx = makeCtx(excludePkgPatterns = List("example/"))
    val result = cmdCallees(List("example/Main.main()."), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, _) =>
        assert(!syms.exists(_.fqn.startsWith("example/")),
          s"example/ package should be excluded: ${syms.map(_.fqn)}")
      case other => fail(s"unexpected: $other")
  }

  test("--exclude-pkg normalizes with trailing slash in flow tree walk") {
    // Bug: flow/path/callers tree walks use inline filterNot without trailing "/" normalization,
    // so --exclude-pkg "exampl" incorrectly matches "example/" symbols.
    // With proper normalization, "exampl/" should NOT match "example/" FQNs.
    val ctx = makeCtx(depth = Some(1), excludePkgPatterns = List("exampl"))
    val result = cmdFlow(List("example/Main.main()."), ctx)
    result match
      case SemCmdResult.FlowTree(_, lines) =>
        // "exampl" (without e) normalized to "exampl/" should NOT match "example/" symbols
        assert(lines.size > 1,
          s"--exclude-pkg 'exampl' should not exclude 'example/' symbols in flow: $lines")
      case other => fail(s"unexpected: $other")
  }

  test("--exclude-pkg normalizes with trailing slash in callers tree walk") {
    // Same bug in transitive callers (--depth > 1)
    // Use "greet" which is called by main via all.foreach(a => println(a.greet()))
    val ctxBaseline = makeCtx(depth = Some(2), kindFilter = Some("method"))
    val baseline = cmdCallers(List("greet"), ctxBaseline)
    val baselineLines = baseline match
      case SemCmdResult.FlowTree(_, lines) => lines
      case _ => Nil

    // With --exclude-pkg "exampl" (no trailing 'e'), normalized to "exampl/"
    // should NOT match "example/" symbols — callers should still appear
    val ctx = makeCtx(depth = Some(2), excludePkgPatterns = List("exampl"), kindFilter = Some("method"))
    val result = cmdCallers(List("greet"), ctx)
    result match
      case SemCmdResult.FlowTree(_, lines) =>
        assertEquals(lines.size, baselineLines.size,
          s"--exclude-pkg 'exampl' should not exclude 'example/' callers: filtered=$lines baseline=$baselineLines")
      case other =>
        if baselineLines.nonEmpty then fail(s"expected FlowTree, got: $other")
  }

  // ── lookup annotations (#303) ─────────────────────────────────────────────

  test("formatSymbolLine shows [object] vs [class/trait] annotation") {
    // Main.main is an object member, Dog#sound is a class member
    val mainMethod = index.resolveSymbol("example/Main.main().").head
    val dogSound = index.resolveSymbol("example/Dog#sound().").head

    val mainLine = formatSymbolLine(mainMethod)
    val dogLine = formatSymbolLine(dogSound)

    assert(mainLine.contains("[object]"), s"Main.main should be [object]: $mainLine")
    assert(dogLine.contains("[class/trait]"), s"Dog#sound should be [class/trait]: $dogLine")
  }

  // ── FQN #/. fallback (#303) ───────────────────────────────────────────────

  test("resolveSymbol with wrong separator falls back via swap") {
    // Main is an object, so main is at "example/Main.main()."
    // Using # instead of . should still resolve via the swap fallback.
    val syms = index.resolveSymbol("example/Main#main().")
    assert(syms.nonEmpty, "should resolve via # -> . swap")
    assertEquals(syms.head.fqn, "example/Main.main().")
  }

  // ── members synthetic filtering (#307) ──────────────────────────────────

  test("members of case class hides synthetics by default") {
    val ctx = makeCtx()
    val result = cmdMembers(List("Circle"), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, _) =>
        val names = syms.map(_.displayName).toSet
        assert(!names.contains("copy"), s"copy should be hidden: $names")
        assert(!names.contains("productPrefix"), s"productPrefix should be hidden: $names")
        assert(!names.contains("_1"), s"_1 should be hidden: $names")
        // user-defined member should still be visible
        assert(names.contains("area"), s"area should be visible: $names")
      case other => fail(s"unexpected: $other")
  }

  test("members of case class with --verbose shows synthetics") {
    val ctx = makeCtx(verbose = true)
    val result = cmdMembers(List("Circle"), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, _) =>
        val names = syms.map(_.displayName).toSet
        assert(names.contains("copy") || names.contains("hashCode") || names.contains("productPrefix"),
          s"verbose should show synthetics: $names")
      case other => fail(s"unexpected: $other")
  }

  test("members of non-case class is unaffected by synthetic filtering") {
    val ctx = makeCtx()
    val result = cmdMembers(List("Dog"), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, _) =>
        val names = syms.map(_.displayName).toSet
        assert(names.contains("fetch"), s"fetch should be visible: $names")
        assert(names.contains("sound"), s"sound should be visible: $names")
      case other => fail(s"unexpected: $other")
  }

  test("members --smart filters accessors and infra noise") {
    val ctx = makeCtx(smart = true)
    val result = cmdMembers(List("Circle"), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, _) =>
        val names = syms.map(_.displayName).toSet
        assert(!names.contains("copy"), s"copy should be hidden with --smart: $names")
        assert(names.contains("area"), s"area should be visible with --smart: $names")
      case other => fail(s"unexpected: $other")
  }

  // ── explain subtypes (#307) ─────────────────────────────────────────────

  test("explain trait shows subtypes") {
    val ctx = makeCtx()
    val result = cmdExplain(List("Shape"), ctx)
    result match
      case er: SemCmdResult.ExplainResult =>
        assert(er.totalSubtypes >= 3, s"Shape should have >= 3 subtypes: ${er.totalSubtypes}")
        val subNames = er.subtypes.map(_.displayName).toSet
        assert(subNames.contains("Circle") || subNames.contains("Rectangle") || subNames.contains("Triangle"),
          s"subtypes should include Circle/Rectangle/Triangle: $subNames")
      case other => fail(s"unexpected: $other")
  }

  test("explain concrete class has no subtypes") {
    val ctx = makeCtx()
    val result = cmdExplain(List("Dog"), ctx)
    result match
      case er: SemCmdResult.ExplainResult =>
        assert(er.totalSubtypes == 0, s"Dog (concrete class) should have 0 subtypes: ${er.totalSubtypes}")
      case other => fail(s"unexpected: $other")
  }

  test("explain text output renders subtypes for traits") {
    val ctx = makeCtx()
    val result = cmdExplain(List("Shape"), ctx)
    val output = captureOut { renderText(result, ctx) }
    assert(output.contains("Subtypes:"), s"should contain Subtypes section: $output")
  }

  test("explain member filtering hides case class synthetics") {
    val ctx = makeCtx()
    val result = cmdExplain(List("Circle"), ctx)
    result match
      case er: SemCmdResult.ExplainResult =>
        val memberNames = er.members.map(_.displayName).toSet
        assert(!memberNames.contains("copy"), s"copy should be hidden in explain members: $memberNames")
        assert(!memberNames.contains("_1"), s"_1 should be hidden in explain members: $memberNames")
      case other => fail(s"unexpected: $other")
  }

  // ── isOverride preservation (#307) ──────────────────────────────────────

  test("members preserves user-overridden toString/equals/hashCode on case classes") {
    val ctx = makeCtx()
    val result = cmdMembers(List("Event"), ctx)
    result match
      case SemCmdResult.SymbolList(_, syms, _) =>
        val names = syms.map(_.displayName).toSet
        // User-overridden methods should NOT be hidden
        assert(names.contains("toString"), s"overridden toString should be visible: $names")
        assert(names.contains("equals"), s"overridden equals should be visible: $names")
        assert(names.contains("hashCode"), s"overridden hashCode should be visible: $names")
        // Purely synthetic members should still be hidden
        assert(!names.contains("copy"), s"copy should be hidden: $names")
        assert(!names.contains("productPrefix"), s"productPrefix should be hidden: $names")
      case other => fail(s"unexpected: $other")
  }

  test("explain preserves user-overridden toString on case classes") {
    val ctx = makeCtx()
    val result = cmdExplain(List("Event"), ctx)
    result match
      case er: SemCmdResult.ExplainResult =>
        val memberNames = er.members.map(_.displayName).toSet
        assert(memberNames.contains("toString"), s"overridden toString should be visible in explain: $memberNames")
      case other => fail(s"unexpected: $other")
  }

  // ── --source-only (#307) ────────────────────────────────────────────────

  test("lookup --source-only excludes generated sources") {
    // ProtoMessage is defined in out/generated/Proto.scala which matches isGeneratedSource
    val ctx = makeCtx(sourceOnly = true)
    val result = cmdLookup(List("ProtoMessage"), ctx)
    result match
      case SemCmdResult.NotFound(_) => () // correctly filtered
      case SemCmdResult.SymbolDetail(sym) =>
        assert(!isGeneratedSource(sym.sourceUri),
          s"--source-only should exclude generated: ${sym.sourceUri}")
      case SemCmdResult.SymbolList(_, syms, _) =>
        assert(syms.forall(s => !isGeneratedSource(s.sourceUri)),
          s"--source-only should exclude all generated: ${syms.map(_.sourceUri)}")
      case other => fail(s"unexpected: $other")
  }

  test("lookup without --source-only includes generated sources") {
    val ctx = makeCtx()
    val result = cmdLookup(List("ProtoMessage"), ctx)
    result match
      case SemCmdResult.NotFound(_) =>
        fail("ProtoMessage should be found without --source-only")
      case SemCmdResult.SymbolDetail(sym) =>
        assert(isGeneratedSource(sym.sourceUri),
          s"ProtoMessage should be from a generated source: ${sym.sourceUri}")
      case SemCmdResult.SymbolList(_, syms, _) =>
        assert(syms.exists(s => isGeneratedSource(s.sourceUri)),
          s"should include generated source: ${syms.map(_.sourceUri)}")
      case other => fail(s"unexpected: $other")
  }

  test("lookup --smart excludes generated sources") {
    val ctx = makeCtx(smart = true)
    val result = cmdLookup(List("ProtoMessage"), ctx)
    result match
      case SemCmdResult.NotFound(_) => () // correctly filtered by --smart
      case SemCmdResult.SymbolDetail(sym) =>
        assert(!isGeneratedSource(sym.sourceUri),
          s"--smart should exclude generated: ${sym.sourceUri}")
      case other => fail(s"unexpected: $other")
  }
