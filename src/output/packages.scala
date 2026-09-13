package scalex.output

import scalex.*

import CmdResult.*

/** "  pkg.padded  count" rows shared by the overview modes. */
private[scalex] def printPackageRows(rows: List[(pkg: String, count: Int)]): Unit =
  rows.foreach((pkg, count) => println(s"  ${pkg.padTo(50, ' ')} $count"))

/** "  Name.padded  N <label>  sig" rows shared by hub-types / most-extended output. */
private[scalex] def printRankedTypeRows(
    rows: List[(name: String, count: Int, signature: String)],
    countLabel: String
): Unit =
  rows.foreach { (name, count, sig) =>
    val sigHint = if (sig.nonEmpty) s"  $sig" else ""
    println(s"  ${name.padTo(30, ' ')} $count $countLabel$sigHint")
  }

private[scalex] def renderOverview(r: CmdResult.Overview, ctx: CommandContext): Unit = {
  val d = r.data
  if (ctx.output.jsonOutput) {
    val kindJson = jKindCounts(d.symbolsByKind)
    val pkgJson = jArr(d.topPackages.map((p, c) => s"""{"package":${jStr(p)},"count":$c}"""))
    if (d.hasArchitecture) {
      val depsJson = if (ctx.overview.concise) {
        // Concise JSON: top 10 most-connected packages only
        val topConnected = d.pkgDeps.toList.sortBy(-_._2.size).take(10)
        topConnected
          .map { (pkg, deps) =>
            s"""${jStr(pkg)}:${jStrArr(deps.toList.sorted.take(10))}"""
          }
          .mkString("{", ",", "}")
      } else {
        d.pkgDeps
          .map { (pkg, deps) =>
            s"""${jStr(pkg)}:${jStrArr(deps)}"""
          }
          .mkString("{", ",", "}")
      }
      val hubJson = jArr(
        d.mostExtended.map((n, c, sig) => s"""{"name":${jStr(n)},"score":$c,"signature":${jStr(sig)}}""")
      )
      val focusPkgJson = d.focusPackage.map(p => s""","focusPackage":${jStr(p)}""").getOrElse("")
      val conciseJson = if (ctx.overview.concise) {
        val totalEdges = d.pkgDeps.values.map(_.size).sum
        s""","concise":true,"totalPackagesWithDeps":${d.pkgDeps.size},"totalEdges":$totalEdges"""
      } else ""
      println(
        s"""{"fileCount":${d.fileCount},"symbolCount":${d.symbolCount},"packageCount":${d.packageCount},"symbolsByKind":$kindJson,"topPackages":$pkgJson,"packageDependencies":$depsJson,"hubTypes":$hubJson$focusPkgJson$conciseJson}"""
      )
    } else {
      val extJson = jArr(
        d.mostExtended.map((n, c, sig) => s"""{"name":${jStr(n)},"implementations":$c,"signature":${jStr(sig)}}""")
      )
      println(
        s"""{"fileCount":${d.fileCount},"symbolCount":${d.symbolCount},"packageCount":${d.packageCount},"symbolsByKind":$kindJson,"topPackages":$pkgJson,"mostExtended":$extJson}"""
      )
    }
  } else if (ctx.overview.concise) {
    // Fixed-size concise output: ~60 lines regardless of codebase size
    val conciseLimit = 10
    val focusNote = d.focusPackage.map(p => s" (scoped to $p)").getOrElse("")
    println(s"Project: ${d.fileCount} files, ${d.symbolCount} symbols, ${d.packageCount} packages$focusNote\n")

    // Symbols by kind — single compact line
    val kindLine = d.symbolsByKind.map((k, c) => s"${c} ${k.label}").mkString(", ")
    println(s"Symbols: $kindLine\n")

    // Top packages — capped at conciseLimit
    val shownPkgs = d.topPackages.take(conciseLimit)
    println(s"Top packages:")
    printPackageRows(shownPkgs)
    val remainingPkgs = d.packageCount - shownPkgs.size
    if (remainingPkgs > 0)
      println(s"  ... and $remainingPkgs more (use overview --limit N to show more)")

    // Package dependency summary — stats + top connectors, NOT full graph
    if (d.pkgDeps.nonEmpty) {
      val totalEdges = d.pkgDeps.values.map(_.size).sum
      val pkgsWithDeps = d.pkgDeps.size
      println(s"\nPackage dependencies: $pkgsWithDeps packages, $totalEdges cross-package edges")
      val topConnectors = d.pkgDeps.toList.sortBy(-_._2.size).take(5)
      println(s"  Most connected:")
      topConnectors.foreach { (pkg, deps) =>
        println(s"    ${pkg.padTo(45, ' ')} → ${deps.size} deps")
      }
    }

    // Hub types — capped at conciseLimit
    val shownHubs = d.mostExtended.take(conciseLimit)
    if (shownHubs.nonEmpty) {
      println(s"\nHub types (top ${shownHubs.size}):")
      printRankedTypeRows(shownHubs, "references")
    }

    println(s"\nDrill down: overview --architecture, overview --focus-package PKG, entrypoints")
  } else {
    println(s"Project overview (${d.fileCount} files, ${d.symbolCount} symbols):\n")
    println("Symbols by kind:")
    d.symbolsByKind.foreach { (kind, count) =>
      println(s"  ${kind.toString.padTo(10, ' ')} $count")
    }
    println(s"\nTop packages (by symbol count):")
    printPackageRows(d.topPackages)
    if (!d.hasArchitecture) {
      println(s"\nMost extended (by package spread, then implementation count):")
      printRankedTypeRows(d.mostExtended, "impl")
    }
    if (d.hasArchitecture) {
      d.focusPackage match {
        case Some(fpkg) =>
          println(s"\nPackage focus: $fpkg")
          val directDeps = d.pkgDeps.getOrElse(fpkg, Set.empty)
          println(s"\n  Depends on:")
          if (directDeps.isEmpty) println("    (none)")
          else directDeps.toList.sorted.foreach(dep => println(s"    $dep"))
          val dependents = d.pkgDeps.filter((pkg, deps) => pkg != fpkg && deps.contains(fpkg)).keySet
          println(s"\n  Depended on by:")
          if (dependents.isEmpty) println("    (none)")
          else dependents.toList.sorted.foreach(dep => println(s"    $dep"))
        case None =>
          println(s"\nPackage dependencies:")
          if (d.pkgDeps.isEmpty) println("  (no cross-package dependencies found)")
          else
            d.pkgDeps.toList.sortBy(_._1).foreach { (pkg, deps) =>
              println(s"  $pkg → ${deps.toList.sorted.mkString(", ")}")
            }
      }
      println(s"\nHub types (by package spread, then extension count):")
      if (d.mostExtended.isEmpty) println("  (none)")
      else printRankedTypeRows(d.mostExtended, "references")
    }
  }
}

