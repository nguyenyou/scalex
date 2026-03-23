// ── members command ─────────────────────────────────────────────────────────

def cmdMembers(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: members <symbol>")
    case query :: _ =>
      // --kind filters members, not the parent symbol resolution
      resolveOne(query, ctx.index, kindFilter = None, ctx.inScope) match
        case None => SemCmdResult.NotFound(s"No symbol found matching '$query'")
        case Some(sym) =>
          val raw = ctx.index.memberIndex.getOrElse(sym.fqn, Nil)
            .filter(m => m.kind != SemKind.Parameter && m.kind != SemKind.TypeParam)

          // Companion-aware case class check: if sym is an object, check if the class is a case class
          val ownerForSynth =
            if sym.fqn.endsWith(".") then
              ctx.index.symbolByFqn.getOrElse(sym.fqn.stripSuffix(".") + "#", sym)
            else sym

          // Default: hide compiler-generated case class synthetics (unless --verbose)
          val withoutSynthetics =
            if ctx.verbose then raw
            else raw.filterNot(m => isSyntheticCaseClassMember(m, ownerForSynth))

          // --smart: additionally filter infrastructure noise + accessors
          val members =
            if ctx.smart then withoutSynthetics.filterNot(isInfraNoise).filterNot(isAccessor)
            else withoutSynthetics

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
