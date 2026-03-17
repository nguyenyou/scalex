def cmdPackage(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex package <pkg>")
    case Some(pkg) =>
      resolvePackage(pkg, ctx) match
        case None =>
          CmdResult.NotFound(
            s"""Package "$pkg" not found""",
            mkPackageNotFound(pkg, ctx, "package"))
        case Some(resolvedPkg) =>
          var symbols = filterSymbols(ctx.idx.symbols.filter(_.packageName == resolvedPkg), ctx)
          if ctx.definitionsOnly then
            val defKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
            symbols = symbols.filter(s => defKinds.contains(s.kind))
          CmdResult.PackageSymbols(resolvedPkg, symbols)
