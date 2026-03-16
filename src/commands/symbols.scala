def cmdSymbols(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex symbols <file>")
    case Some(file) =>
      val results = ctx.idx.fileSymbols(file)
      CmdResult.SymbolList(
        header = s"Symbols in $file:",
        symbols = results,
        total = results.size,
        emptyMessage = s"No symbols found in $file",
        truncate = false)
