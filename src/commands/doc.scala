def cmdDoc(args: List[String], ctx: CommandContext): CmdResult =
  requireArg(args, "Usage: scalex doc <Symbol>") { symbol =>
      val defs = filterSymbols(ctx.idx.findDefinition(symbol), ctx)
      if defs.isEmpty then
        CmdResult.NotFound(
          s"""Definition of "$symbol": not found""",
          mkNotFoundWithSuggestions(symbol, ctx, "doc"))
      else
        val entries = defs.map { s =>
          DocEntryData(s, extractDoc(s.file, s.line))
        }
        CmdResult.DocEntries(symbol, entries)
  }
