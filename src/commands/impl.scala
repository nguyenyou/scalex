def cmdImpl(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex impl <trait>")
    case Some(symbol) =>
      val results = filterSymbols(ctx.idx.findImplementations(symbol), ctx)
      if results.isEmpty then
        CmdResult.NotFound(
          s"""No implementations of "$symbol" found""",
          mkNotFoundWithSuggestions(symbol, ctx, "impl"))
      else
        CmdResult.SymbolList(
          header = s"""Implementations of "$symbol" — ${results.size} found:""",
          symbols = results,
          total = results.size)
