// ── callers command ─────────────────────────────────────────────────────────

def cmdCallers(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: callers <symbol>")
    case query :: _ =>
      val symbols = ctx.index.resolveSymbol(query)
      if symbols.isEmpty then
        return SemCmdResult.NotFound(s"No symbol found matching '$query'")

      val fqns = symbols.map(_.fqn).toSet
      // Find all REFERENCE occurrences of the target symbol
      val refs = fqns.toList.flatMap(fqn =>
        ctx.index.occurrencesBySymbol.getOrElse(fqn, Nil)
      ).filter(_.role == OccRole.Reference)

      // For each reference, find the enclosing method by checking which
      // symbol's definition range contains this occurrence
      val callerFqns = scala.collection.mutable.LinkedHashSet.empty[String]
      refs.foreach { ref =>
        findEnclosingSymbol(ref.file, ref.range.startLine, ctx.index) match
          case Some(enclosing) if !fqns.contains(enclosing) =>
            callerFqns += enclosing
          case _ => ()
      }

      val callerSymbols = callerFqns.toList.flatMap(ctx.index.symbolByFqn.get)
      val filtered = filterByKind(callerSymbols, ctx.kindFilter)
      val limited = filtered.take(ctx.limit)
      val name = symbols.head.displayName

      SemCmdResult.SymbolList(
        s"${filtered.size} callers of '$name'",
        limited,
        filtered.size,
      )

/** Find the method/field whose body contains the given line, using next-sibling heuristic. */
private def findEnclosingSymbol(file: String, line: Int, index: SemIndex): Option[String] =
  // Get all definition occurrences in this file
  val fileOccs = index.occurrencesByFile.getOrElse(file, Nil)
    .filter(_.role == OccRole.Definition)

  // Find symbols that are methods/constructors whose definition is before this line
  // Use the simple heuristic: the closest definition before this line is the enclosing one
  val candidates = fileOccs
    .filter(o => o.range.startLine <= line)
    .flatMap { o =>
      index.symbolByFqn.get(o.symbol).map(s => (sym = s, occ = o))
    }
    .filter { (sym, _) =>
      sym.kind == SemKind.Method || sym.kind == SemKind.Constructor ||
      sym.kind == SemKind.Field
    }
    .sortBy(-_.occ.range.startLine) // closest definition first

  candidates.headOption.flatMap { (sym, occ) =>
    // Find the next sibling definition (same owner) to approximate body end
    val owner = sym.owner
    val siblingDefs = fileOccs
      .filter(o => o.role == OccRole.Definition && o.range.startLine > occ.range.startLine)
      .sortBy(_.range.startLine)
      .filter { o =>
        !o.symbol.startsWith("local") &&
        index.symbolByFqn.get(o.symbol).exists(s => s.owner == owner)
      }
    val bodyEndLine = siblingDefs.headOption.map(_.range.startLine).getOrElse(Int.MaxValue)
    if line < bodyEndLine then Some(sym.fqn) else None
  }
