package scalex.output

import scalex.*

import CmdResult.*
import java.nio.file.Path
import scala.collection.mutable

private def referenceDetails(
    symbol: String,
    targetPkgs: Set[String],
    ctx: CommandContext
): Reference => (relativePath: String, confidence: Confidence) = {
  val byFile = mutable.HashMap.empty[Path, (relativePath: String, confidence: Confidence)]
  ref =>
    byFile.getOrElseUpdate(
      ref.file,
      (
        relativePath = ctx.workspace.relativize(ref.file).toString,
        confidence = ctx.idx.resolveConfidence(ref, symbol, targetPkgs)
      )
    )
}

private[scalex] def renderRefList(r: CmdResult.RefList, ctx: CommandContext): Unit = {
  r.stderrHint.foreach(System.err.println)
  if (ctx.output.jsonOutput) {
    val jFn: Reference => String = if (r.useContext) referenceJsonFormatter(ctx) else ref => jsonRef(ref, ctx.workspace)
    val arr = jArr(r.refs.take(ctx.output.limit).map(jFn))
    val hintStr = r.correctedPattern.map(p => s""","corrected":${jStr(p)}""").getOrElse("")
    println(s"""{"results":$arr,"timedOut":${r.timedOut}$hintStr}""")
  } else {
    if (r.refs.isEmpty) {
      if (r.emptyMessage.nonEmpty) println(r.emptyMessage)
    } else {
      println(r.header)
      val fFn: Reference => String =
        if (r.useContext) referenceTextFormatter(ctx) else ref => formatRef(ref, ctx.workspace)
      renderShown(r.refs, ctx.output.limit, "  ")(ref => println(fFn(ref)))
    }
  }
}

private[scalex] def renderCategorizedRefs(r: CmdResult.CategorizedRefs, ctx: CommandContext): Unit = {
  r.stderrHint.foreach(System.err.println)
  if (ctx.output.jsonOutput) {
    val entries = r.grouped
      .map { (cat, refs) =>
        s""""${cat.toString}":${jArr(refs.take(ctx.output.limit).map(referenceJsonFormatter(ctx)))}"""
      }
      .mkString(",")
    println(s"""{"categories":{$entries},"timedOut":${r.timedOut}}""")
  } else {
    val total = r.grouped.values.map(_.size).sum
    val suffix = timedOutSuffix(r.timedOut)
    println(s"""References to "${r.symbol}" — $total found:$suffix""")
    // Confidence is file-scoped; sort keys are calculated once per reference.
    val details = referenceDetails(r.symbol, r.targetPkgs, ctx)
    val annotated = r.grouped.toList.flatMap { (cat, refs) =>
      refs.map { ref =>
        val (path, conf) = details(ref)
        (cat = cat, ref = ref, conf = conf, sortKey = (path = path, line = ref.line))
      }
    }
    Confidence.values.foreach { conf =>
      val confRefs = annotated.filter(_.conf == conf)
      if (confRefs.nonEmpty) {
        println(s"\n  ${conf.label} (${conf.explanation}):")
        val byCat = confRefs.groupBy(_.cat)
        refCategoryOrder.foreach { cat =>
          byCat.get(cat).filter(_.nonEmpty).foreach { entries =>
            val sorted = entries.sortBy(_.sortKey)
            println(s"\n    ${cat.toString}:")
            renderShown(sorted, ctx.output.limit, "      ")(e => println(s"    ${referenceTextFormatter(ctx)(e.ref)}"))
          }
        }
      }
    }
  }
}

private[scalex] def renderFlatRefs(r: CmdResult.FlatRefs, ctx: CommandContext): Unit = {
  if (ctx.output.jsonOutput) {
    println(s"""{"results":${jArr(
        r.refs.take(ctx.output.limit).map(referenceJsonFormatter(ctx))
      )},"timedOut":${r.timedOut}}""")
  } else {
    val suffix = timedOutSuffix(r.timedOut)
    println(s"""References to "${r.symbol}" — ${r.refs.size} found:$suffix""")
    val details = referenceDetails(r.symbol, r.targetPkgs, ctx)
    val annotated = r.refs.map { ref =>
      val (path, conf) = details(ref)
      (ref = ref, conf = conf, sortKey = (confidence = conf.ordinal, path = path, line = ref.line))
    }
    val sorted = annotated.sortBy(_.sortKey)
    var lastConf: Option[Confidence] = None
    sorted.take(ctx.output.limit).foreach { (ref, conf, _) =>
      if (!lastConf.contains(conf)) {
        println(s"\n  [${conf.label}]")
        lastConf = Some(conf)
      }
      println(referenceTextFormatter(ctx)(ref))
    }
    if (r.refs.size > ctx.output.limit) println(s"  ... and ${r.refs.size - ctx.output.limit} more")
  }
}

