package scalex.output

import scalex.*

import CmdResult.*

private[scalex] def renderInlineBody(body: Option[BodyInfo], indent: String): Unit =
  body.foreach { b =>
    val bodyLines = b.sourceText.split("\n")
    bodyLines.zipWithIndex.foreach { case (line, i) =>
      println(s"$indent${numberedLine(b.startLine + i, line)}")
    }
  }

/** `,"body":…,"bodyStartLine":…,"bodyEndLine":…` fields, or empty when there is no body. */
private[scalex] def jsonBodyFields(body: Option[BodyInfo]): String =
  body
    .map(b => s""","body":${jStr(b.sourceText)},"bodyStartLine":${b.startLine},"bodyEndLine":${b.endLine}""")
    .getOrElse("")

/** The shared member-object core: `"name":…,"kind":…,"line":…,"signature":…` (no braces). */
private[scalex] def jsonMemberFields(m: MemberInfo): String =
  s""""name":${jStr(m.name)},"kind":${jStr(m.kind.label)},"line":${m.line},"signature":${jStr(m.signature)}"""

/** Member line in `members` text output: kind + signature (or name with --brief). */
private[scalex] def memberLine(m: MemberInfo, brief: Boolean): String =
  if (brief) s"${m.kind.label.padTo(5, ' ')} ${m.name.padTo(30, ' ')}"
  else s"${m.kind.label.padTo(5, ' ')} ${m.signature.padTo(50, ' ')}"

/** Member line in `explain` text output: kind + name (or signature with --verbose). */
private[scalex] def explainMemberLine(m: MemberInfo, verbose: Boolean): String =
  s"${m.kind.label.padTo(5, ' ')} ${if (verbose) m.signature else m.name}"

private[scalex] def renderMemberSections(r: CmdResult.MemberSections, ctx: CommandContext): Unit = {
  // Slice a section's items using global running counters (skipLeft, showLeft).
  // Returns (shown items, count of items in this section omitted by the limit).
  def sliceSection[A](
      items: List[A],
      skipLeft: Int,
      showLeft: Int
  ): (shown: List[A], omitted: Int, newSkip: Int, newShow: Int) = {
    val toSkip = skipLeft.min(items.size)
    val available = items.drop(toSkip)
    val toShow = showLeft.min(available.size)
    val shown = available.take(toShow)
    (shown = shown, omitted = available.size - toShow, newSkip = skipLeft - toSkip, newShow = showLeft - toShow)
  }

  if (ctx.output.jsonOutput) {
    val allMembers = r.sections.flatMap { sec =>
      val ownMembers = sec.ownMembers.map { m =>
        val rel = ctx.workspace.relativize(sec.file).toString
        val overrideJson = if (m.isOverride) ""","isOverride":true""" else ""
        s"""{${jsonMemberFields(m)},"file":${jStr(rel)},"owner":${jStr(r.symbol)},"ownerKind":${jStr(
            sec.ownerKind.label
          )},"package":${jStr(sec.packageName)},"inherited":false$overrideJson${jsonBodyFields(m.body)}}"""
      }
      val inheritedMembers = sec.inherited.flatMap { (parentName, parentFile, parentPackage, members) =>
        members.map { m =>
          val rel = parentFile.map(f => ctx.workspace.relativize(f).toString).getOrElse("")
          s"""{${jsonMemberFields(m)},"file":${jStr(rel)},"owner":${jStr(
              parentName
            )},"ownerKind":"inherited","package":${jStr(parentPackage)},"inherited":true}"""
        }
      }
      val companionMembers = sec.companion.toList.flatMap { (compSym, compMembers) =>
        val rel = ctx.workspace.relativize(compSym.file).toString
        compMembers.map { m =>
          s"""{${jsonMemberFields(m)},"file":${jStr(rel)},"owner":${jStr(
              compSym.name
            )},"ownerKind":"companion","package":${jStr(compSym.packageName)},"inherited":false}"""
        }
      }
      ownMembers ++ inheritedMembers ++ companionMembers
    }
    println(jArr(allMembers.drop(ctx.output.offset).take(ctx.output.limit)))
  } else {
    if (r.sections.isEmpty) {
      println(s"""No class/trait/object/enum "${r.symbol}" found""")
    } else {
      // Running counters for global pagination across all sections
      var skipLeft = ctx.output.offset
      var showLeft = ctx.output.limit
      r.sections.foreach { sec =>
        val rel = ctx.workspace.relativize(sec.file)
        println(s"Members of ${sec.ownerKind.label} ${r.symbol}${pkgSuffix(sec.packageName)} — $rel:${sec.line}:")
        if (sec.ownMembers.isEmpty) println("  (no members)")
        else {
          val (shown, omitted, newSkip, newShow) = sliceSection(sec.ownMembers, skipLeft, showLeft)
          skipLeft = newSkip; showLeft = newShow
          if (shown.nonEmpty || omitted > 0) println(s"  Defined in ${r.symbol}:")
          shown.foreach { m =>
            val overrideMarker = if (m.isOverride) "  [override]" else ""
            println(s"    ${memberLine(m, ctx.members.brief)} :${m.line}$overrideMarker")
            renderInlineBody(m.body, "      ")
          }
          if (omitted > 0) println(s"    ... and $omitted more")
        }
        sec.inherited.foreach { (parentName, _, _, pMembers) =>
          val (shown, omitted, newSkip, newShow) = sliceSection(pMembers, skipLeft, showLeft)
          skipLeft = newSkip; showLeft = newShow
          if (shown.nonEmpty || omitted > 0) println(s"  Inherited from $parentName:")
          shown.foreach(m => println(s"    ${memberLine(m, ctx.members.brief)} :${m.line}"))
          if (omitted > 0) println(s"    ... and $omitted more")
        }
        sec.companion.foreach { (compSym, compMembers) =>
          val compRel = ctx.workspace.relativize(compSym.file)
          if (compMembers.isEmpty) {
            println(s"\n  Companion ${compSym.kind.label} ${compSym.name} — $compRel:${compSym.line}:")
            println("    (no members)")
          } else {
            val (shown, omitted, newSkip, newShow) = sliceSection(compMembers, skipLeft, showLeft)
            skipLeft = newSkip; showLeft = newShow
            if (shown.nonEmpty || omitted > 0)
              println(s"\n  Companion ${compSym.kind.label} ${compSym.name} — $compRel:${compSym.line}:")
            shown.foreach(m => println(s"    ${memberLine(m, ctx.members.brief)} :${m.line}"))
            if (omitted > 0) println(s"    ... and $omitted more")
          }
        }
      }
    }
  }
}

