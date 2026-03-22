// ── refs command ────────────────────────────────────────────────────────────

def cmdRefs(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: refs <symbol>")
    case query :: _ =>
      val symbols = ctx.index.resolveSymbol(query)
      if symbols.isEmpty then
        return SemCmdResult.NotFound(s"No symbol found matching '$query'")

      val fqns = symbols.map(_.fqn).toSet
      val allOccs = fqns.toList.flatMap(fqn => ctx.index.occurrencesBySymbol.getOrElse(fqn, Nil))

      val filtered = ctx.roleFilter match
        case Some("def") | Some("definition") =>
          allOccs.filter(_.role == OccRole.Definition)
        case Some("ref") | Some("reference") =>
          allOccs.filter(_.role == OccRole.Reference)
        case _ => allOccs

      val sorted = filtered.sortBy(o => (o.file, o.range.startLine, o.range.startChar))
      val limited = sorted.take(ctx.limit)
      val name = symbols.head.displayName
      SemCmdResult.OccurrenceList(
        s"${filtered.size} occurrences of '$name'",
        limited,
        filtered.size,
      )
