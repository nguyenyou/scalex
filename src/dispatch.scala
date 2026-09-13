package scalex

import scalex.index.*
import scalex.output.*

import clibase.OutputBudget

// ── Command dispatch ────────────────────────────────────────────────────────

def runCommand(cmd: String, args: List[String], ctx: CommandContext): Int = {
  val result = try {
    commandHandlers.get(cmd) match {
      case Some(handler) => handler(args, ctx)
      case None          => CmdResult.UsageError(s"Unknown command: $cmd")
    }
  } catch { case e: GitFailure => CmdResult.Failure(e.getMessage) }
  renderWithBudget(result, ctx)
  commandStatus(result)
}

private[scalex] def commandStatus(result: CmdResult): Int = {
  result match {
    case _: CmdResult.UsageError             => 2
    case _: CmdResult.Failure                => 1
    case CmdResult.WithDiagnostics(inner, _) => commandStatus(inner)
    case _                                   => 0
  }
}

/** Budget JSON as a whole document, never as a prefix of serialized bytes. */
def renderWithBudget(result: CmdResult, ctx: CommandContext): Unit = {
  if (ctx.output.maxOutput > 0 && ctx.output.jsonOutput) {
    val captured = OutputBudget.capture(ctx.output.maxOutput) { render(result, ctx) }
    if (captured.exceeded) {
      println(
        s"""{"truncated":true,"maxOutput":${ctx.output.maxOutput},"hint":"Use --limit, --offset, --path, or --in-package to narrow"}"""
      )
    } else { print(captured.text) }
  } else if (ctx.output.maxOutput > 0) {
    OutputBudget.run(
      ctx.output.maxOutput,
      s"(output truncated at ${ctx.output.maxOutput} chars — use --limit, --offset, --path, or --in-package to narrow)"
    ) {
      render(result, ctx)
    }
  } else { render(result, ctx) }
}
