def cmdDef(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex def <symbol>")
    case Some(symbol) =>
      var results = filterSymbols(ctx.idx.findDefinition(symbol), ctx)
      // Rank: class/trait/object/enum > type/given > def/val/var, non-test > test, shorter path first
      results = results.sortBy { s =>
        val kindRank = s.kind match
          case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Object | SymbolKind.Enum => 0
          case SymbolKind.Type | SymbolKind.Given => 1
          case _ => 2
        val testRank = if isTestFile(s.file, ctx.workspace) then 1 else 0
        val pathLen = ctx.workspace.relativize(s.file).toString.length
        (kindRank, testRank, pathLen)
      }
      if results.isEmpty then
        CmdResult.NotFound(
          s"""Definition of "$symbol": not found""",
          mkNotFoundWithSuggestions(symbol, ctx, "def"))
      else
        CmdResult.SymbolList(
          header = s"""Definition of "$symbol":""",
          symbols = results,
          total = results.size)
