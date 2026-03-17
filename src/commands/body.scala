def cmdBody(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex body <symbol> [--in <owner>]")
    case Some(symbol) =>
      // Find files containing the symbol
      var defs = ctx.idx.findDefinition(symbol)
      if ctx.noTests then defs = defs.filter(s => !isTestFile(s.file, ctx.workspace))
      ctx.pathFilter.foreach { p => defs = defs.filter(s => matchesPath(s.file, p, ctx.workspace)) }
      ctx.excludePath.foreach { p => defs = defs.filter(s => !matchesPath(s.file, p, ctx.workspace)) }
      // Also look in type definitions for member bodies
      val typeKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
      val filesToSearch = if defs.nonEmpty then {
        defs.map(_.file).distinct
      } else {
        // If not found directly, search all files for member bodies
        ctx.inOwner match
          case Some(owner) =>
            ctx.idx.findDefinition(owner).filter(s => typeKinds.contains(s.kind)).map(_.file).distinct
          case None =>
            ctx.idx.symbols.filter(s => typeKinds.contains(s.kind)).map(_.file).distinct
      }
      // Collect (file, body) pairs
      val blocks = filesToSearch.flatMap { f =>
        extractBody(f, symbol, ctx.inOwner).map(b => (file = f, body = b))
      }
      if blocks.isEmpty then
        CmdResult.NotFound(
          s"""No body found for "$symbol"""",
          mkNotFoundWithSuggestions(symbol, ctx, "body"))
      else
        CmdResult.SourceBlocks(symbol, blocks)
