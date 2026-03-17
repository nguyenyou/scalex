def cmdImports(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex imports <symbol>")
    case Some(symbol) =>
      val results = filterRefs(ctx.idx.findImports(symbol, strict = ctx.strict), ctx)
      if results.isEmpty then
        CmdResult.NotFound(
          s"""No imports of "$symbol" found""",
          mkNotFoundWithSuggestions(symbol, ctx, "imports"))
      else
        val suffix = if ctx.idx.timedOut then " (timed out — partial results)" else ""
        CmdResult.RefList(
          header = s"""Imports of "$symbol" — ${results.size} found:$suffix""",
          refs = results,
          timedOut = ctx.idx.timedOut,
          useContext = false)
