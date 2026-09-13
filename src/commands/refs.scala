package scalex.commands

import scalex.*

def cmdRefs(args: List[String], ctx: CommandContext): CmdResult =
  requireArg(args, "Usage: scalex refs <symbol>") { symbol =>
    val targetPkgs = if (ctx.output.jsonOutput || ctx.output.countOnly || ctx.references.topN.isDefined) {
      Set.empty[String]
    } else { ctx.idx.symbolsNamed(symbol).map(_.packageName).toSet }
    def filterByCategory(
        grouped: Map[RefCategory, List[Reference]]
    ): (filtered: Map[RefCategory, List[Reference]], stderrHint: Option[String]) =
      ctx.references.categoryFilter match {
        case Some(catName) =>
          val validCats = RefCategory.values.map(_.toString.toLowerCase).toSet
          val lower = catName.toLowerCase
          if (!validCats.contains(lower))
            (
              Map.empty,
              Some(s"Unknown category: $catName. Valid: ${RefCategory.values.map(_.toString).mkString(", ")}")
            )
          else
            (grouped.filter((cat, _) => cat.toString.toLowerCase == lower), None)
        case None => (grouped, None)
      }
    if (ctx.references.topN.isDefined) {
      val n = ctx.references.topN.get
      val (rawRefs, timedOut) = ctx.idx.findReferences(symbol, strict = ctx.search.strict)
      val allRefs = filterRefs(rawRefs, ctx)
      val byFile = allRefs
        .groupBy(_.file)
        .toList
        .map((f, rs) => (file = f, count = rs.size))
        .sortBy(-_.count)
        .take(n)
      CmdResult.RefsTop(symbol, byFile, allRefs.size, timedOut)
    } else if (ctx.output.countOnly) {
      val (categorized, timedOut) = ctx.idx.categorizeReferences(symbol, strict = ctx.search.strict)
      val rawGrouped = categorized.map((cat, refs) => (cat, filterRefs(refs, ctx)))
      val counts = rawGrouped.toList
        .map((cat, refs) => (category = cat, count = refs.size))
        .filter(_.count > 0)
        .sortBy(entry => refCategoryOrder.indexOf(entry.category))
      val total = counts.map(_.count).sum
      CmdResult.RefsSummary(symbol, counts, total, timedOut)
    } else if (ctx.references.categorize) {
      val (categorized, timedOut) = ctx.idx.categorizeReferences(symbol, strict = ctx.search.strict)
      val rawGrouped = categorized.map((cat, refs) => (cat, filterRefs(refs, ctx)))
      val (grouped, stderrHint) = filterByCategory(rawGrouped)
      CmdResult.CategorizedRefs(symbol, grouped, targetPkgs, timedOut, stderrHint)
    } else {
      val (rawRefs, timedOut) = ctx.idx.findReferences(symbol, strict = ctx.search.strict)
      val results = filterRefs(rawRefs, ctx)
      CmdResult.FlatRefs(symbol, results, targetPkgs, timedOut)
    }
  }
