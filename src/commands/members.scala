def cmdMembers(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex members <Symbol>")
    case Some(symbol) =>
      val typeKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
      val defs = filterSymbols(ctx.idx.findDefinition(symbol).filter(s => typeKinds.contains(s.kind)), ctx)

      if defs.isEmpty then
        CmdResult.NotFound(
          s"""No class/trait/object/enum "$symbol" found""",
          mkNotFoundWithSuggestions(symbol, ctx, "members"))
      else
        val sections = defs.map { s =>
          val inheritResult = collectInheritedMembers(s, ctx)
          val inherited = inheritResult.inherited
          val parentKeys = inheritResult.parentMemberKeys
          val members = extractMembers(s.file, symbol).map { m =>
            if ctx.inherited && parentKeys.contains((name = m.name, kind = m.kind)) then m.copy(isOverride = true) else m
          }
          // Companion lookup (same pattern as explain)
          val companionKinds: Set[SymbolKind] = s.kind match
            case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Enum => Set(SymbolKind.Object)
            case SymbolKind.Object => Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Enum)
            case _ => Set.empty
          val companion: Option[(sym: SymbolInfo, members: List[MemberInfo])] =
            if companionKinds.isEmpty then None
            else
              val allDefs = ctx.idx.findDefinition(symbol).filter(d => typeKinds.contains(d.kind))
              allDefs.find(d => companionKinds.contains(d.kind) && d.packageName == s.packageName && d.file == s.file)
                .map { compSym =>
                  val compMembers = extractMembers(compSym.file, symbol)
                  (sym = compSym, members = compMembers)
                }
          MemberSectionData(
            file = s.file,
            ownerKind = s.kind,
            packageName = s.packageName,
            line = s.line,
            ownMembers = members,
            inherited = inherited,
            companion = companion
          )
        }
        CmdResult.MemberSections(symbol, sections)
