def cmdMembers(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex members <Symbol>")
    case Some(symbol) =>
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
            val m2 = if ctx.inherited && parentKeys.contains((name = m.name, kind = m.kind)) then m.copy(isOverride = true) else m
            if ctx.withBody then enrichMemberWithBody(m2, s.file, symbol, ctx.maxBodyLines) else m2
          }
          // Companion lookup
          val companion = findCompanion(s, symbol, ctx.idx.findDefinition(symbol).filter(d => typeKinds.contains(d.kind)))
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
