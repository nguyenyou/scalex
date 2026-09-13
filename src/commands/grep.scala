package scalex.commands

import java.util.regex.Pattern
import scala.collection.mutable
import java.nio.file.Files
import java.io.IOException

import scalex.*
import scalex.extraction.*

import scala.jdk.CollectionConverters.*
import scala.util.boundary, boundary.break
import scala.collection.mutable.ListBuffer

def cmdGrep(args: List[String], ctx: CommandContext): CmdResult = {
  // Fix each -e pattern individually before joining, so one invalid sub-pattern
  // doesn't cause the entire joined string to be literal-quoted
  val patternOpt =
    if (ctx.search.grepPatterns.nonEmpty)
      Some(ctx.search.grepPatterns.map(p => fixPosixRegex(p).pattern).mkString("|"))
    else args.headOption
  patternOpt match {
    case None             => CmdResult.UsageError("Usage: scalex grep <pattern>")
    case Some(rawPattern) =>
      val (pattern, wasFixed) = fixPosixRegex(rawPattern)
      val isLiteralQuoted = wasFixed && pattern.startsWith("\\Q")
      // Display strings use rawPattern so users never see \Q...\E internals
      val stderrHint =
        if (isLiteralQuoted) Some(s"""  Note: invalid regex, treating as literal search: "$rawPattern"""")
        else if (wasFixed) Some(s"""  Note: auto-corrected POSIX regex to Java regex: "$rawPattern" → "$pattern"""")
        else None
      val hint = if (wasFixed && !isLiteralQuoted) Some(pattern) else None
      ctx.search.inOwner match {
        case Some(owner) if ctx.search.eachMethod =>
          // Per-method grep: iterate members, grep each body, report which methods matched
          grepEachMethod(pattern, rawPattern, owner, ctx, hint, stderrHint)
        case ownerOpt =>
          val (results, timedOut) = ownerOpt match {
            // Scoped grep: restrict to a specific symbol's body span
            case Some(owner) => grepInSymbol(pattern, owner, ctx)
            case None        =>
              ctx.idx.grepFiles(pattern, ctx.filters.noTests, ctx.filters.pathFilter, ctx.filters.excludePath)
          }
          if (ctx.output.countOnly)
            CmdResult.GrepCount(results.size, results.map(_.file).distinct.size, timedOut, hint, stderrHint)
          else {
            val suffix = timedOutSuffix(timedOut)
            val inStr = ownerOpt.map(o => s" in $o").getOrElse("")
            CmdResult.RefList(
              header = s"""Matches for "$rawPattern"$inStr — ${results.size} found:$suffix""",
              refs = results,
              timedOut = timedOut,
              correctedPattern = hint,
              emptyMessage = s"""No matches for "$rawPattern"$inStr$suffix""",
              stderrHint = stderrHint
            )
          }
      }
  }
}

/** Find a symbol's definitions for scoped grep, falling back to an exact-name scan over type symbols when the indexed
  * lookup misses.
  */
private[scalex] def findOwnerDefs(name: String, ctx: CommandContext, typesOnly: Boolean): List[SymbolInfo] = {
  val primary = filterSymbols(ctx.idx.findDefinition(name), ctx.copy(filters = ctx.filters.copy(kindFilter = None)))
  val defs = if (typesOnly) primary.filter(s => typeKinds.contains(s.kind)) else primary
  if (defs.nonEmpty) defs
  else
    filterSymbols(
      ctx.idx.symbols.filter(s => s.name == name && typeKinds.contains(s.kind)),
      ctx.copy(filters = ctx.filters.copy(kindFilter = None))
    )
}

/** Grep a 1-indexed inclusive line span, returning matched lines. */
private[scalex] def grepSpan(
    lines: collection.Seq[String],
    startLine: Int,
    endLine: Int,
    regex: Pattern
): List[(lineNum: Int, text: String)] = {
  val matched = mutable.ListBuffer.empty[(lineNum: Int, text: String)]
  var lineIdx = startLine - 1 // 0-indexed
  val endIdx = math.min(endLine, lines.size) // 1-indexed inclusive -> exclusive in 0-indexed
  while (lineIdx < endIdx) {
    if (regex.matcher(lines(lineIdx)).find())
      matched += ((lineNum = lineIdx + 1, text = lines(lineIdx).trim))
    lineIdx += 1
  }
  matched.toList
}

private[scalex] def grepInSymbol(
    pattern: String,
    owner: String,
    ctx: CommandContext
): (results: List[Reference], timedOut: Boolean) = {
  val regex = Pattern.compile(pattern) // pattern is pre-validated by fixPosixRegex

  // Split Owner.member if present
  val (ownerName, memberName) = splitOwnerMember(owner) match {
    case Some((o, m)) => (o, Some(m))
    case None         => (owner, None)
  }

  val ownerDefs = findOwnerDefs(ownerName, ctx, typesOnly = false)
  val results = ownerDefs.flatMap { sym =>
    // Body span(s) for the owner (or a member within it); read the file once per definition
    val bodies = memberName match {
      case Some(mName) => extractBody(sym.file, mName, Some(ownerName))
      case None        => extractBody(sym.file, ownerName, None)
    }
    if (bodies.isEmpty) Nil
    else {
      val lines = try Files.readAllLines(sym.file).asScala
      catch {
        case _: IOException => Seq.empty
      }
      bodies.flatMap { b =>
        grepSpan(lines, b.startLine, b.endLine, regex).map(m => Reference(sym.file, m.lineNum, m.text))
      }
    }
  }
  (results, false)
}

private[scalex] val eachMethodTimeoutMs = 20_000L

private[scalex] def grepEachMethod(
    pattern: String,
    displayPattern: String,
    owner: String,
    ctx: CommandContext,
    hint: Option[String],
    stderrHint: Option[String]
): CmdResult = boundary {
  val regex = Pattern.compile(pattern) // pattern is pre-validated by fixPosixRegex

  // Find the owner type
  val ownerDefs = findOwnerDefs(owner, ctx, typesOnly = true)
  if (ownerDefs.isEmpty)
    break(CmdResult.NotFound(s"Type not found: $owner", mkNotFoundWithSuggestions(owner, ctx, "grep")))

  val deadline = System.nanoTime() + eachMethodTimeoutMs * 1_000_000
  var timedOut = false
  val diagnostics = ListBuffer.empty[String]
  val matches = mutable.ListBuffer.empty[MethodGrepMatch]
  ownerDefs.foreach { sym =>
    if (!timedOut) {
      // Single parse: extract members with body spans
      val membersWithSpans = extractMembersWithSpans(sym.file, sym.name, Some(sym.kind))
      val lines = try Files.readAllLines(sym.file).asScala
      catch {
        case _: IOException =>
          diagnostics += s"scalex: unreadable file: ${sym.file}"
          Seq.empty
      }

      if (lines.nonEmpty)
        membersWithSpans.foreach { ms =>
          if (System.nanoTime() < deadline) {
            val matchedLines = grepSpan(lines, ms.startLine, ms.endLine, regex)
            if (matchedLines.nonEmpty)
              matches += MethodGrepMatch(ms.member, sym.file, matchedLines.size, matchedLines)
          } else timedOut = true
        }
    }
  }
  val result = if (ctx.output.countOnly) {
    val total = matches.map(_.matchCount).sum
    CmdResult.GrepCount(total, matches.map(_.file).distinct.size, timedOut, hint, stderrHint)
  } else
    CmdResult.GrepByMethod(displayPattern, owner, matches.toList, hint, stderrHint, timedOut)
  CmdResult.WithDiagnostics(result, diagnostics.toList)
}
