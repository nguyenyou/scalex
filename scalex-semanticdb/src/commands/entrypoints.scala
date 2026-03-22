// ── entrypoints command ─────────────────────────────────────────────────────

private val testFrameworkParents = Set(
  "munit/FunSuite#", "org/scalatest/FunSuite#", "org/scalatest/AnyFunSuite#",
  "org/scalatest/FlatSpec#", "org/scalatest/AnyFlatSpec#",
  "org/scalatest/WordSpec#", "org/scalatest/AnyWordSpec#",
  "utest/TestSuite#", "zio/test/ZIOSpecDefault#",
)

/** Exact FQNs for App-like traits. */
private val appParents = Set(
  "scala/App#",
  "scala/App.",
)

/** Exact annotation FQNs for @main. */
private val mainAnnotations = Set(
  "scala/main#",
)

def cmdEntrypoints(args: List[String], ctx: SemCommandContext): SemCmdResult =
  val entries = scala.collection.mutable.ListBuffer.empty[SemSymbol]

  ctx.index.symbolByFqn.values.foreach { sym =>
    // @main annotation (exact FQN match)
    if sym.annotations.exists(a => mainAnnotations.contains(a)) then
      entries += sym
    // def main(...) in objects
    else if sym.displayName == "main" && sym.kind == SemKind.Method then
      val owner = ctx.index.symbolByFqn.get(sym.owner)
      if owner.exists(_.kind == SemKind.Object) then
        entries += sym
    // extends App (exact FQN match, not substring)
    else if sym.kind == SemKind.Object && sym.parents.exists(appParents.contains) then
      entries += sym
    // test suites
    else if sym.kind == SemKind.Class && sym.parents.exists(testFrameworkParents.contains) then
      entries += sym
  }

  val sorted = entries.toList.sortBy(s => (s.sourceUri, s.displayName))
  val limited = sorted.take(ctx.limit)
  SemCmdResult.SymbolList(s"${entries.size} entrypoints", limited, entries.size)