private[scalex] def renderDocEntries(r: CmdResult.DocEntries, ctx: CommandContext): Unit = {
  if (ctx.output.jsonOutput) {
    val entries = r.entries.take(ctx.output.limit).map { e =>
      val rel = ctx.workspace.relativize(e.sym.file).toString
      s"""{"name":${jStr(e.sym.name)},"kind":${jStr(e.sym.kind.label)},"file":${jStr(
          rel
        )},"line":${e.sym.line},"package":${jStr(e.sym.packageName)},"doc":${jOpt(e.doc)}}"""
    }
    println(jArr(entries))
  } else {
    r.entries.take(ctx.output.limit).foreach { e =>
      val rel = ctx.workspace.relativize(e.sym.file)
      println(s"${e.sym.kind.label} ${r.symbol}${pkgSuffix(e.sym.packageName)} — $rel:${e.sym.line}:")
      e.doc match {
        case Some(doc) => println(doc)
        case None      => println("  (no scaladoc)")
      }
      println()
    }
  }
}

private[scalex] def renderHierarchyResult(r: CmdResult.HierarchyResult, ctx: CommandContext): Unit = {
  val tree = r.tree
  def nodeJson(n: HierarchyNode): String = {
    val file = jOpt(n.sym.map(s => ctx.workspace.relativize(s.file).toString))
    val kind = jOpt(n.sym.map(_.kind.label))
    val line = n.sym.map(_.line.toString).getOrElse("null")
    s"""{"name":${jStr(n.name)},"kind":$kind,"file":$file,"line":$line,"package":${jStr(
        n.packageName
      )},"isExternal":${n.isExternal}}"""
  }
  def treeJson(t: HierarchyTree): String = {
    val ps = jArr(t.parents.map(treeJson))
    val cs = jArr(t.children.map(treeJson))
    val trunc = if (t.truncatedChildren > 0) s""","truncatedChildren":${t.truncatedChildren}""" else ""
    s"""{"node":${nodeJson(t.root)},"parents":$ps,"children":$cs$trunc}"""
  }
  // One recursive printer for both directions: `down` walks children (with
  // truncation notes), parents-mode marks external nodes instead.
  def printLevel(nodes: List[HierarchyTree], indent: String, down: Boolean): Unit = {
    nodes.zipWithIndex.foreach { case (t, i) =>
      val isLast = i == nodes.size - 1
      val prefix = if (isLast) s"$indent└── " else s"$indent├── "
      val nextIndent = if (isLast) s"$indent    " else s"$indent│   "
      val n = t.root
      val nkind = n.sym.map(_.kind.label + " ").getOrElse("")
      val nloc =
        if (!down && n.isExternal) " [external]"
        else n.sym.map(s => s" — ${ctx.workspace.relativize(s.file)}:${s.line}").getOrElse("")
      println(s"$prefix$nkind${n.name}${pkgSuffix(n.packageName)}$nloc")
      printLevel(if (down) t.children else t.parents, nextIndent, down)
      if (down && t.truncatedChildren > 0)
        println(s"$nextIndent... and ${t.truncatedChildren} more children")
    }
  }
  if (ctx.output.jsonOutput) {
    println(treeJson(tree))
  } else {
    val rootNode = tree.root
    val kind = rootNode.sym.map(_.kind.label).getOrElse("unknown")
    val loc = rootNode.sym.map(s => s" — ${ctx.workspace.relativize(s.file)}:${s.line}").getOrElse("")
    println(s"Hierarchy of $kind ${rootNode.name}${pkgSuffix(rootNode.packageName)}$loc:")
    if (ctx.hierarchy.goUp) {
      println("  Parents:")
      if (tree.parents.isEmpty) println("    (none)")
      else printLevel(tree.parents, "    ", down = false)
    }
    if (ctx.hierarchy.goDown) {
      println("  Children:")
      if (tree.children.isEmpty) println("    (none)")
      else printLevel(tree.children, "    ", down = true)
    }
  }
}

