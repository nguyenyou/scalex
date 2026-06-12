def cmdImpl(args: List[String], ctx: CommandContext): CmdResult =
  requireArg(args, "Usage: scalex impl <trait>") { symbol =>
      val results = filterSymbols(ctx.idx.findImplementations(symbol), ctx)
      if results.isEmpty then
        CmdResult.NotFound(
          s"""No implementations of "$symbol" found""",
          mkNotFoundWithSuggestions(symbol, ctx, "impl"))
      else
        CmdResult.SymbolList(
          header = s"""Implementations of "$symbol" — ${results.size} found:""",
          symbols = results)
  }
