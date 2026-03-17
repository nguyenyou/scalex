def cmdExplain(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex explain <symbol>")
    case Some(symbol) =>
      var defs = ctx.idx.findDefinition(symbol)
      if ctx.noTests then defs = defs.filter(s => !isTestFile(s.file, ctx.workspace))
      ctx.pathFilter.foreach { p => defs = defs.filter(s => matchesPath(s.file, p, ctx.workspace)) }
      ctx.excludePath.foreach { p => defs = defs.filter(s => !matchesPath(s.file, p, ctx.workspace)) }
      // Rank: class/trait/object/enum first (same as def command)
      defs = defs.sortBy { s =>
        val kindRank = s.kind match
          case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Object | SymbolKind.Enum => 0
          case SymbolKind.Type | SymbolKind.Given => 1
          case _ => 2
        val testRank = if isTestFile(s.file, ctx.workspace) then 1 else 0
        val pathLen = ctx.workspace.relativize(s.file).toString.length
        (kindRank, testRank, pathLen)
      }
      // If no results and symbol contains ".", try Owner.member resolution
      if defs.isEmpty && symbol.contains(".") then
        resolveDottedMember(symbol, ctx) match
          case Some(memberResults) =>
            val msym = memberResults.head
            val doc = extractScaladoc(msym.file, msym.line)
            return CmdResult.Explanation(msym, doc, Nil, Nil, Nil)
          case None => ()
      if defs.isEmpty then
        // Fuzzy fallback: try search and auto-show best match if unambiguous
        val typeKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
        var fuzzyResults = ctx.idx.search(symbol).filter(s => typeKinds.contains(s.kind))
        if ctx.noTests then fuzzyResults = fuzzyResults.filter(s => !isTestFile(s.file, ctx.workspace))
        ctx.pathFilter.foreach { p => fuzzyResults = fuzzyResults.filter(s => matchesPath(s.file, p, ctx.workspace)) }
        ctx.excludePath.foreach { p => fuzzyResults = fuzzyResults.filter(s => !matchesPath(s.file, p, ctx.workspace)) }
        // Auto-use if exactly one strong match by name (exact case-insensitive, prefix, or suffix)
        val lower = symbol.toLowerCase
        val strongMatches = fuzzyResults.filter { s =>
          val nl = s.name.toLowerCase
          nl == lower || nl.startsWith(lower) || lower.startsWith(nl) || lower.endsWith(nl)
        }
        val distinctNames = strongMatches.map(_.name.toLowerCase).distinct
        if distinctNames.size == 1 then
          val bestMatch = strongMatches.head
          Console.err.println(s"""(no exact match for "$symbol" — showing ${bestMatch.name} instead)""")
          defs = strongMatches.toList
        end if
      if defs.isEmpty then
        // Package fallback: if symbol matches a package name, delegate to summary
        val lower = symbol.toLowerCase
        val pkgMatch = ctx.idx.packages.find(_.equalsIgnoreCase(symbol))
          .orElse(ctx.idx.packages.find(_.toLowerCase.endsWith("." + lower)))
        pkgMatch match
          case Some(pkg) =>
            Console.err.println(s"""(no type "$symbol" found — showing package summary instead)""")
            cmdSummary(List(symbol), ctx)
          case None =>
            CmdResult.NotFound(
              s"""No definition of "$symbol" found""",
              mkNotFoundWithSuggestions(symbol, ctx, "explain"))
      else
        val sym = defs.head
        // Deduplicate by (name, package): trait+companion = 1 match, cross-package = distinct (see #8bd6b57)
        val distinctByNamePkg = defs.map(s => (name = s.name.toLowerCase, pkg = s.packageName)).distinct.size
        val otherMatches = distinctByNamePkg - 1
        // For qualified lookups, use the simple name for member/impl queries
        val simpleName = if symbol.contains(".") then symbol.substring(symbol.lastIndexOf('.') + 1) else symbol
        // Scaladoc
        val doc = extractScaladoc(sym.file, sym.line)
        // Members (for types)
        val typeKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
        val inheritResult = if typeKinds.contains(sym.kind) then collectInheritedMembers(sym, ctx)
          else (inherited = Nil: List[(parentName: String, parentFile: Option[java.nio.file.Path], parentPackage: String, members: List[MemberInfo])], parentMemberKeys = Set.empty[(name: String, kind: SymbolKind)])
        val inherited = inheritResult.inherited
        val parentKeys = inheritResult.parentMemberKeys
        val members = if typeKinds.contains(sym.kind) then
          extractMembers(sym.file, simpleName).map { m =>
            if ctx.inherited && parentKeys.contains((name = m.name, kind = m.kind)) then m.copy(isOverride = true) else m
          }.sortBy(memberKindRank).take(ctx.membersLimit)
        else Nil
        // Companion lookup
        val companionKinds: Set[SymbolKind] = sym.kind match
          case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Enum => Set(SymbolKind.Object)
          case SymbolKind.Object => Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Enum)
          case _ => Set.empty
        val companion: Option[(sym: SymbolInfo, members: List[MemberInfo])] =
          if companionKinds.isEmpty then None
          else
            defs.find(d => companionKinds.contains(d.kind) && d.packageName == sym.packageName && d.file == sym.file)
              .map { compSym =>
                val compMembers = extractMembers(compSym.file, simpleName).sortBy(memberKindRank).take(ctx.membersLimit)
                (sym = compSym, members = compMembers)
              }
        if ctx.shallow then
          // Shallow mode: definition + members + companion only
          CmdResult.Explanation(sym, doc, members, Nil, Nil, companion, Nil, otherMatches = otherMatches)
        else
          // Implementations
          val allImpls = filterSymbols(ctx.idx.findImplementations(simpleName), ctx)
          val totalImpls = allImpls.size
          val impls = allImpls.take(ctx.implLimit)
          // Expanded implementations
          val expandedImpls =
            if ctx.expandDepth > 0 then expandImpls(impls, ctx, 1, Set(s"${sym.packageName}.${sym.name}".toLowerCase))
            else Nil
          // Import refs (apply path/exclude/noTests filters)
          val importRefs = filterRefs(ctx.idx.findImports(simpleName, timeoutMs = 3000), ctx)
          CmdResult.Explanation(sym, doc, members, impls, importRefs, companion, expandedImpls,
            otherMatches = otherMatches, totalImpls = totalImpls, inherited = inherited)

private def expandImpls(impls: List[SymbolInfo], ctx: CommandContext,
                        depth: Int, visited: Set[String]): List[ExplainedImpl] =
  if depth > ctx.expandDepth then Nil
  else
    val typeKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
    impls.filter(s => typeKinds.contains(s.kind)).take(ctx.implLimit).map { impl =>
      val key = s"${impl.packageName}.${impl.name}".toLowerCase
      if visited.contains(key) then ExplainedImpl(impl, Nil, Nil)
      else
        val members = extractMembers(impl.file, impl.name).sortBy(memberKindRank).take(ctx.membersLimit)
        val subImpls = filterSymbols(ctx.idx.findImplementations(impl.name), ctx).take(ctx.implLimit)
        val expanded = expandImpls(subImpls, ctx, depth + 1, visited + key)
        ExplainedImpl(impl, members, expanded)
    }

private def memberKindRank(m: MemberInfo): Int = m.kind match
  case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Object | SymbolKind.Enum => 0
  case SymbolKind.Def => 1
  case SymbolKind.Val | SymbolKind.Var => 2
  case SymbolKind.Type => 3
  case _ => 4
