def cmdPackage(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex package <pkg>")
    case Some(pkg) =>
      val lower = pkg.toLowerCase
      // Match: exact → suffix (.pkg) → substring; prefer package with most symbols
      def bestMatch(candidates: Iterable[String]): Option[String] =
        if candidates.isEmpty then None
        else Some(candidates.maxBy(p => ctx.idx.packageToSymbols.getOrElse(p, Nil).size))
      val matched = ctx.idx.packages.find(_.equalsIgnoreCase(pkg))
        .orElse(bestMatch(ctx.idx.packages.filter(_.toLowerCase.endsWith("." + lower))))
        .orElse(bestMatch(ctx.idx.packages.filter(_.toLowerCase.contains(lower))))
      matched match
        case None =>
          // Substring already failed, so try matching individual segments of the query
          val segments = lower.split("[.]").filter(_.nonEmpty)
          val pkgSuggestions = if segments.nonEmpty then
            ctx.idx.packages.filter { p =>
              val pl = p.toLowerCase
              segments.exists(seg => pl.contains(seg))
            }.toList.sortBy(p => -ctx.idx.packageToSymbols.getOrElse(p, Nil).size).take(5)
          else Nil
          CmdResult.NotFound(
            s"""Package "$pkg" not found""",
            NotFoundHint(pkg, ctx.idx.fileCount, ctx.idx.parseFailures, "package", ctx.batchMode, false, pkgSuggestions))
        case Some(resolvedPkg) =>
          val symbols = filterSymbols(ctx.idx.symbols.filter(_.packageName == resolvedPkg), ctx)
          CmdResult.PackageSymbols(resolvedPkg, symbols)
