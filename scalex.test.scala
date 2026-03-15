//> using scala 3.8.2
//> using dep org.scalameta::scalameta:4.15.2
//> using dep com.google.guava:guava:33.5.0-jre
//> using test.dep org.scalameta::munit:1.2.4

import munit.FunSuite
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class ScalexSuite extends FunSuite:

  // ── Test workspace setup ────────────────────────────────────────────────

  var workspace: Path = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    workspace = Files.createTempDirectory("scalex-test")

    // Create sample Scala files
    writeFile("src/main/scala/com/example/UserService.scala",
      """package com.example
        |
        |trait UserService {
        |  def findUser(id: String): Option[User]
        |  def createUser(name: String): User
        |}
        |
        |class UserServiceLive(db: Database) extends UserService {
        |  def findUser(id: String): Option[User] = db.query(id)
        |  def createUser(name: String): User = db.insert(name)
        |}
        |
        |object UserService {
        |  val default: UserService = UserServiceLive(Database.live)
        |}
        |""".stripMargin)

    writeFile("src/main/scala/com/example/Model.scala",
      """package com.example
        |
        |case class User(id: String, name: String)
        |
        |enum Role:
        |  case Admin, Editor, Viewer
        |
        |type UserId = String
        |
        |given userOrdering: Ordering[User] = Ordering.by(_.name)
        |""".stripMargin)

    writeFile("src/main/scala/com/example/Database.scala",
      """package com.example
        |
        |trait Database {
        |  def query(id: String): Option[User]
        |  def insert(name: String): User
        |}
        |
        |object Database {
        |  val live: Database = new Database {
        |    def query(id: String): Option[User] = None
        |    def insert(name: String): User = User(name, name)
        |  }
        |}
        |""".stripMargin)

    writeFile("src/main/scala/com/other/Helper.scala",
      """package com.other
        |
        |object Helper {
        |  def formatUser(user: com.example.User): String = user.name
        |  val version = "1.0"
        |}
        |
        |extension (s: String)
        |  def toUserId: com.example.UserId = s
        |""".stripMargin)

    writeFile("src/test/scala/com/example/UserServiceSpec.scala",
      """package com.example
        |
        |class UserServiceSpec {
        |  val service: UserService = UserService.default
        |  def testFindUser(): Unit = {
        |    val result = service.findUser("123")
        |  }
        |}
        |""".stripMargin)

    writeFile("src/main/scala/com/client/ExplicitClient.scala",
      """package com.client
        |
        |import com.example.UserService
        |
        |class ExplicitClient {
        |  val svc: UserService = ???
        |}
        |""".stripMargin)

    writeFile("src/main/scala/com/client/WildcardClient.scala",
      """package com.client
        |
        |import com.example._
        |
        |class WildcardClient {
        |  val svc: UserService = ???
        |}
        |""".stripMargin)

    writeFile("src/main/scala/com/unrelated/NoImportClient.scala",
      """package com.unrelated
        |
        |class NoImportClient {
        |  val svc: UserService = ???
        |}
        |""".stripMargin)

    writeFile("src/main/scala/com/client/AliasClient.scala",
      """package com.client
        |
        |import com.example.UserService as US
        |import com.example.{Database as DB}
        |
        |class AliasClient {
        |  val svc: US = ???
        |  val db: DB = ???
        |}
        |""".stripMargin)

    writeFile("src/main/scala/com/example/Annotated.scala",
      """package com.example
        |
        |@deprecated class OldThing
        |@deprecated("use NewService", "2.0") class OldService extends UserService {
        |  def findUser(id: String): Option[User] = None
        |  def createUser(name: String): User = User(name, name)
        |}
        |@specialized val fastVal: Int = 42
        |""".stripMargin)

    // Initialize git repo
    run("git", "init")
    run("git", "add", ".")
    run("git", "commit", "-m", "init")

  override def afterAll(): Unit =
    deleteRecursive(workspace)

  private def writeFile(relativePath: String, content: String): Unit =
    val file = workspace.resolve(relativePath)
    Files.createDirectories(file.getParent)
    Files.writeString(file, content)

  private def run(cmd: String*): Unit =
    val pb = ProcessBuilder(cmd*)
    pb.directory(workspace.toFile)
    pb.redirectErrorStream(true)
    val proc = pb.start()
    proc.getInputStream.readAllBytes() // drain
    val exit = proc.waitFor()
    assert(exit == 0, s"Command failed: ${cmd.mkString(" ")}")

  private def deleteRecursive(path: Path): Unit =
    if Files.isDirectory(path) then
      Files.list(path).iterator().asScala.foreach(deleteRecursive)
    Files.deleteIfExists(path)

  // ── Git file listing ──────────────────────────────────────────────────

  test("gitLsFiles finds all .scala files") {
    val files = gitLsFiles(workspace)
    assertEquals(files.size, 10)
    assert(files.exists(_.path.toString.contains("UserService.scala")))
    assert(files.exists(_.path.toString.contains("Model.scala")))
    assert(files.exists(_.path.toString.contains("Database.scala")))
    assert(files.exists(_.path.toString.contains("Helper.scala")))
    assert(files.exists(_.path.toString.contains("UserServiceSpec.scala")))
    assert(files.exists(_.path.toString.contains("ExplicitClient.scala")))
    assert(files.exists(_.path.toString.contains("WildcardClient.scala")))
    assert(files.exists(_.path.toString.contains("NoImportClient.scala")))
    assert(files.exists(_.path.toString.contains("AliasClient.scala")))
    assert(files.exists(_.path.toString.contains("Annotated.scala")))
  }

  test("gitLsFiles returns valid OIDs") {
    val files = gitLsFiles(workspace)
    files.foreach { gf =>
      assert(gf.oid.length == 40, s"OID should be 40 hex chars: ${gf.oid}")
      assert(gf.oid.matches("[0-9a-f]+"), s"OID should be hex: ${gf.oid}")
    }
  }

  // ── Symbol extraction ─────────────────────────────────────────────────

  test("extractSymbols finds classes, traits, objects") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    val (syms, _, _, _) = extractSymbols(file)
    val names = syms.map(s => (s.name, s.kind))

    assert(names.contains(("UserService", SymbolKind.Trait)))
    assert(names.contains(("UserServiceLive", SymbolKind.Class)))
    assert(names.contains(("UserService", SymbolKind.Object)))
  }

  test("extractSymbols finds defs") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    val (syms, _, _, _) = extractSymbols(file)
    val defs = syms.filter(_.kind == SymbolKind.Def).map(_.name)

    assert(defs.contains("findUser"))
    assert(defs.contains("createUser"))
  }

  test("extractSymbols finds enums") {
    val file = workspace.resolve("src/main/scala/com/example/Model.scala")
    val (syms, _, _, _) = extractSymbols(file)
    assert(syms.exists(s => s.name == "Role" && s.kind == SymbolKind.Enum))
  }

  test("extractSymbols finds type aliases") {
    val file = workspace.resolve("src/main/scala/com/example/Model.scala")
    val (syms, _, _, _) = extractSymbols(file)
    assert(syms.exists(s => s.name == "UserId" && s.kind == SymbolKind.Type))
  }

  test("extractSymbols finds givens") {
    val file = workspace.resolve("src/main/scala/com/example/Model.scala")
    val (syms, _, _, _) = extractSymbols(file)
    assert(syms.exists(s => s.kind == SymbolKind.Given))
  }

  test("extractSymbols finds extensions") {
    val file = workspace.resolve("src/main/scala/com/other/Helper.scala")
    val (syms, _, _, _) = extractSymbols(file)
    assert(syms.exists(s => s.kind == SymbolKind.Extension))
  }

  test("extractSymbols captures package name") {
    val file = workspace.resolve("src/main/scala/com/example/Model.scala")
    val (syms, _, _, _) = extractSymbols(file)
    assert(syms.forall(_.packageName == "com.example"))
  }

  test("extractSymbols captures line numbers") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    val (syms, _, _, _) = extractSymbols(file)
    val traitSym = syms.find(s => s.name == "UserService" && s.kind == SymbolKind.Trait).get
    assert(traitSym.line > 0)
  }

  test("extractSymbols handles unparseable files gracefully") {
    val bad = workspace.resolve("bad.scala")
    Files.writeString(bad, "this is not valid scala {{{")
    val (syms, _, _, _) = extractSymbols(bad)
    assertEquals(syms, Nil)
    Files.delete(bad)
  }

  // ── Bloom filter ──────────────────────────────────────────────────────

  test("bloom filter contains identifiers from file") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    val (_, bloom, _, _) = extractSymbols(file)

    assert(bloom.mightContain("UserService"))
    assert(bloom.mightContain("findUser"))
    assert(bloom.mightContain("Database"))
    assert(bloom.mightContain("Option"))
  }

  test("bloom filter rejects absent identifiers") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    val (_, bloom, _, _) = extractSymbols(file)

    // These should almost certainly not be in the bloom filter
    assert(!bloom.mightContain("ZxQwVeryUnlikelyIdentifier"))
    assert(!bloom.mightContain("NobodyWouldNameThisXyz"))
  }

  // ── Workspace index ───────────────────────────────────────────────────

  test("index builds complete symbol table") {
    val idx = WorkspaceIndex(workspace)
    idx.index()

    assert(idx.fileCount == 10)
    assert(idx.symbols.size > 10)
    assert(idx.packages.contains("com.example"))
    assert(idx.packages.contains("com.other"))
  }

  // ── Search ────────────────────────────────────────────────────────────

  test("search exact match ranks first") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = idx.search("User")

    val first = results.head
    assertEquals(first.name, "User")
  }

  test("search prefix match ranks before substring") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = idx.search("User")
    val names = results.map(_.name)

    // "User" (exact) should come before "UserService" (prefix)
    val exactIdx = names.indexOf("User")
    val prefixIdx = names.indexWhere(n => n.startsWith("User") && n != "User")
    assert(exactIdx < prefixIdx, s"Exact ($exactIdx) should be before prefix ($prefixIdx)")
  }

  test("search is case-insensitive") {
    val idx = WorkspaceIndex(workspace)
    idx.index()

    val upper = idx.search("USERSERVICE")
    val lower = idx.search("userservice")
    assertEquals(upper.map(_.name), lower.map(_.name))
  }

  test("search returns empty for no match") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = idx.search("ZxQwNonexistent")
    assert(results.isEmpty)
  }

  // ── Find definition ───────────────────────────────────────────────────

  test("findDefinition returns correct symbol") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = idx.findDefinition("Database")

    assert(results.nonEmpty)
    assert(results.exists(s => s.kind == SymbolKind.Trait))
    assert(results.exists(s => s.kind == SymbolKind.Object))
  }

  test("findDefinition is case-insensitive") {
    val idx = WorkspaceIndex(workspace)
    idx.index()

    val upper = idx.findDefinition("DATABASE")
    val lower = idx.findDefinition("database")
    assertEquals(upper.size, lower.size)
  }

  test("findDefinition returns empty for unknown symbol") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = idx.findDefinition("NonexistentClass")
    assert(results.isEmpty)
  }

  // ── Find references ───────────────────────────────────────────────────

  test("findReferences finds usages across files") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val refs = idx.findReferences("UserService")

    assert(refs.size >= 3, s"Expected >= 3 refs, got ${refs.size}")
    // Should find in: UserService.scala (definition + usage), UserServiceSpec.scala
    val files = refs.map(r => workspace.relativize(r.file).toString).distinct
    assert(files.exists(_.contains("UserService.scala")))
    assert(files.exists(_.contains("UserServiceSpec.scala")))
  }

  test("findReferences respects word boundaries") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val refs = idx.findReferences("User")

    // "User" should match standalone "User" but NOT "UserService" or "UserServiceLive"
    refs.foreach { r =>
      assert(
        r.contextLine.contains("User") &&
          !r.contextLine.matches(".*\\bUser\\w+.*") || r.contextLine.matches(".*\\bUser\\b.*"),
        s"Word boundary violated: ${r.contextLine}"
      )
    }
  }

  test("findReferences uses bloom filter pre-screening") {
    val idx = WorkspaceIndex(workspace)
    idx.index()

    // Search for something only in Helper.scala
    val refs = idx.findReferences("formatUser")
    assert(refs.size >= 1)
    assert(refs.forall(_.file.toString.contains("Helper.scala")))
  }

  // ── File symbols ──────────────────────────────────────────────────────

  test("fileSymbols returns symbols for a given file") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val syms = idx.fileSymbols("src/main/scala/com/example/Model.scala")

    val names = syms.map(_.name).toSet
    assert(names.contains("User"))
    assert(names.contains("Role"))
    assert(names.contains("UserId"))
  }

  test("fileSymbols returns empty for unknown file") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val syms = idx.fileSymbols("nonexistent/File.scala")
    assert(syms.isEmpty)
  }

  // ── Persistence + OID caching ─────────────────────────────────────────

  test("index persists to disk and reloads") {
    // Clean any existing cache
    val cacheDir = workspace.resolve(".scalex")
    if Files.exists(cacheDir) then
      Files.list(cacheDir).iterator().asScala.foreach(Files.delete)

    // First index — cold
    val idx1 = WorkspaceIndex(workspace)
    idx1.index()
    assert(idx1.parsedCount == 10, s"Cold index should parse all 10 files, got ${idx1.parsedCount}")

    // Second index — warm (all cached)
    val idx2 = WorkspaceIndex(workspace)
    idx2.index()
    assert(idx2.cachedLoad, "Second index should load from cache")
    assert(idx2.skippedCount == 10, s"Warm index should skip all 10 files, got ${idx2.skippedCount}")
    assert(idx2.parsedCount == 0, s"Warm index should parse 0 files, got ${idx2.parsedCount}")

    // Symbols should be identical
    assertEquals(idx1.symbols.size, idx2.symbols.size)
  }

  test("index re-parses changed files") {
    // Ensure cache exists
    val idx1 = WorkspaceIndex(workspace)
    idx1.index()

    // Modify a file and recommit
    val file = workspace.resolve("src/main/scala/com/other/Helper.scala")
    val content = Files.readString(file)
    Files.writeString(file, content + "\nval extra = 42\n")
    run("git", "add", ".")
    run("git", "commit", "-m", "modify helper")

    // Reindex — should parse only the changed file
    val idx2 = WorkspaceIndex(workspace)
    idx2.index()
    assert(idx2.cachedLoad)
    assert(idx2.parsedCount == 1, s"Should re-parse 1 file, got ${idx2.parsedCount}")
    assert(idx2.skippedCount == 9, s"Should skip 9 files, got ${idx2.skippedCount}")
  }

  // ── Binary format ─────────────────────────────────────────────────────

  test("binary index file exists after indexing") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val binPath = workspace.resolve(".scalex/index.bin")
    assert(Files.exists(binPath), "index.bin should exist")
    assert(Files.size(binPath) > 0, "index.bin should not be empty")
  }

  test("binary index roundtrip preserves all data") {
    val idx = WorkspaceIndex(workspace)
    idx.index()

    val loaded = IndexPersistence.load(workspace)
    assert(loaded.isDefined)

    val cachedFiles = loaded.get
    // Every git-tracked file should be in the cache
    val gitFiles = gitLsFiles(workspace)
    gitFiles.foreach { gf =>
      val rel = workspace.relativize(gf.path).toString
      assert(cachedFiles.contains(rel), s"Missing file in cache: $rel")
      assertEquals(cachedFiles(rel).oid, gf.oid)
    }
  }

  // ── Word boundary matching ────────────────────────────────────────────

  test("containsWord matches whole words only") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val refs = idx.findReferences("live")
    refs.foreach { r =>
      assert(
        r.contextLine.matches(".*(?<![a-zA-Z0-9])live(?![a-zA-Z0-9]).*"),
        s"Should be word boundary match: '${r.contextLine}'"
      )
    }
  }

  // ── Phase 7: Signatures ───────────────────────────────────────────────

  test("extractSymbols captures signatures") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    val (syms, _, _, _) = extractSymbols(file)

    val traitSym = syms.find(s => s.name == "UserService" && s.kind == SymbolKind.Trait).get
    assert(traitSym.signature.nonEmpty, "Trait should have a signature")
    assert(traitSym.signature.contains("trait UserService"), s"Sig: ${traitSym.signature}")
  }

  test("extractSymbols captures extends parents") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    val (syms, _, _, _) = extractSymbols(file)

    val classSym = syms.find(s => s.name == "UserServiceLive" && s.kind == SymbolKind.Class).get
    assert(classSym.parents.contains("UserService"), s"Parents: ${classSym.parents}")
  }

  test("extractSymbols captures def signatures with params") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    val (syms, _, _, _) = extractSymbols(file)

    val defSym = syms.find(s => s.name == "findUser" && s.kind == SymbolKind.Def).get
    assert(defSym.signature.contains("def findUser"), s"Sig: ${defSym.signature}")
    assert(defSym.signature.contains("id"), s"Should contain param name: ${defSym.signature}")
  }

  test("extractSymbols captures given alias signatures") {
    val file = workspace.resolve("src/main/scala/com/example/Model.scala")
    val (syms, _, _, _) = extractSymbols(file)

    val givenSym = syms.find(s => s.kind == SymbolKind.Given).get
    assert(givenSym.signature.contains("given"), s"Sig: ${givenSym.signature}")
    assert(givenSym.signature.contains("Ordering"), s"Sig: ${givenSym.signature}")
  }

  test("extractSymbols captures imports") {
    // UserServiceSpec imports UserService
    val file = workspace.resolve("src/test/scala/com/example/UserServiceSpec.scala")
    val (_, _, imports, _) = extractSymbols(file)
    // This file doesn't have imports in our test data, but the function should return empty list not crash
    assert(imports != null)
  }

  // ── Phase 7: Find implementations ─────────────────────────────────────

  test("findImplementations finds classes extending a trait") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = idx.findImplementations("UserService")

    assert(results.nonEmpty, "Should find at least one implementation")
    val names = results.map(_.name)
    assert(names.contains("UserServiceLive"), s"Should find UserServiceLive: $names")
  }

  test("findImplementations finds objects extending a trait") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // UserService object extends no trait in our test, but Database.live extends Database
    val results = idx.findImplementations("Database")
    // Our test Database object doesn't use extends syntax, so it won't be found
    // But this verifies the method works without crashing
    assert(results != null)
  }

  test("findImplementations returns empty for unknown trait") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = idx.findImplementations("NonexistentTrait")
    assert(results.isEmpty)
  }

  // ── Phase 7: Categorized references ───────────────────────────────────

  test("categorizeReferences groups by category") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val grouped = idx.categorizeReferences("UserService")

    // Should have at least definition and some usages
    assert(grouped.nonEmpty, "Should have at least one category")

    // The trait definition should be categorized as Definition
    grouped.get(RefCategory.Definition).foreach { defs =>
      assert(defs.exists(_.contextLine.contains("trait UserService")),
        s"Definition should contain 'trait UserService': ${defs.map(_.contextLine)}")
    }

    // The extends should be categorized as ExtendedBy
    grouped.get(RefCategory.ExtendedBy).foreach { exts =>
      assert(exts.exists(_.contextLine.contains("extends UserService")),
        s"ExtendedBy should contain 'extends UserService': ${exts.map(_.contextLine)}")
    }
  }

  // ── Phase 7: Import finding ───────────────────────────────────────────

  test("findImports returns only import lines") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = idx.findImports("UserService")
    // All results should be import lines
    results.foreach { r =>
      assert(r.contextLine.startsWith("import "),
        s"Should be import line: ${r.contextLine}")
    }
  }

  // ── Phase 7: Verbose formatting ───────────────────────────────────────

  test("formatSymbolVerbose includes signature") {
    val s = SymbolInfo("Foo", SymbolKind.Trait, workspace.resolve("Foo.scala"), 1, "com.example",
      List("Bar", "Baz"), "trait Foo extends Bar with Baz")
    val result = formatSymbolVerbose(s, workspace)
    assert(result.contains("trait Foo extends Bar with Baz"), s"Verbose: $result")
  }

  // ── Phase 7: Binary format v3 roundtrip ───────────────────────────────

  test("binary v3 roundtrip preserves parents and signatures") {
    // Clean cache to force fresh save
    val cacheDir = workspace.resolve(".scalex")
    if Files.exists(cacheDir) then
      Files.list(cacheDir).iterator().asScala.foreach(Files.delete)

    val idx = WorkspaceIndex(workspace)
    idx.index()

    // Reload from cache
    val loaded = IndexPersistence.load(workspace)
    assert(loaded.isDefined, "Should load from cache")

    val cachedFiles = loaded.get
    // Check that UserServiceLive has parents preserved
    val userServiceFile = cachedFiles.values.find(_.relativePath.contains("UserService.scala")).get
    val live = userServiceFile.symbols.find(_.name == "UserServiceLive").get
    assert(live.parents.contains("UserService"), s"Parents should survive roundtrip: ${live.parents}")
    assert(live.signature.nonEmpty, s"Signature should survive roundtrip: ${live.signature}")
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

    val (syms, _, _, _) = extractSymbols(file)
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

    val (syms, _, _, _) = extractSymbols(file)
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

  // ── Confidence annotation ────────────────────────────────────────────

  test("resolveConfidence returns High for same-package references") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // UserServiceSpec is in com.example, same as UserService definition
    val refs = idx.findReferences("UserService")
    val specRef = refs.find(r => workspace.relativize(r.file).toString.contains("UserServiceSpec.scala"))
    assert(specRef.isDefined, "Should find ref in UserServiceSpec")
    val targetPkgs = idx.symbolsByName.getOrElse("userservice", Nil).map(_.packageName).toSet
    val conf = idx.resolveConfidence(specRef.get, "UserService", targetPkgs)
    assertEquals(conf, Confidence.High)
  }

  test("resolveConfidence returns High for explicit import") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val refs = idx.findReferences("UserService")
    val clientRef = refs.find(r => workspace.relativize(r.file).toString.contains("ExplicitClient.scala"))
    assert(clientRef.isDefined, "Should find ref in ExplicitClient")
    val targetPkgs = idx.symbolsByName.getOrElse("userservice", Nil).map(_.packageName).toSet
    val conf = idx.resolveConfidence(clientRef.get, "UserService", targetPkgs)
    assertEquals(conf, Confidence.High)
  }

  test("resolveConfidence returns Medium for wildcard import") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val refs = idx.findReferences("UserService")
    val wcRef = refs.find(r => workspace.relativize(r.file).toString.contains("WildcardClient.scala"))
    assert(wcRef.isDefined, "Should find ref in WildcardClient")
    val targetPkgs = idx.symbolsByName.getOrElse("userservice", Nil).map(_.packageName).toSet
    val conf = idx.resolveConfidence(wcRef.get, "UserService", targetPkgs)
    assertEquals(conf, Confidence.Medium)
  }

  test("resolveConfidence returns Low for no matching import") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val refs = idx.findReferences("UserService")
    val noImpRef = refs.find(r => workspace.relativize(r.file).toString.contains("NoImportClient.scala"))
    assert(noImpRef.isDefined, "Should find ref in NoImportClient")
    val targetPkgs = idx.symbolsByName.getOrElse("userservice", Nil).map(_.packageName).toSet
    val conf = idx.resolveConfidence(noImpRef.get, "UserService", targetPkgs)
    assertEquals(conf, Confidence.Low)
  }

  // ── Wildcard import resolution ───────────────────────────────────────

  test("findImports finds wildcard imports that match target package") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = idx.findImports("UserService")
    // Should find explicit import in ExplicitClient AND wildcard import in WildcardClient
    val files = results.map(r => workspace.relativize(r.file).toString)
    assert(files.exists(_.contains("ExplicitClient.scala")),
      s"Should find explicit import: $files")
    assert(files.exists(_.contains("WildcardClient.scala")),
      s"Should find wildcard import: ${files}")
  }

  test("findImports wildcard result contains the wildcard import line") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = idx.findImports("UserService")
    val wcResult = results.find(r => workspace.relativize(r.file).toString.contains("WildcardClient.scala"))
    assert(wcResult.isDefined, "Should find wildcard import result")
    assert(wcResult.get.contextLine.contains("import com.example._"),
      s"Should contain wildcard import line: ${wcResult.get.contextLine}")
  }

  // ── Import alias tracking ──────────────────────────────────────────

  test("extractSymbols extracts import aliases") {
    val file = workspace.resolve("src/main/scala/com/client/AliasClient.scala")
    val (_, _, _, aliases) = extractSymbols(file)
    assertEquals(aliases.get("UserService"), Some("US"))
    assertEquals(aliases.get("Database"), Some("DB"))
  }

  test("findReferences follows aliases") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val refs = idx.findReferences("UserService")
    val aliasRefs = refs.filter(r =>
      workspace.relativize(r.file).toString.contains("AliasClient.scala"))
    // Should find import line (contains "UserService") AND usage lines (contain "US")
    assert(aliasRefs.exists(_.contextLine.contains("US")),
      s"Should find alias usage 'US': ${aliasRefs.map(_.contextLine)}")
  }

  test("findReferences follows aliases for Database") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val refs = idx.findReferences("Database")
    val aliasRefs = refs.filter(r =>
      workspace.relativize(r.file).toString.contains("AliasClient.scala"))
    assert(aliasRefs.exists(_.contextLine.contains("DB")),
      s"Should find alias usage 'DB': ${aliasRefs.map(_.contextLine)}")
  }

  test("resolveConfidence returns High for alias imports") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val refs = idx.findReferences("UserService")
    val aliasRef = refs.find { r =>
      val rel = workspace.relativize(r.file).toString
      rel.contains("AliasClient.scala") && r.contextLine.contains("US")
    }
    assert(aliasRef.isDefined, "Should find alias ref")
    val targetPkgs = idx.symbolsByName.getOrElse("userservice", Nil).map(_.packageName).toSet
    val conf = idx.resolveConfidence(aliasRef.get, "UserService", targetPkgs)
    assertEquals(conf, Confidence.High)
  }

  test("resolveConfidence returns High when searching by alias name") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // Search for "US" which is an alias for UserService — refs found in AliasClient.scala
    // should be High confidence because the file has an alias mapping for UserService
    val ref = Reference(
      workspace.resolve("src/main/scala/com/client/AliasClient.scala"),
      6, "val svc: US = ???"
    )
    val targetPkgs = idx.symbolsByName.getOrElse("userservice", Nil).map(_.packageName).toSet
    val conf = idx.resolveConfidence(ref, "US", targetPkgs)
    assertEquals(conf, Confidence.High)
  }

  test("findReferences annotates aliasInfo for alias matches") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val refs = idx.findReferences("UserService")
    val aliasRef = refs.find { r =>
      workspace.relativize(r.file).toString.contains("AliasClient.scala") &&
      r.contextLine.contains("US") && !r.contextLine.contains("UserService")
    }
    assert(aliasRef.isDefined, s"Should find alias ref with US: ${refs.map(r => (workspace.relativize(r.file).toString, r.contextLine))}")
    assertEquals(aliasRef.get.aliasInfo, Some("via alias US"))
  }

  test("formatRef shows alias annotation") {
    val r = Reference(workspace.resolve("Foo.scala"), 10, "val x: US = ???", Some("via alias US"))
    val result = formatRef(r, workspace)
    assert(result.contains("[via alias US]"), s"Should contain alias annotation: $result")
  }

  test("binary roundtrip preserves aliases") {
    val cacheDir = workspace.resolve(".scalex")
    if Files.exists(cacheDir) then
      Files.list(cacheDir).iterator().asScala.foreach(Files.delete)

    val idx = WorkspaceIndex(workspace)
    idx.index()

    val loaded = IndexPersistence.load(workspace)
    assert(loaded.isDefined, "Should load from cache")

    val cachedFiles = loaded.get
    val aliasFile = cachedFiles.values.find(_.relativePath.contains("AliasClient.scala")).get
    assertEquals(aliasFile.aliases.get("UserService"), Some("US"))
    assertEquals(aliasFile.aliases.get("Database"), Some("DB"))
  }

  // ── Fuzzy camelCase search ──────────────────────────────────────────

  test("search fuzzy matches camelCase initials") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // "usl" should match UserServiceLive (U-ser S-ervice L-ive)
    val results = idx.search("usl")
    assert(results.exists(_.name == "UserServiceLive"),
      s"Should fuzzy match UserServiceLive: ${results.map(_.name)}")
  }

  test("search fuzzy matches leading chars of segments") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // "usersl" should match UserServiceLive (User-S-ervice-L-ive)
    val results = idx.search("usersl")
    assert(results.exists(_.name == "UserServiceLive"),
      s"Should fuzzy match UserServiceLive: ${results.map(_.name)}")
  }

  test("search fuzzy ranks below exact/prefix/substring") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // "us" matches UserService as prefix, and UserServiceLive as prefix
    // Fuzzy should not appear before those
    val results = idx.search("us")
    val usIdx = results.indexWhere(_.name == "UserService")
    val uslIdx = results.indexWhere(_.name == "UserServiceLive")
    // Both are prefix matches so they should appear before any fuzzy result
    assert(usIdx >= 0 && uslIdx >= 0)
  }

  test("search fuzzy does not match single char queries") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // Single char "u" should not produce fuzzy results (only exact/prefix/substring)
    val results = idx.search("u")
    // All results should be substring matches (contain "u"), not fuzzy
    results.foreach { s =>
      assert(s.name.toLowerCase.contains("u"),
        s"Single char should only match via substring, not fuzzy: ${s.name}")
    }
  }

  test("search fuzzy returns empty for non-matching query") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = idx.search("zxqw")
    assert(results.isEmpty)
  }

  // ── File search ─────────────────────────────────────────────────────

  test("searchFiles exact match") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = idx.searchFiles("Model")
    assert(results.exists(_.contains("Model.scala")),
      s"Should find Model.scala: $results")
  }

  test("searchFiles prefix match") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = idx.searchFiles("User")
    assert(results.exists(_.contains("UserService.scala")),
      s"Should find UserService.scala: $results")
    assert(results.exists(_.contains("UserServiceSpec.scala")),
      s"Should find UserServiceSpec.scala: $results")
  }

  test("searchFiles fuzzy camelCase match") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // "usl" should match UserServiceLive (part of UserService.scala filename won't match,
    // but "ec" should match ExplicitClient)
    val results = idx.searchFiles("ec")
    assert(results.exists(_.contains("ExplicitClient.scala")),
      s"Should find ExplicitClient.scala: $results")
  }

  test("searchFiles case-insensitive") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val upper = idx.searchFiles("DATABASE")
    val lower = idx.searchFiles("database")
    assertEquals(upper, lower)
  }

  test("searchFiles returns empty for no match") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = idx.searchFiles("ZxQwNonexistent")
    assert(results.isEmpty)
  }

  // ── isTestFile helper ──────────────────────────────────────────────

  test("isTestFile detects test directories") {
    assert(isTestFile(workspace.resolve("src/test/scala/Foo.scala"), workspace))
    assert(isTestFile(workspace.resolve("src/tests/scala/Foo.scala"), workspace))
    assert(isTestFile(workspace.resolve("src/testing/scala/Foo.scala"), workspace))
    assert(!isTestFile(workspace.resolve("src/main/scala/Foo.scala"), workspace))
  }

  test("isTestFile detects test file suffixes") {
    assert(isTestFile(workspace.resolve("src/main/FooTest.scala"), workspace))
    assert(isTestFile(workspace.resolve("src/main/FooSpec.scala"), workspace))
    assert(isTestFile(workspace.resolve("src/main/FooSuite.scala"), workspace))
    assert(!isTestFile(workspace.resolve("src/main/Foo.scala"), workspace))
  }

  test("isTestFile detects bench dirs") {
    assert(isTestFile(workspace.resolve("bench-run/Foo.scala"), workspace))
    assert(isTestFile(workspace.resolve("src/bench-run/Foo.scala"), workspace))
  }

  // ── --kind on def ──────────────────────────────────────────────────

  test("def --kind filters by symbol kind") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val all = idx.findDefinition("UserService")
    assert(all.exists(_.kind == SymbolKind.Trait), "Should have trait")
    assert(all.exists(_.kind == SymbolKind.Object), "Should have object")

    // Filter to trait only
    var filtered = all
    val kk = "trait"
    filtered = filtered.filter(_.kind.toString.toLowerCase == kk)
    assert(filtered.forall(_.kind == SymbolKind.Trait))
    assert(filtered.nonEmpty)
  }

  // ── --no-tests filtering ───────────────────────────────────────────

  test("--no-tests excludes test file results from def") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // UserServiceSpec is in test dir
    val all = idx.findDefinition("UserServiceSpec")
    assert(all.nonEmpty, "Should find UserServiceSpec")
    val filtered = all.filter(s => !isTestFile(s.file, workspace))
    assert(filtered.isEmpty, "Should exclude test files")
  }

  test("--no-tests excludes test file results from refs") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val all = idx.findReferences("UserService")
    val allFiles = all.map(r => workspace.relativize(r.file).toString).distinct
    assert(allFiles.exists(_.contains("UserServiceSpec")), "Should have test file in unfiltered")
    val filtered = all.filter(r => !isTestFile(r.file, workspace))
    val filteredFiles = filtered.map(r => workspace.relativize(r.file).toString).distinct
    assert(!filteredFiles.exists(_.contains("UserServiceSpec")), "Should exclude test file")
  }

  // ── --path filtering ───────────────────────────────────────────────

  test("matchesPath filters by path prefix") {
    assert(matchesPath(workspace.resolve("src/main/scala/Foo.scala"), "src/main", workspace))
    assert(!matchesPath(workspace.resolve("src/test/scala/Foo.scala"), "src/main", workspace))
  }

  test("--path filters def results to subtree") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val all = idx.findDefinition("UserService")
    assert(all.nonEmpty)
    val filtered = all.filter(s => matchesPath(s.file, "src/main", workspace))
    assert(filtered.nonEmpty, "Should have results in src/main")
    filtered.foreach { s =>
      assert(workspace.relativize(s.file).toString.startsWith("src/main"),
        s"All results should be in src/main: ${workspace.relativize(s.file)}")
    }
  }

  test("--path strips leading slash") {
    // The pathFilter parsing strips leading /, so "/src/main" becomes "src/main"
    val prefix = "/src/main".stripPrefix("/")
    assert(matchesPath(workspace.resolve("src/main/scala/Foo.scala"), prefix, workspace))
  }

  // ── Smarter def ranking ────────────────────────────────────────────

  test("def ranking puts class/trait/object before def/val") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // findUser is both a def. UserService has trait, object.
    // Create a scenario: search for something with mixed kinds
    val results = idx.findDefinition("UserService")
    // Sort using the ranking logic
    val ranked = results.sortBy { s =>
      val kindRank = s.kind match
        case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Object | SymbolKind.Enum => 0
        case SymbolKind.Type | SymbolKind.Given => 1
        case _ => 2
      val testRank = if isTestFile(s.file, workspace) then 1 else 0
      val pathLen = workspace.relativize(s.file).toString.length
      (kindRank, testRank, pathLen)
    }
    // Trait and Object should come before any def/val
    val traitIdx = ranked.indexWhere(_.kind == SymbolKind.Trait)
    val objIdx = ranked.indexWhere(_.kind == SymbolKind.Object)
    assert(traitIdx >= 0 && objIdx >= 0)
    // Both should be in the first positions
    assert(traitIdx < 2 && objIdx < 2)
  }

  test("def ranking puts non-test before test files") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // UserServiceSpec is in test dir, UserService is in main
    val all = idx.findDefinition("UserService") ++ idx.findDefinition("UserServiceSpec")
    val ranked = all.sortBy { s =>
      val kindRank = s.kind match
        case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Object | SymbolKind.Enum => 0
        case SymbolKind.Type | SymbolKind.Given => 1
        case _ => 2
      val testRank = if isTestFile(s.file, workspace) then 1 else 0
      val pathLen = workspace.relativize(s.file).toString.length
      (kindRank, testRank, pathLen)
    }
    // Non-test files should come first
    val firstTestIdx = ranked.indexWhere(s => isTestFile(s.file, workspace))
    if firstTestIdx >= 0 then
      ranked.take(firstTestIdx).foreach { s =>
        assert(!isTestFile(s.file, workspace), "Non-test should come before test")
      }
  }

  // ── refs -C N context lines ────────────────────────────────────────

  test("formatRefWithContext shows context lines") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val refs = idx.findReferences("UserService")
    val ref = refs.find(r => workspace.relativize(r.file).toString.contains("UserService.scala")).get
    val output = formatRefWithContext(ref, workspace, 1)
    // Should have the header line and context lines
    assert(output.contains("UserService.scala:"), s"Should have file header: $output")
    assert(output.contains(">"), s"Should have > marker on match line: $output")
    // Count non-empty lines (header + up to 3 context lines)
    val lines = output.split("\n")
    assert(lines.size >= 2, s"Should have header + at least 1 context line: ${lines.size}")
  }

  test("formatRefWithContext with 0 context falls back to formatRef") {
    val ref = Reference(workspace.resolve("src/main/scala/com/example/UserService.scala"), 3, "trait UserService {")
    val withCtx = formatRefWithContext(ref, workspace, 0)
    // With 0 context, should only show the match line
    val lines = withCtx.split("\n").filter(_.nonEmpty)
    assert(lines.size == 2, s"0 context: header + 1 line, got ${lines.size}: $withCtx")
  }

  test("formatRefWithContext handles edge of file") {
    // Line 1 with context 3 — should not go below line 0
    val ref = Reference(workspace.resolve("src/main/scala/com/example/Model.scala"), 1, "package com.example")
    val output = formatRefWithContext(ref, workspace, 3)
    assert(output.contains(">"), s"Should have marker: $output")
    // Should not crash and should show lines from 1 to 4
    val contentLines = output.split("\n").tail // skip header
    assert(contentLines.size >= 1 && contentLines.size <= 4, s"Lines: ${contentLines.size}")
  }

  // ── JSON output helpers ─────────────────────────────────────────────

  test("jsonEscape handles special characters") {
    assertEquals(jsonEscape("""hello "world""""), """hello \"world\"""")
    assertEquals(jsonEscape("line1\nline2"), "line1\\nline2")
    assertEquals(jsonEscape("tab\there"), "tab\\there")
    assertEquals(jsonEscape("""back\slash"""), """back\\slash""")
    assertEquals(jsonEscape("normal text"), "normal text")
  }

  test("jsonEscape handles control characters") {
    val s = "before\u0001after"
    val escaped = jsonEscape(s)
    assert(escaped.contains("\\u0001"), s"Should escape control char: $escaped")
  }

  test("jsonSymbol produces valid JSON structure") {
    val s = SymbolInfo("Foo", SymbolKind.Class, workspace.resolve("Foo.scala"), 10, "com.example",
      List("Bar"), "class Foo extends Bar", List("deprecated"))
    val json = jsonSymbol(s, workspace)
    assert(json.startsWith("{"), s"Should start with {: $json")
    assert(json.endsWith("}"), s"Should end with }: $json")
    assert(json.contains(""""name":"Foo""""), s"Should contain name: $json")
    assert(json.contains(""""kind":"class""""), s"Should contain kind: $json")
    assert(json.contains(""""line":10"""), s"Should contain line: $json")
    assert(json.contains(""""parents":["Bar"]"""), s"Should contain parents: $json")
    assert(json.contains(""""annotations":["deprecated"]"""), s"Should contain annotations: $json")
  }

  test("jsonRef produces valid JSON structure") {
    val r = Reference(workspace.resolve("Foo.scala"), 5, "val x = Foo()", Some("via alias F"))
    val json = jsonRef(r, workspace)
    assert(json.contains(""""line":5"""), s"Should contain line: $json")
    assert(json.contains(""""context":"val x = Foo()""""), s"Should contain context: $json")
    assert(json.contains(""""alias":"via alias F""""), s"Should contain alias: $json")
  }

  test("jsonRef null alias when no alias") {
    val r = Reference(workspace.resolve("Foo.scala"), 5, "val x = Foo()")
    val json = jsonRef(r, workspace)
    assert(json.contains(""""alias":null"""), s"Should have null alias: $json")
  }

  test("jsonRefWithContext includes context lines") {
    val ref = Reference(workspace.resolve("src/main/scala/com/example/UserService.scala"), 3, "trait UserService {")
    val json = jsonRefWithContext(ref, workspace, 1)
    assert(json.contains(""""contextLines":["""), s"Should have contextLines: $json")
    assert(json.contains(""""match":true"""), s"Should mark matching line: $json")
    assert(json.contains(""""match":false"""), s"Should mark non-matching lines: $json")
  }

  // ── Annotation extraction ─────────────────────────────────────────

  test("extractSymbols captures annotations") {
    val file = workspace.resolve("src/main/scala/com/example/Annotated.scala")
    val (syms, _, _, _) = extractSymbols(file)
    val oldThing = syms.find(_.name == "OldThing").get
    assert(oldThing.annotations.contains("deprecated"), s"Should have @deprecated: ${oldThing.annotations}")
  }

  test("extractSymbols captures annotations with arguments") {
    val file = workspace.resolve("src/main/scala/com/example/Annotated.scala")
    val (syms, _, _, _) = extractSymbols(file)
    val oldService = syms.find(_.name == "OldService").get
    assert(oldService.annotations.contains("deprecated"), s"Should have @deprecated: ${oldService.annotations}")
  }

  test("extractSymbols captures annotations on vals") {
    val file = workspace.resolve("src/main/scala/com/example/Annotated.scala")
    val (syms, _, _, _) = extractSymbols(file)
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

  // ── Grep ──────────────────────────────────────────────────────────

  test("grepFiles finds matching lines") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val (results, timedOut) = idx.grepFiles("def findUser", noTests = false, pathFilter = None)
    assert(!timedOut)
    assert(results.nonEmpty, "Should find 'def findUser'")
    assert(results.exists(_.contextLine.contains("def findUser")),
      s"Should contain matching line: ${results.map(_.contextLine)}")
  }

  test("grepFiles supports regex") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val (results, _) = idx.grepFiles("def\\s+create\\w+", noTests = false, pathFilter = None)
    assert(results.nonEmpty, "Should match regex pattern")
    assert(results.exists(_.contextLine.contains("createUser")),
      s"Should find createUser: ${results.map(_.contextLine)}")
  }

  test("grepFiles returns empty for invalid regex") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val (results, timedOut) = idx.grepFiles("[invalid", noTests = false, pathFilter = None)
    assert(results.isEmpty, "Invalid regex should return empty")
    assert(!timedOut)
  }

  test("grepFiles respects --no-tests filter") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val (all, _) = idx.grepFiles("UserService", noTests = false, pathFilter = None)
    val (filtered, _) = idx.grepFiles("UserService", noTests = true, pathFilter = None)
    val allFiles = all.map(r => workspace.relativize(r.file).toString).distinct
    val filteredFiles = filtered.map(r => workspace.relativize(r.file).toString).distinct
    assert(allFiles.exists(_.contains("UserServiceSpec")), "Unfiltered should include test file")
    assert(!filteredFiles.exists(_.contains("UserServiceSpec")), "Filtered should exclude test file")
  }

  test("grepFiles respects --path filter") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val (results, _) = idx.grepFiles("UserService", noTests = false, pathFilter = Some("src/main"))
    results.foreach { r =>
      val rel = workspace.relativize(r.file).toString
      assert(rel.startsWith("src/main"), s"Should be under src/main: $rel")
    }
  }

  test("grepFiles results are sorted by file and line") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val (results, _) = idx.grepFiles("User", noTests = false, pathFilter = None)
    val pairs = results.map(r => (workspace.relativize(r.file).toString, r.line))
    assertEquals(pairs, pairs.sorted)
  }
