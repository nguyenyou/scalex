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
          var allSymbols = ctx.idx.symbols.filter { s =>
            s.packageName == resolvedPkg || s.packageName.startsWith(prefix)
          }
          if ctx.noTests then allSymbols = allSymbols.filter(s => !isTestFile(s.file, ctx.workspace))
          ctx.pathFilter.foreach { p => allSymbols = allSymbols.filter(s => matchesPath(s.file, p, ctx.workspace)) }
          ctx.excludePath.foreach { p => allSymbols = allSymbols.filter(s => !matchesPath(s.file, p, ctx.workspace)) }

          // Group by sub-package relative to resolvedPkg
          val grouped = allSymbols.groupBy { s =>
            if s.packageName == resolvedPkg then "(root)"
            else s.packageName.stripPrefix(prefix)
          }
          val subPackages = grouped.toList
            .map((sub, syms) => (subPkg = sub, count = syms.size))
            .sortBy(-_.count)
          CmdResult.PackageSummary(resolvedPkg, subPackages, allSymbols.size)
