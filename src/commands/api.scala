def cmdApi(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex api <package>")
    case Some(pkg) =>
      val lower = pkg.toLowerCase
      // Package resolution: exact → suffix → substring (same as cmdPackage)
      def bestMatch(candidates: Iterable[String]): Option[String] =
        if candidates.isEmpty then None
        else Some(candidates.maxBy(p => ctx.idx.packageToSymbols.getOrElse(p, Nil).size))
      val matched = ctx.idx.packages.find(_.equalsIgnoreCase(pkg))
        .orElse(bestMatch(ctx.idx.packages.filter(_.toLowerCase.endsWith("." + lower))))
        .orElse(bestMatch(ctx.idx.packages.filter(_.toLowerCase.contains(lower))))
      matched match
        case None =>
          val segments = lower.split("[.]").filter(_.nonEmpty)
          val pkgSuggestions = if segments.nonEmpty then
            ctx.idx.packages.filter { p =>
              val pl = p.toLowerCase
              segments.exists(seg => pl.contains(seg))
            }.toList.sortBy(p => -ctx.idx.packageToSymbols.getOrElse(p, Nil).size).take(5)
          else Nil
          CmdResult.NotFound(
            s"""Package "$pkg" not found""",
            NotFoundHint(pkg, ctx.idx.fileCount, ctx.idx.parseFailures, "api", ctx.batchMode, false, pkgSuggestions))
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
