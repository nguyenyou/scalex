package scalex.commands

import scalex.*

def cmdOverrides(args: List[String], ctx: CommandContext): CmdResult =
  requireArg(args, "Usage: scalex overrides <method> [--of <trait>]") { methodName =>
    var results = findOverrides(ctx.idx, methodName, ctx.hierarchy.ofTrait, ctx.output.limit)
    if (ctx.members.withBody)
      results = results.map { o =>
        bodyWithinLimit(o.file, methodName, Some(o.enclosingClass), ctx.members.maxBodyLines) match {
          case Some(b) => o.copy(body = Some(b))
          case None    => o
        }
      }
    if (results.isEmpty) {
      val ofStr = ctx.hierarchy.ofTrait.map(t => s" of $t").getOrElse("")
      CmdResult.NotFound(
        s"""No overrides of "$methodName"$ofStr found""",
        mkNotFoundWithSuggestions(methodName, ctx, "overrides")
      )
    } else {
      val ofStr = ctx.hierarchy.ofTrait.map(t => s" (in implementations of $t)").getOrElse("")
      CmdResult.OverrideList(header = s"Overrides of $methodName$ofStr — ${results.size} found:", results = results)
    }
  }
