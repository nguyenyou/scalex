// ── subtypes command ────────────────────────────────────────────────────────

def cmdSubtypes(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: subtypes <symbol>")
    case query :: _ =>
      val sym = resolveOne(query, ctx.index, ctx.kindFilter, ctx.inScope) match
        case None => return SemCmdResult.NotFound(s"No symbol found matching '$query'")
        case Some(s) => s
      val lines = scala.collection.mutable.ListBuffer.empty[String]
      val visited = scala.collection.mutable.Set.empty[String]
      var count = 0

      def walk(fqn: String, indent: Int, maxDepth: Int): Unit =
        if visited.contains(fqn) || indent > maxDepth || count >= ctx.limit then return
        visited += fqn
        val children = ctx.index.subtypeIndex.getOrElse(fqn, Nil)
        children.foreach { childFqn =>
          if count < ctx.limit then
            ctx.index.symbolByFqn.get(childFqn) match
              case Some(s) if s.kind == SemKind.Local || s.kind == SemKind.Parameter =>
                () // skip locals and parameters — they are not real subtypes
              case Some(s) =>
                val prefix = "  " * indent
                lines += s"$prefix${s.kind.toString.toLowerCase} ${s.displayName} (${s.sourceUri})"
                count += 1
                walk(childFqn, indent + 1, maxDepth)
              case None =>
                val prefix = "  " * indent
                lines += s"$prefix[unresolved] $childFqn"
                count += 1
        }

      lines += s"${sym.kind.toString.toLowerCase} ${sym.displayName}"
      walk(sym.fqn, 1, ctx.depth.getOrElse(3))

      SemCmdResult.Tree(s"Subtypes of '${sym.displayName}'", lines.toList)
