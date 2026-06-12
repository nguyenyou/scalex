import scala.jdk.CollectionConverters.*
import scala.util.boundary, boundary.break

def cmdGrep(args: List[String], ctx: CommandContext): CmdResult =
  // Fix each -e pattern individually before joining, so one invalid sub-pattern
  // doesn't cause the entire joined string to be literal-quoted
  val patternOpt = if ctx.grepPatterns.nonEmpty then
    Some(ctx.grepPatterns.map(p => fixPosixRegex(p).pattern).mkString("|"))
  else args.headOption
  patternOpt match
    case None => CmdResult.UsageError("Usage: scalex grep <pattern>")
    case Some(rawPattern) =>
      val (pattern, wasFixed) = fixPosixRegex(rawPattern)
      val isLiteralQuoted = wasFixed && pattern.startsWith("\\Q")
      // Use rawPattern in display strings so users never see \Q...\E internals
      val displayPattern = rawPattern
      val stderrHint =
        if isLiteralQuoted then Some(s"""  Note: invalid regex, treating as literal search: "$rawPattern"""")
        else if wasFixed then Some(s"""  Note: auto-corrected POSIX regex to Java regex: "$rawPattern" → "$pattern"""")
        else None
      val hint = if wasFixed && !isLiteralQuoted then Some(s""","corrected":"$pattern"""") else None
      ctx.inOwner match
        case Some(owner) if ctx.eachMethod =>
          // Per-method grep: iterate members, grep each body, report which methods matched
          grepEachMethod(pattern, displayPattern, owner, ctx, hint, stderrHint)
        case Some(owner) =>
          // Scoped grep: restrict to a specific symbol's body span
          val (scopedResults, scopedTimedOut) = grepInSymbol(pattern, owner, ctx)
          if ctx.countOnly then
            val fileCount = scopedResults.map(_.file).distinct.size
            CmdResult.GrepCount(scopedResults.size, fileCount, scopedTimedOut, hint, stderrHint)
          else
            val suffix = timedOutSuffix(scopedTimedOut)
            val inStr = s""" in $owner"""
            CmdResult.RefList(
              header = s"""Matches for "$displayPattern"$inStr — ${scopedResults.size} found:$suffix""",
              refs = scopedResults,
              timedOut = scopedTimedOut,
              hint = hint,
              emptyMessage = s"""No matches for "$displayPattern"$inStr$suffix""",
              stderrHint = stderrHint)
        case None =>
          val (results, grepTimedOut) = ctx.idx.grepFiles(pattern, ctx.noTests, ctx.pathFilter, ctx.excludePath)
          if ctx.countOnly then
            val fileCount = results.map(_.file).distinct.size
            CmdResult.GrepCount(results.size, fileCount, grepTimedOut, hint, stderrHint)
          else
            val suffix = timedOutSuffix(grepTimedOut)
            CmdResult.RefList(
              header = s"""Matches for "$displayPattern" — ${results.size} found:$suffix""",
              refs = results,
              timedOut = grepTimedOut,
              hint = hint,
              emptyMessage = s"""No matches for "$displayPattern"$suffix""",
              stderrHint = stderrHint)

/** Find a symbol's definitions for scoped grep, falling back to an exact-name
  * scan over type symbols when the indexed lookup misses. */
private def findOwnerDefs(name: String, ctx: CommandContext, typesOnly: Boolean): List[SymbolInfo] = {
  val primary = filterSymbols(ctx.idx.findDefinition(name), ctx.copy(kindFilter = None))
  val defs = if typesOnly then primary.filter(s => typeKinds.contains(s.kind)) else primary
  if defs.nonEmpty then defs
  else filterSymbols(ctx.idx.symbols.filter(s => s.name == name && typeKinds.contains(s.kind)), ctx.copy(kindFilter = None))
}

/** Grep a 1-indexed inclusive line span, returning matched lines. */
private def grepSpan(lines: collection.Seq[String], startLine: Int, endLine: Int,
                     regex: java.util.regex.Pattern): List[(lineNum: Int, text: String)] = {
  val matched = scala.collection.mutable.ListBuffer.empty[(lineNum: Int, text: String)]
  var lineIdx = startLine - 1 // 0-indexed
  val endIdx = math.min(endLine, lines.size) // 1-indexed inclusive -> exclusive in 0-indexed
  while lineIdx < endIdx do {
    if regex.matcher(lines(lineIdx)).find() then
      matched += ((lineNum = lineIdx + 1, text = lines(lineIdx).trim))
    lineIdx += 1
  }
  matched.toList
}

private def grepInSymbol(pattern: String, owner: String, ctx: CommandContext): (results: List[Reference], timedOut: Boolean) = boundary {
  val regex = java.util.regex.Pattern.compile(pattern) // pattern is pre-validated by fixPosixRegex

  // Split Owner.member if present
  val (ownerName, memberName) = splitOwnerMember(owner) match {
    case Some((o, m)) => (o, Some(m))
    case None => (owner, None)
  }

  // Find the owner's files
  val ownerDefs = findOwnerDefs(ownerName, ctx, typesOnly = false)
  if ownerDefs.isEmpty then break((Nil, false))

  val results = scala.collection.mutable.ListBuffer.empty[Reference]
  ownerDefs.foreach { sym =>
    // Get the body span(s) for the owner (and optionally a member within it)
    val bodies = memberName match
      case Some(mName) => extractBody(sym.file, mName, Some(ownerName))
      case None => extractBody(sym.file, ownerName, None)

    bodies.foreach { b =>
      val lines = try java.nio.file.Files.readAllLines(sym.file).asScala catch
        case _: java.io.IOException => break((Nil, false))
      grepSpan(lines, b.startLine, b.endLine, regex).foreach { m =>
        results += Reference(sym.file, m.lineNum, m.text)
      }
    }
  }
  (results.toList, false)
}

private val eachMethodTimeoutMs = 20_000L

private def grepEachMethod(pattern: String, displayPattern: String, owner: String, ctx: CommandContext, hint: Option[String], stderrHint: Option[String]): CmdResult = boundary {
  val regex = java.util.regex.Pattern.compile(pattern) // pattern is pre-validated by fixPosixRegex

  // Find the owner type
  val ownerDefs = findOwnerDefs(owner, ctx, typesOnly = true)
  if ownerDefs.isEmpty then
    break(CmdResult.NotFound(s"Type not found: $owner", mkNotFoundWithSuggestions(owner, ctx, "grep")))

  val deadline = System.nanoTime() + eachMethodTimeoutMs * 1_000_000
  var timedOut = false
  val matches = scala.collection.mutable.ListBuffer.empty[MethodGrepMatch]
  ownerDefs.foreach { sym =>
    if !timedOut then
      // Single parse: extract members with body spans
      val membersWithSpans = extractMembersWithSpans(sym.file, sym.name, Some(sym.kind))
      val lines = try java.nio.file.Files.readAllLines(sym.file).asScala catch
        case _: java.io.IOException =>
          Console.err.println(s"scalex: unreadable file: ${sym.file}")
          Seq.empty

      if lines.nonEmpty then
        membersWithSpans.foreach { ms =>
          if System.nanoTime() < deadline then
            val matchedLines = grepSpan(lines, ms.startLine, ms.endLine, regex)
            if matchedLines.nonEmpty then
              matches += MethodGrepMatch(ms.member, sym.file, matchedLines.size, matchedLines)
          else timedOut = true
        }
  }
  if ctx.countOnly then
    val total = matches.map(_.matchCount).sum
    CmdResult.GrepCount(total, matches.map(_.file).distinct.size, timedOut, hint, stderrHint)
  else
    CmdResult.GrepByMethod(displayPattern, owner, matches.toList, hint, stderrHint, timedOut)
}
