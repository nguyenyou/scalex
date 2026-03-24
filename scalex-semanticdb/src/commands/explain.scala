// ── explain command ────────────────────────────────────────────────────────

def cmdExplain(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: explain <symbol> [--kind K]")
    case query :: _ =>
      resolveOne(query, ctx.index, ctx.kindFilter, ctx.inScope) match
        case None => SemCmdResult.NotFound(s"No symbol found matching '$query'")
        case Some(sym) =>
          val definedAt = ctx.index.definitionRanges.get(sym.fqn)
            .map((f, r) => (file = f, line = r.startLine + 1))

          val isCallable = sym.kind == SemKind.Method || sym.kind == SemKind.Constructor || sym.kind == SemKind.Field

          // Callers (for callable symbols)
          val (callerList: List[SemSymbol], totalCallers: Int) =
            if isCallable then
              val all = findCallersTraitAware(sym.fqn, ctx.index)
              val f1 = if ctx.smart then all.filterNot(isInfraNoise).filterNot(isMonadicCombinator) else all
              val f2 = if ctx.excludeTest then f1.filterNot(s => isTestSource(s.sourceUri)) else f1
              val filtered = filterByExcludePkg(f2, ctx.excludePkgPatterns)
              (callerList = filtered.take(5), totalCallers = filtered.size)
            else (callerList = Nil, totalCallers = 0)

          // Callees (for callable symbols)
          val (calleeList: List[SemSymbol], totalCallees: Int) =
            if isCallable then
              val all = findCallees(sym.fqn, ctx.index)
                .filterNot(s => isTrivial(s.fqn))
              val f1 = if ctx.smart then all.filterNot(isAccessor).filterNot(isInfraNoise).filterNot(isMonadicCombinator) else all
              val f2 = if ctx.excludeTest then f1.filterNot(s => isTestSource(s.sourceUri)) else f1
              val filtered = filterByExcludePkg(f2, ctx.excludePkgPatterns)
              (calleeList = filtered.take(5), totalCallees = filtered.size)
            else (calleeList = Nil, totalCallees = 0)

          // Members (for type symbols)
          val isType = sym.kind == SemKind.Class || sym.kind == SemKind.Trait ||
                       sym.kind == SemKind.Object || sym.kind == SemKind.Interface
          val (memberList: List[SemSymbol], totalMembers: Int) =
            if isType then
              val allMembers = ctx.index.memberIndex.getOrElse(sym.fqn, Nil)
                .filter(m => m.kind != SemKind.Parameter && m.kind != SemKind.TypeParam)
              // Companion-aware case class check: if sym is an object, check if the class is a case class
              val ownerForSynth =
                if sym.fqn.endsWith(".") then
                  ctx.index.symbolByFqn.getOrElse(sym.fqn.stripSuffix(".") + "#", sym)
                else sym
              val withoutSynthetics =
                if ctx.verbose then allMembers
                else allMembers.filterNot(m => isSyntheticCaseClassMember(m, ownerForSynth) || isDefaultParamAccessor(m))
              val members =
                if ctx.smart then withoutSynthetics.filterNot(isInfraNoise).filterNot(isAccessor)
                else withoutSynthetics
              (memberList = members.take(5), totalMembers = members.size)
            else (memberList = Nil, totalMembers = 0)

          // Subtypes (for traits/abstract classes)
          val isTraitOrAbstract = sym.kind == SemKind.Trait || sym.kind == SemKind.Interface ||
            (sym.kind == SemKind.Class && sym.isAbstract)
          val (subtypeList: List[SemSymbol], totalSubtypes: Int) =
            if isTraitOrAbstract then
              val childFqns = ctx.index.subtypeIndex.getOrElse(sym.fqn, Nil)
              val resolved = childFqns.flatMap(ctx.index.symbolByFqn.get)
                .filter(s => s.kind != SemKind.Local && s.kind != SemKind.Parameter)
              val f1 = if ctx.smart then resolved.filterNot(isInfraNoise) else resolved
              val f2 = if ctx.excludeTest then f1.filterNot(s => isTestSource(s.sourceUri)) else f1
              val filtered = filterByExcludePkg(f2, ctx.excludePkgPatterns)
              (subtypeList = filtered.take(3), totalSubtypes = filtered.size)
            else (subtypeList = Nil, totalSubtypes = 0)

          SemCmdResult.ExplainResult(
            sym = sym,
            definedAt = definedAt,
            callers = callerList, totalCallers = totalCallers,
            callees = calleeList, totalCallees = totalCallees,
            parents = sym.parents,
            members = memberList, totalMembers = totalMembers,
            subtypes = subtypeList, totalSubtypes = totalSubtypes,
          )
