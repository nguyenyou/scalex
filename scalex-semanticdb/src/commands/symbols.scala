// ── symbols command ─────────────────────────────────────────────────────────

def cmdSymbols(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil =>
      // All symbols, grouped summary
      val all = ctx.index.symbolByFqn.values.toList
      val filtered = filterByKind(all, ctx.kindFilter)
      val sorted = filtered.sortBy(s => (s.sourceUri, s.displayName))
      val limited = sorted.take(ctx.limit)
      SemCmdResult.SymbolList(s"${filtered.size} symbols", limited, filtered.size)

    case file :: _ =>
      // Symbols in a specific file (match by suffix)
      val matching = ctx.index.symbolsByFile.toList.flatMap { (uri, syms) =>
        if uri.contains(file) || uri.endsWith(file) then syms else Nil
      }
      val filtered = filterByKind(matching, ctx.kindFilter)
      val sorted = filtered.sortBy(s => (kindOrder(s.kind), s.displayName))
      val limited = sorted.take(ctx.limit)
      SemCmdResult.SymbolList(
        s"${filtered.size} symbols in files matching '$file'",
        limited,
        filtered.size,
      )
