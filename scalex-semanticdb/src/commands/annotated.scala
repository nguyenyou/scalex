// ── annotated command ───────────────────────────────────────────────────────

def cmdAnnotated(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: annotated <annotation>")
    case query :: _ =>
      val lower = query.toLowerCase
      val matches = ctx.index.symbolByFqn.values.toList.filter { s =>
        s.annotations.exists(_.toLowerCase.contains(lower))
      }
      val filtered = filterByKind(matches, ctx.kindFilter)
      val sorted = filtered.sortBy(s => (kindOrder(s.kind), s.displayName))
      val limited = sorted.take(ctx.limit)
      SemCmdResult.SymbolList(s"${filtered.size} symbols with annotation matching '$query'", limited, filtered.size)
