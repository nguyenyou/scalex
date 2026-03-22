// ── supertypes command ──────────────────────────────────────────────────────

def cmdSupertypes(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: supertypes <symbol>")
    case query :: _ =>
      val symbols = ctx.index.resolveSymbol(query)
      if symbols.isEmpty then
        return SemCmdResult.NotFound(s"No symbol found matching '$query'")

      val sym = symbols.head
      val lines = scala.collection.mutable.ListBuffer.empty[String]
      val visited = scala.collection.mutable.Set.empty[String]

      def walk(fqn: String, indent: Int): Unit =
        if visited.contains(fqn) then return
        visited += fqn
        ctx.index.symbolByFqn.get(fqn) match
          case Some(s) =>
            val prefix = "  " * indent
            val kindStr = s.kind.toString.toLowerCase
            lines += s"$prefix$kindStr ${s.displayName} (${s.fqn})"
            s.parents.foreach(p => walk(p, indent + 1))
          case None =>
            val prefix = "  " * indent
            lines += s"$prefix[external] $fqn"

      lines += s"${sym.kind.toString.toLowerCase} ${sym.displayName}"
      sym.parents.foreach(p => walk(p, 1))

      SemCmdResult.Tree(s"Supertypes of '${sym.displayName}'", lines.toList)
