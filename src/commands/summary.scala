def cmdSummary(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex summary <package>")
    case Some(pkg) =>
      resolvePackage(pkg, ctx) match
        case None =>
          CmdResult.NotFound(
            s"""Package "$pkg" not found""",
            mkPackageNotFound(pkg, ctx, "summary"))
        case Some(resolvedPkg) =>
          val prefix = resolvedPkg + "."
          // Collect all packages that start with resolvedPkg (including itself)
          var allSymbols = filterSymbols(symbolsInPackage(resolvedPkg, ctx.idx.symbols), ctx)

          // Group by sub-package relative to resolvedPkg
          val grouped = allSymbols.groupBy { s =>
            if s.packageName == resolvedPkg then "(root)"
            else s.packageName.stripPrefix(prefix)
          }
          val subPackages = grouped.toList
            .map((sub, syms) => (subPkg = sub, count = syms.size))
            .sortBy(-_.count)
          CmdResult.PackageSummary(resolvedPkg, subPackages, allSymbols.size)