private[scalex] def renderPackages(r: CmdResult.Packages, ctx: CommandContext): Unit = {
  if (ctx.output.jsonOutput) {
    println(jStrArr(r.packages))
  } else {
    println(s"Packages (${r.packages.size}):")
    r.packages.foreach(p => println(s"  $p"))
  }
}

private[scalex] def pluralKind(kind: SymbolKind): String = kind match {
  case SymbolKind.Class => "Classes"
  case _                => s"${kind.toString}s"
}

private[scalex] def renderPackageSymbols(r: CmdResult.PackageSymbols, ctx: CommandContext): Unit = {
  if (ctx.output.jsonOutput) {
    val arr = jArr(r.symbols.take(ctx.output.limit).map(s => jsonSymbol(s, ctx.workspace)))
    val truncated = if (r.symbols.size > ctx.output.limit) ",\"truncated\":true" else ""
    println(s"""{"package":${jStr(r.pkg)},"symbolCount":${r.symbols.size},"symbols":$arr$truncated}""")
  } else {
    if (r.symbols.isEmpty) {
      println(s"""Package ${r.pkg}: (no symbols)""")
    } else {
      println(s"Package ${r.pkg} (${r.symbols.size} symbols):\n")
      val byKind: List[(kind: SymbolKind, syms: List[SymbolInfo])] =
        r.symbols.groupBy(_.kind).toList.sortBy(-_._2.size).map((k, s) => (kind = k, syms = s))
      byKind.foreach { (kind, syms) =>
        println(s"  ${pluralKind(kind)} (${syms.size}):")
        renderShown(syms.sortBy(_.name), ctx.output.limit, "    ") { s =>
          if (ctx.output.verbose)
            println(
              s"    ${s.name.padTo(30, ' ')} ${s.signature.take(60)}  — ${ctx.workspace.relativize(s.file)}:${s.line}"
            )
          else
            println(s"    ${s.name.padTo(30, ' ')} ${ctx.workspace.relativize(s.file)}:${s.line}")
        }
      }
    }
  }
}

