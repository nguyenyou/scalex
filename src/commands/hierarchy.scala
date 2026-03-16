def cmdHierarchy(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex hierarchy <symbol> [--up] [--down] [--depth N]")
    case Some(symbol) =>
      buildHierarchy(ctx.idx, symbol, ctx.goUp, ctx.goDown, ctx.maxDepth, ctx.workspace) match
        case None =>
          CmdResult.NotFound(
            s"""No definition of "$symbol" found""",
            mkNotFoundWithSuggestions(symbol, ctx, "hierarchy"))
        case Some(tree) =>
          CmdResult.HierarchyResult(symbol, tree)
