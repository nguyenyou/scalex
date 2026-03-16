import java.nio.file.Path

def cmdContext(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex context <file:line>")
    case Some(arg) =>
      val parts = arg.split(":")
      if parts.length < 2 then
        CmdResult.UsageError("Usage: scalex context <file:line> (e.g. src/Main.scala:42)")
      else
        val filePath = parts.dropRight(1).mkString(":")
        val lineNum = parts.last.toIntOption
        lineNum match
          case None => CmdResult.UsageError(s"Invalid line number: ${parts.last}")
          case Some(line) =>
            val resolved = if Path.of(filePath).isAbsolute then Path.of(filePath) else ctx.workspace.resolve(filePath)
            val scopes = extractScopes(resolved, line)
            CmdResult.Scopes(resolved, line, scopes)
