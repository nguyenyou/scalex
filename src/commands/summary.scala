def cmdSummary(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex summary <package>")
    case Some(pkg) =>
      val lower = pkg.toLowerCase
      // Same resolution as cmdPackage
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
            NotFoundHint(pkg, ctx.idx.fileCount, ctx.idx.parseFailures, "summary", ctx.batchMode, false, pkgSuggestions))
        case Some(resolvedPkg) =>
          val prefix = resolvedPkg + "."
          // Collect all packages that start with resolvedPkg (including itself)
          var allSymbols = ctx.idx.symbols.filter { s =>
            s.packageName == resolvedPkg || s.packageName.startsWith(prefix)
          }
          if ctx.noTests then allSymbols = allSymbols.filter(s => !isTestFile(s.file, ctx.workspace))
          ctx.pathFilter.foreach { p => allSymbols = allSymbols.filter(s => matchesPath(s.file, p, ctx.workspace)) }

          // Group by sub-package relative to resolvedPkg
          val grouped = allSymbols.groupBy { s =>
            if s.packageName == resolvedPkg then "(root)"
            else s.packageName.stripPrefix(prefix)
          }
          val subPackages = grouped.toList
            .map((sub, syms) => (subPkg = sub, count = syms.size))
            .sortBy(-_.count)
          CmdResult.PackageSummary(resolvedPkg, subPackages, allSymbols.size)
