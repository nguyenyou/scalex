def cmdSymbols(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex symbols <file>")
    case Some(file) =>
      val results = ctx.idx.fileSymbols(file)
      if ctx.summaryMode then
        val counts = results.groupBy(_.kind).toList.sortBy(-_._2.size)
          .map((k, syms) => s"${syms.size} ${k.toString.toLowerCase}${if syms.size > 1 then "s" else ""}")
          .mkString(", ")
        val summary = if counts.nonEmpty then s"$file: $counts (${results.size} total)" else s"$file: no symbols"
        CmdResult.StringList(header = "", items = Nil, total = results.size, emptyMessage = summary)
      else
        CmdResult.SymbolList(
          header = s"Symbols in $file:",
          symbols = results,
          total = results.size,
          emptyMessage = s"No symbols found in $file",
          truncate = false)
