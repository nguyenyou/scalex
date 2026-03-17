import scala.collection.mutable

private val stdlibParentNames = Set(
  "product", "serializable", "anyval", "anyref", "any", "matchable",
  "equals", "object", "enum", "throwable", "exception"
)

private def isStdlibParent(name: String): Boolean =
  stdlibParentNames.contains(name.toLowerCase)

def cmdOverview(args: List[String], ctx: CommandContext): CmdResult =
  val allSymbols = if ctx.noTests then ctx.idx.symbols.filter(s => !isTestFile(s.file, ctx.workspace))
                   else ctx.idx.symbols
  val symbolsByKind = allSymbols.groupBy(_.kind).toList.sortBy(-_._2.size)
  val topPackages: List[(pkg: String, syms: List[SymbolInfo])] = allSymbols.groupBy(_.packageName)
    .filter(_._1.nonEmpty).toList.sortBy(-_._2.size).take(ctx.limit)
    .map((p, s) => (pkg = p, syms = s))
  val mostExtended = ctx.idx.parentIndex.toList
    .filter((name, _) => ctx.idx.symbolsByName.contains(name) && !isStdlibParent(name))
    .map { (name, impls) =>
      if ctx.noTests then (name, impls.filter(s => !isTestFile(s.file, ctx.workspace)))
      else (name, impls)
    }
    .filter(_._2.nonEmpty)
    .sortBy(-_._2.size).take(ctx.limit)

  val effectiveArch = ctx.architecture || ctx.focusPackage.isDefined

  // Architecture: compute package dependency graph from imports
  val archPkgDeps: Map[String, Set[String]] = if effectiveArch then {
    val deps = mutable.HashMap.empty[String, mutable.HashSet[String]]
    allSymbols.groupBy(_.file).foreach { (file, syms) =>
      val filePkg = syms.headOption.map(_.packageName).getOrElse("")
      if filePkg.nonEmpty then {
        parseFile(file).foreach { tree =>
          val (imports, _) = extractImports(tree)
          imports.foreach { imp =>
            val trimmed = imp.trim.stripPrefix("import ")
            // Extract package from import: "com.example.Foo" → "com.example"
            val lastDot = trimmed.lastIndexOf('.')
            if lastDot > 0 then {
              val importPkg = trimmed.substring(0, lastDot)
              // Only track cross-package dependencies
              if importPkg != filePkg && ctx.idx.packages.contains(importPkg) then {
                deps.getOrElseUpdate(filePkg, mutable.HashSet.empty) += importPkg
              }
            }
          }
        }
      }
    }
    deps.map((k, v) => k -> v.toSet).toMap
  } else Map.empty

  // Focus package: filter dependency graph to direct deps/dependents
  val filteredPkgDeps = ctx.focusPackage match
    case Some(fpkg) =>
      val directDeps = archPkgDeps.getOrElse(fpkg, Set.empty)
      val dependents = archPkgDeps.filter((_, deps) => deps.contains(fpkg)).keySet
      val relevant = Set(fpkg) ++ directDeps ++ dependents
      archPkgDeps.filter((pkg, _) => relevant.contains(pkg))
        .map((pkg, deps) => pkg -> deps.filter(relevant.contains))
    case None => archPkgDeps

  // Architecture: hub types (most-referenced + most-extended)
  val hubTypes: List[(name: String, score: Int)] = if effectiveArch then {
    val refCounts = mutable.HashMap.empty[String, Int]
    ctx.idx.parentIndex.foreach { (name, impls) =>
      if ctx.idx.symbolsByName.contains(name) && !isStdlibParent(name) then
        val filteredImpls = if ctx.noTests then impls.filter(s => !isTestFile(s.file, ctx.workspace)) else impls
        refCounts(name) = refCounts.getOrElse(name, 0) + filteredImpls.size
    }
    refCounts.filter(_._2 > 0).toList.sortBy(-_._2).take(ctx.limit)
  } else Nil

  CmdResult.Overview(OverviewData(
    fileCount = if ctx.noTests then allSymbols.map(_.file).distinct.size else ctx.idx.fileCount,
    symbolCount = allSymbols.size,
    packageCount = if ctx.noTests then allSymbols.map(_.packageName).filter(_.nonEmpty).distinct.size else ctx.idx.packages.size,
    symbolsByKind = symbolsByKind.map((k, syms) => (kind = k, count = syms.size)),
    topPackages = topPackages.map((p, syms) => (pkg = p, count = syms.size)),
    mostExtended = mostExtended.map((n, impls) => (name = n, count = impls.size)),
    pkgDeps = filteredPkgDeps,
    hubTypes = hubTypes,
    hasArchitecture = effectiveArch,
    focusPackage = ctx.focusPackage
  ))
