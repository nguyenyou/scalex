// ── diagnostics command ─────────────────────────────────────────────────────

def cmdDiagnostics(args: List[String], ctx: SemCommandContext): SemCmdResult =
  val diags = args match
    case Nil =>
      // All diagnostics
      ctx.index.diagnosticsByFile.values.flatten.toList
    case file :: _ =>
      // Diagnostics for a specific file (match by suffix)
      val matching = ctx.index.diagnosticsByFile.toList.flatMap { (uri, diags) =>
        if uri.contains(file) || uri.endsWith(file) then diags else Nil
      }
      matching

  val sorted = diags.sortBy(d => (d.file, d.range.startLine))
  val limited = sorted.take(ctx.limit)

  SemCmdResult.DiagnosticList(
    s"${diags.size} diagnostics",
    limited,
    diags.size,
  )
