import munit.FunSuite
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class AnalysisSuite extends ScalexTestBase:

  // ── hierarchy command — buildHierarchy ────────────────────────────────

  test("buildHierarchy returns root node with correct name") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val result = buildHierarchy(idx, "UserServiceLive", goUp = true, goDown = true, workspace)
    assert(result.isDefined, "Should find hierarchy for UserServiceLive")
    val tree = result.get
    assertEquals(tree.root.name, "UserServiceLive")
    assert(!tree.root.isExternal, "Root should not be external")
    assert(tree.root.kind.contains(SymbolKind.Class), s"Should be a class: ${tree.root.kind}")
    assert(tree.root.file.isDefined, "Root should have a file")
    assert(tree.root.line.isDefined, "Root should have a line")
  }

  test("buildHierarchy returns root for UserService trait") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val result = buildHierarchy(idx, "UserService", goUp = true, goDown = true, workspace)
    assert(result.isDefined, "Should find hierarchy for UserService")
    val tree = result.get
    // UserService resolves to the trait (first hit)
    assertEquals(tree.root.name, "UserService")
  }

  test("buildHierarchy --down finds children (regression #80)") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val result = buildHierarchy(idx, "UserService", goUp = false, goDown = true, workspace)
    assert(result.isDefined, "Should find hierarchy for UserService")
    val tree = result.get
    val childNames = tree.children.map(_.root.name).toSet
    assert(childNames.contains("UserServiceLive"),
      s"Should find UserServiceLive as child: $childNames")
    assert(childNames.contains("OldService"),
      s"Should find OldService as child: $childNames")
  }

  test("buildHierarchy --up finds parents (regression #80)") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val result = buildHierarchy(idx, "UserServiceLive", goUp = true, goDown = false, workspace)
    assert(result.isDefined, "Should find hierarchy for UserServiceLive")
    val tree = result.get
    val parentNames = tree.parents.map(_.root.name).toSet
    assert(parentNames.contains("UserService"),
      s"Should find UserService as parent: $parentNames")
  }

  test("buildHierarchy goUp=false produces empty parents") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val result = buildHierarchy(idx, "UserServiceLive", goUp = false, goDown = false, workspace)
    assert(result.isDefined, "Should find hierarchy")
    val tree = result.get
    assert(tree.parents.isEmpty, "Should have no parents with goUp=false")
    assert(tree.children.isEmpty, "Should have no children with goDown=false")
  }

  test("buildHierarchy returns None for unknown symbol") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val result = buildHierarchy(idx, "NonExistentType", goUp = true, goDown = true, workspace)
    assert(result.isEmpty, "Should return None for unknown symbol")
  }

  test("buildHierarchy root node isExternal is false for indexed symbols") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val result = buildHierarchy(idx, "PaymentServiceLive", goUp = true, goDown = false, workspace)
    assert(result.isDefined)
    val tree = result.get
    assert(!tree.root.isExternal, "Root should not be external")
    assert(tree.root.packageName == "com.example", s"Package should be com.example: ${tree.root.packageName}")
  }

  // ── overrides command — findOverrides ────────────────────────────────

  test("findOverrides finds method overrides with --of trait") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = findOverrides(idx, "findUser", Some("UserService"), 50)
    assert(results.nonEmpty, "Should find overrides of findUser in UserService impls")
    assert(results.exists(_.enclosingClass == "UserServiceLive"),
      s"Should find override in UserServiceLive: ${results.map(_.enclosingClass)}")
  }

  test("findOverrides returns empty for nonexistent method") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = findOverrides(idx, "nonExistentMethod", Some("UserService"), 50)
    assert(results.isEmpty, "Should return empty for nonexistent method")
  }

  test("findOverrides returns correct enclosing class info") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = findOverrides(idx, "findUser", Some("UserService"), 50)
    results.foreach { r =>
      assert(r.enclosingClass.nonEmpty, s"Enclosing class should not be empty")
      assert(r.signature.nonEmpty, s"Signature should not be empty: ${r.enclosingClass}")
      assert(r.line > 0, s"Line should be positive: ${r.enclosingClass}")
    }
  }

  // ── explain command — constituent calls ───────────────────────────────

  test("explain ranks class/trait above val/object (regression #80)") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // UserService has trait + object + val references — explain should pick the trait
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("explain", List("UserService"), CommandContext(idx = idx, workspace = workspace))
    }
    val output = out.toString
    assert(output.contains("trait UserService"),
      s"explain should pick trait UserService, not val/object: $output")
  }

  test("explain: constituent calls work together for PaymentService") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // findDefinition
    val defs = idx.findDefinition("PaymentService")
    assert(defs.nonEmpty, "Should find PaymentService definition")
    val sym = defs.head
    // extractScaladoc
    val doc = extractScaladoc(sym.file, sym.line)
    assert(doc.isDefined, "Should find scaladoc for PaymentService")
    assert(doc.get.contains("processing payments"))
    // extractMembers
    val members = extractMembers(sym.file, sym.name)
    assert(members.nonEmpty, "Should find members of PaymentService")
    assert(members.exists(_.name == "processPayment"))
    // findImplementations
    val impls = idx.findImplementations("PaymentService")
    assert(impls.nonEmpty, "Should find implementations of PaymentService")
    assert(impls.exists(_.name == "PaymentServiceLive"),
      s"Should find PaymentServiceLive: ${impls.map(_.name)}")
  }

  // ── deps command — extractDeps ────────────────────────────────────────

  test("extractDeps finds import deps for a symbol") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val (importDeps, _) = extractDeps(idx, "ExplicitClient", workspace)
    // ExplicitClient imports com.example.UserService
    assert(importDeps.exists(_.name == "UserService"),
      s"Should find UserService in import deps: ${importDeps.map(_.name)}")
  }

  test("extractDeps finds body refs (type names used in the body)") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val (importDeps, bodyDeps) = extractDeps(idx, "ExplicitClient", workspace)
    // The body of ExplicitClient uses UserService as a type
    val allNames = (importDeps ++ bodyDeps).map(_.name).toSet
    assert(allNames.contains("UserService"),
      s"Should find UserService in deps: imports=${importDeps.map(_.name)}, body=${bodyDeps.map(_.name)}")
  }

  // ── context command — extractScopes ────────────────────────────────────

  test("extractScopes finds enclosing package + class + def for a line inside a method") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    // Line 9: "def findUser(id: String): Option[User] = db.query(id)" inside UserServiceLive
    val scopes = extractScopes(file, 9)
    assert(scopes.nonEmpty, s"Should find scopes for line 9")
    val scopeNames = scopes.map(_.name)
    val scopeKinds = scopes.map(_.kind)
    assert(scopeKinds.contains("package"), s"Should contain package scope: $scopeKinds")
    assert(scopeNames.exists(_ == "UserServiceLive"), s"Should contain UserServiceLive: $scopeNames")
    assert(scopeNames.exists(_ == "findUser"), s"Should contain findUser: $scopeNames")
  }

  test("extractScopes returns empty for out-of-range line") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    val scopes = extractScopes(file, 999)
    assert(scopes.isEmpty, s"Should return empty for out-of-range line: ${scopes.map(_.name)}")
  }

  // ── diff command — extractSymbolsFromSource ────────────────────────────

  test("extractSymbolsFromSource extracts symbols from source string") {
    val source =
      """package com.example
        |
        |class Foo {
        |  def bar: Int = 42
        |  val baz: String = "hello"
        |}
        |
        |trait Qux {
        |  def quux: Boolean = true
        |}
        |""".stripMargin
    val symbols = extractSymbolsFromSource(source, "test.scala")
    val names = symbols.map(_.name).toSet
    assert(names.contains("Foo"), s"Should find class Foo: $names")
    assert(names.contains("bar"), s"Should find def bar: $names")
    assert(names.contains("baz"), s"Should find val baz: $names")
    assert(names.contains("Qux"), s"Should find trait Qux: $names")
    assert(names.contains("quux"), s"Should find def quux: $names")
  }

  test("extractSymbolsFromSource basic symbol comparison") {
    val oldSource =
      """package com.example
        |class Foo {
        |  def bar: Int = 42
        |}
        |""".stripMargin
    val newSource =
      """package com.example
        |class Foo {
        |  def bar: Int = 42
        |  def baz: String = "new"
        |}
        |""".stripMargin
    val oldSyms = extractSymbolsFromSource(oldSource, "Foo.scala")
    val newSyms = extractSymbolsFromSource(newSource, "Foo.scala")
    val oldNames = oldSyms.map(_.name).toSet
    val newNames = newSyms.map(_.name).toSet
    val added = newNames -- oldNames
    assert(added.contains("baz"), s"Should detect baz as added: $added")
    assert(!added.contains("bar"), s"bar should not be added: $added")
  }

  // ── ast-pattern — astPatternSearch ────────────────────────────────────

  test("astPatternSearch with --extends filter finds PaymentServiceLive") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = astPatternSearch(idx, workspace,
      hasMethod = None, extendsTrait = Some("PaymentService"),
      bodyContains = None, noTests = false, pathFilter = None, limit = 50)
    assert(results.nonEmpty, "Should find types extending PaymentService")
    assert(results.exists(_.name == "PaymentServiceLive"),
      s"Should find PaymentServiceLive: ${results.map(_.name)}")
  }

  test("astPatternSearch with --has-method filter finds types containing findUser") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = astPatternSearch(idx, workspace,
      hasMethod = Some("findUser"), extendsTrait = None,
      bodyContains = None, noTests = false, pathFilter = None, limit = 50)
    assert(results.nonEmpty, "Should find types with findUser method")
    val names = results.map(_.name).toSet
    assert(names.contains("UserServiceLive"), s"Should find UserServiceLive: $names")
    assert(names.contains("UserService"), s"Should find UserService trait: $names")
  }

  // ── Test awareness: coverage ─────────────────────────────────────────────

  test("coverage finds refs only in test files") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("coverage", List("UserService"), CommandContext(idx = idx, workspace = workspace))
    }
    val output = out.toString
    assert(output.contains("Coverage of"), s"Should show coverage header: $output")
    assert(output.contains("test"), s"Should mention test files: $output")
  }

  test("coverage excludes non-test file refs") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val refs = idx.findReferences("UserService")
    val allFiles = refs.map(r => workspace.relativize(r.file).toString).distinct
    val testFiles = refs.filter(r => isTestFile(r.file, workspace)).map(r => workspace.relativize(r.file).toString).distinct
    // There should be refs in non-test files too
    assert(allFiles.size > testFiles.size, s"Should have non-test refs too: all=$allFiles test=$testFiles")
  }

  test("coverage --json output") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("coverage", List("UserService"), CommandContext(idx = idx, workspace = workspace, jsonOutput = true))
    }
    val output = out.toString.trim
    assert(output.startsWith("{"), s"JSON should start with brace: $output")
    assert(output.contains("\"symbol\":\"UserService\""), s"Should contain symbol: $output")
    assert(output.contains("\"testFileCount\""), s"Should contain testFileCount: $output")
    assert(output.contains("\"referenceCount\""), s"Should contain referenceCount: $output")
  }
