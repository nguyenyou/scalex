package scalex.commands

import scalex.*

def cmdAstPattern(args: List[String], ctx: CommandContext): CmdResult = {
  val results = astPatternSearch(
    ctx.idx,
    ctx.workspace,
    ctx.ast.hasMethodFilter,
    ctx.ast.extendsFilter,
    ctx.ast.bodyContainsFilter,
    ctx.filters.noTests,
    ctx.filters.pathFilter,
    ctx.filters.excludePath,
    ctx.output.limit
  )
  val filters = List(
    ctx.ast.hasMethodFilter.map(m => s"has-method=$m"),
    ctx.ast.extendsFilter.map(e => s"extends=$e"),
    ctx.ast.bodyContainsFilter.map(b => s"""body-contains="$b"""")
  ).flatten.mkString(", ")
  CmdResult.AstMatches(filters, results)
}
