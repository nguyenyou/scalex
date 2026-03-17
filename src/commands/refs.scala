def cmdRefs(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex refs <symbol>")
    case Some(symbol) =>
      val targetPkgs = ctx.idx.symbolsByName.getOrElse(symbol.toLowerCase, Nil).map(_.packageName).toSet
      def filterByCategory(grouped: Map[RefCategory, List[Reference]]): (filtered: Map[RefCategory, List[Reference]], stderrHint: Option[String]) =
        ctx.categoryFilter match
          case Some(catName) =>
            val validCats = RefCategory.values.map(_.toString.toLowerCase).toSet
            val lower = catName.toLowerCase
            if !validCats.contains(lower) then
              (Map.empty, Some(s"Unknown category: $catName. Valid: ${RefCategory.values.map(_.toString).mkString(", ")}"))
            else
              (grouped.filter((cat, _) => cat.toString.toLowerCase == lower), None)
          case None => (grouped, None)
      if ctx.categorize then
        val rawGrouped = ctx.idx.categorizeReferences(symbol, strict = ctx.strict).map((cat, refs) => (cat, filterRefs(refs, ctx)))
        val (grouped, stderrHint) = filterByCategory(rawGrouped)
        CmdResult.CategorizedRefs(symbol, grouped, targetPkgs, ctx.idx.timedOut, stderrHint)
      else
        val results = filterRefs(ctx.idx.findReferences(symbol, strict = ctx.strict), ctx)
        CmdResult.FlatRefs(symbol, results, targetPkgs, ctx.idx.timedOut)
