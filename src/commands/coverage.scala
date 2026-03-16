def cmdCoverage(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex coverage <symbol>")
    case Some(symbol) =>
      val refs = ctx.idx.findReferences(symbol)
      val testRefs = refs.filter(r => isTestFile(r.file, ctx.workspace))
      val testFiles = testRefs.map(r => ctx.workspace.relativize(r.file).toString).distinct
      CmdResult.CoverageReport(symbol, refs.size, testRefs, testFiles)
