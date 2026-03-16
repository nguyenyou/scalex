def cmdGrep(args: List[String], ctx: CommandContext): CmdResult =
  val patternOpt = if ctx.grepPatterns.nonEmpty then Some(ctx.grepPatterns.mkString("|"))
                   else args.headOption
  patternOpt match
    case None => CmdResult.UsageError("Usage: scalex grep <pattern>")
    case Some(rawPattern) =>
      val (pattern, wasFixed) = fixPosixRegex(rawPattern)
      val stderrHint = if wasFixed then Some(s"""  Note: auto-corrected POSIX regex to Java regex: "$rawPattern" → "$pattern"""") else None
      val hint = if wasFixed then Some(s""","corrected":"$pattern"""") else None
      val (results, grepTimedOut) = ctx.idx.grepFiles(pattern, ctx.noTests, ctx.pathFilter)
      if ctx.countOnly then
        val fileCount = results.map(_.file).distinct.size
        CmdResult.GrepCount(results.size, fileCount, grepTimedOut, hint, stderrHint)
      else
        val suffix = if grepTimedOut then " (timed out — partial results)" else ""
        CmdResult.RefList(
          header = s"""Matches for "$pattern" — ${results.size} found:$suffix""",
          refs = results,
          timedOut = grepTimedOut,
          hint = hint,
          emptyMessage = s"""No matches for "$pattern"$suffix""",
          stderrHint = stderrHint)
