package scalex.output

import scalex.EntrypointCategory.*

import scalex.*
import scalex.extraction.*

import CmdResult.*
import java.nio.file.Path

/** Context lines around a [startLine, endLine] body span, clamped to the file. */
private[scalex] def contextWindow(lines: Array[String], startLine: Int, endLine: Int, n: Int): (
    before: Seq[(lineNum: Int, text: String)],
    after: Seq[(lineNum: Int, text: String)]
) = {
  val total = lines.length
  val ctxStart = math.max(1, startLine - n)
  val ctxEnd = math.min(total, endLine + n)
  val before = (ctxStart until startLine).filter(i => i >= 1 && i <= total).map(i => (lineNum = i, text = lines(i - 1)))
  val after = ((endLine + 1) to ctxEnd).filter(i => i >= 1 && i <= total).map(i => (lineNum = i, text = lines(i - 1)))
  (before = before, after = after)
}

private[scalex] def renderSourceBlocks(r: CmdResult.SourceBlocks, ctx: CommandContext): Unit = {
  def windowFor(
      file: Path,
      b: BodyInfo
  ): (before: Seq[(lineNum: Int, text: String)], after: Seq[(lineNum: Int, text: String)]) =
    if (r.contextLines > 0)
      contextWindow(readSourceLines(file).getOrElse(Array.empty[String]), b.startLine, b.endLine, r.contextLines)
    else (before = Seq.empty, after = Seq.empty)

  if (ctx.output.jsonOutput) {
    val arr = r.blocks.take(ctx.output.limit).map { (file, b) =>
      val rel = ctx.workspace.relativize(file).toString
      val importsJson =
        if (r.showImports)
          extractImportLines(file).map(imp => s""","imports":${jStr(imp)}""").getOrElse("")
        else ""
      val contextJson = if (r.contextLines > 0) {
        val (before, after) = windowFor(file, b)
        s""","contextBefore":${jStrArr(before.map(_.text))},"contextAfter":${jStrArr(after.map(_.text))}"""
      } else ""
      val abstractJson = if (b.isAbstract) ""","isAbstract":true""" else ""
      s"""{"name":${jStr(b.symbolName)},"owner":${jStr(b.ownerName)},"file":${jStr(
          rel
        )},"startLine":${b.startLine},"endLine":${b.endLine},"body":${jStr(
          b.sourceText
        )}$abstractJson$importsJson$contextJson}"""
    }
    println(jArr(arr))
  } else {
    r.blocks.take(ctx.output.limit).foreach { (file, b) =>
      val ownerStr = if (b.ownerName.nonEmpty) s" — ${b.ownerName}" else ""
      val rel = ctx.workspace.relativize(file)
      // Imports block
      if (r.showImports)
        extractImportLines(file).foreach { imp =>
          println(s"Imports — $rel:")
          imp.split("\n").foreach(l => println(s"  $l"))
          println()
        }
      val label = if (b.isAbstract) "Signature" else "Body"
      val abstractNote = if (b.isAbstract) " (abstract, no body)" else ""
      println(s"$label of ${b.symbolName}$ownerStr — $rel:${b.startLine}$abstractNote:")
      val (before, after) = windowFor(file, b)
      before.foreach((i, text) => println(s"  ${numberedLine(i, text)}"))
      if (before.nonEmpty) println("  ---")
      renderInlineBody(Some(b), "  ")
      if (after.nonEmpty) println("  ---")
      after.foreach((i, text) => println(s"  ${numberedLine(i, text)}"))
      println()
    }
  }
}

private[scalex] def renderTestSuites(r: CmdResult.TestSuites, ctx: CommandContext): Unit = {
  if (ctx.output.jsonOutput) {
    val arr = r.suites.take(ctx.output.limit).map { suite =>
      val rel = ctx.workspace.relativize(suite.file).toString
      val testsJson = jArr(suite.tests.map { tc =>
        val bodyField = if (r.showBody) {
          tc.body.map(b => s""","body":${jStr(b.sourceText)}""").getOrElse("")
        } else ""
        s"""{"name":${jStr(tc.name)},"line":${tc.line}$bodyField}"""
      })
      s"""{"suite":${jStr(suite.name)},"file":${jStr(rel)},"line":${suite.line},"tests":$testsJson}"""
    }
    println(jArr(arr))
  } else {
    if (r.suites.isEmpty) {
      println(r.emptyMessage)
    } else {
      r.suites.take(ctx.output.limit).foreach { suite =>
        val rel = ctx.workspace.relativize(suite.file)
        println(s"${suite.name} — $rel:${suite.line}:")
        suite.tests.foreach { tc =>
          println(s"""  test  "${tc.name}"  :${tc.line}""")
          if (r.showBody || ctx.output.verbose) {
            tc.body.foreach { b =>
              renderInlineBody(Some(b), "    ")
              println()
            }
          }
        }
        if (!r.showBody && !ctx.output.verbose) println()
      }
    }
  }
}

