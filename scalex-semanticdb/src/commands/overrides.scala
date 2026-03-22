// ── overrides command ───────────────────────────────────────────────────────

def cmdOverrides(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: overrides <method>")
    case query :: _ =>
      val symbols = ctx.index.resolveSymbol(query)
      if symbols.isEmpty then
        return SemCmdResult.NotFound(s"No symbol found matching '$query'")

      // Collect all symbols that override any of the matched symbols
      val fqns = symbols.map(_.fqn).toSet
      val overriders = fqns.toList.flatMap(fqn =>
        ctx.index.overrideIndex.getOrElse(fqn, Nil)
      ).distinctBy(_.fqn)

      val filtered = filterByKind(overriders, ctx.kindFilter)
      val sorted = filtered.sortBy(s => (s.sourceUri, s.displayName))
      val limited = sorted.take(ctx.limit)
      val name = symbols.head.displayName
      SemCmdResult.SymbolList(s"${filtered.size} overrides of '$name'", limited, filtered.size)
