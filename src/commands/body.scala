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
      // Fallback: if no results and symbol has a dot, split into Owner.member
      if blocks.isEmpty && symbol.contains(".") && ctx.inOwner.isEmpty then
        val lastDot = symbol.lastIndexOf('.')
        if lastDot > 0 then
          val ownerName = symbol.substring(0, lastDot)
          val memberName = symbol.substring(lastDot + 1)
          val ownerFiles = ctx.idx.findDefinition(ownerName)
            .filter(s => typeKinds.contains(s.kind))
            .map(_.file).distinct
          val dottedBlocks = ownerFiles.flatMap { f =>
            extractBody(f, memberName, Some(ownerName)).map(b => (file = f, body = b))
          }
          if dottedBlocks.nonEmpty then
            return CmdResult.SourceBlocks(memberName, dottedBlocks)

      if blocks.isEmpty then
        val msg = ctx.inOwner match
          case Some(owner) => s"""No body found for "$symbol" in $owner"""
          case None => s"""No body found for "$symbol""""
        CmdResult.NotFound(msg, mkNotFoundWithSuggestions(symbol, ctx, "body"))
      else
        CmdResult.SourceBlocks(symbol, blocks)
