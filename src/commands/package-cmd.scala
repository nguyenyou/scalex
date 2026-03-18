private val kindRankMap: Map[SymbolKind, Int] = Map(
  SymbolKind.Trait -> 0, SymbolKind.Class -> 1, SymbolKind.Enum -> 2, SymbolKind.Object -> 3,
).withDefaultValue(4)

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
          if ctx.explainMode then
            val allTypes = symbols.filter(s => typeKinds.contains(s.kind))
              .sortBy(s => (kindRank = kindRankMap(s.kind), name = s.name))
            val types = allTypes.take(ctx.limit)
            val entries = types.map { s =>
              val members = extractMembers(s.file, s.name, Some(s.kind)).sortBy(memberKindRank).take(3)
              val implCount = filterSymbols(ctx.idx.findImplementations(s.name), ctx).size
              PackageExplainedEntry(s, members, implCount)
            }
            CmdResult.PackageExplained(resolvedPkg, entries, symbols.size, allTypes.size)
          else
            if ctx.definitionsOnly then
              symbols = symbols.filter(s => typeKinds.contains(s.kind))
            CmdResult.PackageSymbols(resolvedPkg, symbols)
