// ── occurrences command ─────────────────────────────────────────────────────

def cmdOccurrences(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: occurrences <file>")
    case file :: _ =>
      val matching = ctx.index.occurrencesByFile.toList.flatMap { (uri, occs) =>
        if uri.contains(file) || uri.endsWith(file) then occs else Nil
      }

      val filtered = ctx.roleFilter match
        case Some("def") | Some("definition") =>
          matching.filter(_.role == OccRole.Definition)
        case Some("ref") | Some("reference") =>
          matching.filter(_.role == OccRole.Reference)
        case _ => matching

      val sorted = filtered.sortBy(o => (o.range.startLine, o.range.startChar))
      val limited = sorted.take(ctx.limit)

      SemCmdResult.OccurrenceList(
        s"${filtered.size} occurrences in files matching '$file'",
        limited,
        filtered.size,
      )
