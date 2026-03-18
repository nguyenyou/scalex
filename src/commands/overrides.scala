def cmdOverrides(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex overrides <method> [--of <trait>]")
    case Some(methodName) =>
      var results = findOverrides(ctx.idx, methodName, ctx.ofTrait, ctx.limit)
      if ctx.withBody then
        results = results.map { o =>
          val bodies = extractBody(o.file, methodName, Some(o.enclosingClass))
          bodies.headOption match
            case Some(b) if ctx.maxBodyLines <= 0 || (b.endLine - b.startLine + 1) <= ctx.maxBodyLines =>
              o.copy(body = Some(b))
            case _ => o
        }
      if results.isEmpty then
        val ofStr = ctx.ofTrait.map(t => s" of $t").getOrElse("")
        CmdResult.NotFound(
          s"""No overrides of "$methodName"$ofStr found""",
          mkNotFoundWithSuggestions(methodName, ctx, "overrides"))
      else
        val ofStr = ctx.ofTrait.map(t => s" (in implementations of $t)").getOrElse("")
        CmdResult.OverrideList(
          header = s"Overrides of $methodName$ofStr — ${results.size} found:",
          results = results)
