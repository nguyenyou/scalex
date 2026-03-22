// ── packages command ────────────────────────────────────────────────────────

def cmdPackages(args: List[String], ctx: SemCommandContext): SemCmdResult =
  val pkgs = ctx.index.packages.toList.sorted
  val limited = pkgs.take(ctx.limit)
  SemCmdResult.PackageList(s"${pkgs.size} packages", limited, pkgs.size)
