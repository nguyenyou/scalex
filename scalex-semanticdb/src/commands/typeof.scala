// ── type command ────────────────────────────────────────────────────────────

def cmdTypeOf(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: type <symbol>")
    case query :: _ =>
      val sym = resolveOne(query, ctx.index, ctx.kindFilter) match
        case None => return SemCmdResult.NotFound(s"No symbol found matching '$query'")
        case Some(s) => s
      // The signature field already contains the pretty-printed type info
      if sym.signature.nonEmpty then
        SemCmdResult.TypeResult(sym.displayName, sym.signature)
      else
        SemCmdResult.NotFound(s"No type information available for '${sym.displayName}'")
