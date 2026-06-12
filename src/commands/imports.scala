def cmdImports(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex imports <symbol>")
    case Some(symbol) =>
      val (rawResults, timedOut) = ctx.idx.findImports(symbol, strict = ctx.strict)
      val results = filterRefs(rawResults, ctx)
      if results.isEmpty then
        CmdResult.NotFound(
          s"""No imports of "$symbol" found""",
          mkNotFoundWithSuggestions(symbol, ctx, "imports").copy(timedOut = timedOut))
      else
        val suffix = timedOutSuffix(timedOut)
        CmdResult.RefList(
          header = s"""Imports of "$symbol" — ${results.size} found:$suffix""",
          refs = results,
          timedOut = timedOut,
          useContext = false)