private[scalex] def renderOverrideList(r: CmdResult.OverrideList, ctx: CommandContext): Unit = {
  if (ctx.output.jsonOutput) {
    val arr = r.results.map { o =>
      val rel = ctx.workspace.relativize(o.file).toString
      s"""{"enclosingClass":${jStr(o.enclosingClass)},"enclosingKind":${jStr(o.enclosingKind.label)},"file":${jStr(
          rel
        )},"line":${o.line},"signature":${jStr(o.signature)},"package":${jStr(o.packageName)}${jsonBodyFields(
          o.body
        )}}"""
    }
    println(jArr(arr))
  } else {
    println(r.header)
    r.results.foreach { o =>
      val rel = ctx.workspace.relativize(o.file)
      println(s"  ${o.enclosingClass}${pkgSuffix(o.packageName)} — $rel:${o.line}")
      println(s"    ${o.signature}")
      renderInlineBody(o.body, "    ")
    }
  }
}

private[scalex] def renderExplanation(r: CmdResult.Explanation, ctx: CommandContext): Unit = {
  val sym = r.sym
  val rel = ctx.workspace.relativize(sym.file)
  // Companion members identical to primary members are deduplicated in both modes
  val primaryKeys = r.members.map(m => (name = m.name, kind = m.kind)).toSet
  def uniqueCompanionMembers(compMembers: List[MemberInfo]): List[MemberInfo] =
    compMembers.filter(m => !primaryKeys.contains((name = m.name, kind = m.kind)))
  if (ctx.output.jsonOutput) {
    val membersJson = jArr(r.members.map { m =>
      val overrideJson = if (m.isOverride) ""","isOverride":true""" else ""
      s"""{${jsonMemberFields(m)}$overrideJson${jsonBodyFields(m.body)}}"""
    })
    val implsJson = jArr(r.impls.map(s => jsonSymbol(s, ctx.workspace)))
    val companionJson = r.companion
      .map { (compSym, compMembers) =>
        val cMembers = jArr(uniqueCompanionMembers(compMembers).map(m => s"{${jsonMemberFields(m)}}"))
        s"""{"definition":${jsonSymbol(compSym, ctx.workspace)},"members":$cMembers}"""
      }
      .getOrElse("null")
    def explainedImplJson(ei: ExplainedImpl): String = {
      val mJson = jArr(ei.members.map(m => s"{${jsonMemberFields(m)}}"))
      val subJson = jArr(ei.subImpls.map(explainedImplJson))
      s"""{"definition":${jsonSymbol(ei.sym, ctx.workspace)},"members":$mJson,"subImplementations":$subJson}"""
    }
    val expandedJson = jArr(r.expandedImpls.map(explainedImplJson))
    val importCount = r.importRefs.size
    val importRefsJson =
      if (importCount <= 10)
        s""","importFiles":${jArr(r.importRefs.map(ref => jsonRef(ref, ctx.workspace)))}"""
      else ""
    val otherJson =
      if (r.otherMatches.nonEmpty)
        s""","otherMatches":${jStrArr(r.otherMatches)}"""
      else ""
    val totalImplJson = if (r.totalImpls > r.impls.size) s""","totalImplementations":${r.totalImpls}""" else ""
    val inheritedJson = if (r.inherited.nonEmpty) {
      val groups = jArr(r.inherited.map { (parentName, parentFile, parentPackage, members) =>
        val pRel = jOpt(parentFile.map(f => ctx.workspace.relativize(f).toString))
        val mJson = jArr(members.map(m => s"{${jsonMemberFields(m)}}"))
        s"""{"parent":${jStr(parentName)},"parentFile":$pRel,"parentPackage":${jStr(parentPackage)},"members":$mJson}"""
      })
      s""","inherited":$groups"""
    } else ""
    val relatedJson =
      if (r.relatedTypes.nonEmpty)
        s""","relatedTypes":${jArr(r.relatedTypes.map(s => jsonSymbol(s, ctx.workspace)))}"""
      else ""
    println(
      s"""{"definition":${jsonSymbol(sym, ctx.workspace)},"doc":${jOpt(
          r.doc
        )},"members":$membersJson,"implementations":$implsJson,"importCount":$importCount$importRefsJson,"companion":$companionJson,"expandedImplementations":$expandedJson$otherJson$totalImplJson$inheritedJson$relatedJson}"""
    )
  } else {
    println(s"Explanation of ${sym.kind.label} ${sym.name}${pkgSuffix(sym.packageName)}:\n")
    println(s"  Definition: $rel:${sym.line}")
    println(s"  Signature: ${sym.signature}")
    if (sym.parents.nonEmpty) println(s"  Extends: ${sym.parents.mkString(", ")}")
    println()
    r.doc match {
      case Some(d) =>
        println("  Scaladoc:")
        d.split("\n").foreach(l => println(s"    $l"))
        println()
      case None =>
        if (!ctx.members.brief) println("  Scaladoc: (none)\n")
    }
    if (r.members.nonEmpty) {
      println(s"  Members (top ${r.members.size}):")
      r.members.foreach { m =>
        val overrideMarker = if (m.isOverride) "  [override]" else ""
        println(s"    ${explainMemberLine(m, ctx.output.verbose)}$overrideMarker")
        renderInlineBody(m.body, "      ")
      }
      println()
    }
    r.inherited.foreach { (parentName, _, _, pMembers) =>
      println(s"  Inherited from $parentName:")
      renderShown(pMembers, ctx.members.membersLimit, "    ")(m =>
        println(s"    ${explainMemberLine(m, ctx.output.verbose)}")
      )
      println()
    }
    r.companion.foreach { (compSym, compMembers) =>
      val compRel = ctx.workspace.relativize(compSym.file)
      println(s"  Companion ${compSym.kind.label} ${compSym.name} — $compRel:${compSym.line}")
      if (compMembers.nonEmpty) {
        val uniqueCompMembers = uniqueCompanionMembers(compMembers)
        val dupeCount = compMembers.size - uniqueCompMembers.size
        if (uniqueCompMembers.nonEmpty)
          uniqueCompMembers.foreach(m => println(s"    ${explainMemberLine(m, ctx.output.verbose)}"))
        if (dupeCount > 0)
          println(s"    ($dupeCount members shared with ${sym.kind.label}, shown above)")
      }
      println()
    }
    if (r.impls.nonEmpty) {
      val implHeader =
        if (r.totalImpls > r.impls.size)
          s"  Implementations (showing ${r.impls.size} of ${r.totalImpls} — use --impl-limit to adjust):"
        else
          s"  Implementations (${r.impls.size}):"
      println(implHeader)
      r.impls.foreach(s => println(formatSymbol(s, ctx.workspace)))
      println()
    }
    if (r.expandedImpls.nonEmpty) {
      println("  Expanded implementations:")
      def printExpanded(impls: List[ExplainedImpl], indent: String): Unit = {
        impls.foreach { ei =>
          println(
            s"$indent${ei.sym.kind.label} ${ei.sym.name} — ${ctx.workspace.relativize(ei.sym.file)}:${ei.sym.line}"
          )
          ei.members.foreach(m => println(s"$indent  ${explainMemberLine(m, ctx.output.verbose)}"))
          if (ei.subImpls.nonEmpty) printExpanded(ei.subImpls, indent + "  ")
        }
      }
      printExpanded(r.expandedImpls, "    ")
      println()
    }
    if (r.relatedTypes.nonEmpty) {
      println(s"  Related types (${r.relatedTypes.size}):")
      r.relatedTypes.foreach { s =>
        val relFile = ctx.workspace.relativize(s.file)
        println(s"    ${s.kind.label.padTo(5, ' ')} ${s.name}${pkgSuffix(s.packageName)} — $relFile:${s.line}")
      }
      println()
    }
    if (!ctx.members.shallow && !ctx.members.brief) {
      val importCount = r.importRefs.size
      if (importCount == 0)
        println("  Imported by: 0 files")
      else if (importCount <= 10) {
        println(s"  Imported by ($importCount files):")
        r.importRefs.foreach(ref => println(s"    ${ctx.workspace.relativize(ref.file)}:${ref.line}"))
      } else
        println(s"  Imported by: $importCount files (use `scalex imports ${sym.name}` for full list)")
    }
    if (r.otherMatches.nonEmpty) {
      Console.err.println(s"(${r.otherMatches.size} other match${if (r.otherMatches.size > 1) "es" else ""}:)")
      r.otherMatches.foreach(m => Console.err.println(s"  scalex explain $m"))
    }
  }
}
