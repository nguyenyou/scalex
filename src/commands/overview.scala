import scala.collection.mutable

private val stdlibParentNames = Set(
  "product", "serializable", "anyval", "anyref", "any", "matchable",
  "equals", "object", "enum", "throwable", "exception"
)

private def isStdlibParent(name: String): Boolean =
  stdlibParentNames.contains(name.toLowerCase)

private def recoverName(lower: String, symbolsByName: Map[String, List[SymbolInfo]]): String =
  symbolsByName.get(lower).flatMap(_.headOption).map(_.name).getOrElse(lower)

private def recoverSignature(lower: String, symbolsByName: Map[String, List[SymbolInfo]]): String =
  symbolsByName.get(lower).flatMap(_.headOption).map(_.signature).getOrElse("")

def cmdOverview(args: List[String], ctx: CommandContext): CmdResult =
  var allSymbols = filterSymbols(ctx.idx.symbols, ctx)

  val symbolsByKind = allSymbols.groupBy(_.kind).toList.sortBy(-_._2.size)
  val topPackages: List[(pkg: String, syms: List[SymbolInfo])] = allSymbols.groupBy(_.packageName)
    .filter(_._1.nonEmpty).toList.sortBy(-_._2.size).take(ctx.limit)
    .map((p, s) => (pkg = p, syms = s))

  val mostExtended = ctx.idx.parentIndex.toList
    .filter((name, _) => ctx.idx.symbolsByName.contains(name) && !isStdlibParent(name))
    .filter((name, _) => recoverName(name, ctx.idx.symbolsByName).length > 1) // exclude single-char names
    .map { (name, impls) =>
      val filtered = filterSymbols(impls, ctx)
      val distinctPkgs = filtered.map(_.packageName).distinct.size
      (name = name, impls = filtered, distinctPkgs = distinctPkgs)
    }
    .filter(_.impls.nonEmpty)
    .sortBy(t => (primary = -t.distinctPkgs, secondary = -t.impls.size))
    .take(ctx.limit)

  val effectiveArch = ctx.architecture || ctx.focusPackage.isDefined

  // Architecture: compute package dependency graph from imports
  val archPkgDeps: Map[String, Set[String]] = if effectiveArch then {
    val deps = mutable.HashMap.empty[String, mutable.HashSet[String]]
    allSymbols.groupBy(_.file).foreach { (file, syms) =>
      val filePkg = syms.headOption.map(_.packageName).getOrElse("")
      if filePkg.nonEmpty && !isJavaFile(file) then {
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
  val hubTypes: List[(name: String, score: Int, signature: String)] = if effectiveArch then {
    val refCounts = mutable.HashMap.empty[String, (count: Int, distinctPkgs: Int)]
    ctx.idx.parentIndex.foreach { (name, impls) =>
      if ctx.idx.symbolsByName.contains(name) && !isStdlibParent(name) &&
         recoverName(name, ctx.idx.symbolsByName).length > 1 then
        val filtered = filterSymbols(impls, ctx)
        if filtered.nonEmpty then
          refCounts(name) = (count = filtered.size, distinctPkgs = filtered.map(_.packageName).distinct.size)
    }
    refCounts.toList
      .sortBy((_, data) => (-data.distinctPkgs, -data.count))
      .take(ctx.limit)
      .map((name, data) => (
        name = recoverName(name, ctx.idx.symbolsByName),
        score = data.count,
        signature = recoverSignature(name, ctx.idx.symbolsByName)
      ))
  } else Nil

  CmdResult.Overview(OverviewData(
    fileCount = allSymbols.map(_.file).distinct.size,
    symbolCount = allSymbols.size,
    packageCount = allSymbols.map(_.packageName).filter(_.nonEmpty).distinct.size,
    symbolsByKind = symbolsByKind.map((k, syms) => (kind = k, count = syms.size)),
    topPackages = topPackages.map((p, syms) => (pkg = p, count = syms.size)),
    mostExtended = mostExtended.map(t => (
      name = recoverName(t.name, ctx.idx.symbolsByName),
      count = t.impls.size,
      signature = recoverSignature(t.name, ctx.idx.symbolsByName)
    )),
    pkgDeps = filteredPkgDeps,
    hubTypes = hubTypes,
    hasArchitecture = effectiveArch,
    focusPackage = ctx.focusPackage
  ))
