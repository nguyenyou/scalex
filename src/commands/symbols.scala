def cmdSymbols(args: List[String], ctx: CommandContext): CmdResult =
  requireArg(args, "Usage: scalex symbols <file>") { file =>
      val results = ctx.idx.fileSymbols(file)
      if ctx.summaryMode then
        val grouped = countByKind(results)
        if ctx.jsonOutput then
          val byKind = grouped.map((k, count) => s""""${k.label}":$count""").mkString(",")
          // Consistent with overview/index-stats symbolsByKind format
          println(s"""{"file":${jStr(file)},"symbolsByKind":{$byKind},"total":${results.size}}""")
          CmdResult.StringList(header = "", items = Nil, total = 0) // already printed
        else
          val counts = grouped.map((k, count) => s"$count ${k.label}${if count > 1 then "s" else ""}").mkString(", ")
          val summary = if counts.nonEmpty then s"$file: $counts (${results.size} total)" else s"$file: no symbols"
          CmdResult.StringList(header = "", items = Nil, total = 0, emptyMessage = summary)
      else
        CmdResult.SymbolList(
          header = s"Symbols in $file:",
          symbols = results,
          total = results.size,
          emptyMessage = s"No symbols found in $file",
          truncate = false)
  }
