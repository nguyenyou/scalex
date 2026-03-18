def cmdBody(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex body <symbol> [--in <owner>]")
    case Some(symbol) =>
      // Find files containing the symbol
      val defs = filterSymbols(ctx.idx.findDefinition(symbol), ctx.copy(kindFilter = None))
      val ownerFiles = ctx.inOwner match
        case Some(owner) =>
          filterSymbols(ctx.idx.findDefinition(owner), ctx.copy(kindFilter = None))
            .filter(s => typeKinds.contains(s.kind)).map(_.file).distinct
        case None => Nil
      val filesToSearch = if defs.nonEmpty then {
        // When --in is specified, also include the owner's files — the symbol may
        // be indexed in a different file but also exist as a nested def in the owner
        (defs.map(_.file).distinct ++ ownerFiles).distinct
      } else {
        // If not found directly, search owner files or all type files
        if ownerFiles.nonEmpty then ownerFiles
        else
          filterSymbols(ctx.idx.symbols.filter(s => typeKinds.contains(s.kind)), ctx.copy(kindFilter = None))
            .map(_.file).distinct
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
          val ownerFiles = filterSymbols(ctx.idx.findDefinition(ownerName), ctx)
            .filter(s => typeKinds.contains(s.kind))
            .map(_.file).distinct
          val dottedBlocks = ownerFiles.flatMap { f =>
            extractBody(f, memberName, Some(ownerName)).map(b => (file = f, body = b))
          }
          if dottedBlocks.nonEmpty then
            return CmdResult.SourceBlocks(memberName, dottedBlocks, ctx.contextLines, ctx.showImports)

      if blocks.isEmpty then
        val msg = ctx.inOwner match
          case Some(owner) => s"""No body found for "$symbol" in $owner"""
          case None => s"""No body found for "$symbol""""
        CmdResult.NotFound(msg, mkNotFoundWithSuggestions(symbol, ctx, "body"))
      else
        CmdResult.SourceBlocks(symbol, blocks, ctx.contextLines, ctx.showImports)
