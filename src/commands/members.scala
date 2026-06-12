def cmdMembers(args: List[String], ctx: CommandContext): CmdResult =
  requireArg(args, "Usage: scalex members <Symbol>") { symbol =>
      val simpleName = simpleNameOf(symbol)
      val typeDefs = findTypeDefs(symbol, ctx)
      val defs = filterSymbols(typeDefs, ctx)

      if defs.isEmpty then
        CmdResult.NotFound(
          s"""No class/trait/object/enum "$symbol" found""",
          mkNotFoundWithSuggestions(symbol, ctx, "members"))
      else
        val sections = defs.map { s =>
          val inheritResult = collectInheritedMembers(s, ctx)
          val inherited = inheritResult.inherited
          val parentKeys = inheritResult.parentMemberKeys
          val members = decorateMembers(extractMembers(s.file, simpleName, Some(s.kind)), parentKeys, s.file, simpleName, ctx)
          // Companion lookup (against unfiltered defs so --kind doesn't hide the companion)
          val companion = findCompanion(s, simpleName, typeDefs)
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
  }
