def cmdFile(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex file <query>")
    case Some(query) =>
      val results = ctx.idx.searchFiles(query)
      CmdResult.StringList(
        header = s"""Found ${results.size} files matching "$query":""",
        items = results,
        total = results.size,
        emptyMessage = s"""Found 0 files matching "$query"\n  Hint: scalex indexes ${ctx.idx.fileCount} git-tracked .scala files.""")
