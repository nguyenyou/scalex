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
      // --source-only / --smart: exclude generated sources
      val afterSourceFilter =
        if ctx.sourceOnly || ctx.smart then filtered.filterNot(s => isGeneratedSource(s.sourceUri))
        else filtered

      if afterSourceFilter.isEmpty then
        SemCmdResult.NotFound(s"No symbol found matching '$query'")
      else if afterSourceFilter.size == 1 then
        SemCmdResult.SymbolDetail(afterSourceFilter.head)
      else
        val limited = afterSourceFilter.take(ctx.limit)
        SemCmdResult.SymbolList(s"${afterSourceFilter.size} symbols matching '$query'", limited, afterSourceFilter.size)
