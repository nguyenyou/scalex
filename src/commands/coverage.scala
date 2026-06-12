def cmdCoverage(args: List[String], ctx: CommandContext): CmdResult =
  requireArg(args, "Usage: scalex coverage <symbol>") { symbol =>
      val refs = ctx.idx.findReferences(symbol).results
      val testRefs = refs.filter(r => isTestFile(r.file, ctx.workspace))
      val testFiles = testRefs.map(r => ctx.workspace.relativize(r.file).toString).distinct
      // Hint computed here so the renderer stays a pure formatter
      val hint = if refs.isEmpty then Some(mkNotFoundWithSuggestions(symbol, ctx, "coverage")) else None
      CmdResult.CoverageReport(symbol, refs.size, testRefs, testFiles, hint)
  }
