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
      if ctx.topN.isDefined then
        val n = ctx.topN.get
        val allRefs = filterRefs(ctx.idx.findReferences(symbol, strict = ctx.strict), ctx)
        val byFile = allRefs.groupBy(_.file).toList.map((f, rs) => (file = f, count = rs.size))
          .sortBy(-_.count).take(n)
        CmdResult.RefsTop(symbol, byFile, allRefs.size, ctx.idx.timedOut)
      else if ctx.countOnly then
        val rawGrouped = ctx.idx.categorizeReferences(symbol, strict = ctx.strict).map((cat, refs) => (cat, filterRefs(refs, ctx)))
        val counts = rawGrouped.toList.map((cat, refs) => (category = cat, count = refs.size)).filter(_.count > 0)
          .sortBy { entry =>
            val order = List(RefCategory.Definition, RefCategory.ExtendedBy, RefCategory.ImportedBy,
                             RefCategory.UsedAsType, RefCategory.Usage, RefCategory.Comment)
            order.indexOf(entry.category)
          }
        val total = counts.map(_.count).sum
        CmdResult.RefsSummary(symbol, counts, total, ctx.idx.timedOut)
      else if ctx.categorize then
        val rawGrouped = ctx.idx.categorizeReferences(symbol, strict = ctx.strict).map((cat, refs) => (cat, filterRefs(refs, ctx)))
        val (grouped, stderrHint) = filterByCategory(rawGrouped)
        CmdResult.CategorizedRefs(symbol, grouped, targetPkgs, ctx.idx.timedOut, stderrHint)
      else
        val results = filterRefs(ctx.idx.findReferences(symbol, strict = ctx.strict), ctx)
        CmdResult.FlatRefs(symbol, results, targetPkgs, ctx.idx.timedOut)
