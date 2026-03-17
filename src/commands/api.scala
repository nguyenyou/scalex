def cmdApi(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex api <package>")
    case Some(pkg) =>
      resolvePackage(pkg, ctx) match
        case None =>
          CmdResult.NotFound(
            s"""Package "$pkg" not found""",
            mkPackageNotFound(pkg, ctx, "api"))
        case Some(resolvedPkg) =>
          val surface = ctx.idx.findApiSurface(resolvedPkg, ctx.usedByFilter)
          // Apply kind/test/path filters to the symbols
          val filteredSymbols = filterSymbols(surface.map(_.symbol), ctx).toSet
          val filtered = surface.filter(e => filteredSymbols.contains(e.symbol))
          val (exported, internal) = filtered.partition(_.importerCount > 0)
          val sorted = exported.sortBy(e => -e.importerCount)
          val allPkgSymbols = ctx.idx.packageToSymbols.getOrElse(resolvedPkg, Set.empty)
          CmdResult.ApiSurface(
            pkg = resolvedPkg,
            symbols = sorted,
            totalInPackage = allPkgSymbols.size,
            internalOnly = internal.map(_.symbol.name).sorted
          )