private[scalex] def renderTestCount(r: CmdResult.TestCount, ctx: CommandContext): Unit = {
  if (ctx.output.jsonOutput)
    println(s"""{"suites":${r.suites},"tests":${r.tests},"dynamicSites":${r.dynamicSites}}""")
  else {
    val qualifier = if (r.dynamicSites > 0) " (literal names only)" else ""
    println(s"${r.tests} tests${qualifier} across ${r.suites} suites")
    if (r.dynamicSites > 0)
      Console.err.println(s"  ${r.dynamicSites} dynamic test sites detected — actual count requires runtime")
  }
}

private[scalex] def renderCoverageReport(r: CmdResult.CoverageReport, ctx: CommandContext): Unit = {
  if (ctx.output.jsonOutput) {
    val refsJson = jArr(r.testRefs.take(ctx.output.limit).map(referenceJsonFormatter(ctx)))
    println(s"""{"symbol":${jStr(
        r.symbol
      )},"testFileCount":${r.testFiles.size},"referenceCount":${r.testRefs.size},"references":$refsJson}""")
  } else {
    if (r.testRefs.isEmpty) {
      if (r.totalRefs == 0) {
        println(s"""Coverage of "${r.symbol}" — no references found""")
        r.hint.foreach(renderHint)
      } else {
        println(s"""Coverage of "${r.symbol}" — ${r.totalRefs} refs but 0 in test files""")
      }
    } else {
      println(s"""Coverage of "${r.symbol}" — ${r.testRefs.size} refs in ${r.testFiles.size} test files:""")
      r.testFiles.sorted.foreach { f =>
        val fileRefs = r.testRefs.filter(ref => ctx.workspace.relativize(ref.file).toString == f)
        println(s"  $f")
        fileRefs.take(ctx.output.limit).foreach { ref =>
          println(s"    :${ref.line}  ${ref.contextLine}")
        }
      }
    }
  }
}

private[scalex] def renderDependencies(r: CmdResult.Dependencies, ctx: CommandContext): Unit = {
  def depJson(d: DepInfo): String = {
    val file = jOpt(d.file.map(f => ctx.workspace.relativize(f).toString))
    val line = d.line.map(_.toString).getOrElse("null")
    s"""{"name":${jStr(d.name)},"kind":${jStr(d.kind)},"file":$file,"line":$line,"package":${jStr(
        d.packageName
      )},"depth":${d.depth}}"""
  }
  def printDep(d: DepInfo): Unit = {
    val indent = "  " * d.depth
    val loc = d.file.map(f => s" — ${ctx.workspace.relativize(f)}:${d.line.getOrElse(0)}").getOrElse("")
    println(s"    $indent${d.kind.padTo(9, ' ')} ${d.name}$loc")
  }
  if (ctx.output.jsonOutput) {
    println(s"""{"imports":${jArr(r.importDeps.map(depJson))},"bodyReferences":${jArr(r.bodyDeps.map(depJson))}}""")
  } else {
    println(s"""Dependencies of "${r.symbol}":""")
    if (r.importDeps.nonEmpty) {
      println(s"\n  Imports:")
      renderShown(r.importDeps, ctx.output.limit, "    ")(printDep)
    }
    if (r.bodyDeps.nonEmpty) {
      println(s"\n  Body references:")
      renderShown(r.bodyDeps, ctx.output.limit, "    ")(printDep)
    }
  }
}

private[scalex] def renderScopes(r: CmdResult.Scopes, ctx: CommandContext): Unit = {
  if (ctx.output.jsonOutput) {
    val arr = jArr(r.scopes.map { s =>
      s"""{"name":${jStr(s.name)},"kind":${jStr(s.kind)},"line":${s.line}}"""
    })
    println(s"""{"file":${jStr(ctx.workspace.relativize(r.file).toString)},"line":${r.line},"scopes":$arr}""")
  } else {
    val rel = ctx.workspace.relativize(r.file)
    println(s"Context at $rel:${r.line}:")
    if (r.scopes.isEmpty) println("  (no enclosing scopes found)")
    else {
      r.scopes.foreach { s =>
        println(s"  ${s.kind.padTo(9, ' ')} ${s.name} (line ${s.line})")
      }
    }
  }
}

