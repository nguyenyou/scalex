// ── members command ─────────────────────────────────────────────────────────

def cmdMembers(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: members <symbol>")
    case query :: _ =>
      val symbols = ctx.index.resolveSymbol(query)
      if symbols.isEmpty then
        return SemCmdResult.NotFound(s"No symbol found matching '$query'")

      val sym = symbols.head
      val members = ctx.index.memberIndex.getOrElse(sym.fqn, Nil)
        .filter(m => m.kind != SemKind.Parameter && m.kind != SemKind.TypeParam)

      val filtered = filterByKind(members, ctx.kindFilter)
      val sorted = filtered.sortBy(m => (kindOrder(m.kind), m.displayName))
      val limited = sorted.take(ctx.limit)

      SemCmdResult.SymbolList(
        s"${filtered.size} members of '${sym.displayName}'",
        limited,
        filtered.size,
      )

private def kindOrder(k: SemKind): Int = k match
  case SemKind.Constructor => 0
  case SemKind.Type        => 1
  case SemKind.Class       => 2
  case SemKind.Trait       => 3
  case SemKind.Object      => 4
  case SemKind.Method      => 5
  case SemKind.Field       => 6
  case _                   => 7
