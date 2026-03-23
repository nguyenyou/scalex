// ── lookup command ──────────────────────────────────────────────────────────

def cmdLookup(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: lookup <symbol>")
    case query :: _ =>
      val matches = ctx.index.resolveSymbol(query)
      val byKind = filterByKind(matches, ctx.kindFilter)
      // Apply --in scope filter
      val filtered = ctx.inScope match
        case Some(scope) =>
          val scopeLower = scope.toLowerCase
          val scopeFqnLower = scope.replace(".", "/").toLowerCase
          val scoped = byKind.filter { s =>
            s.owner.toLowerCase.contains(scopeLower) ||
            s.fqn.toLowerCase.contains(scopeFqnLower) ||
            s.sourceUri.toLowerCase.contains(scopeLower)
          }
          if scoped.nonEmpty then scoped
          else
            System.err.println(s"Warning: --in '$scope' matched no candidates, falling back to unscoped resolution")
            byKind
        case None => byKind
      if filtered.isEmpty then
        SemCmdResult.NotFound(s"No symbol found matching '$query'")
      else if filtered.size == 1 then
        SemCmdResult.SymbolDetail(filtered.head)
      else
        val limited = filtered.take(ctx.limit)
        SemCmdResult.SymbolList(s"${filtered.size} symbols matching '$query'", limited, filtered.size)
