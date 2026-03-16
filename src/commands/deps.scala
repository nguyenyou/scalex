def cmdDeps(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex deps <symbol>")
    case Some(symbol) =>
      val (importDeps, bodyDeps) = extractDeps(ctx.idx, symbol, ctx.workspace)
      if importDeps.isEmpty && bodyDeps.isEmpty then
        CmdResult.NotFound(
          s"""No dependencies found for "$symbol"""",
          mkNotFoundWithSuggestions(symbol, ctx, "deps"))
      else
        CmdResult.Dependencies(symbol, importDeps, bodyDeps)
