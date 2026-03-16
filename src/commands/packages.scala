def cmdPackages(args: List[String], ctx: CommandContext): CmdResult =
  CmdResult.Packages(ctx.idx.packages.toList.sorted)
