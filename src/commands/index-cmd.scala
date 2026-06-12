def cmdIndex(args: List[String], ctx: CommandContext): CmdResult =
  val byKind = countByKind(ctx.idx.symbols)
  CmdResult.IndexStats(
    fileCount = ctx.idx.fileCount,
    symbolCount = ctx.idx.symbols.size,
    packageCount = ctx.idx.packages.size,
    symbolsByKind = byKind,
    indexTimeMs = ctx.idx.indexTimeMs,
    cachedLoad = ctx.idx.cachedLoad,
    parsedCount = ctx.idx.parsedCount,
    skippedCount = ctx.idx.skippedCount,
    parseFailures = ctx.idx.parseFailures,
    parseFailedFiles = ctx.idx.parseFailedFiles
  )
