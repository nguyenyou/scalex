// ── imports command ─────────────────────────────────────────────────────────

/** Heuristic: references in the first N lines of a file are likely import-site references. */
private val IMPORT_LINE_THRESHOLD = 15

def cmdImports(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: imports <symbol>")
    case query :: _ =>
      val symbols = ctx.index.resolveSymbol(query)
      if symbols.isEmpty then
        return SemCmdResult.NotFound(s"No symbol found matching '$query'")

      val fqns = symbols.map(_.fqn).toSet
      // Find all reference occurrences in the first N lines of each file
      val importOccs = fqns.toList.flatMap(fqn =>
        ctx.index.occurrencesBySymbol.getOrElse(fqn, Nil)
      ).filter { occ =>
        occ.role == OccRole.Reference && occ.range.startLine < IMPORT_LINE_THRESHOLD
      }.sortBy(o => (o.file, o.range.startLine))

      val limited = importOccs.take(ctx.limit)
      val name = symbols.head.displayName
      SemCmdResult.OccurrenceList(
        s"${importOccs.size} import-site references of '$name'",
        limited,
        importOccs.size,
      )
