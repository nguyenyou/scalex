// ── deps command ────────────────────────────────────────────────────────────

def cmdDeps(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: deps <symbol>")
    case query :: _ =>
      val symbols = ctx.index.resolveSymbol(query)
      if symbols.isEmpty then
        return SemCmdResult.NotFound(s"No symbol found matching '$query'")

      val sym = symbols.head
      val deps = scala.collection.mutable.LinkedHashSet.empty[String]

      // 1. Parent types (from ClassSignature)
      sym.parents.foreach(deps += _)

      // 2. Callees (symbols referenced in body)
      val callees = findCallees(sym.fqn, ctx.index)
      callees.foreach(c => deps += c.fqn)

      // 3. Members' type references (owner's members that reference other types)
      val members = ctx.index.memberIndex.getOrElse(sym.fqn, Nil)
      members.foreach { m =>
        m.parents.foreach(deps += _)
      }

      // Resolve to SemSymbols, filter stdlib
      val depSymbols = deps.toList
        .filterNot(isTrivial)
        .filterNot(_ == sym.fqn)
        .flatMap(ctx.index.symbolByFqn.get)

      val filtered = filterByKind(depSymbols, ctx.kindFilter)
      val limited = filtered.take(ctx.limit)
      SemCmdResult.SymbolList(
        s"${filtered.size} dependencies of '${sym.displayName}'",
        limited,
        filtered.size,
      )
