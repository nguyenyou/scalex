// ── overview command ────────────────────────────────────────────────────────

def cmdOverview(args: List[String], ctx: SemCommandContext): SemCmdResult =
  val idx = ctx.index
  val entries = scala.collection.mutable.ListBuffer.empty[(String, Int)]

  // File count
  entries += ("Files" -> idx.fileCount)

  // Symbol counts by kind
  val byKind = idx.symbolByFqn.values
    .filter(s => s.kind != SemKind.Parameter && s.kind != SemKind.TypeParam && s.kind != SemKind.Local)
    .groupBy(_.kind)
    .map((k, syms) => (k.toString, syms.size))
    .toList
    .sortBy(-_._2)
  entries ++= byKind

  // Top packages by symbol count
  val topPkgs = idx.symbolsByPackage.toList
    .map((pkg, syms) => (s"pkg: $pkg", syms.size))
    .sortBy(-_._2)
    .take(10)
  entries ++= topPkgs

  // Most-extended types
  val topExtended = idx.subtypeIndex.toList
    .map((parent, children) => (s"extended: $parent", children.size))
    .sortBy(-_._2)
    .take(5)
  entries ++= topExtended

  SemCmdResult.SummaryList(s"Overview", entries.toList, entries.size)
