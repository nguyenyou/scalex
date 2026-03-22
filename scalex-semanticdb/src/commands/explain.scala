// ── explain command ─────────────────────────────────────────────────────────

def cmdExplain(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: explain <symbol>")
    case query :: _ =>
      val symbols = ctx.index.resolveSymbol(query)
      if symbols.isEmpty then
        return SemCmdResult.NotFound(s"No symbol found matching '$query'")

      val sym = symbols.head

      // Members (excluding params/type params)
      val members = ctx.index.memberIndex.getOrElse(sym.fqn, Nil)
        .filter(m => m.kind != SemKind.Parameter && m.kind != SemKind.TypeParam)
        .sortBy(m => (kindOrder(m.kind), m.displayName))

      // Subtype count
      val subtypeCount = ctx.index.subtypeIndex.getOrElse(sym.fqn, Nil).size

      // Reference count
      val refCount = ctx.index.occurrencesBySymbol.getOrElse(sym.fqn, Nil)
        .count(_.role == OccRole.Reference)

      // Supertypes (parent FQNs resolved to display names where possible)
      val supertypes = sym.parents.map { parentFqn =>
        ctx.index.symbolByFqn.get(parentFqn)
          .map(p => s"${p.kind.toString.toLowerCase} ${p.displayName}")
          .getOrElse(parentFqn)
      }

      SemCmdResult.ExplainResult(sym, members, subtypeCount, refCount, supertypes)
