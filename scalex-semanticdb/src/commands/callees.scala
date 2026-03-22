// ── callees command ─────────────────────────────────────────────────────────

def cmdCallees(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: callees <symbol>")
    case query :: _ =>
      val symbols = ctx.index.resolveSymbol(query)
      if symbols.isEmpty then
        return SemCmdResult.NotFound(s"No symbol found matching '$query'")

      val sym = symbols.head
      val callees = findCallees(sym.fqn, ctx.index)
      val withoutAccessors = if ctx.noAccessors then callees.filterNot(isAccessor) else callees
      val filtered = filterByExclude(filterByKind(withoutAccessors, ctx.kindFilter), ctx.excludePatterns)
      val limited = filtered.take(ctx.limit)

      SemCmdResult.SymbolList(
        s"${filtered.size} callees of '${sym.displayName}'",
        limited,
        filtered.size,
      )

/** Find all symbols referenced within a method's body range. */
def findCallees(fqn: String, index: SemIndex): List[SemSymbol] =
  // Get the definition location of this symbol
  index.definitionRanges.get(fqn) match
    case None => Nil
    case Some((file, defRange)) =>
      // Get all occurrences in this file that are references
      val fileOccs = index.occurrencesByFile.getOrElse(file, Nil)

      // Find the next sibling definition (same owner) to approximate body end.
      // Local definitions (local0, etc.) are skipped — they're inside the body.
      val owner = index.symbolByFqn.get(fqn).map(_.owner).getOrElse("")
      val siblingDefs = fileOccs
        .filter(o => o.role == OccRole.Definition && o.range.startLine > defRange.startLine)
        .sortBy(_.range.startLine)
        .filter { o =>
          !o.symbol.startsWith("local") &&
          index.symbolByFqn.get(o.symbol).exists(s => s.owner == owner)
        }

      val bodyEndLine = siblingDefs.headOption.map(_.range.startLine).getOrElse(Int.MaxValue)

      // Collect all references within the body range.
      // On the def line itself, only include refs after the definition's end column
      // (handles single-line methods like `def sound: String = "Woof"`).
      val refsInBody = fileOccs.filter { o =>
        o.role == OccRole.Reference &&
        o.range.startLine < bodyEndLine &&
        o.symbol != fqn && // exclude self-references
        (o.range.startLine > defRange.startLine ||
         (o.range.startLine == defRange.startLine && o.range.startChar > defRange.endChar))
      }

      // Deduplicate and resolve to SemSymbols
      val seen = scala.collection.mutable.Set.empty[String]
      refsInBody.flatMap { o =>
        if seen.contains(o.symbol) then None
        else
          seen += o.symbol
          index.symbolByFqn.get(o.symbol)
      }.filter(s =>
        s.kind == SemKind.Method || s.kind == SemKind.Constructor || s.kind == SemKind.Field
      )
