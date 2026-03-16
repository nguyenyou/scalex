def cmdAnnotated(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex annotated <annotation>")
    case Some(query) =>
      val annot = query.stripPrefix("@")
      val results = filterSymbols(ctx.idx.findAnnotated(annot), ctx)
      CmdResult.SymbolList(
        header = s"Symbols annotated with @$annot — ${results.size} found:",
        symbols = results,
        total = results.size,
        emptyMessage = s"No symbols with @$annot annotation found")
