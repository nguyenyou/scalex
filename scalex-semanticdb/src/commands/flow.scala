// ── flow command ────────────────────────────────────────────────────────────

def cmdFlow(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: flow <method> [--depth N]")
    case query :: _ =>
      val sym = resolveOne(query, ctx.index, ctx.kindFilter, ctx.inScope) match
        case None => return SemCmdResult.NotFound(s"No symbol found matching '$query'")
        case Some(s) => s
      val lines = scala.collection.mutable.ListBuffer.empty[String]
      val visited = scala.collection.mutable.Set.empty[String]
      val maxDepth = ctx.depth.getOrElse(3)

      // In --smart mode, only recurse into callees from the same module as the root method.
      // Cross-module callees appear as leaves (shown but not expanded).
      val rootModule = if ctx.smart then Some(modulePrefix(sym.sourceUri)) else None

      def walk(fqn: String, indent: Int): Unit =
        if indent > maxDepth || visited.contains(fqn) then return
        visited += fqn

        val callees = findCallees(fqn, ctx.index)
          .filterNot(s => isTrivial(s.fqn))
          .filterNot(s => (ctx.noAccessors || ctx.smart) && isAccessor(s))
          .filterNot(s => ctx.smart && isInfraNoise(s))
          .filterNot(s => ctx.smart && isMonadicCombinator(s))
          .filterNot(s => ctx.excludeTest && isTestSource(s.sourceUri))
          .filterNot(s => ctx.excludePatterns.exists(p => s.fqn.contains(p) || s.sourceUri.contains(p)))
          .filterNot(s => ctx.excludePkgPatterns.exists(p => s.fqn.startsWith(p)))

        callees.foreach { callee =>
          if !visited.contains(callee.fqn) then
            val prefix = "  " * indent
            val loc = ctx.index.definitionRanges.get(callee.fqn) match
              case Some((file, range)) => s" ($file:${range.startLine + 1})"
              case None => ""
            lines += s"$prefix${callee.kind.toString.toLowerCase} ${callee.displayName}$loc"
            // In --smart mode, only recurse into same-module callees
            val sameModule = rootModule.forall(rm => callee.sourceUri.startsWith(rm))
            if sameModule then walk(callee.fqn, indent + 1)
            else visited += callee.fqn // mark cross-module leaves as visited to prevent duplicates
        }

      val rootLoc = ctx.index.definitionRanges.get(sym.fqn) match
        case Some((file, range)) => s" ($file:${range.startLine + 1})"
        case None => ""
      lines.prepend(s"${sym.kind.toString.toLowerCase} ${sym.displayName}$rootLoc")
      walk(sym.fqn, 1)

      SemCmdResult.FlowTree(s"Call flow from '${sym.displayName}' (depth=$maxDepth)", lines.toList)
