def cmdDef(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex def <symbol>")
    case Some(symbol) =>
      var results = filterSymbols(ctx.idx.findDefinition(symbol), ctx)
      // Rank: class/trait/object/enum > type/given > def/val/var, non-test > test, shorter path first
      results = results.sortBy { s =>
        val kindRank = s.kind match
          case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Object | SymbolKind.Enum => 0
          case SymbolKind.Type | SymbolKind.Given => 1
          case _ => 2
        val testRank = if isTestFile(s.file, ctx.workspace) then 1 else 0
        val pathLen = ctx.workspace.relativize(s.file).toString.length
        (kindRank, testRank, pathLen)
      }
      // If no results and symbol contains ".", try Owner.member resolution
      if results.isEmpty && symbol.contains(".") then
        resolveDottedMember(symbol, ctx) match
          case Some(memberResults) =>
            CmdResult.SymbolList(
              header = s"""Definition of "$symbol":""",
              symbols = memberResults,
              total = memberResults.size)
          case None =>
            CmdResult.NotFound(
              s"""Definition of "$symbol": not found""",
              mkNotFoundWithSuggestions(symbol, ctx, "def"))
      else if results.isEmpty then
        CmdResult.NotFound(
          s"""Definition of "$symbol": not found""",
          mkNotFoundWithSuggestions(symbol, ctx, "def"))
      else
        CmdResult.SymbolList(
          header = s"""Definition of "$symbol":""",
          symbols = results,
          total = results.size)

/** Resolve Owner.member syntax: if Owner is a type, extract its members and filter to the member name */
private def resolveDottedMember(symbol: String, ctx: CommandContext): Option[List[SymbolInfo]] = {
  val lastDot = symbol.lastIndexOf('.')
  if lastDot <= 0 then return None
  val ownerName = symbol.substring(0, lastDot)
  val memberName = symbol.substring(lastDot + 1)
  val typeKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
  val ownerDefs = filterSymbols(ctx.idx.findDefinition(ownerName), ctx).filter(s => typeKinds.contains(s.kind))
  if ownerDefs.isEmpty then return None
  val memberResults = ownerDefs.flatMap { owner =>
    val simpleName = if ownerName.contains(".") then ownerName.substring(ownerName.lastIndexOf('.') + 1) else ownerName
    val members = extractMembers(owner.file, simpleName)
    members.filter(_.name.equalsIgnoreCase(memberName)).map { m =>
      SymbolInfo(
        name = m.name,
        kind = m.kind,
        file = owner.file,
        line = m.line,
        packageName = owner.packageName,
        signature = m.signature,
        annotations = m.annotations
      )
    }
  }
  if memberResults.nonEmpty then Some(memberResults) else None
}
