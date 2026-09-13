package scalex.commands

import scalex.*

def cmdSymbols(args: List[String], ctx: CommandContext): CmdResult =
  requireArg(args, "Usage: scalex symbols <file>") { file =>
    val results = ctx.idx.fileSymbols(file)
    if (ctx.overview.summaryMode)
      CmdResult.SymbolSummary(file, countByKind(results), results.size)
    else
      CmdResult.SymbolList(
        header = s"Symbols in $file:",
        symbols = results,
        emptyMessage = s"No symbols found in $file",
        truncate = false
      )
  }
