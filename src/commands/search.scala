def cmdSearch(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex search <query>")
    case Some(query) =>
      var results = ctx.idx.search(query)
      ctx.searchMode.foreach {
        case "exact" =>
          val lower = query.toLowerCase
          results = results.filter(_.name.toLowerCase == lower)
        case "prefix" =>
          val lower = query.toLowerCase
          results = results.filter(_.name.toLowerCase.startsWith(lower))
        case _ => ()
      }
      if ctx.definitionsOnly then
        val defKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
        results = results.filter(s => defKinds.contains(s.kind))
      results = filterSymbols(results, ctx)
      if results.isEmpty then
        CmdResult.NotFound(
          s"""Found 0 symbols matching "$query"""",
          NotFoundHint(query, ctx.idx.fileCount, ctx.idx.parseFailures, "search", ctx.batchMode, query.contains("/") || query.startsWith(".")))
      else
        CmdResult.SymbolList(
          header = s"""Found ${results.size} symbols matching "$query":""",
          symbols = results,
          total = results.size)
