// ── related command ─────────────────────────────────────────────────────────

def cmdRelated(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: related <symbol>")
    case query :: _ =>
      val symbols = ctx.index.resolveSymbol(query)
      if symbols.isEmpty then
        return SemCmdResult.NotFound(s"No symbol found matching '$query'")

      val sym = symbols.head
      val targetFqn = sym.fqn

      // Collect all files where this symbol appears
      val filesWithTarget = ctx.index.occurrencesBySymbol
        .getOrElse(targetFqn, Nil)
        .map(_.file)
        .distinct

      // Count co-occurrences: how many files/locations each other symbol shares
      val cooccurrences = scala.collection.mutable.HashMap.empty[String, Int]
      filesWithTarget.foreach { file =>
        val occsInFile = ctx.index.occurrencesByFile.getOrElse(file, Nil)
        occsInFile.foreach { occ =>
          if occ.symbol != targetFqn && occ.symbol.nonEmpty then
            cooccurrences(occ.symbol) = cooccurrences.getOrElse(occ.symbol, 0) + 1
        }
      }

      // Resolve to SemSymbols, filter out trivial stdlib symbols, rank by count
      val entries = cooccurrences.toList
        .filterNot((fqn, _) => isTrivial(fqn))
        .flatMap { (fqn, count) =>
          ctx.index.symbolByFqn.get(fqn).map(s => (sym = s, count = count))
        }
        .filter { (sym, _) =>
          sym.kind != SemKind.Parameter && sym.kind != SemKind.TypeParam &&
          sym.kind != SemKind.Local && sym.kind != SemKind.Constructor
        }
        .sortBy(-_.count)

      val limited = entries.take(ctx.limit)
      SemCmdResult.RelatedList(
        s"Symbols related to '${sym.displayName}' (by co-occurrence in ${filesWithTarget.size} files)",
        limited,
        entries.size,
      )
