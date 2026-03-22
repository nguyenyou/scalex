// ── file command ────────────────────────────────────────────────────────────

def cmdFile(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: file <query>")
    case query :: _ =>
      val lower = query.toLowerCase
      val matches = ctx.index.allUris.filter(_.toLowerCase.contains(lower))
      val sorted = matches.sorted
      val limited = sorted.take(ctx.limit)
      SemCmdResult.FileList(s"${matches.size} files matching '$query'", limited, matches.size)
