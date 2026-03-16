def cmdIndex(args: List[String], ctx: CommandContext): CmdResult =
  val byKind = ctx.idx.symbols.groupBy(_.kind).toList.sortBy(-_._2.size).map((k, v) => (kind = k, count = v.size))
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
