// ── summary command ─────────────────────────────────────────────────────────

def cmdSummary(args: List[String], ctx: SemCommandContext): SemCmdResult =
  val filterPkg = args.headOption.map(_.toLowerCase)

  val counts = ctx.index.symbolsByPackage.toList
    .filter { (pkg, _) =>
      filterPkg.forall(f => pkg.toLowerCase.contains(f))
    }
    .map { (pkg, syms) =>
      val meaningful = syms.count(s =>
        s.kind != SemKind.Parameter && s.kind != SemKind.TypeParam &&
        s.kind != SemKind.Local && s.kind != SemKind.Constructor
      )
      (pkg, meaningful)
    }
    .filter(_._2 > 0)
    .sortBy(-_._2)

  val limited = counts.take(ctx.limit)
  SemCmdResult.SummaryList(s"${counts.size} packages", limited, counts.size)
