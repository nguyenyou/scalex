def cmdSymbols(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex symbols <file>")
    case Some(file) =>
      val results = ctx.idx.fileSymbols(file)
      if ctx.summaryMode then
        val grouped = countByKind(results)
        if ctx.jsonOutput then
          val byKind = grouped.map((k, count) => s""""${k.toString.toLowerCase}":$count""").mkString(",")
          // Consistent with overview/index-stats symbolsByKind format
          println(s"""{"file":"${jsonEscape(file)}","symbolsByKind":{$byKind},"total":${results.size}}""")
          CmdResult.StringList(header = "", items = Nil, total = 0) // already printed
        else
          val counts = grouped.map((k, count) => s"$count ${k.toString.toLowerCase}${if count > 1 then "s" else ""}").mkString(", ")
          val summary = if counts.nonEmpty then s"$file: $counts (${results.size} total)" else s"$file: no symbols"
          CmdResult.StringList(header = "", items = Nil, total = 0, emptyMessage = summary)
      else
        CmdResult.SymbolList(
          header = s"Symbols in $file:",
          symbols = results,
          total = results.size,
          emptyMessage = s"No symbols found in $file",
          truncate = false)
