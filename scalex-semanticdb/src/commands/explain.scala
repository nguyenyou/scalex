// ── explain command ────────────────────────────────────────────────────────

def cmdExplain(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: explain <symbol> [--kind K]")
    case query :: _ =>
      val sym = resolveOne(query, ctx.index, ctx.kindFilter) match
        case None => return SemCmdResult.NotFound(s"No symbol found matching '$query'")
        case Some(s) => s

      val definedAt = ctx.index.definitionRanges.get(sym.fqn)
        .map((f, r) => (file = f, line = r.startLine + 1))

      val isCallable = sym.kind == SemKind.Method || sym.kind == SemKind.Constructor || sym.kind == SemKind.Field

      // Callers (for callable symbols)
      val (callerList: List[SemSymbol], totalCallers: Int) =
        if isCallable then
          val all = findCallers(sym.fqn, ctx.index)
          val filtered = if ctx.smart then all.filterNot(isInfraNoise) else all
          (callerList = filtered.take(5), totalCallers = filtered.size)
        else (callerList = Nil, totalCallers = 0)

      // Callees (for callable symbols)
      val (calleeList: List[SemSymbol], totalCallees: Int) =
        if isCallable then
          val all = findCallees(sym.fqn, ctx.index)
            .filterNot(s => isTrivial(s.fqn))
          val filtered = if ctx.smart then all.filterNot(isAccessor).filterNot(isInfraNoise) else all
          (calleeList = filtered.take(5), totalCallees = filtered.size)
        else (calleeList = Nil, totalCallees = 0)

      // Members (for type symbols)
      val isType = sym.kind == SemKind.Class || sym.kind == SemKind.Trait ||
                   sym.kind == SemKind.Object || sym.kind == SemKind.Interface
      val (memberList: List[SemSymbol], totalMembers: Int) =
        if isType then
          val all = ctx.index.memberIndex.getOrElse(sym.fqn, Nil)
            .filter(m => m.kind != SemKind.Parameter && m.kind != SemKind.TypeParam)
          (memberList = all.take(5), totalMembers = all.size)
        else (memberList = Nil, totalMembers = 0)

      SemCmdResult.ExplainResult(
        sym = sym,
        definedAt = definedAt,
        callers = callerList, totalCallers = totalCallers,
        callees = calleeList, totalCallees = totalCallees,
        parents = sym.parents,
        members = memberList, totalMembers = totalMembers,
      )
