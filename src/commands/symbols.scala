def cmdSymbols(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex symbols <file>")
    case Some(file) =>
      val results = ctx.idx.fileSymbols(file)
      if ctx.summaryMode then
        val grouped = results.groupBy(_.kind).toList.sortBy(-_._2.size)
        val counts = grouped.map((k, syms) => s"${syms.size} ${k.toString.toLowerCase}${if syms.size > 1 then "s" else ""}").mkString(", ")
        val summary = if counts.nonEmpty then s"$file: $counts (${results.size} total)" else s"$file: no symbols"
        // Use items list so JSON mode serializes the kind:count pairs
        val items = grouped.map((k, syms) => s"${k.toString.toLowerCase}: ${syms.size}")
        CmdResult.StringList(header = summary, items = items, total = results.size, emptyMessage = summary)
      else
        CmdResult.SymbolList(
          header = s"Symbols in $file:",
          symbols = results,
          total = results.size,
          emptyMessage = s"No symbols found in $file",
          truncate = false)
