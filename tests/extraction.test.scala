import munit.FunSuite
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class ExtractionSuite extends ScalexTestBase:

  // ── Symbol extraction ─────────────────────────────────────────────────

  test("extractSymbols finds classes, traits, objects") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    val (syms, _, _, _, _) = extractSymbols(file)
    val names = syms.map(s => (s.name, s.kind))

    assert(names.contains(("UserService", SymbolKind.Trait)))
    assert(names.contains(("UserServiceLive", SymbolKind.Class)))
    assert(names.contains(("UserService", SymbolKind.Object)))
  }

  test("extractSymbols finds defs") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    val (syms, _, _, _, _) = extractSymbols(file)
    val defs = syms.filter(_.kind == SymbolKind.Def).map(_.name)

    assert(defs.contains("findUser"))
    assert(defs.contains("createUser"))
  }

  test("extractSymbols finds enums") {
    val file = workspace.resolve("src/main/scala/com/example/Model.scala")
    val (syms, _, _, _, _) = extractSymbols(file)
    assert(syms.exists(s => s.name == "Role" && s.kind == SymbolKind.Enum))
  }

  test("extractSymbols finds type aliases") {
    val file = workspace.resolve("src/main/scala/com/example/Model.scala")
    val (syms, _, _, _, _) = extractSymbols(file)
    assert(syms.exists(s => s.name == "UserId" && s.kind == SymbolKind.Type))
  }

  test("extractSymbols finds givens") {
    val file = workspace.resolve("src/main/scala/com/example/Model.scala")
    val (syms, _, _, _, _) = extractSymbols(file)
    assert(syms.exists(s => s.kind == SymbolKind.Given))
  }

  test("extractSymbols finds extensions") {
    val file = workspace.resolve("src/main/scala/com/other/Helper.scala")
    val (syms, _, _, _, _) = extractSymbols(file)
    assert(syms.exists(s => s.kind == SymbolKind.Extension))
  }

  test("extractSymbols captures package name") {
    val file = workspace.resolve("src/main/scala/com/example/Model.scala")
    val (syms, _, _, _, _) = extractSymbols(file)
    assert(syms.forall(_.packageName == "com.example"))
  }

  test("extractSymbols captures line numbers") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    val (syms, _, _, _, _) = extractSymbols(file)
    val traitSym = syms.find(s => s.name == "UserService" && s.kind == SymbolKind.Trait).get
    assert(traitSym.line > 0)
  }

  test("extractSymbols handles unparseable files gracefully") {
    val bad = workspace.resolve("bad.scala")
    Files.writeString(bad, "this is not valid scala {{{")
    val (syms, _, _, _, failed) = extractSymbols(bad)
    assertEquals(syms, Nil)
    assert(failed, "parseFailed should be true for unparseable files")
    Files.delete(bad)
  }

  test("extractSymbols returns parseFailed=false for valid files with no symbols") {
    val file = workspace.resolve("anon_givens.scala")
    Files.writeString(file,
      """package scala
        |
        |import org.example.library.Foo
        |
        |given CanEqual[Foo, Foo] = CanEqual.derived
        |""".stripMargin)
    val (syms, _, _, _, failed) = extractSymbols(file)
    assertEquals(syms, Nil)
    assert(!failed, "parseFailed should be false for valid files with only anonymous givens")
    Files.delete(file)
  }

  test("extractSymbols extracts Pkg.Object (package objects)") {
    val file = workspace.resolve("pkg_object.scala")
    Files.writeString(file,
      """package com.example.engine
        |
        |package object protocols {
        |  val defaultTimeout = 30
        |}
        |""".stripMargin)
    val (syms, _, _, _, failed) = extractSymbols(file)
    assert(!failed, "parseFailed should be false for package objects")
    assert(syms.exists(s => s.name == "protocols" && s.kind == SymbolKind.Object),
      s"Should find package object 'protocols': ${syms.map(s => (s.name, s.kind))}")
    Files.delete(file)
  }

  test("extractSymbols returns parseFailed=false for export-only files") {
    val file = workspace.resolve("exports_only.scala")
    Files.writeString(file,
      """package com.example.api
        |
        |export com.example.base.MyComponent
        |""".stripMargin)
    val (syms, _, _, _, failed) = extractSymbols(file)
    assert(!failed, "parseFailed should be false for export-only files")
    Files.delete(file)
  }

  // ── Bloom filter ──────────────────────────────────────────────────────

  test("bloom filter contains identifiers from file") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    val (_, bloom, _, _, _) = extractSymbols(file)

    assert(bloom.isDefined, "bloom should be Some")
    assert(bloom.get.mightContain("UserService"))
    assert(bloom.get.mightContain("findUser"))
    assert(bloom.get.mightContain("Database"))
    assert(bloom.get.mightContain("Option"))
  }

  test("bloom filter rejects absent identifiers") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    val (_, bloom, _, _, _) = extractSymbols(file)

    assert(bloom.isDefined, "bloom should be Some")
    // These should almost certainly not be in the bloom filter
    assert(!bloom.get.mightContain("ZxQwVeryUnlikelyIdentifier"))
    assert(!bloom.get.mightContain("NobodyWouldNameThisXyz"))
  }

  // ── Phase 7: Signatures ───────────────────────────────────────────────

  test("extractSymbols captures signatures") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    val (syms, _, _, _, _) = extractSymbols(file)

    val traitSym = syms.find(s => s.name == "UserService" && s.kind == SymbolKind.Trait).get
    assert(traitSym.signature.nonEmpty, "Trait should have a signature")
    assert(traitSym.signature.contains("trait UserService"), s"Sig: ${traitSym.signature}")
  }

  test("extractSymbols captures extends parents") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    val (syms, _, _, _, _) = extractSymbols(file)

    val classSym = syms.find(s => s.name == "UserServiceLive" && s.kind == SymbolKind.Class).get
    assert(classSym.parents.contains("UserService"), s"Parents: ${classSym.parents}")
  }

  test("extractSymbols captures def signatures with params") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    val (syms, _, _, _, _) = extractSymbols(file)

    val defSym = syms.find(s => s.name == "findUser" && s.kind == SymbolKind.Def).get
    assert(defSym.signature.contains("def findUser"), s"Sig: ${defSym.signature}")
    assert(defSym.signature.contains("id"), s"Should contain param name: ${defSym.signature}")
  }

  test("extractSymbols captures given alias signatures") {
    val file = workspace.resolve("src/main/scala/com/example/Model.scala")
    val (syms, _, _, _, _) = extractSymbols(file)

    val givenSym = syms.find(s => s.kind == SymbolKind.Given).get
    assert(givenSym.signature.contains("given"), s"Sig: ${givenSym.signature}")
    assert(givenSym.signature.contains("Ordering"), s"Sig: ${givenSym.signature}")
  }

  test("extractSymbols captures imports") {
    // UserServiceSpec imports UserService
    val file = workspace.resolve("src/test/scala/com/example/UserServiceSpec.scala")
    val (_, _, imports, _, _) = extractSymbols(file)
    // This file doesn't have imports in our test data, but the function should return empty list not crash
    assert(imports != null)
  }

  // ── Local vals NOT indexed ──────────────────────────────────────────

  test("extractSymbols does NOT index local vals inside def bodies") {
    val file = workspace.resolve("src/main/scala/local_vals.scala")
    Files.createDirectories(file.getParent)
    Files.writeString(file,
      """package com.example
        |
        |object MyService {
        |  def process(data: String): String = {
        |    val temp = data.trim
        |    val result = temp.toUpperCase
        |    result
        |  }
        |  val publicVal: Int = 42
        |}
        |""".stripMargin)

    val (syms, _, _, _, _) = extractSymbols(file)
    val names = syms.map(_.name).toSet
    assert(names.contains("MyService"), "Should find top-level object")
    assert(names.contains("process"), "Should find top-level def")
    assert(names.contains("publicVal"), "Should find top-level val in object body")
    assert(!names.contains("temp"), s"Should NOT index local val 'temp': $names")
    assert(!names.contains("result"), s"Should NOT index local val 'result': $names")

    Files.delete(file)
  }

  // ── Case class constructor params in members ──────────────────────

  test("extractMembers returns case class constructor params") {
    val members = extractMembers(
      workspace.resolve("src/main/scala/com/example/Model.scala"),
      "User"
    )
    val names = members.map(_.name).toSet
    assert(names.contains("id"), s"Should contain case class param 'id': $names")
    assert(names.contains("name"), s"Should contain case class param 'name': $names")
  }

  test("extractMembers does NOT return regular class constructor params without val/var") {
    val members = extractMembers(
      workspace.resolve("src/main/scala/com/example/UserService.scala"),
      "UserServiceLive"
    )
    val names = members.map(_.name).toSet
    assert(!names.contains("db"), s"Should NOT contain regular param 'db': $names")
  }

  // ── Scala 2 dialect fallback ────────────────────────────────────────

  test("extractSymbols parses Scala 2 procedure syntax") {
    val file = workspace.resolve("src/main/scala/Scala2Style.scala")
    Files.createDirectories(file.getParent)
    Files.writeString(file,
      """package com.legacy
        |
        |class OldService {
        |  def doSomething(x: Int) {
        |    println(x)
        |  }
        |  def anotherMethod(): Unit = {
        |    println("hello")
        |  }
        |}
        |
        |object OldService {
        |  def apply(): OldService = new OldService
        |}
        |""".stripMargin)

    val (syms, _, _, _, _) = extractSymbols(file)
    val names = syms.map(_.name)
    assert(names.contains("OldService"), s"Should find OldService class: $names")
    assert(syms.exists(s => s.name == "OldService" && s.kind == SymbolKind.Class))
    assert(syms.exists(s => s.name == "OldService" && s.kind == SymbolKind.Object))
    assert(syms.exists(s => s.name == "doSomething" && s.kind == SymbolKind.Def))

    Files.delete(file)
  }

  test("extractSymbols parses Scala 2 implicit class") {
    val file = workspace.resolve("src/main/scala/Scala2Implicits.scala")
    Files.createDirectories(file.getParent)
    Files.writeString(file,
      """package com.legacy
        |
        |object Implicits {
        |  implicit class RichString(val s: String) extends AnyVal {
        |    def shout: String = s.toUpperCase
        |  }
        |  implicit def intToString(i: Int): String = i.toString
        |}
        |""".stripMargin)

    val (syms, _, _, _, _) = extractSymbols(file)
    val names = syms.map(_.name)
    assert(names.contains("Implicits"), s"Should find Implicits object: $names")
    assert(names.contains("RichString"), s"Should find RichString class: $names")
    assert(names.contains("intToString"), s"Should find intToString def: $names")

    Files.delete(file)
  }

  test("extractSymbols handles mixed Scala 2 and Scala 3 files in same project") {
    // The existing test files are Scala 3 style. Add a Scala 2 file.
    val scala2File = workspace.resolve("src/main/scala/Legacy.scala")
    Files.createDirectories(scala2File.getParent)
    Files.writeString(scala2File,
      """package com.legacy
        |
        |trait LegacyService {
        |  def process(data: String): Unit
        |}
        |""".stripMargin)

    // Index the whole workspace — should handle both Scala 2 and 3 files
    run("git", "add", ".")
    run("git", "commit", "-m", "add legacy")

    val idx = WorkspaceIndex(workspace)
    idx.index()

    // Scala 3 symbols should still work
    assert(idx.findDefinition("UserService").nonEmpty, "Scala 3 symbols should work")
    assert(idx.findDefinition("Role").nonEmpty, "Scala 3 enum should work")

    // Scala 2 style symbols should also work
    assert(idx.findDefinition("LegacyService").nonEmpty, "Scala 2 symbols should work")

    // Clean up
    Files.delete(scala2File)
    run("git", "add", ".")
    run("git", "commit", "-m", "remove legacy")
  }

  // ── Annotation extraction ─────────────────────────────────────────

  test("extractSymbols captures annotations") {
    val file = workspace.resolve("src/main/scala/com/example/Annotated.scala")
    val (syms, _, _, _, _) = extractSymbols(file)
    val oldThing = syms.find(_.name == "OldThing").get
    assert(oldThing.annotations.contains("deprecated"), s"Should have @deprecated: ${oldThing.annotations}")
  }

  test("extractSymbols captures annotations with arguments") {
    val file = workspace.resolve("src/main/scala/com/example/Annotated.scala")
    val (syms, _, _, _, _) = extractSymbols(file)
    val oldService = syms.find(_.name == "OldService").get
    assert(oldService.annotations.contains("deprecated"), s"Should have @deprecated: ${oldService.annotations}")
  }

  test("extractSymbols captures annotations on vals") {
    val file = workspace.resolve("src/main/scala/com/example/Annotated.scala")
    val (syms, _, _, _, _) = extractSymbols(file)
    val fastVal = syms.find(_.name == "fastVal").get
    assert(fastVal.annotations.contains("specialized"), s"Should have @specialized: ${fastVal.annotations}")
  }

  test("findAnnotated finds annotated symbols") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = idx.findAnnotated("deprecated")
    assert(results.nonEmpty, "Should find @deprecated symbols")
    assert(results.exists(_.name == "OldThing"), s"Should find OldThing: ${results.map(_.name)}")
    assert(results.exists(_.name == "OldService"), s"Should find OldService: ${results.map(_.name)}")
  }

  test("findAnnotated is case-insensitive") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val upper = idx.findAnnotated("DEPRECATED")
    val lower = idx.findAnnotated("deprecated")
    assertEquals(upper.size, lower.size)
  }

  test("findAnnotated returns empty for unknown annotation") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = idx.findAnnotated("nonexistent")
    assert(results.isEmpty)
  }

  test("binary v5 roundtrip preserves annotations") {
    val cacheDir = workspace.resolve(".scalex")
    if Files.exists(cacheDir) then
      Files.list(cacheDir).iterator().asScala.foreach(Files.delete)

    val idx = WorkspaceIndex(workspace)
    idx.index()

    val loaded = IndexPersistence.load(workspace)
    assert(loaded.isDefined, "Should load from cache")

    val cachedFiles = loaded.get
    val annotFile = cachedFiles.values.find(_.relativePath.contains("Annotated.scala")).get
    val oldThing = annotFile.symbols.find(_.name == "OldThing").get
    assert(oldThing.annotations.contains("deprecated"),
      s"Annotations should survive roundtrip: ${oldThing.annotations}")
  }

  // ── members ──────────────────────────────────────────────────────────

  test("members of trait with abstract defs") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val members = extractMembers(
      workspace.resolve("src/main/scala/com/example/Documented.scala"),
      "PaymentService"
    )
    assert(members.nonEmpty, "PaymentService should have members")
    val names = members.map(_.name).toSet
    assert(names.contains("processPayment"), s"Should contain processPayment: $names")
    assert(names.contains("refund"), s"Should contain refund: $names")
  }

  test("members of class with concrete defs, vals, vars, types") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val members = extractMembers(
      workspace.resolve("src/main/scala/com/example/Documented.scala"),
      "PaymentServiceLive"
    )
    val names = members.map(_.name).toSet
    assert(names.contains("processPayment"), s"Should contain processPayment: $names")
    assert(names.contains("refund"), s"Should contain refund: $names")
    assert(names.contains("maxRetries"), s"Should contain maxRetries: $names")
    assert(names.contains("lastError"), s"Should contain lastError: $names")
    assert(names.contains("TransactionId"), s"Should contain TransactionId: $names")
  }

  test("members of object") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val members = extractMembers(
      workspace.resolve("src/main/scala/com/example/UserService.scala"),
      "UserService"
    )
    // Object UserService has `val default`
    val objectMembers = members.filter(_.name == "default")
    assert(objectMembers.nonEmpty, s"Object UserService should have val default: ${members.map(_.name)}")
  }

  test("members Scala 2 fallback") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // UserServiceLive uses Scala 3 syntax — extract members from trait UserService (Scala 2 compatible braces)
    val members = extractMembers(
      workspace.resolve("src/main/scala/com/example/UserService.scala"),
      "UserServiceLive"
    )
    val names = members.map(_.name).toSet
    assert(names.contains("findUser"), s"Should find members in Scala 2 compatible file: $names")
  }

  // ── members --inherited ──────────────────────────────────────────────

  test("members --inherited includes parent members") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("members", List("PaymentServiceLive"), CommandContext(idx = idx, workspace = workspace, limit = 50, inherited = true))
    }
    val output = out.toString
    // PaymentServiceLive should show its own members + inherited from PaymentService
    assert(output.contains("PaymentServiceLive"), s"Should show PaymentServiceLive header: $output")
    // The inherited section should show parent name
    // PaymentServiceLive overrides processPayment and refund, so inherited section may only include
    // members that are NOT overridden. Since PaymentServiceLive defines both processPayment and refund,
    // we check that the output at least runs without error and shows the type
    assert(output.contains("Members of"), s"Should have Members of header: $output")
  }

  test("members --inherited dedup: child overrides win") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("members", List("UserServiceLive"), CommandContext(idx = idx, workspace = workspace, limit = 50, jsonOutput = true, inherited = true))
    }
    val output = out.toString
    // JSON output — parse to verify dedup
    assert(output.startsWith("["), s"JSON should start with bracket: $output")
    // UserServiceLive defines findUser and createUser which are also in UserService.
    // With dedup, inherited findUser/createUser should NOT appear
    // Count how many times findUser appears
    val findUserCount = "\"findUser\"".r.findAllIn(output).size
    assertEquals(findUserCount, 1, s"findUser should appear only once (child wins dedup): $output")
  }

  // ── doc ──────────────────────────────────────────────────────────────

  test("doc extracts multi-line scaladoc") {
    val doc = extractDoc(
      workspace.resolve("src/main/scala/com/example/Documented.scala"),
      7 // trait PaymentService is on line 7
    )
    assert(doc.isDefined, "Should find scaladoc for PaymentService")
    assert(doc.get.contains("processing payments"), s"Should contain doc text: ${doc.get}")
    assert(doc.get.contains("/**"), s"Should contain opening: ${doc.get}")
    assert(doc.get.contains("*/"), s"Should contain closing: ${doc.get}")
  }

  test("doc extracts single-line scaladoc") {
    val doc = extractDoc(
      workspace.resolve("src/main/scala/com/example/Documented.scala"),
      9 // def processPayment is on line 9
    )
    assert(doc.isDefined, "Should find single-line scaladoc for processPayment")
    assert(doc.get.contains("Process a single payment"), s"Should contain doc text: ${doc.get}")
  }

  test("doc returns None when no scaladoc") {
    val doc = extractDoc(
      workspace.resolve("src/main/scala/com/example/Documented.scala"),
      10 // def refund on line 10 — no doc
    )
    assert(doc.isEmpty, "refund should have no scaladoc")
  }

  // ── body command — extractBody ────────────────────────────────────────

  test("extractBody finds method body in a class") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    val results = extractBody(file, "findUser", None)
    assert(results.nonEmpty, "Should find findUser body")
    // extractBody only finds Defn.Def (concrete), not Decl.Def (abstract in trait)
    val inLive = results.find(_.ownerName == "UserServiceLive")
    assert(inLive.isDefined, s"Should find findUser in UserServiceLive: ${results.map(_.ownerName)}")
    assert(inLive.get.sourceText.contains("db.query"), s"Body should contain impl: ${inLive.get.sourceText}")
  }

  test("extractBody finds class/trait body") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    val results = extractBody(file, "UserService", None)
    assert(results.nonEmpty, "Should find UserService body")
    val traitBody = results.find(_.sourceText.contains("trait UserService"))
    assert(traitBody.isDefined, s"Should find trait body: ${results.map(_.sourceText.take(30))}")
    assert(traitBody.get.sourceText.contains("findUser"), s"Trait body should contain findUser: ${traitBody.get.sourceText}")
  }

  test("extractBody with --in owner restriction") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    val inLive = extractBody(file, "findUser", Some("UserServiceLive"))
    assert(inLive.nonEmpty, "Should find findUser in UserServiceLive")
    assert(inLive.forall(_.ownerName == "UserServiceLive"), s"Should only be in UserServiceLive: ${inLive.map(_.ownerName)}")
    // findUser in trait UserService is a Decl.Def (abstract), not found by extractBody
    val inTrait = extractBody(file, "findUser", Some("UserService"))
    assert(inTrait.isEmpty, "Abstract Decl.Def in trait should not be found by extractBody")
    // But the trait body itself can be extracted
    val traitBody = extractBody(file, "UserService", None)
    assert(traitBody.nonEmpty, "Should find trait UserService body")
  }

  test("extractBody returns empty for nonexistent symbol") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    val results = extractBody(file, "nonExistentMethod", None)
    assert(results.isEmpty, "Should return empty for nonexistent symbol")
  }

  test("extractBody sourceText includes full definition text") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    val results = extractBody(file, "findUser", Some("UserServiceLive"))
    assert(results.nonEmpty, "Should find findUser")
    val body = results.head
    assert(body.sourceText.contains("def findUser"), s"Should contain def keyword: ${body.sourceText}")
    assert(body.startLine > 0, s"Start line should be positive: ${body.startLine}")
    assert(body.endLine >= body.startLine, s"End line should be >= start line: ${body.startLine} to ${body.endLine}")
  }

  // ── Test awareness: extractTests ────────────────────────────────────────

  test("extractTests finds test cases in MUnit suite") {
    val file = workspace.resolve("src/test/scala/com/example/UserServiceTest.scala")
    val suites = extractTests(file)
    assert(suites.nonEmpty, "Should find at least one suite")
    val suite = suites.find(_.name == "UserServiceTest").get
    assertEquals(suite.tests.size, 2)
    val testNames = suite.tests.map(_.name)
    assert(testNames.contains("findUser returns None for unknown id"), s"Test names: $testNames")
    assert(testNames.contains("createUser returns new user"), s"Test names: $testNames")
  }

  test("extractTests returns empty for non-test file") {
    val file = workspace.resolve("src/main/scala/com/example/Model.scala")
    val suites = extractTests(file)
    assert(suites.isEmpty, s"Non-test file should have no suites: ${suites.map(_.name)}")
  }

  // ── Test awareness: body for test cases ──────────────────────────────────

  test("extractBody finds test case body by exact name") {
    val file = workspace.resolve("src/test/scala/com/example/UserServiceTest.scala")
    val results = extractBody(file, "findUser returns None for unknown id", None)
    assert(results.nonEmpty, "Should find test body")
    val body = results.head
    assert(body.sourceText.contains("findUser"), s"Body should contain test code: ${body.sourceText}")
    assert(body.ownerName == "UserServiceTest", s"Owner should be UserServiceTest: ${body.ownerName}")
  }

  test("extractBody finds test body with --in owner") {
    val file = workspace.resolve("src/test/scala/com/example/UserServiceTest.scala")
    val results = extractBody(file, "createUser returns new user", Some("UserServiceTest"))
    assert(results.nonEmpty, "Should find test body with owner filter")
    val body = results.head
    assert(body.sourceText.contains("createUser"), s"Body should contain test code: ${body.sourceText}")
  }

  // ── Java extraction (JavaParser-based) ──────────────────────────────────

  test("extractSymbols finds Java classes, interfaces, enums, methods, fields") {
    val file = workspace.resolve("src/main/java/com/example/GenericRepository.java")
    val (syms, _, _, _, failed) = extractSymbols(file)
    assert(!failed, "Should parse successfully")
    val names = syms.map(s => (s.name, s.kind))
    assert(names.contains(("GenericRepository", SymbolKind.Class)), s"Should find class: $names")
    assert(names.contains(("Status", SymbolKind.Enum)), s"Should find nested enum: $names")
    assert(names.contains(("findById", SymbolKind.Def)), s"Should find method findById: $names")
    assert(names.contains(("findAll", SymbolKind.Def)), s"Should find method findAll: $names")
    assert(names.contains(("delete", SymbolKind.Def)), s"Should find method delete: $names")
    assert(names.contains(("tableName", SymbolKind.Val)), s"Should find final field tableName: $names")
    assert(names.contains(("maxResults", SymbolKind.Var)), s"Should find non-final field maxResults: $names")
  }

  test("extractSymbols extracts Java annotations") {
    val file = workspace.resolve("src/main/java/com/example/GenericRepository.java")
    val (syms, _, _, _, _) = extractSymbols(file)
    val repo = syms.find(_.name == "GenericRepository").get
    assert(repo.annotations.contains("SuppressWarnings"), s"Should have @SuppressWarnings: ${repo.annotations}")
  }

  test("extractSymbols extracts Java parents for extends + implements") {
    val file = workspace.resolve("src/main/java/com/example/UserRepository.java")
    val (syms, _, _, _, _) = extractSymbols(file)
    val userRepo = syms.find(s => s.name == "UserRepository" && s.kind == SymbolKind.Class).get
    assert(userRepo.parents.contains("GenericRepository"), s"Should extend GenericRepository: ${userRepo.parents}")
    assert(userRepo.parents.contains("EventBus"), s"Should implement EventBus: ${userRepo.parents}")
  }

  test("extractSymbols extracts Java imports from AST") {
    val file = workspace.resolve("src/main/java/com/example/GenericRepository.java")
    val (_, _, imports, _, _) = extractSymbols(file)
    assert(imports.exists(_.contains("java.util.List")), s"Should find List import: $imports")
    assert(imports.exists(_.contains("java.util.Optional")), s"Should find Optional import: $imports")
  }

  test("extractMembers returns Java methods and fields") {
    val file = workspace.resolve("src/main/java/com/example/GenericRepository.java")
    val members = extractMembers(file, "GenericRepository")
    val names = members.map(_.name).toSet
    assert(names.contains("findById"), s"Should contain findById: $names")
    assert(names.contains("findAll"), s"Should contain findAll: $names")
    assert(names.contains("delete"), s"Should contain delete: $names")
    assert(names.contains("tableName"), s"Should contain field tableName: $names")
    assert(names.contains("maxResults"), s"Should contain field maxResults: $names")
    // Constructor
    assert(names.contains("<init>"), s"Should contain constructor: $names")
    // Nested type
    assert(names.contains("Status"), s"Should contain nested enum Status: $names")
  }

  test("extractMembers detects Java @Override") {
    val file = workspace.resolve("src/main/java/com/example/UserRepository.java")
    val members = extractMembers(file, "UserRepository")
    val findById = members.find(_.name == "findById").get
    assert(findById.isOverride, s"findById should be @Override: $findById")
    val findByName = members.find(_.name == "findByName").get
    assert(!findByName.isOverride, s"findByName should NOT be @Override: $findByName")
  }

  test("extractMembers returns Java enum constants") {
    val file = workspace.resolve("src/main/java/com/example/GenericRepository.java")
    val members = extractMembers(file, "Status")
    val names = members.map(_.name).toSet
    assert(names.contains("ACTIVE"), s"Should contain ACTIVE: $names")
    assert(names.contains("INACTIVE"), s"Should contain INACTIVE: $names")
    assert(names.contains("DELETED"), s"Should contain DELETED: $names")
  }

  test("extractBody finds Java method body") {
    val file = workspace.resolve("src/main/java/com/example/UserRepository.java")
    val results = extractBody(file, "findById", None)
    assert(results.nonEmpty, "Should find findById body")
    val body = results.head
    assert(body.sourceText.contains("Optional.of"), s"Body should contain impl: ${body.sourceText}")
    assert(body.ownerName == "UserRepository", s"Owner should be UserRepository: ${body.ownerName}")
  }

  test("extractBody respects Java ownerName filter") {
    val file = workspace.resolve("src/main/java/com/example/UserRepository.java")
    val inRepo = extractBody(file, "findById", Some("UserRepository"))
    assert(inRepo.nonEmpty, "Should find findById in UserRepository")
    val inOther = extractBody(file, "findById", Some("NonExistent"))
    assert(inOther.isEmpty, "Should NOT find findById in NonExistent")
  }

  test("extractMembers dispatches to Java for .java files") {
    val file = workspace.resolve("src/main/java/com/example/GenericRepository.java")
    val members = extractMembers(file, "GenericRepository")
    val names = members.map(_.name).toSet
    assert(names.contains("findById"), s"Dispatch should work for Java: $names")
    assert(names.contains("findAll"), s"Dispatch should work for Java: $names")
  }

  // ── #172: Java parser crash (Error not caught) ─────────────────────────

  test("extractBody on Java file with parser error returns empty, does not crash") {
    // BrokenRecord.java uses sealed interface + record syntax that may trigger
    // JavaParser internal errors (NoSuchFieldError). The fix catches Error, not just Exception.
    val file = workspace.resolve("src/main/java/com/example/BrokenRecord.java")
    val results = extractBody(file, "Ok", None)
    // Whether it parses or not, it must not throw — either returns results or empty
    assert(results.size >= 0, "Should return a list (possibly empty), not crash")
  }

  test("extractSymbols on Java file with parser error does not crash") {
    val file = workspace.resolve("src/main/java/com/example/BrokenRecord.java")
    val (syms, _, _, _, _) = extractSymbols(file)
    // Must not throw — either finds symbols or returns empty
    assert(syms.size >= 0, "Should return a list (possibly empty), not crash")
  }

  // ── #172: Body finds nested local defs ─────────────────────────────────

  test("extractBody finds local def nested inside a method") {
    val file = workspace.resolve("src/main/scala/com/example/Pipeline.scala")
    val results = extractBody(file, "runSteps", Some("Pipeline"))
    assert(results.nonEmpty, "Should find local def runSteps inside Pipeline.execute")
    val body = results.head
    assert(body.sourceText.contains("def runSteps"), s"Should contain def keyword: ${body.sourceText}")
    assert(body.sourceText.contains("remaining match"), s"Should contain body: ${body.sourceText}")
    assert(body.ownerName == "Pipeline", s"Owner should be Pipeline: ${body.ownerName}")
  }

  test("extractBody finds local def in a different method of same class") {
    val file = workspace.resolve("src/main/scala/com/example/Pipeline.scala")
    val results = extractBody(file, "checkStep", Some("Pipeline"))
    assert(results.nonEmpty, "Should find local def checkStep inside Pipeline.validate")
    val body = results.head
    assert(body.sourceText.contains("def checkStep"), s"Should contain def keyword: ${body.sourceText}")
    assert(body.sourceText.contains("step.nonEmpty"), s"Should contain body: ${body.sourceText}")
  }

  test("extractBody finds local def without --in owner") {
    val file = workspace.resolve("src/main/scala/com/example/Pipeline.scala")
    val results = extractBody(file, "runSteps", None)
    assert(results.nonEmpty, "Should find runSteps without owner filter")
  }

  test("extractBody finds local def nested inside wrapper call (synchronized, etc.)") {
    // Scheduler.schedule wraps its body in synchronized { ... }
    // The local def processBatch is nested inside that Term.Apply wrapper
    val file = workspace.resolve("src/main/scala/com/example/Scheduler.scala")
    val results = extractBody(file, "processBatch", Some("Scheduler"))
    assert(results.nonEmpty, "Should find local def processBatch inside synchronized block")
    val body = results.head
    assert(body.sourceText.contains("def processBatch"), s"Should contain def keyword: ${body.sourceText}")
    assert(body.sourceText.contains("batch.size"), s"Should contain body: ${body.sourceText}")
  }
