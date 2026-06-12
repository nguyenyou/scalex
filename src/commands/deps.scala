def cmdDeps(args: List[String], ctx: CommandContext): CmdResult =
  requireArg(args, "Usage: scalex deps <symbol> [--depth N]") { symbol =>
      val depth = (if ctx.maxDepth < 0 then 1 else ctx.maxDepth).max(1).min(5)
      val (importDeps, bodyDeps) = extractDeps(ctx.idx, symbol, ctx.workspace, maxDepth = depth)
      if importDeps.isEmpty && bodyDeps.isEmpty then
        CmdResult.NotFound(
          s"""No dependencies found for "$symbol"""",
          mkNotFoundWithSuggestions(symbol, ctx, "deps"))
      else
        CmdResult.Dependencies(symbol, importDeps, bodyDeps)
  }
