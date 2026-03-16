def cmdExplain(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex explain <symbol>")
    case Some(symbol) =>
      var defs = ctx.idx.findDefinition(symbol)
      if ctx.noTests then defs = defs.filter(s => !isTestFile(s.file, ctx.workspace))
      ctx.pathFilter.foreach { p => defs = defs.filter(s => matchesPath(s.file, p, ctx.workspace)) }
      // Rank: class/trait/object/enum first (same as def command)
      defs = defs.sortBy { s =>
        val kindRank = s.kind match
          case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Object | SymbolKind.Enum => 0
          case SymbolKind.Type | SymbolKind.Given => 1
          case _ => 2
        val testRank = if isTestFile(s.file, ctx.workspace) then 1 else 0
        val pathLen = ctx.workspace.relativize(s.file).toString.length
        (kindRank, testRank, pathLen)
      }
      if defs.isEmpty then
        CmdResult.NotFound(
          s"""No definition of "$symbol" found""",
          mkNotFoundWithSuggestions(symbol, ctx, "explain"))
      else
        val sym = defs.head
        // Scaladoc
        val doc = extractScaladoc(sym.file, sym.line)
        // Members (for types)
        val typeKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
        val members = if typeKinds.contains(sym.kind) then extractMembers(sym.file, symbol).take(10) else Nil
        // Implementations
        val impls = ctx.idx.findImplementations(symbol).take(ctx.implLimit)
        // Import count
        val importCount = ctx.idx.findImports(symbol, timeoutMs = 3000).size
        CmdResult.Explanation(sym, doc, members, impls, importCount)
