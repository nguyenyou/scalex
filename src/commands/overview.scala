package scalex.commands

import scalex.*
import scalex.index.*
import scalex.extraction.*

import scala.collection.mutable

private[scalex] val stdlibParentNames = Set(
  "product",
  "serializable",
  "anyval",
  "anyref",
  "any",
  "matchable",
  "equals",
  "object",
  "enum",
  "throwable",
  "exception",
  // Java exceptions / errors
  "runtimeexception",
  "ioexception",
  "illegalargumentexception",
  "illegalstateexception",
  "unsupportedoperationexception",
  "nullpointerexception",
  "classcastexception",
  "indexoutofboundsexception",
  "error",
  "assertionerror",
  "stackoverflowerror",
  "outofmemoryerror",
  // Java interfaces
  "comparable",
  "cloneable",
  "autocloseable",
  "closeable",
  "runnable",
  "callable",
  "iterable",
  "iterator",
  // Scala function types
  "function0",
  "function1",
  "function2",
  "function3",
  "partialfunction"
)

private[scalex] def isStdlibParent(name: String): Boolean =
  stdlibParentNames.contains(name.toLowerCase)

/** Returns true if ALL definitions of `lowerName` in `symbolsByName` are in stdlib packages. */
private[scalex] def isStdlibPackageOnly(lowerName: String, symbolsByName: Map[String, List[SymbolInfo]]): Boolean =
  symbolsByName.get(lowerName) match {
    case None       => false
    case Some(syms) => syms.forall(s => isStdlibPackage(s.packageName.toLowerCase))
  }

/** The indexed symbol behind a lowercased parentIndex key, if any. */
private[scalex] def recoverSymbol(lower: String, symbolsByName: Map[String, List[SymbolInfo]]): Option[SymbolInfo] =
  symbolsByName.get(lower).flatMap(_.headOption)

private[scalex] def recoverName(lower: String, symbolsByName: Map[String, List[SymbolInfo]]): String =
  recoverSymbol(lower, symbolsByName).map(_.name).getOrElse(lower)

private[scalex] def recoverSignature(lower: String, symbolsByName: Map[String, List[SymbolInfo]]): String =
  recoverSymbol(lower, symbolsByName).map(_.signature).getOrElse("")

def cmdOverview(args: List[String], ctx: CommandContext): CmdResult = {
  val allSymbols = filterSymbols(ctx.idx.symbols, ctx)

  val symbolsByKind = countByKind(allSymbols)
  val topPackages: List[(pkg: String, syms: List[SymbolInfo])] = allSymbols
    .groupBy(_.packageName)
    .filter(_._1.nonEmpty)
    .toList
    .sortBy(-_._2.size)
    .take(ctx.output.limit)
    .map((p, s) => (pkg = p, syms = s))

  // Most-extended non-stdlib parents, ranked — rendered as "most extended" or
  // as "hub types" depending on the architecture flag
  val mostExtended = ctx.idx.parentIndex.toList
    .filter((name, _) =>
      ctx.idx.symbolsByName.contains(name) && !isStdlibParent(name) && !isStdlibPackageOnly(name, ctx.idx.symbolsByName)
    )
    .filter((name, _) => recoverName(name, ctx.idx.symbolsByName).length > 1) // exclude single-char names
    .map { (name, impls) =>
      val filtered = filterSymbols(impls, ctx)
      val distinctPkgs = filtered.map(_.packageName).distinct.size
      (name = name, impls = filtered, distinctPkgs = distinctPkgs)
    }
    .filter(_.impls.nonEmpty)
    .sortBy(t => (primary = -t.distinctPkgs, secondary = -t.impls.size))
    .take(ctx.output.limit)

  val effectiveArch = ctx.overview.architecture || ctx.overview.focusPackage.isDefined || ctx.overview.concise

  // Architecture: compute package dependency graph from imports recorded at
  // index time (no source reparse needed)
  val archPkgDeps: Map[String, Set[String]] = if (effectiveArch) {
    val deps = mutable.HashMap.empty[String, mutable.HashSet[String]]
    allSymbols.groupBy(_.file).foreach { (file, syms) =>
      val filePkg = syms.headOption.map(_.packageName).getOrElse("")
      if (filePkg.nonEmpty && !isJavaFile(file)) {
        ctx.idx.fileImports(file).foreach { imp =>
          parseImportTarget(imp).foreach { (importPkg, _, _) =>
            // Only track cross-package dependencies
            if (importPkg != filePkg && ctx.idx.packages.contains(importPkg)) {
              deps.getOrElseUpdate(filePkg, mutable.HashSet.empty) += importPkg
            }
          }
        }
      }
    }
    deps.map((k, v) => k -> v.toSet).toMap
  } else Map.empty

  // Focus package: filter dependency graph to direct deps/dependents
  val filteredPkgDeps = ctx.overview.focusPackage match {
    case Some(fpkg) =>
      val directDeps = archPkgDeps.getOrElse(fpkg, Set.empty)
      val dependents = archPkgDeps.filter((_, deps) => deps.contains(fpkg)).keySet
      val relevant = Set(fpkg) ++ directDeps ++ dependents
      archPkgDeps
        .filter((pkg, _) => relevant.contains(pkg))
        .map((pkg, deps) => pkg -> deps.filter(relevant.contains))
    case None => archPkgDeps
  }

  CmdResult.Overview(
    OverviewData(
      fileCount = allSymbols.map(_.file).distinct.size,
      symbolCount = allSymbols.size,
      packageCount = allSymbols.map(_.packageName).filter(_.nonEmpty).distinct.size,
      symbolsByKind = symbolsByKind,
      topPackages = topPackages.map((p, syms) => (pkg = p, count = syms.size)),
      mostExtended = mostExtended.map(t =>
        (
          name = recoverName(t.name, ctx.idx.symbolsByName),
          count = t.impls.size,
          signature = recoverSignature(t.name, ctx.idx.symbolsByName)
        )
      ),
      pkgDeps = filteredPkgDeps,
      hasArchitecture = effectiveArch,
      focusPackage = ctx.overview.focusPackage
    )
  )
}
