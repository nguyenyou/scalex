// ── path command ───────────────────────────────────────────────────────────

def cmdPath(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case source :: target :: _ =>
      val srcSym = resolveOne(source, ctx.index, ctx.kindFilter) match
        case None => return SemCmdResult.NotFound(s"No symbol found matching '$source'")
        case Some(s) => s

      // --kind only disambiguates source, not target (target accepts all matching FQNs)
      val tgtSymbols = ctx.index.resolveSymbol(target)
      if tgtSymbols.isEmpty then
        return SemCmdResult.NotFound(s"No symbol found matching '$target'")
      val tgtFqns = tgtSymbols.map(_.fqn).toSet

      val maxDepth = ctx.depth.getOrElse(5)
      val rootModule = if ctx.smart then Some(modulePrefix(srcSym.sourceUri)) else None

      // BFS with parent tracking
      val queue = scala.collection.mutable.Queue((fqn = srcSym.fqn, depth = 0))
      val visited = scala.collection.mutable.Set.empty[String]
      val parentMap = scala.collection.mutable.HashMap.empty[String, String] // child → parent
      var found: Option[String] = None

      while queue.nonEmpty && found.isEmpty do
        val node = queue.dequeue()
        if !visited.contains(node.fqn) && node.depth <= maxDepth then {
          visited += node.fqn

          if tgtFqns.contains(node.fqn) && node.fqn != srcSym.fqn then
            found = Some(node.fqn)
          else
            val callees = findCallees(node.fqn, ctx.index)
              .filterNot(s => isTrivial(s.fqn))
              .filterNot(s => (ctx.noAccessors || ctx.smart) && isAccessor(s))
              .filterNot(s => ctx.smart && isInfraNoise(s))
              .filterNot(s => ctx.excludePatterns.exists(p => s.fqn.contains(p) || s.sourceUri.contains(p)))

            // In --smart mode, restrict BFS to same module — but always allow the target through
            val filtered = rootModule match
              case Some(rm) => callees.filter(s => s.sourceUri.startsWith(rm) || tgtFqns.contains(s.fqn))
              case None => callees

            filtered.foreach { callee =>
              if !visited.contains(callee.fqn) && !parentMap.contains(callee.fqn) then
                parentMap(callee.fqn) = node.fqn
                queue.enqueue((fqn = callee.fqn, depth = node.depth + 1))
            }
        }

      found match
        case None =>
          SemCmdResult.NotFound(s"No call path from '${srcSym.displayName}' to '$target' within depth $maxDepth")
        case Some(targetFqn) =>
          // Reconstruct path from parent pointers
          val path = scala.collection.mutable.ListBuffer(targetFqn)
          var current = targetFqn
          while parentMap.contains(current) do
            current = parentMap(current)
            path.prepend(current)

          val lines = path.toList.zipWithIndex.map { (fqn, i) =>
            val sym = ctx.index.symbolByFqn.get(fqn)
            val name = sym.map(_.displayName).getOrElse(fqn)
            val kind = sym.map(_.kind.toString.toLowerCase).getOrElse("unknown")
            val loc = ctx.index.definitionRanges.get(fqn) match
              case Some((file, range)) => s" ($file:${range.startLine + 1})"
              case None => ""
            val prefix = if i == 0 then "" else "  " * i + "-> "
            s"$prefix$kind $name$loc"
          }
          val tgtName = ctx.index.symbolByFqn.get(targetFqn).map(_.displayName).getOrElse(target)
          SemCmdResult.FlowTree(
            s"Call path from '${srcSym.displayName}' to '$tgtName' (${path.size - 1} hops)",
            lines,
          )
    case _ =>
      SemCmdResult.UsageError("Usage: path <source> <target> [--depth N] [--smart] [--exclude ...]")
