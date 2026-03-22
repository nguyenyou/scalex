// ── coverage command ────────────────────────────────────────────────────────

private val testPathPatterns = List("/test/", "/tests/", "/spec/", "/specs/", "Test.scala", "Spec.scala", "Suite.scala")

def cmdCoverage(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: coverage <symbol>")
    case query :: _ =>
      val symbols = ctx.index.resolveSymbol(query)
      if symbols.isEmpty then
        return SemCmdResult.NotFound(s"No symbol found matching '$query'")

      val fqns = symbols.map(_.fqn).toSet
      val allOccs = fqns.toList.flatMap(fqn =>
        ctx.index.occurrencesBySymbol.getOrElse(fqn, Nil)
      ).filter(_.role == OccRole.Reference)

      val testOccs = allOccs.filter(o => testPathPatterns.exists(o.file.contains(_)))
      val testFiles = testOccs.map(_.file).distinct.sorted

      val name = symbols.head.displayName
      SemCmdResult.CoverageResult(name, allOccs.size, testOccs.size, testFiles)
