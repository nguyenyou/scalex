def cmdTests(args: List[String], ctx: CommandContext): CmdResult =
  val nameFilter = args.headOption
  var filesToScan = ctx.idx.gitFiles.map(_.path).filter(f => isTestFile(f, ctx.workspace))
  ctx.pathFilter.foreach { p => filesToScan = filesToScan.filter(f => matchesPath(f, p, ctx.workspace)) }
  ctx.excludePath.foreach { p => filesToScan = filesToScan.filter(f => !matchesPath(f, p, ctx.workspace)) }
  val allSuites = filesToScan.flatMap(extractTests).map { suite =>
    nameFilter match
      case Some(pattern) =>
        val lower = pattern.toLowerCase
        val filtered = suite.tests.filter(_.name.toLowerCase.contains(lower))
        suite.copy(tests = filtered)
      case None => suite
  }.filter(_.tests.nonEmpty)
  val showBody = nameFilter.isDefined
  val suiteResults = allSuites.map { suite =>
    val tests = suite.tests.map { tc =>
      val body = if showBody || ctx.verbose then
        extractBody(suite.file, tc.name, Some(suite.name)).headOption
      else None
      TestCaseResult(tc.name, tc.line, body)
    }
    TestSuiteResult(suite.name, suite.file, suite.line, tests)
  }
  val emptyMsg = if nameFilter.isDefined then s"""No tests matching "${nameFilter.get}"""" else "No test suites found"
  CmdResult.TestSuites(suiteResults, showBody, emptyMsg)
