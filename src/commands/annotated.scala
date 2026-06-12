def cmdAnnotated(args: List[String], ctx: CommandContext): CmdResult =
  requireArg(args, "Usage: scalex annotated <annotation>") { query =>
      val annot = query.stripPrefix("@")
      val results = filterSymbols(ctx.idx.findAnnotated(annot), ctx)
      CmdResult.SymbolList(
        header = s"Symbols annotated with @$annot — ${results.size} found:",
        symbols = results,
        total = results.size,
        emptyMessage = s"No symbols with @$annot annotation found")
  }
