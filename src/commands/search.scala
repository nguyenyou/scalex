def cmdSearch(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex search <query>")
    case Some(query) =>
      var results = ctx.idx.search(query)
      ctx.searchMode.foreach {
        case "exact" =>
          val lower = query.toLowerCase
          results = results.filter(_.name.toLowerCase == lower)
        case "prefix" =>
          val lower = query.toLowerCase
          results = results.filter(_.name.toLowerCase.startsWith(lower))
        case _ => ()
      }
      if ctx.definitionsOnly then
        val defKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
        results = results.filter(s => defKinds.contains(s.kind))
      // Filter by return type: check if signature ends with ": <Type>" or "]: <Type>"
      ctx.returnsFilter.foreach { rt =>
        results = results.filter { s =>
          val sig = s.signature
          sig.nonEmpty && {
            // Match ": Type" or "]: Type" patterns near the end of the signature
            val colonIdx = sig.lastIndexOf(':')
            colonIdx >= 0 && sig.substring(colonIdx + 1).trim.toLowerCase.contains(rt.toLowerCase)
          }
        }
      }
      // Filter by parameter type: check if signature's parameter section contains the type
      ctx.takesFilter.foreach { tt =>
        results = results.filter { s =>
          val sig = s.signature
          sig.nonEmpty && {
            val parenStart = sig.indexOf('(')
            val parenEnd = sig.lastIndexOf(')')
            parenStart >= 0 && parenEnd > parenStart &&
              sig.substring(parenStart, parenEnd + 1).toLowerCase.contains(tt.toLowerCase)
          }
        }
      }
      results = filterSymbols(results, ctx)
      if results.isEmpty then
        CmdResult.NotFound(
          s"""Found 0 symbols matching "$query"""",
          mkNotFoundWithSuggestions(query, ctx, "search"))
      else
        CmdResult.SymbolList(
          header = s"""Found ${results.size} symbols matching "$query":""",
          symbols = results,
          total = results.size)
