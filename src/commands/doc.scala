def cmdDoc(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex doc <Symbol>")
    case Some(symbol) =>
      val defs = filterSymbols(ctx.idx.findDefinition(symbol), ctx)
      if defs.isEmpty then
        CmdResult.NotFound(
          s"""Definition of "$symbol": not found""",
          mkNotFoundWithSuggestions(symbol, ctx, "doc"))
      else
        val entries = defs.map { s =>
          DocEntryData(s, extractScaladoc(s.file, s.line))
        }
        CmdResult.DocEntries(symbol, entries)
