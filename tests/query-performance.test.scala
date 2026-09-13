package scalex

import scalex.index.WorkspaceIndex

class QueryPerformanceSuite extends ScalexTestBase {
  test("isolated and reusable name lookups preserve results and ordering") {
    WorkspaceIndex.load(workspace)
    val shared = WorkspaceIndex.load(workspace)
    val isolated = WorkspaceIndex.load(workspace, reuseNameIndex = false)
    val queries = shared.symbols.flatMap { symbol =>
      List(symbol.name, symbol.name.toUpperCase, s"${symbol.packageName}.${symbol.name}")
    } ++ List("example.UserService", "UserService.default", "NoSuchOwner.member", "missing")
    queries.distinct.foreach { query =>
      assertEquals(isolated.findDefinition(query), shared.findDefinition(query), query)
      assertEquals(isolated.symbolsNamed(query), shared.symbolsNamed(query), query)
    }
    assertEquals(isolated.findImports("UserService").results.toSet, shared.findImports("UserService").results.toSet)
  }
}
