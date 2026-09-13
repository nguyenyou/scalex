package scalex.commands

import scalex.*

def cmdDeps(args: List[String], ctx: CommandContext): CmdResult =
  requireArg(args, "Usage: scalex deps <symbol> [--depth N]") { symbol =>
    val depth = (if (ctx.hierarchy.maxDepth < 0) 1 else ctx.hierarchy.maxDepth).max(1).min(5)
    val (importDeps, bodyDeps) = extractDeps(ctx.idx, symbol, ctx.workspace, maxDepth = depth)
    if (importDeps.isEmpty && bodyDeps.isEmpty)
      CmdResult.NotFound(s"""No dependencies found for "$symbol"""", mkNotFoundWithSuggestions(symbol, ctx, "deps"))
    else
      CmdResult.Dependencies(symbol, importDeps, bodyDeps)
  }
