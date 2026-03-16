def cmdAstPattern(args: List[String], ctx: CommandContext): CmdResult =
  val results = astPatternSearch(ctx.idx, ctx.workspace, ctx.hasMethodFilter, ctx.extendsFilter, ctx.bodyContainsFilter, ctx.noTests, ctx.pathFilter, ctx.limit)
  val filters = List(
    ctx.hasMethodFilter.map(m => s"has-method=$m"),
    ctx.extendsFilter.map(e => s"extends=$e"),
    ctx.bodyContainsFilter.map(b => s"""body-contains="$b"""")
  ).flatten.mkString(", ")
  CmdResult.AstMatches(filters, results)
