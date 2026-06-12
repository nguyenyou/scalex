def cmdFile(args: List[String], ctx: CommandContext): CmdResult =
  requireArg(args, "Usage: scalex file <query>") { query =>
      val results = ctx.idx.searchFiles(query)
      CmdResult.StringList(
        header = s"""Found ${results.size} files matching "$query":""",
        items = results,
        emptyMessage = s"""Found 0 files matching "$query"\n  Hint: scalex indexes ${ctx.idx.fileCount} git-tracked .scala files.""")
  }
