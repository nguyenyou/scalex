// ── flow command ────────────────────────────────────────────────────────────

/** Stdlib/trivial prefixes to filter out of flow trees. */
private val trivialPrefixes = Set(
  "scala/", "java/lang/", "java/util/", "scala/Predef",
  "scala/collection/", "scala/runtime/",
)

private def isTrivial(fqn: String): Boolean =
  trivialPrefixes.exists(fqn.startsWith)

def cmdFlow(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: flow <method> [--depth N]")
    case query :: _ =>
      val symbols = ctx.index.resolveSymbol(query)
      if symbols.isEmpty then
        return SemCmdResult.NotFound(s"No symbol found matching '$query'")

      val resolved = filterByKind(symbols, ctx.kindFilter)
      val sym = (if resolved.nonEmpty then resolved else symbols).head
      val lines = scala.collection.mutable.ListBuffer.empty[String]
      val visited = scala.collection.mutable.Set.empty[String]
      val maxDepth = ctx.depth

      def walk(fqn: String, indent: Int): Unit =
        if indent > maxDepth || visited.contains(fqn) then return
        visited += fqn

        val callees = findCallees(fqn, ctx.index)
          .filterNot(s => isTrivial(s.fqn))
          .filterNot(s => (ctx.noAccessors || ctx.smart) && isAccessor(s))
          .filterNot(s => ctx.smart && isInfraNoise(s))
          .filterNot(s => ctx.excludePatterns.exists(p => s.fqn.contains(p) || s.sourceUri.contains(p)))

        callees.foreach { callee =>
          val prefix = "  " * indent
          val loc = ctx.index.definitionRanges.get(callee.fqn) match
            case Some((file, range)) => s" ($file:${range.startLine + 1})"
            case None => ""
          lines += s"$prefix${callee.kind.toString.toLowerCase} ${callee.displayName}$loc"
          walk(callee.fqn, indent + 1)
        }

      val rootLoc = ctx.index.definitionRanges.get(sym.fqn) match
        case Some((file, range)) => s" ($file:${range.startLine + 1})"
        case None => ""
      lines.prepend(s"${sym.kind.toString.toLowerCase} ${sym.displayName}$rootLoc")
      walk(sym.fqn, 1)

      SemCmdResult.FlowTree(s"Call flow from '${sym.displayName}' (depth=$maxDepth)", lines.toList)