private[scalex] def renderSymbolDiff(r: CmdResult.SymbolDiff, ctx: CommandContext): Unit = {
  if (ctx.output.jsonOutput) {
    if (r.filesChanged == 0) {
      println("""{"added":[],"removed":[],"modified":[]}""")
    } else {
      def diffSymJson(s: DiffSymbol): String =
        s"""{"name":${jStr(s.name)},"kind":${jStr(s.kind.label)},"file":${jStr(
            s.file
          )},"line":${s.line},"package":${jStr(s.packageName)},"signature":${jStr(s.signature)}}"""
      val addedJson = jArr(r.added.take(ctx.output.limit).map(diffSymJson))
      val removedJson = jArr(r.removed.take(ctx.output.limit).map(diffSymJson))
      val modifiedJson = jArr(r.modified.take(ctx.output.limit).map { (o, n) =>
        s"""{"old":${diffSymJson(o)},"new":${diffSymJson(n)}}"""
      })
      println(s"""{"ref":${jStr(
          r.ref
        )},"filesChanged":${r.filesChanged},"added":$addedJson,"removed":$removedJson,"modified":$modifiedJson}""")
    }
  } else {
    if (r.filesChanged == 0) {
      println(s"No Scala files changed compared to ${r.ref}")
    } else {
      println(s"Symbol changes compared to ${r.ref} (${r.filesChanged} files changed):")
      def printGroup(label: String, marker: String, syms: List[DiffSymbol]): Unit =
        if (syms.nonEmpty) {
          println(s"\n  $label (${syms.size}):")
          renderShown(syms, ctx.output.limit, "    ")(s =>
            println(s"    $marker ${s.kind.label.padTo(9, ' ')} ${s.name} — ${s.file}:${s.line}")
          )
        }
      printGroup("Added", "+", r.added)
      printGroup("Removed", "-", r.removed)
      printGroup("Modified", "~", r.modified.map(_.after))
      if (r.added.isEmpty && r.removed.isEmpty && r.modified.isEmpty)
        println("  No symbol-level changes detected")
    }
  }
}

private[scalex] def renderEntrypoints(r: CmdResult.Entrypoints, ctx: CommandContext): Unit = {
  val byCategory = r.entries.groupBy(_.category)
  // Display order, text label, and JSON key per category — one table
  val categories = List(
    (cat = MainAnnotation, label = "@main annotated", jsonKey = "mainAnnotated"),
    (cat = MainMethod, label = "def main(...) methods", jsonKey = "mainMethods"),
    (cat = ExtendsApp, label = "extends App", jsonKey = "extendsApp"),
    (cat = TestSuite, label = "Test suites", jsonKey = "testSuites")
  )
  if (ctx.output.jsonOutput) {
    val groups = categories
      .map { c =>
        val entries = byCategory.getOrElse(c.cat, Nil).take(ctx.output.limit)
        val arr = jArr(entries.map { e =>
          val rel = ctx.workspace.relativize(e.sym.file).toString
          val line = e.memberLine.getOrElse(e.sym.line)
          s"""{"name":${jStr(e.sym.name)},"kind":${jStr(e.sym.kind.label)},"file":${jStr(
              rel
            )},"line":$line,"package":${jStr(e.sym.packageName)}}"""
        })
        s""""${c.jsonKey}":$arr"""
      }
      .mkString(",")
    println(s"""{"entrypoints":{$groups},"total":${r.total}}""")
  } else {
    if (r.entries.isEmpty)
      println("No entrypoints found")
    else {
      println(s"Entrypoints — ${r.total} found:\n")
      categories.foreach { c =>
        val entries = byCategory.getOrElse(c.cat, Nil)
        if (entries.nonEmpty) {
          println(s"  ${c.label} (${entries.size}):")
          renderShown(entries, ctx.output.limit, "    ") { e =>
            val rel = ctx.workspace.relativize(e.sym.file)
            val line = e.memberLine.getOrElse(e.sym.line)
            println(s"    ${e.sym.kind.label.padTo(9, ' ')} ${e.sym.name} — $rel:$line")
          }
          println()
        }
      }
    }
  }
}
