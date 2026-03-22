// ── package command ─────────────────────────────────────────────────────────

def cmdPackageSymbols(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: package <pkg>")
    case query :: _ =>
      val lower = query.toLowerCase
      // Find matching package FQNs (e.g. "example" matches "example/")
      val matchingPkgs = ctx.index.symbolsByPackage.keys
        .filter(pkg => pkg.toLowerCase.contains(lower))
        .toList

      val symbols = matchingPkgs
        .flatMap(pkg => ctx.index.symbolsByPackage.getOrElse(pkg, Nil))
        .filter(s => s.kind != SemKind.Parameter && s.kind != SemKind.TypeParam && s.kind != SemKind.Local)

      val filtered = filterByKind(symbols, ctx.kindFilter)
      val sorted = filtered.sortBy(s => (kindOrder(s.kind), s.displayName))
      val limited = sorted.take(ctx.limit)
      SemCmdResult.SymbolList(s"${filtered.size} symbols in packages matching '$query'", limited, filtered.size)
