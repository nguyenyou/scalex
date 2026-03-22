// ── type command ────────────────────────────────────────────────────────────

def cmdTypeOf(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: type <symbol>")
    case query :: _ =>
      val symbols = ctx.index.resolveSymbol(query)
      if symbols.isEmpty then
        return SemCmdResult.NotFound(s"No symbol found matching '$query'")

      val sym = symbols.head
      // The signature field already contains the pretty-printed type info
      if sym.signature.nonEmpty then
        SemCmdResult.TypeResult(sym.displayName, sym.signature)
      else
        SemCmdResult.NotFound(s"No type information available for '${sym.displayName}'")