private[scalex] def renderGrepCount(r: CmdResult.GrepCount, ctx: CommandContext): Unit = {
  r.stderrHint.foreach(System.err.println)
  if (ctx.output.jsonOutput) {
    val hintStr = r.correctedPattern.map(p => s""","corrected":${jStr(p)}""").getOrElse("")
    println(s"""{"matches":${r.matches},"files":${r.files},"timedOut":${r.timedOut}$hintStr}""")
  } else {
    val suffix = timedOutSuffix(r.timedOut)
    println(s"${r.matches} matches across ${r.files} files$suffix")
  }
}

private[scalex] def renderGrepByMethod(r: CmdResult.GrepByMethod, ctx: CommandContext): Unit = {
  r.stderrHint.foreach(System.err.println)
  val suffix = timedOutSuffix(r.timedOut)
  if (ctx.output.jsonOutput) {
    val items = jArr(r.methods.take(ctx.output.limit).map { m =>
      val file = ctx.workspace.relativize(m.file).toString
      val linesJson = jArr(m.matchLines.map(ml => s"""{"line":${ml.lineNum},"text":${jStr(ml.text)}}"""))
      s"""{"name":${jStr(m.member.name)},"kind":${jStr(m.member.kind.label)},"signature":${jStr(
          m.member.signature
        )},"file":${jStr(file)},"line":${m.member.line},"matches":${m.matchCount},"matchLines":$linesJson}"""
    })
    val total = r.methods.map(_.matchCount).sum
    val truncated =
      if (r.methods.size > ctx.output.limit) s""","truncated":true,"totalMethods":${r.methods.size}""" else ""
    val timedOutStr = if (r.timedOut) s""","timedOut":true""" else ""
    val hintStr = r.correctedPattern.map(p => s""","corrected":${jStr(p)}""").getOrElse("")
    println(s"""{"pattern":${jStr(r.pattern)},"owner":${jStr(
        r.owner
      )},"methods":$items,"totalMatches":$total$truncated$timedOutStr$hintStr}""")
  } else {
    if (r.methods.isEmpty)
      println(s"""No methods in ${r.owner} whose body contains "${r.pattern}"$suffix""")
    else {
      val total = r.methods.map(_.matchCount).sum
      println(
        s"""Methods in ${r.owner} whose body contains "${r.pattern}" — ${r.methods.size} methods, $total matches:$suffix"""
      )
      val maxSig = r.methods.take(ctx.output.limit).map(_.member.signature.length).maxOption.getOrElse(0)
      renderShown(r.methods, ctx.output.limit, "  ", " (use --limit 0 to show all)") { m =>
        val loc = s"${ctx.workspace.relativize(m.file)}:${m.member.line}"
        val pad = " " * (maxSig - m.member.signature.length)
        val plural = if (m.matchCount == 1) "match" else "matches"
        println(s"  ${m.member.signature}$pad — $loc  (${m.matchCount} $plural)")
        m.matchLines.foreach { ml =>
          println(s"      ${ml.lineNum} | ${ml.text}")
        }
      }
    }
  }
}

private[scalex] def renderRefsTop(r: CmdResult.RefsTop, ctx: CommandContext): Unit = {
  val suffix = timedOutSuffix(r.timedOut)
  if (ctx.output.jsonOutput) {
    val filesJson = jArr(r.fileRanking.map { (file, count) =>
      s"""{"file":${jStr(ctx.workspace.relativize(file).toString)},"count":$count}"""
    })
    println(s"""{"symbol":${jStr(r.symbol)},"files":$filesJson,"total":${r.total},"timedOut":${r.timedOut}}""")
  } else {
    val fileCount = r.fileRanking.size
    println(s"Top $fileCount files referencing '${r.symbol}' (${r.total} total references)$suffix:")
    r.fileRanking.foreach { (file, count) =>
      val rel = ctx.workspace.relativize(file)
      println(f"  $count%4d  $rel")
    }
  }
}

private[scalex] def renderRefsSummary(r: CmdResult.RefsSummary, ctx: CommandContext): Unit = {
  if (ctx.output.jsonOutput) {
    val counts = r.categoryCounts.map((cat, count) => s""""${cat.toString}":$count""").mkString("{", ",", "}")
    println(s"""{"symbol":${jStr(r.symbol)},"counts":$counts,"total":${r.total},"timedOut":${r.timedOut}}""")
  } else {
    val suffix = timedOutSuffix(r.timedOut)
    val parts = r.categoryCounts.map { (cat, count) =>
      val label = cat match {
        case RefCategory.Definition => "definitions"
        case RefCategory.ExtendedBy => "extensions"
        case RefCategory.ImportedBy => "importers"
        case RefCategory.UsedAsType => "type usages"
        case RefCategory.Usage      => "usages"
        case RefCategory.Comment    => "comments"
      }
      s"$count $label"
    }
    println(s"""References to "${r.symbol}" — ${r.total} total: ${parts.mkString(", ")}$suffix""")
  }
}
