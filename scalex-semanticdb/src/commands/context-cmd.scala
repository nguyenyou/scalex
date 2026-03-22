// ── context command ─────────────────────────────────────────────────────────

def cmdContext(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: context <file:line>")
    case query :: _ =>
      // Parse file:line
      val (fileQuery, targetLine) = query.lastIndexOf(':') match
        case -1 => (query, 1)
        case i =>
          val lineStr = query.substring(i + 1)
          lineStr.toIntOption match
            case Some(line) => (query.substring(0, i), line)
            case None => (query, 1)

      // Find the file
      val matchingFiles = ctx.index.allUris.filter(_.contains(fileQuery))
      if matchingFiles.isEmpty then
        return SemCmdResult.NotFound(s"No file matching '$fileQuery'")

      val file = matchingFiles.head
      // 0-indexed line
      val line0 = targetLine - 1

      // Find all definition occurrences in this file
      val defs = ctx.index.occurrencesByFile.getOrElse(file, Nil)
        .filter(_.role == OccRole.Definition)
        .sortBy(_.range.startLine)

      // Find scopes that enclose this line: definitions before the target line
      // that are classes/traits/objects/methods
      val enclosing = defs
        .filter(o => o.range.startLine <= line0)
        .flatMap(o => ctx.index.symbolByFqn.get(o.symbol))
        .filter(s =>
          s.kind == SemKind.Class || s.kind == SemKind.Trait || s.kind == SemKind.Object ||
          s.kind == SemKind.Method || s.kind == SemKind.Package || s.kind == SemKind.PackageObj
        )
        // Walk the owner chain to find truly enclosing scopes
        .sortBy(_.fqn.count(_ == '/'))

      SemCmdResult.ContextResult(s"Scopes at $file:$targetLine", enclosing.toList)
