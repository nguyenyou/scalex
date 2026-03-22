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
      val filtered = filterByKind(callees, ctx.kindFilter)
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
      // We skip local definitions (local0, etc.) and nested members since they
      // are part of this method's body, not siblings.
      val owner = index.symbolByFqn.get(fqn).map(_.owner).getOrElse("")
      val siblingDefs = fileOccs
        .filter(o => o.role == OccRole.Definition && o.range.startLine > defRange.startLine)
        .sortBy(_.range.startLine)
        .filter { o =>
          // A sibling is a symbol with the same owner, or a top-level def
          !o.symbol.startsWith("local") &&
          index.symbolByFqn.get(o.symbol).exists(s => s.owner == owner)
        }

      val bodyEndLine = siblingDefs.headOption.map(_.range.startLine).getOrElse(Int.MaxValue)

      // Collect all references within the body range
      val refsInBody = fileOccs.filter { o =>
        o.role == OccRole.Reference &&
        o.range.startLine >= defRange.startLine &&
        o.range.startLine < bodyEndLine &&
        o.symbol != fqn // exclude self-references
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
