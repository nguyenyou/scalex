// ── lookup command ──────────────────────────────────────────────────────────

def cmdLookup(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: lookup <symbol>")
    case query :: _ =>
      val matches = ctx.index.resolveSymbol(query)
      val filtered = filterByKind(matches, ctx.kindFilter)
      if filtered.isEmpty then
        SemCmdResult.NotFound(s"No symbol found matching '$query'")
      else if filtered.size == 1 then
        SemCmdResult.SymbolDetail(filtered.head)
      else
        val limited = filtered.take(ctx.limit)
        SemCmdResult.SymbolList(s"${filtered.size} symbols matching '$query'", limited, filtered.size)
