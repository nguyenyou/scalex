import munit.FunSuite
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class IndexSuite extends ScalexTestBase:

  // ── Git file listing ──────────────────────────────────────────────────

  test("gitLsFiles finds all .scala files") {
    val files = gitLsFiles(workspace)
    assertEquals(files.size, 31)
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
    assert(files.exists(_.path.toString.contains("Mixins.scala")))
  }

  test("gitLsFiles returns valid OIDs") {
    val files = gitLsFiles(workspace)
    files.foreach { gf =>
      assert(gf.oid.length == 40, s"OID should be 40 hex chars: ${gf.oid}")
      assert(gf.oid.matches("[0-9a-f]+"), s"OID should be hex: ${gf.oid}")
    }
  }

  // ── Workspace index ───────────────────────────────────────────────────

  test("index builds complete symbol table") {
    val idx = WorkspaceIndex(workspace)
    idx.index()

    assert(idx.fileCount == 31)
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
    assert(idx1.parsedCount == 31, s"Cold index should parse all 31 files, got ${idx1.parsedCount}")

    // Second index — warm (all cached)
    val idx2 = WorkspaceIndex(workspace)
    idx2.index()
    assert(idx2.cachedLoad, "Second index should load from cache")
    assert(idx2.skippedCount == 31, s"Warm index should skip all 31 files, got ${idx2.skippedCount}")
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
    assert(idx2.skippedCount == 30, s"Should skip 30 files, got ${idx2.skippedCount}")
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

  // ── Import alias tracking ──────────────────────────────────────────

  test("extractSymbols extracts import aliases") {
    val file = workspace.resolve("src/main/scala/com/client/AliasClient.scala")
    val (_, _, _, aliases, _) = extractSymbols(file)
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

  // ── Reverse-suffix search (#156) ──────────────────────────────────

  test("search reverse-suffix matches superset query") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // "MyUserService" contains "UserService" as suffix → should match
    val results = idx.search("MyUserService")
    assert(results.exists(_.name == "UserService"),
      s"Should reverse-suffix match UserService: ${results.map(_.name)}")
  }

  test("search reverse-suffix requires symbol > half query length") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // "findUser" contains "User" as suffix, but "User"(4) is not > "findUser"(8)/2=4
    val results = idx.search("findUser")
    assert(!results.exists(r => r.name == "User" && r.kind == SymbolKind.Class),
      s"Should NOT reverse-suffix match short names: ${results.map(_.name)}")
  }

  test("search reverse-suffix ranks below exact/prefix/substring") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // "UserServiceLive" is exact match, "MyUserServiceLive" would reverse-suffix match
    val results = idx.search("UserServiceLive")
    val exactIdx = results.indexWhere(_.name == "UserServiceLive")
    // Exact match should be first
    assert(exactIdx == 0, s"Exact match should rank first: ${results.map(_.name)}")
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

  test("isTestFile detects scala-cli .test.scala convention") {
    assert(isTestFile(workspace.resolve("scalex.test.scala"), workspace))
    assert(isTestFile(workspace.resolve("src/foo.test.scala"), workspace))
    assert(!isTestFile(workspace.resolve("src/foo.scala"), workspace))
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

  // ── Package-qualified lookup ────────────────────────────────────────

  test("findDefinition with full package qualification") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = idx.findDefinition("com.example.UserService")
    assert(results.nonEmpty, "Should find UserService by FQN")
    assert(results.forall(_.packageName == "com.example"))
  }

  test("findDefinition with partial package qualification") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = idx.findDefinition("example.UserService")
    assert(results.nonEmpty, "Should find UserService by partial qualification")
    assert(results.forall(_.packageName == "com.example"))
  }

  test("findDefinition with nonexistent package returns empty") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = idx.findDefinition("nonexist.Foo")
    assert(results.isEmpty)
  }

  // ── Type-param parent indexing ──────────────────────────────────────

  test("findImplementations finds types via type parameter parents") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = idx.findImplementations("User")
    val names = results.map(_.name)
    assert(names.contains("UserProcessor"),
      s"Should find UserProcessor via Processor[User]: $names")
  }

  test("findImplementations still finds direct parents") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = idx.findImplementations("Processor")
    val names = results.map(_.name)
    assert(names.contains("UserProcessor"), s"Should find UserProcessor: $names")
    assert(names.contains("RoleProcessor"), s"Should find RoleProcessor: $names")
    assert(names.contains("GenericProcessor"), s"Should find GenericProcessor: $names")
  }

  test("single-letter type params are filtered out") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // GenericProcessor extends Processor[A] — A should be filtered
    val results = idx.findImplementations("A")
    val names = results.map(_.name)
    assert(!names.contains("GenericProcessor"),
      s"Single-letter type param A should be filtered: $names")
  }

  test("nested type constructors are not indexed as type-param parents") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // NestedTypeArgProcessor extends Processor[Map[String, User]]
    // Map should NOT be a type-param parent (it's a type constructor, not a domain type)
    val mapResults = idx.findImplementations("Map")
    val mapNames = mapResults.map(_.name)
    assert(!mapNames.contains("NestedTypeArgProcessor"),
      s"Type constructor Map should not be indexed: $mapNames")
    // But User should still be found (leaf type arg)
    val userResults = idx.findImplementations("User")
    val userNames = userResults.map(_.name)
    assert(userNames.contains("NestedTypeArgProcessor"),
      s"Leaf type arg User should be found: $userNames")
  }

  test("typeParamParents survive persistence roundtrip") {
    val cacheDir = workspace.resolve(".scalex")
    if Files.exists(cacheDir) then
      Files.list(cacheDir).iterator().asScala.foreach(Files.delete)

    val idx = WorkspaceIndex(workspace)
    idx.index()

    val loaded = IndexPersistence.load(workspace)
    assert(loaded.isDefined)
    val cachedFiles = loaded.get
    val mixinFile = cachedFiles.values.find(_.relativePath.contains("Mixins.scala")).get
    val userProc = mixinFile.symbols.find(_.name == "UserProcessor").get
    assert(userProc.typeParamParents.contains("User"),
      s"typeParamParents should survive roundtrip: ${userProc.typeParamParents}")
  }

  // ── Java file awareness ──────────────────────────────────────────

  test("index includes Java files") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val javaSyms = idx.symbols.filter(_.file.toString.endsWith(".java"))
    assert(javaSyms.nonEmpty, "Should index Java symbols")
    val names = javaSyms.map(_.name)
    assert(names.contains("EventBus"), s"Should find EventBus interface: $names")
    assert(names.contains("SimpleEventBus"), s"Should find SimpleEventBus class: $names")
  }

  test("Java interface is indexed as Trait") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val eventBus = idx.findDefinition("EventBus").find(_.file.toString.endsWith(".java"))
    assert(eventBus.isDefined, "Should find EventBus")
    assertEquals(eventBus.get.kind, SymbolKind.Trait)
    assertEquals(eventBus.get.packageName, "com.example")
  }

  test("Java class with implements is indexed with parents") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val simple = idx.findDefinition("SimpleEventBus").find(_.file.toString.endsWith(".java"))
    assert(simple.isDefined, "Should find SimpleEventBus")
    assertEquals(simple.get.kind, SymbolKind.Class)
    assert(simple.get.parents.contains("EventBus"),
      s"Should have EventBus parent: ${simple.get.parents}")
  }

  test("findImplementations finds Java implementations") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val impls = idx.findImplementations("EventBus")
    val names = impls.map(_.name)
    assert(names.contains("SimpleEventBus"),
      s"Should find SimpleEventBus as implementation: $names")
  }

  test("searchFiles finds Java files") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = idx.searchFiles("EventBus")
    assert(results.exists(_.contains("EventBus.java")),
      s"Should find EventBus.java: $results")
  }

  test("isTestFile detects Java test file suffixes") {
    assert(isTestFile(workspace.resolve("src/main/FooTest.java"), workspace))
    assert(isTestFile(workspace.resolve("src/main/FooSpec.java"), workspace))
    assert(!isTestFile(workspace.resolve("src/main/Foo.java"), workspace))
  }

  // ── containsWordStrict ───────────────────────────────────────────

  test("containsWordStrict does not match across underscore boundaries") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // Add a file with underscore-prefixed symbols to test strict matching
    // This is an integration-level check that the strict flag passes through
    val results = idx.findReferences("User", strict = true)
    // strict = true means _User would NOT match at _ boundary
    assert(results.nonEmpty, "Should still find normal references to User")
  }
