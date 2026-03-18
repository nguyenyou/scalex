import scala.jdk.CollectionConverters.*

def cmdGrep(args: List[String], ctx: CommandContext): CmdResult =
  val patternOpt = if ctx.grepPatterns.nonEmpty then Some(ctx.grepPatterns.mkString("|"))
                   else args.headOption
  patternOpt match
    case None => CmdResult.UsageError("Usage: scalex grep <pattern>")
    case Some(rawPattern) =>
      val (pattern, wasFixed) = fixPosixRegex(rawPattern)
      val stderrHint = if wasFixed then Some(s"""  Note: auto-corrected POSIX regex to Java regex: "$rawPattern" → "$pattern"""") else None
      val hint = if wasFixed then Some(s""","corrected":"$pattern"""") else None
      ctx.inOwner match
        case Some(owner) =>
          // Scoped grep: restrict to a specific symbol's body span
          val (scopedResults, scopedTimedOut) = grepInSymbol(pattern, owner, ctx)
          if ctx.countOnly then
            val fileCount = scopedResults.map(_.file).distinct.size
            CmdResult.GrepCount(scopedResults.size, fileCount, scopedTimedOut, hint, stderrHint)
          else
            val suffix = if scopedTimedOut then " (timed out — partial results)" else ""
            val inStr = s""" in $owner"""
            CmdResult.RefList(
              header = s"""Matches for "$pattern"$inStr — ${scopedResults.size} found:$suffix""",
              refs = scopedResults,
              timedOut = scopedTimedOut,
              hint = hint,
              emptyMessage = s"""No matches for "$pattern"$inStr$suffix""",
              stderrHint = stderrHint)
        case None =>
          val (results, grepTimedOut) = ctx.idx.grepFiles(pattern, ctx.noTests, ctx.pathFilter, ctx.excludePath)
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

private def grepInSymbol(pattern: String, owner: String, ctx: CommandContext): (results: List[Reference], timedOut: Boolean) = {
  val regex = try java.util.regex.Pattern.compile(pattern)
  catch
    case e: java.util.regex.PatternSyntaxException =>
      Console.err.println(s"Invalid regex: ${e.getMessage}")
      return (Nil, false)

  // Split Owner.member if present
  val (ownerName, memberName) = if owner.contains(".") then
    val lastDot = owner.lastIndexOf('.')
    (owner.substring(0, lastDot), Some(owner.substring(lastDot + 1)))
  else (owner, None)

  // Find the owner's files
  var ownerDefs = filterSymbols(ctx.idx.findDefinition(ownerName), ctx.copy(kindFilter = None))
  if ownerDefs.isEmpty then
    ownerDefs = filterSymbols(ctx.idx.symbols.filter(s => s.name == ownerName && typeKinds.contains(s.kind)), ctx.copy(kindFilter = None))
  if ownerDefs.isEmpty then return (Nil, false)

  val results = scala.collection.mutable.ListBuffer.empty[Reference]
  ownerDefs.foreach { sym =>
    // Get the body span(s) for the owner (and optionally a member within it)
    val bodies = memberName match
      case Some(mName) => extractBody(sym.file, mName, Some(ownerName))
      case None => extractBody(sym.file, ownerName, None)

    bodies.foreach { b =>
      val lines = try java.nio.file.Files.readAllLines(sym.file).asScala catch
        case _: java.io.IOException => return (Nil, false)
      // Grep within the body span
      var lineIdx = b.startLine - 1 // 0-indexed
      val endIdx = math.min(b.endLine, lines.size) // 1-indexed inclusive -> exclusive in 0-indexed
      while lineIdx < endIdx do
        val line = lines(lineIdx)
        if regex.matcher(line).find() then
          results += Reference(sym.file, lineIdx + 1, line.trim)
        lineIdx += 1
    }
  }
  (results.toList, false)
}