private[scalex] def renderPackageExplained(r: CmdResult.PackageExplained, ctx: CommandContext): Unit = {
  if (ctx.output.jsonOutput) {
    val typesJson = jArr(r.entries.map { e =>
      val mJson = jArr(e.members.map(m => s"{${jsonMemberFields(m)}}"))
      s"""{"definition":${jsonSymbol(e.sym, ctx.workspace)},"members":$mJson,"implCount":${e.implCount}}"""
    })
    val truncatedJson = if (r.totalTypes > r.entries.size) s""","totalTypes":${r.totalTypes},"truncated":true""" else ""
    println(s"""{"package":${jStr(r.pkg)},"totalSymbols":${r.totalSymbols},"types":$typesJson$truncatedJson}""")
  } else {
    if (r.entries.isEmpty) {
      println(s"""Package ${r.pkg}: (no types)""")
    } else {
      val typeHeader =
        if (r.totalTypes > r.entries.size)
          s"showing ${r.entries.size} of ${r.totalTypes} types, ${r.totalSymbols} symbols"
        else
          s"${r.entries.size} types of ${r.totalSymbols} symbols"
      println(s"Package ${r.pkg} ($typeHeader):\n")
      r.entries.foreach { e =>
        val rel = ctx.workspace.relativize(e.sym.file)
        val implSuffix = if (e.implCount > 0) s" (${e.implCount} impls)" else ""
        println(s"  ${e.sym.kind.label} ${e.sym.name}$implSuffix — $rel:${e.sym.line}")
        println(s"    ${e.sym.signature}")
        if (e.members.nonEmpty)
          e.members.foreach { m =>
            println(s"    ${m.kind.label.padTo(5, ' ')} ${m.name}: ${m.signature.take(60)}")
          }
        println()
      }
      if (r.totalTypes > r.entries.size)
        println(s"  ... and ${r.totalTypes - r.entries.size} more types (use --limit to adjust)")
    }
  }
}

private[scalex] def renderPackageSummary(r: CmdResult.PackageSummary, ctx: CommandContext): Unit = {
  if (ctx.output.jsonOutput) {
    val arr = jArr(r.subPackages.map { (sub, count) =>
      s"""{"subPackage":${jStr(sub)},"symbolCount":$count}"""
    })
    println(s"""{"package":${jStr(r.pkg)},"totalSymbols":${r.totalSymbols},"subPackages":$arr}""")
  } else {
    if (r.subPackages.isEmpty) {
      println(s"""Package ${r.pkg}: no symbols found""")
    } else {
      println(s"Summary of ${r.pkg} (${r.totalSymbols} symbols):\n")
      val maxNameLen = r.subPackages.map(_.subPkg.length).maxOption.getOrElse(10).min(50)
      r.subPackages.foreach { (sub, count) =>
        val label = if (sub == "(root)") "(root)" else s".${sub}"
        println(s"  ${label.padTo(maxNameLen + 2, ' ')} $count")
      }
    }
  }
}

private[scalex] def renderApiSurface(r: CmdResult.ApiSurface, ctx: CommandContext): Unit = {
  if (ctx.output.jsonOutput) {
    val items = jArr(r.symbols.take(ctx.output.limit).map { (sym, count) =>
      val rel = ctx.workspace.relativize(sym.file).toString
      s"""{"name":${jStr(sym.name)},"kind":${jStr(sym.kind.label)},"file":${jStr(
          rel
        )},"line":${sym.line},"package":${jStr(sym.packageName)},"importerCount":$count}"""
    })
    println(s"""{"package":${jStr(
        r.pkg
      )},"exportedCount":${r.symbols.size},"totalInPackage":${r.totalInPackage},"symbols":$items,"internalOnly":${jStrArr(
        r.internalOnly
      )}}""")
  } else {
    if (r.symbols.isEmpty && r.internalOnly.isEmpty) {
      println(s"""API surface of ${r.pkg}: no symbols found""")
    } else {
      val exportedCount = r.symbols.size
      println(s"API surface of ${r.pkg} ($exportedCount of ${r.totalInPackage} symbols imported externally):\n")
      renderShown(r.symbols, ctx.output.limit, "  ") { (sym, count) =>
        val rel = ctx.workspace.relativize(sym.file)
        val importerLabel = if (count == 1) "importer" else "importers"
        println(s"  ${sym.name.padTo(25, ' ')} ${sym.kind.label.padTo(9, ' ')} $count $importerLabel  $rel:${sym.line}")
      }
      if (r.internalOnly.nonEmpty) {
        val shown = r.internalOnly.take(10)
        val suffix = if (r.internalOnly.size > 10) s", ... and ${r.internalOnly.size - 10} more" else ""
        println(s"\n  Not imported externally (${r.internalOnly.size}): ${shown.mkString(", ")}$suffix")
      }
    }
  }
}
