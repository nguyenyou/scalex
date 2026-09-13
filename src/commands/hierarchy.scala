package scalex.commands

import scalex.*

def cmdHierarchy(args: List[String], ctx: CommandContext): CmdResult =
  requireArg(args, "Usage: scalex hierarchy <symbol> [--up] [--down] [--depth N]") { symbol =>
    val depth = if (ctx.hierarchy.maxDepth < 0) 5 else ctx.hierarchy.maxDepth.max(1)
    buildHierarchy(ctx.idx, symbol, ctx.hierarchy.goUp, ctx.hierarchy.goDown, depth, ctx.workspace) match {
      case None =>
        CmdResult.NotFound(s"""No definition of "$symbol" found""", mkNotFoundWithSuggestions(symbol, ctx, "hierarchy"))
      case Some(tree) =>
        CmdResult.HierarchyResult(symbol, tree)
    }
  }
