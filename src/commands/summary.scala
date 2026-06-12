def cmdSummary(args: List[String], ctx: CommandContext): CmdResult =
  requireArg(args, "Usage: scalex summary <package>") { pkg =>
      withResolvedPackage(pkg, ctx, "summary") { resolvedPkg =>
        val prefix = resolvedPkg + "."
        // Collect all packages that start with resolvedPkg (including itself)
        val allSymbols = filterSymbols(symbolsInPackage(resolvedPkg, ctx.idx.symbols), ctx)

        // Group by sub-package relative to resolvedPkg
        val grouped = allSymbols.groupBy { s =>
          if s.packageName == resolvedPkg then "(root)"
          else s.packageName.stripPrefix(prefix)
        }
        val subPackages = grouped.toList
          .map((sub, syms) => (subPkg = sub, count = syms.size))
          .sortBy(-_.count)
        CmdResult.PackageSummary(resolvedPkg, subPackages, allSymbols.size)
      }
  }
