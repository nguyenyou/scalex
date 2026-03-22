// ── imports command ─────────────────────────────────────────────────────────

def cmdImports(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: imports <symbol>")
    case query :: _ =>
      val symbols = ctx.index.resolveSymbol(query)
      if symbols.isEmpty then
        return SemCmdResult.NotFound(s"No symbol found matching '$query'")

      val fqns = symbols.map(_.fqn).toSet

      // For each file, find the line of the first definition occurrence.
      // References before that line are in the import/package header region.
      val firstDefByFile = scala.collection.mutable.HashMap.empty[String, Int]
      ctx.index.occurrencesByFile.foreach { (file, occs) =>
        val firstDef = occs.iterator
          .filter(o => o.role == OccRole.Definition)
          .map(_.range.startLine)
          .buffered
        if firstDef.hasNext then
          firstDefByFile(file) = firstDef.head
      }

      // Find all reference occurrences of the target in the import region of each file
      val importOccs = fqns.toList.flatMap(fqn =>
        ctx.index.occurrencesBySymbol.getOrElse(fqn, Nil)
      ).filter { occ =>
        occ.role == OccRole.Reference &&
        occ.range.startLine < firstDefByFile.getOrElse(occ.file, 50)
      }
      // Deduplicate by file (one entry per importing file)
      .groupBy(_.file)
      .values
      .map(_.minBy(_.range.startLine))
      .toList
      .sortBy(o => (o.file, o.range.startLine))

      val limited = importOccs.take(ctx.limit)
      val name = symbols.head.displayName
      SemCmdResult.OccurrenceList(
        s"${importOccs.size} import-site references of '$name'",
        limited,
        importOccs.size,
      )
