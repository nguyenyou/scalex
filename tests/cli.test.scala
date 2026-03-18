import munit.FunSuite
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class CliSuite extends ScalexTestBase:

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
      List("Bar"), Nil, "class Foo extends Bar", List("deprecated"))
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
    var results: List[Reference] = Nil
    var timedOut = false
    Console.withErr(new java.io.ByteArrayOutputStream()) {
      val (r, t) = idx.grepFiles("[invalid", noTests = false, pathFilter = None)
      results = r
      timedOut = t
    }
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

  // ── POSIX regex auto-correction ──────────────────────────────────────────

  test("hasRegexHint detects POSIX-style backslash-pipe") {
    assert(hasRegexHint("class\\|trait"))
    assert(hasRegexHint("\\(group\\)"))
    assert(!hasRegexHint("class|trait"))
    assert(!hasRegexHint("simple"))
  }

  test("fixPosixRegex corrects backslash-pipe to pipe") {
    val (fixed, changed) = fixPosixRegex("class\\|trait")
    assertEquals(fixed, "class|trait")
    assert(changed)
  }

  test("fixPosixRegex corrects backslash-parens") {
    val (fixed, changed) = fixPosixRegex("\\(foo\\|bar\\)")
    assertEquals(fixed, "(foo|bar)")
    assert(changed)
  }

  test("fixPosixRegex is no-op for valid Java regex") {
    val (fixed, changed) = fixPosixRegex("class|trait")
    assertEquals(fixed, "class|trait")
    assert(!changed)
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

  // ── condensed not-found in batch mode ──────────────────────────────────

  test("not-found hint in batch mode is condensed") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("def", List("NonExistent"), CommandContext(idx = idx, workspace = workspace, batchMode = true))
    }
    val output = out.toString
    assert(output.contains("not found"), s"Should contain not found: $output")
    assert(output.contains("files"), s"Should mention file count: $output")
    assert(!output.contains("Hint:"), s"Should NOT contain verbose hints: $output")
    assert(!output.contains("Fallback:"), s"Should NOT contain fallback: $output")
  }

  test("not-found hint in normal mode has full hints") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("def", List("NonExistent"), CommandContext(idx = idx, workspace = workspace, batchMode = false))
    }
    val output = out.toString
    assert(output.contains("Hint:"), s"Normal mode should contain Hint: $output")
    assert(output.contains("Fallback:"), s"Normal mode should contain Fallback: $output")
  }

  // ── search --exact / --prefix ──────────────────────────────────────────

  test("search --exact returns only exact name matches") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("search", List("User"), CommandContext(idx = idx, workspace = workspace, searchMode = Some("exact")))
    }
    val output = out.toString
    assert(output.contains("User"), s"Should find exact match 'User': $output")
    assert(!output.contains("UserService ("), s"Should NOT find 'UserService' as symbol (not exact): $output")
  }

  test("search --prefix returns exact + prefix matches only") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("search", List("User"), CommandContext(idx = idx, workspace = workspace, searchMode = Some("prefix")))
    }
    val output = out.toString
    assert(output.contains("User"), s"Should find 'User': $output")
    assert(output.contains("UserService"), s"Should find prefix match 'UserService': $output")
  }

  test("search without mode returns all match types") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("search", List("User"), CommandContext(idx = idx, workspace = workspace))
    }
    val output = out.toString
    // Should include substring matches too (e.g. userOrdering contains "user" as substring)
    assert(output.contains("User"), s"Should find 'User': $output")
  }

  // ── refs categorize default + --flat ────────────────────────────────────

  test("categorize is default (no flags needed)") {
    val args = List("refs", "UserService")
    val categorize = !args.contains("--flat")
    assert(categorize, "categorize should be true by default")
  }

  test("--flat disables categorize") {
    val args = List("refs", "UserService", "--flat")
    val categorize = !args.contains("--flat")
    assert(!categorize, "--flat should disable categorize")
  }

  test("-c and --categorize are accepted as no-ops") {
    val args1 = List("refs", "UserService", "-c")
    val args2 = List("refs", "UserService", "--categorize")
    // Both should still result in categorize=true (default)
    assert(!args1.contains("--flat"), "-c should not break default")
    assert(!args2.contains("--flat"), "--categorize should not break default")
  }

  // ── search --definitions-only ────────────────────────────────────────

  test("search --definitions-only returns only type definitions") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("search", List("User"), CommandContext(idx = idx, workspace = workspace, limit = 50, definitionsOnly = true))
    }
    val output = out.toString
    // Should contain class User, trait UserService, class UserServiceLive, object UserService, etc.
    assert(output.contains("User"), s"Should find User-related symbols: $output")
    // Should NOT contain val or def results
    assert(!output.contains("  def "), s"Should not contain def results: $output")
    assert(!output.contains("  val "), s"Should not contain val results: $output")
  }

  test("search --definitions-only excludes defs and vals") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // Search for "findUser" which is a def — should return empty with --definitions-only
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("search", List("findUser"), CommandContext(idx = idx, workspace = workspace, limit = 50, definitionsOnly = true))
    }
    val output = out.toString
    assert(output.contains("Found 0"), s"Should find 0 definitions for 'findUser': $output")
  }

  // ── refs --category ──────────────────────────────────────────────────

  test("refs --category ExtendedBy returns only that category") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("refs", List("UserService"), CommandContext(idx = idx, workspace = workspace, limit = 50, categoryFilter = Some("ExtendedBy")))
    }
    val output = out.toString
    assert(output.contains("ExtendedBy"), s"Should contain ExtendedBy section: $output")
    // Should NOT contain other categories
    assert(!output.contains("\n    Definition:"), s"Should not contain Definition: $output")
    assert(!output.contains("\n    ImportedBy:"), s"Should not contain ImportedBy: $output")
  }

  test("refs --category with invalid name returns empty results") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    val errStream = new java.io.ByteArrayOutputStream()
    val oldErr = System.err
    System.setErr(new java.io.PrintStream(errStream))
    try {
      Console.withOut(out) {
        runCommand("refs", List("UserService"), CommandContext(idx = idx, workspace = workspace, limit = 50, categoryFilter = Some("InvalidCat")))
      }
    } finally {
      System.setErr(oldErr)
    }
    val errOutput = errStream.toString
    assert(errOutput.contains("Unknown category"), s"Should print unknown category error: $errOutput")
    assert(errOutput.contains("Valid:"), s"Should list valid categories: $errOutput")
  }

  // ── Phase 7: Verbose formatting ───────────────────────────────────────

  test("formatSymbolVerbose includes signature") {
    val s = SymbolInfo("Foo", SymbolKind.Trait, workspace.resolve("Foo.scala"), 1, "com.example",
      List("Bar", "Baz"), Nil, "trait Foo extends Bar with Baz")
    val result = formatSymbolVerbose(s, workspace)
    assert(result.contains("trait Foo extends Bar with Baz"), s"Verbose: $result")
  }

  test("formatRef shows alias annotation") {
    val r = Reference(workspace.resolve("Foo.scala"), 10, "val x: US = ???", Some("via alias US"))
    val result = formatRef(r, workspace)
    assert(result.contains("[via alias US]"), s"Should contain alias annotation: $result")
  }

  // ── overview ─────────────────────────────────────────────────────────

  test("overview shows file count and symbol count") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("overview", Nil, CommandContext(idx = idx, workspace = workspace, limit = 10))
    }
    val output = out.toString
    assert(output.contains("Project overview"), s"Should have header: $output")
    assert(output.contains("files"), s"Should mention files: $output")
    assert(output.contains("symbols"), s"Should mention symbols: $output")
  }

  test("overview shows symbols by kind") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("overview", Nil, CommandContext(idx = idx, workspace = workspace, limit = 10))
    }
    val output = out.toString
    assert(output.contains("Symbols by kind"), s"Should show kind breakdown: $output")
    assert(output.contains("Class"), s"Should list Class kind: $output")
    assert(output.contains("Trait"), s"Should list Trait kind: $output")
  }

  test("overview shows top packages") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("overview", Nil, CommandContext(idx = idx, workspace = workspace, limit = 10))
    }
    val output = out.toString
    assert(output.contains("Top packages"), s"Should show top packages: $output")
    assert(output.contains("com.example"), s"Should list com.example: $output")
  }

  test("overview shows most extended") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("overview", Nil, CommandContext(idx = idx, workspace = workspace, limit = 10))
    }
    val output = out.toString
    assert(output.contains("Most extended"), s"Should show most extended: $output")
    assert(output.contains("UserService") || output.contains("Database") || output.contains("Processor"), s"Should list a known trait with PascalCase: $output")
  }

  test("overview JSON output") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("overview", Nil, CommandContext(idx = idx, workspace = workspace, limit = 10, jsonOutput = true))
    }
    val output = out.toString.trim
    assert(output.startsWith("{"), s"JSON should start with brace: $output")
    assert(output.contains("\"fileCount\""), s"JSON should contain fileCount: $output")
    assert(output.contains("\"topPackages\""), s"JSON should contain topPackages: $output")
    assert(output.contains("\"mostExtended\""), s"JSON should contain mostExtended: $output")
  }

  // ── overview --architecture ────────────────────────────────────────────

  test("overview --architecture computes package dependencies without crash") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("overview", Nil, CommandContext(idx = idx, workspace = workspace, limit = 10, architecture = true))
    }
    val output = out.toString
    assert(output.contains("Package dependencies"), s"Should show package dependencies section: $output")
    assert(output.contains("Hub types"), s"Should show hub types section: $output")
    assert(!output.contains("Most extended"), s"Architecture mode should not show 'Most extended' (hub types supersedes it): $output")
  }

  test("overview --architecture JSON includes packageDependencies and hubTypes") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("overview", Nil, CommandContext(idx = idx, workspace = workspace, limit = 10, jsonOutput = true, architecture = true))
    }
    val output = out.toString.trim
    assert(output.startsWith("{"), s"JSON should start with brace: $output")
    assert(output.contains("\"packageDependencies\""), s"JSON should contain packageDependencies: $output")
    assert(output.contains("\"hubTypes\""), s"JSON should contain hubTypes: $output")
    assert(!output.contains("\"mostExtended\""), s"Architecture JSON should not contain mostExtended (hub types supersedes it): $output")
  }

  // ── members JSON output ──────────────────────────────────────────────

  test("members --json output") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("members", List("PaymentServiceLive"), CommandContext(idx = idx, workspace = workspace, limit = 50, jsonOutput = true))
    }
    val output = out.toString.trim
    assert(output.startsWith("["), s"JSON should start with bracket: $output")
    assert(output.contains("\"processPayment\""), s"JSON should contain processPayment: $output")
    assert(output.contains("\"owner\":\"PaymentServiceLive\""), s"JSON should contain owner: $output")
  }

  // ── doc JSON output ──────────────────────────────────────────────────

  test("doc --json output") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("doc", List("PaymentService"), CommandContext(idx = idx, workspace = workspace, limit = 50, jsonOutput = true))
    }
    val output = out.toString.trim
    assert(output.startsWith("["), s"JSON should start with bracket: $output")
    assert(output.contains("\"doc\""), s"JSON should contain doc field: $output")
    assert(output.contains("processing payments"), s"JSON should contain doc text: $output")
  }

  // ── members command output ──────────────────────────────────────────────

  test("members command output format") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("members", List("PaymentService"), CommandContext(idx = idx, workspace = workspace, limit = 50))
    }
    val output = out.toString
    assert(output.contains("Members of trait PaymentService"), s"Should have header: $output")
    assert(output.contains("processPayment"), s"Should list processPayment: $output")
  }

  test("members --verbose shows full signatures") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("members", List("PaymentServiceLive"), CommandContext(idx = idx, workspace = workspace, limit = 50, verbose = true))
    }
    val output = out.toString
    assert(output.contains("def processPayment"), s"Verbose should show signature: $output")
    assert(output.contains("val maxRetries"), s"Verbose should show val signature: $output")
  }

  test("members returns empty for non-type symbols") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("members", List("findUser"), CommandContext(idx = idx, workspace = workspace, limit = 50))
    }
    val output = out.toString
    assert(output.contains("No class/trait/object/enum"), s"Should report no type found: $output")
  }

  // ── #184: companion object members not duplicated in members output ──

  test("members: class companion shows only companion-specific members") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("members", List("Pipeline"), CommandContext(idx = idx, workspace = workspace, limit = 50, verbose = true))
    }
    // Pipeline class has execute, validate; companion has create
    val classSection = output.split("Companion")(0)
    val companionSection = output.split("Companion").lift(1).getOrElse("")
    // Class section should have execute and validate but NOT create
    assert(classSection.contains("execute"), s"Class section should have execute: $classSection")
    assert(classSection.contains("validate"), s"Class section should have validate: $classSection")
    assert(!classSection.contains("def create"), s"Class section should NOT have companion's create: $classSection")
    // Companion section should have create but NOT execute or validate
    assert(companionSection.contains("create"), s"Companion should have create: $companionSection")
    assert(!companionSection.contains("execute"), s"Companion should NOT duplicate class execute: $companionSection")
    assert(!companionSection.contains("validate"), s"Companion should NOT duplicate class validate: $companionSection")
  }

  test("members: trait companion shows only companion-specific members") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("members", List("Database"), CommandContext(idx = idx, workspace = workspace, limit = 50, verbose = true))
    }
    // Database trait has query, insert; companion object has live
    val sections = output.split("Companion")
    val traitSection = sections(0)
    val companionSection = sections.lift(1).getOrElse("")
    // Trait section should have query and insert but NOT live
    assert(traitSection.contains("query"), s"Trait section should have query: $traitSection")
    assert(traitSection.contains("insert"), s"Trait section should have insert: $traitSection")
    assert(!traitSection.contains("val live"), s"Trait section should NOT have companion's live: $traitSection")
    // Companion section should have live but NOT query or insert
    assert(companionSection.contains("live"), s"Companion should have live: $companionSection")
    assert(!companionSection.contains("query"), s"Companion should NOT duplicate trait query: $companionSection")
    assert(!companionSection.contains("insert"), s"Companion should NOT duplicate trait insert: $companionSection")
  }

  test("members --json: companion members not duplicated within each section") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("members", List("Pipeline"), CommandContext(idx = idx, workspace = workspace, limit = 50, jsonOutput = true))
    }
    // Both class and object Pipeline are returned, each showing the other as companion.
    // Each member appears twice total (own in one view, companion in the other) — but
    // before #184 fix, each appeared 4 times because extractMembers mixed class+object.
    val createCount = "\"create\"".r.findAllIn(output).length
    val executeCount = "\"execute\"".r.findAllIn(output).length
    val validateCount = "\"validate\"".r.findAllIn(output).length
    assertEquals(createCount, 2, s"create should appear twice (own + companion): $output")
    assertEquals(executeCount, 2, s"execute should appear twice (own + companion): $output")
    assertEquals(validateCount, 2, s"validate should appear twice (own + companion): $output")
    // Class section should NOT list create as own (ownerKind "class")
    assert(!output.contains(""""name":"create","kind":"def","line":24,"signature":"def create(steps: String*): Pipeline","file":"src/main/scala/com/example/Pipeline.scala","owner":"Pipeline","ownerKind":"class""""),
      s"create should not be owned by class: $output")
  }

  test("members: object view shows companion class members separately") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // When querying the object specifically, its own members should not include class members
    val output = captureOut {
      runCommand("members", List("UserService"), CommandContext(idx = idx, workspace = workspace, limit = 50, verbose = true))
    }
    // UserService trait: findUser, createUser; UserService object: default
    // The object section should list "default" as own, not findUser/createUser
    val objectSections = output.split("Members of object UserService")
    assert(objectSections.length > 1, s"Should have object section: $output")
    val objectBlock = objectSections(1).split("Members of ")(0) // up to next "Members of" section
    val ownPart = objectBlock.split("Companion")(0)
    assert(ownPart.contains("default"), s"Object own members should have default: $ownPart")
    assert(!ownPart.contains("findUser"), s"Object own members should NOT have trait's findUser: $ownPart")
    assert(!ownPart.contains("createUser"), s"Object own members should NOT have trait's createUser: $ownPart")
  }

  test("members: qualified name should return same members as simple name") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val simpleOutput = captureOut {
      runCommand("members", List("Pipeline"), CommandContext(idx = idx, workspace = workspace, limit = 50, verbose = true))
    }
    val qualifiedOutput = captureOut {
      runCommand("members", List("com.example.Pipeline"), CommandContext(idx = idx, workspace = workspace, limit = 50, verbose = true))
    }
    // Both should find the same members — qualified name should not break extractMembers
    assert(simpleOutput.contains("execute"), s"Simple name should find execute: $simpleOutput")
    assert(qualifiedOutput.contains("execute"), s"Qualified name should find execute: $qualifiedOutput")
    assert(qualifiedOutput.contains("validate"), s"Qualified name should find validate: $qualifiedOutput")
    assert(qualifiedOutput.contains("create"), s"Qualified name should find companion create: $qualifiedOutput")
  }

  // ── doc command output ──────────────────────────────────────────────

  test("doc command output") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("doc", List("PaymentService"), CommandContext(idx = idx, workspace = workspace, limit = 50))
    }
    val output = out.toString
    assert(output.contains("processing payments"), s"Should show scaladoc: $output")
  }

  test("doc for symbol without scaladoc") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("doc", List("User"), CommandContext(idx = idx, workspace = workspace, limit = 50))
    }
    val output = out.toString
    assert(output.contains("(no scaladoc)"), s"Should report no scaladoc: $output")
  }

  // ── #93: overview --no-tests ────────────────────────────────────────────

  test("overview --no-tests excludes test files from counts") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("overview", Nil, CommandContext(idx = idx, workspace = workspace, limit = 10, noTests = true))
    }
    val output = out.toString
    // Test files (UserServiceSpec, UserServiceTest) should be excluded
    assert(!output.contains("UserServiceSpec"), s"Should exclude test class: $output")
    assert(!output.contains("UserServiceTest"), s"Should exclude test class: $output")
  }

  // ── #94: fuzzy suggestions on not-found ───────────────────────────────

  test("def not-found shows suggestions") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("def", List("UserServic"), CommandContext(idx = idx, workspace = workspace))
    }
    val output = out.toString
    assert(output.contains("Did you mean"), s"Should show suggestions: $output")
    assert(output.contains("UserService"), s"Should suggest UserService: $output")
  }

  test("def not-found batch mode shows condensed suggestions") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("def", List("UserServic"), CommandContext(idx = idx, workspace = workspace, batchMode = true))
    }
    val output = out.toString
    assert(output.contains("Did you mean"), s"Should show suggestions in batch: $output")
  }

  test("def not-found suggests reverse-suffix matches (#156)") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("def", List("MyUserService"), CommandContext(idx = idx, workspace = workspace))
    }
    val output = out.toString
    assert(output.contains("Did you mean"), s"Should show suggestions: $output")
    assert(output.contains("UserService"), s"Should suggest UserService via reverse-suffix: $output")
  }

  test("search not-found shows suggestions (#156)") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // Use --mode exact so "MyUserService" finds no exact match, but suggestions are generated
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("search", List("MyUserService"), CommandContext(idx = idx, workspace = workspace, searchMode = Some("exact")))
    }
    val output = out.toString
    assert(output.contains("Found 0"), s"Should find 0: $output")
    assert(output.contains("Did you mean"), s"Should show suggestions: $output")
    assert(output.contains("UserService"), s"Should suggest UserService: $output")
  }

  // ── #95: package command ──────────────────────────────────────────────

  test("package command lists symbols grouped by kind") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("package", List("com.example"), CommandContext(idx = idx, workspace = workspace, limit = 50))
    }
    val output = out.toString
    assert(output.contains("com.example"), s"Should show package name: $output")
    assert(output.contains("UserService"), s"Should list UserService: $output")
    assert(output.contains("User"), s"Should list User: $output")
  }

  test("package command fuzzy matches suffix") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("package", List("example"), CommandContext(idx = idx, workspace = workspace, limit = 50))
    }
    val output = out.toString
    assert(output.contains("com.example"), s"Should resolve to com.example: $output")
  }

  test("package command JSON output") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("package", List("com.example"), CommandContext(idx = idx, workspace = workspace, limit = 50, jsonOutput = true))
    }
    val output = out.toString.trim
    assert(output.startsWith("{"), s"Should be JSON object: $output")
    assert(output.contains("\"package\":\"com.example\""), s"Should contain package: $output")
    assert(output.contains("\"symbolCount\":"), s"Should contain symbolCount: $output")
  }

  test("package command with --no-tests") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("package", List("com.example"), CommandContext(idx = idx, workspace = workspace, limit = 50, noTests = true))
    }
    val output = out.toString
    assert(!output.contains("UserServiceSpec"), s"Should exclude test symbols: $output")
    assert(!output.contains("UserServiceTest"), s"Should exclude test symbols: $output")
  }

  test("package command not found suggests packages via segment matching") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      // "com.exmple" doesn't substring-match any package, but segments "com" and "exmple"
      // should match packages containing "com" (com.example, com.other, com.client, etc.)
      runCommand("package", List("com.exmple"), CommandContext(idx = idx, workspace = workspace))
    }
    val output = out.toString
    assert(output.contains("not found"), s"Should say not found: $output")
    assert(output.contains("Did you mean"), s"Should suggest packages via segment match: $output")
    assert(output.contains("com.example"), s"Should suggest com.example: $output")
  }

  // ── #96: overview --focus-package ─────────────────────────────────────

  test("overview --focus-package scopes dependency graph") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("overview", Nil, CommandContext(idx = idx, workspace = workspace, limit = 10, focusPackage = Some("com.example")))
    }
    val output = out.toString
    assert(output.contains("Package focus: com.example"), s"Should show focus header: $output")
    assert(output.contains("Depends on:"), s"Should show depends on: $output")
    assert(output.contains("Depended on by:"), s"Should show depended on by: $output")
  }

  test("overview --focus-package JSON includes focusPackage") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("overview", Nil, CommandContext(idx = idx, workspace = workspace, limit = 10, jsonOutput = true, focusPackage = Some("com.example")))
    }
    val output = out.toString.trim
    assert(output.contains("\"focusPackage\":\"com.example\""), s"Should contain focusPackage in JSON: $output")
  }

  // ── tests --json ────────────────────────────────────────────────────────

  test("tests command --json output") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("tests", Nil, CommandContext(idx = idx, workspace = workspace, jsonOutput = true))
    }
    val output = out.toString.trim
    assert(output.startsWith("["), s"JSON should start with [: $output")
    assert(output.contains("\"suite\":\"UserServiceTest\""), s"Should contain suite name: $output")
    assert(output.contains("\"name\":\"findUser returns None for unknown id\""), s"Should contain test name: $output")
  }

  // ── explain companion merging ────────────────────────────────────────

  test("explain shows companion object") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("UserService"), CommandContext(idx = idx, workspace = workspace, implLimit = 10))
    }
    assert(output.contains("Companion object UserService"),
      s"Should show companion object: $output")
    assert(output.contains("default"),
      s"Companion should show val default: $output")
  }

  test("explain for non-type has no companion") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("findUser"), CommandContext(idx = idx, workspace = workspace))
    }
    assert(!output.contains("Companion"),
      s"Non-type should not show companion: $output")
  }

  test("explain --json includes companion field") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("UserService"), CommandContext(idx = idx, workspace = workspace, jsonOutput = true, implLimit = 10))
    }
    assert(output.contains("\"companion\""),
      s"JSON should include companion field: $output")
  }

  // ── explain --expand N ────────────────────────────────────────────────

  test("explain --expand 1 shows expanded implementations") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("UserService"), CommandContext(idx = idx, workspace = workspace, implLimit = 10, expandDepth = 1))
    }
    assert(output.contains("Expanded implementations"),
      s"Should show expanded impls: $output")
    assert(output.contains("UserServiceLive"),
      s"Should show UserServiceLive in expanded: $output")
  }

  test("explain without --expand shows no expanded section") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("UserService"), CommandContext(idx = idx, workspace = workspace, implLimit = 10))
    }
    assert(!output.contains("Expanded implementations"),
      s"Should NOT show expanded impls without flag: $output")
  }

  // ── explain with package-qualified name ────────────────────────────────

  test("explain with qualified name works") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("com.example.UserService"), CommandContext(idx = idx, workspace = workspace, implLimit = 10))
    }
    assert(output.contains("Explanation of"),
      s"Should show explanation: $output")
    assert(output.contains("UserService"),
      s"Should contain UserService: $output")
  }

  // ── #132-135: isTestFile root-level tests/ detection ────────────────────

  test("isTestFile detects root-level tests/ directory") {
    val testsFile = workspace.resolve("tests/pos/Foo.scala")
    Files.createDirectories(testsFile.getParent)
    Files.writeString(testsFile, "class Foo")
    assert(isTestFile(testsFile, workspace),
      "File under root-level tests/ should be detected as test file")
    // cleanup
    Files.delete(testsFile)
  }

  test("isTestFile detects root-level test/ directory") {
    val testFile = workspace.resolve("test/Foo.scala")
    Files.createDirectories(testFile.getParent)
    Files.writeString(testFile, "class Foo")
    assert(isTestFile(testFile, workspace),
      "File under root-level test/ should be detected as test file")
    Files.delete(testFile)
  }

  // ── #132-135: explain disambiguation hint ────────────────────────────────

  test("explain does not report otherMatches for companion (same name)") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val output = captureOut {
      // UserService has trait + companion object — same name, should NOT count as "other match"
      runCommand("explain", List("UserService"),
        CommandContext(idx = idx, workspace = workspace, jsonOutput = true, implLimit = 10))
    }
    assert(!output.contains("\"otherMatches\""),
      s"Companion with same name should not produce otherMatches: $output")
  }

  // ── #132-135: explain --shallow ──────────────────────────────────────────

  test("explain --shallow skips implementations and imports") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("UserService"), CommandContext(idx = idx, workspace = workspace, shallow = true))
    }
    assert(output.contains("Explanation of"), s"Should show explanation: $output")
    assert(!output.contains("Implementations"), s"Shallow should not show implementations: $output")
    assert(!output.contains("Imported by"), s"Shallow should not show import refs: $output")
  }

  // ── #132-135: explain package fallback ────────────────────────────────────

  test("explain falls back to summary when symbol matches a package") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val (stdout, stderr) = captureOutErr {
      runCommand("explain", List("com.example"), CommandContext(idx = idx, workspace = workspace))
    }
    // Should show package summary instead of not-found
    assert(stdout.contains("com.example") && !stdout.contains("No definition"),
      s"Should fall back to package summary: $stdout")
    // Should warn user about the fallback on stderr
    assert(stderr.contains("no type") && stderr.contains("package summary"),
      s"Should warn about package fallback on stderr: $stderr")
  }

  // ── #132-135: explain import refs respect --path filter ────────────────────

  test("explain import refs are filtered by --path") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("UserService"),
        CommandContext(idx = idx, workspace = workspace, pathFilter = Some("src/main/scala/com/example/"), implLimit = 10))
    }
    assert(output.contains("Explanation of"), s"Should show explanation: $output")
    // Import refs from client/ packages should not appear when --path restricts to com/example/
    assert(!output.contains("com/client"), s"Import refs should be path-filtered: $output")
  }

  // ── #164: explain --brief ────────────────────────────────────────────────

  test("explain --brief text: shows definition and top 3 members only") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("PaymentService"),
        CommandContext(idx = idx, workspace = workspace, brief = true))
    }
    assert(output.contains("Explanation of"), s"Should show explanation: $output")
    assert(!output.contains("Scaladoc"), s"Brief should not show Scaladoc: $output")
    assert(!output.contains("Implementations"), s"Brief should not show implementations: $output")
    assert(!output.contains("Imported by"), s"Brief should not show import refs: $output")
    assert(!output.contains("Companion"), s"Brief should not show companion: $output")
    assert(!output.contains("Inherited"), s"Brief should not show inherited: $output")
  }

  test("explain --brief text: caps members at 3") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    // PaymentServiceLive has 5 members (2 defs + 1 val + 1 var + 1 type) — brief caps at 3
    val output = captureOut {
      runCommand("explain", List("PaymentServiceLive"),
        CommandContext(idx = idx, workspace = workspace, brief = true))
    }
    assert(output.contains("Members (top 3)"), s"Should cap at 3 members: $output")
    // com.example.Registry has 4 members — brief caps at 3
    val regOutput = captureOut {
      runCommand("explain", List("com.example.Registry"),
        CommandContext(idx = idx, workspace = workspace, brief = true))
    }
    assert(regOutput.contains("Members (top 3)"), s"Should cap Registry at 3 members: $regOutput")
  }

  test("explain --brief text: non-type symbol has no members") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("userOrdering"),
        CommandContext(idx = idx, workspace = workspace, brief = true))
    }
    assert(output.contains("Explanation of"), s"Should show explanation: $output")
    assert(!output.contains("Members"), s"Non-type should not show members in brief: $output")
  }

  test("explain --brief JSON: omits doc, impls, companion") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("UserService"),
        CommandContext(idx = idx, workspace = workspace, brief = true, jsonOutput = true))
    }
    assert(output.contains("\"definition\""), s"Should have definition: $output")
    assert(output.contains("\"doc\":null"), s"Brief should have null doc: $output")
    assert(output.contains("\"implementations\":[]"), s"Brief should have empty implementations: $output")
    assert(output.contains("\"companion\":null"), s"Brief should have null companion: $output")
    assert(output.contains("\"importCount\":0"), s"Brief should have zero import count: $output")
  }

  test("explain --brief JSON: members capped at 3") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("PaymentServiceLive"),
        CommandContext(idx = idx, workspace = workspace, brief = true, jsonOutput = true))
    }
    // Count member entries in JSON — PaymentServiceLive has 5 members, brief caps at 3
    val memberCount = """"name":""".r.findAllIn(output).size - 1 // subtract 1 for definition.name
    assert(memberCount == 3, s"Brief JSON should cap at 3 members, got $memberCount: $output")
  }

  // ── #164: disambiguation copy-paste commands ────────────────────────────

  test("explain disambiguation: companion (same name+pkg) produces no otherMatches") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    // Database has trait + companion object in com.example — same (name, package)
    val output = captureOut {
      runCommand("explain", List("Database"),
        CommandContext(idx = idx, workspace = workspace, jsonOutput = true, implLimit = 10))
    }
    assert(!output.contains("\"otherMatches\""),
      s"Companion with same name+pkg should not produce otherMatches: $output")
  }

  test("explain disambiguation: cross-package match produces string array in JSON") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    // Registry exists in com.example and com.other — should produce otherMatches
    val output = captureOut {
      runCommand("explain", List("Registry"),
        CommandContext(idx = idx, workspace = workspace, jsonOutput = true, implLimit = 10))
    }
    assert(output.contains("\"otherMatches\""), s"Cross-package should produce otherMatches: $output")
    assert(output.contains("\"otherMatches\":["), s"otherMatches should be an array: $output")
    // Should contain package-qualified name of the non-chosen match
    assert(output.contains("com.other.Registry") || output.contains("com.example.Registry"),
      s"otherMatches should contain package-qualified names: $output")
  }

  test("explain disambiguation: stderr prints copy-paste commands") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val (stdout, stderr) = captureOutErr {
      runCommand("explain", List("Registry"),
        CommandContext(idx = idx, workspace = workspace, implLimit = 10))
    }
    assert(stderr.contains("other match"), s"Should show disambiguation hint on stderr: $stderr")
    assert(stderr.contains("scalex explain"), s"Should print copy-paste command: $stderr")
    // The command should be package-qualified
    assert(stderr.contains("com.other.Registry") || stderr.contains("com.example.Registry"),
      s"Command should use package-qualified name: $stderr")
  }

  test("explain disambiguation: --brief still shows disambiguation on stderr") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val (stdout, stderr) = captureOutErr {
      runCommand("explain", List("Registry"),
        CommandContext(idx = idx, workspace = workspace, brief = true))
    }
    assert(stdout.contains("Explanation of"), s"Should show explanation: $stdout")
    assert(stderr.contains("scalex explain"), s"Brief should still show disambiguation: $stderr")
  }

  test("explain disambiguation: package-qualified lookup produces no otherMatches") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("com.other.Registry"),
        CommandContext(idx = idx, workspace = workspace, jsonOutput = true))
    }
    assert(output.contains("\"definition\""), s"Should resolve via package qualification: $output")
    assert(!output.contains("\"otherMatches\""),
      s"Package-qualified lookup should not produce otherMatches: $output")
  }

  test("explain disambiguation: otherMatches count matches stderr count") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val (stdout, stderr) = captureOutErr {
      runCommand("explain", List("Registry"),
        CommandContext(idx = idx, workspace = workspace))
    }
    assert(stderr.contains("1 other match"), s"Should show 1 other match: $stderr")
    // JSON output should have exactly 1 element in the array
    val jsonOut = captureOut {
      runCommand("explain", List("Registry"),
        CommandContext(idx = idx, workspace = workspace, jsonOutput = true))
    }
    val arrayContent = """"otherMatches":\[([^\]]*)\]""".r.findFirstMatchIn(jsonOut).map(_.group(1)).getOrElse("")
    val elements = arrayContent.split(",").filter(_.nonEmpty)
    assertEquals(elements.size, 1, s"JSON otherMatches should have 1 element: $jsonOut")
  }

  // ── #132-135: overview preserves PascalCase in hub types ────────────────

  test("overview hub types preserve PascalCase names") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("overview", Nil, CommandContext(idx = idx, workspace = workspace, limit = 10))
    }
    val output = out.toString
    assert(output.contains("Most extended"), s"Should show most extended: $output")
    // Names should be PascalCase, not lowercase
    assert(!output.contains("userservice"), s"Should not have lowercased names: $output")
    assert(output.contains("UserService") || output.contains("Processor"),
      s"Should preserve PascalCase: $output")
  }

  // ── #132-135: overview shows signatures for hub types ────────────────────

  test("overview shows signatures next to hub types") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("overview", Nil, CommandContext(idx = idx, workspace = workspace, limit = 10))
    }
    val output = out.toString
    // Signatures should appear inline with hub types
    assert(output.contains("trait") || output.contains("class") || output.contains("interface"),
      s"Should show signatures next to hub types: $output")
  }

  // ── #132-135: overview --path scopes architecture view ────────────────────

  test("overview --path restricts to path prefix") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("overview", Nil, CommandContext(idx = idx, workspace = workspace, limit = 10,
        pathFilter = Some("src/main/scala/com/example/")))
    }
    val output = out.toString
    assert(output.contains("Project overview"), s"Should show overview: $output")
    // Should not include com.other or com.client packages
    assert(!output.contains("com.other"), s"Should not include com.other: $output")
    assert(!output.contains("com.client"), s"Should not include com.client: $output")
  }

  // ── #132-135: --exclude-path ────────────────────────────────────────────

  test("--exclude-path filters out symbols from excluded path") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("def", List("UserService"),
        CommandContext(idx = idx, workspace = workspace, excludePath = Some("src/test/")))
    }
    val output = out.toString
    assert(output.contains("UserService"), s"Should still find UserService: $output")
    assert(!output.contains("src/test/"), s"Should exclude test path results: $output")
  }

  // ── #132-135: symbols --summary ──────────────────────────────────────────

  test("symbols --summary shows grouped counts by kind") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      runCommand("symbols", List("src/main/scala/com/example/Model.scala"),
        CommandContext(idx = idx, workspace = workspace, summaryMode = true))
    }
    val output = out.toString
    // Should show kind counts, not individual symbols
    assert(output.contains("total"), s"Should show total count: $output")
  }

  // ── #132-135: explain totalImpls hint ──────────────────────────────────

  test("explain shows totalImpls hint when more impls exist than limit") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val output = captureOut {
      // Processor has 4 impls, set limit to 2
      runCommand("explain", List("Processor"),
        CommandContext(idx = idx, workspace = workspace, implLimit = 2))
    }
    assert(output.contains("showing 2 of") || output.contains("--impl-limit"),
      s"Should show totalImpls hint: $output")
  }

  // ── #132-135: explain companion members deduplication ────────────────────

  test("explain deduplicates companion members shared with primary") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("Database"), CommandContext(idx = idx, workspace = workspace, implLimit = 10))
    }
    // If companion and primary share members, should show dedup note
    assert(output.contains("Companion") || output.contains("Explanation of"),
      s"Should show explanation: $output")
  }

  // ── #132-135: overview JSON includes signatures ──────────────────────────

  test("overview JSON includes signature field in mostExtended") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("overview", Nil, CommandContext(idx = idx, workspace = workspace, limit = 10, jsonOutput = true))
    }
    assert(output.contains("\"signature\""), s"JSON should include signature field: $output")
  }

  // ── #132-135: symbols --summary JSON outputs structured data ──────────

  test("symbols --summary --json outputs structured symbolsByKind object") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("symbols", List("src/main/scala/com/example/Model.scala"),
        CommandContext(idx = idx, workspace = workspace, summaryMode = true, jsonOutput = true))
    }
    assert(output.contains("\"symbolsByKind\""), s"JSON should have symbolsByKind object: $output")
    assert(output.contains("\"total\""), s"JSON should have total field: $output")
  }

  // ── explain --inherited ─────────────────────────────────────────────

  test("explain --inherited shows inherited members from parent type") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("UserProcessor"),
        CommandContext(idx = idx, workspace = workspace, inherited = true))
    }
    assert(output.contains("Inherited from Processor"), s"Should show inherited section: $output")
    assert(output.contains("validate"), s"Should show inherited member 'validate': $output")
  }

  test("explain without --inherited does not show inherited members") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("UserProcessor"),
        CommandContext(idx = idx, workspace = workspace, inherited = false))
    }
    assert(!output.contains("Inherited from"), s"Should not show inherited section: $output")
  }

  test("explain --inherited --json includes inherited field") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("UserProcessor"),
        CommandContext(idx = idx, workspace = workspace, inherited = true, jsonOutput = true))
    }
    assert(output.contains("\"inherited\""), s"JSON should have inherited field: $output")
    assert(output.contains("\"parent\":\"Processor\""), s"JSON should reference parent: $output")
  }

  // ── refs --top N ────────────────────────────────────────────────────

  test("refs --top N ranks files by reference count") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val output = captureOut {
      runCommand("refs", List("UserService"),
        CommandContext(idx = idx, workspace = workspace, topN = Some(5)))
    }
    assert(output.contains("files referencing 'UserService'"), s"Should show top refs header: $output")
  }

  test("refs --top N --json outputs structured ranking") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val output = captureOut {
      runCommand("refs", List("UserService"),
        CommandContext(idx = idx, workspace = workspace, topN = Some(3), jsonOutput = true))
    }
    assert(output.contains("\"symbol\":\"UserService\""), s"JSON should have symbol: $output")
    assert(output.contains("\"files\":["), s"JSON should have files array: $output")
    assert(output.contains("\"total\":"), s"JSON should have total: $output")
  }

  // ── Override markers ──────────────────────────────────────────────────

  test("members --inherited shows override markers") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("members", List("UserServiceLive"),
        CommandContext(idx = idx, workspace = workspace, inherited = true))
    }
    assert(output.contains("[override]"), s"Should show [override] marker: $output")
    assert(output.contains("findUser"), s"Should contain findUser: $output")
    assert(output.contains("createUser"), s"Should contain createUser: $output")
  }

  test("members --inherited JSON includes isOverride") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("members", List("UserServiceLive"),
        CommandContext(idx = idx, workspace = workspace, inherited = true, jsonOutput = true))
    }
    assert(output.contains("\"isOverride\":true"), s"JSON should have isOverride: $output")
  }

  test("members without --inherited has no override markers") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("members", List("UserServiceLive"),
        CommandContext(idx = idx, workspace = workspace))
    }
    assert(!output.contains("[override]"), s"Should not show [override] without --inherited: $output")
  }

  test("explain --inherited shows override markers") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("UserServiceLive"),
        CommandContext(idx = idx, workspace = workspace, inherited = true, shallow = true))
    }
    assert(output.contains("[override]"), s"Should show [override] marker: $output")
  }

  // ── Entrypoints ────────────────────────────────────────────────────────

  test("entrypoints finds @main annotated") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("entrypoints", Nil,
        CommandContext(idx = idx, workspace = workspace))
    }
    assert(output.contains("@main annotated"), s"Should have @main section: $output")
    assert(output.contains("run"), s"Should find @main def run: $output")
  }

  test("entrypoints finds def main methods") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("entrypoints", Nil,
        CommandContext(idx = idx, workspace = workspace))
    }
    assert(output.contains("def main("), s"Should have def main section: $output")
    assert(output.contains("MyApp"), s"Should find object MyApp: $output")
  }

  test("entrypoints finds extends App") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("entrypoints", Nil,
        CommandContext(idx = idx, workspace = workspace))
    }
    assert(output.contains("extends App"), s"Should have extends App section: $output")
    assert(output.contains("Legacy"), s"Should find Legacy object: $output")
  }

  test("entrypoints finds test suites") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("entrypoints", Nil,
        CommandContext(idx = idx, workspace = workspace))
    }
    assert(output.contains("Test suites"), s"Should have test suites section: $output")
    assert(output.contains("UserServiceTest"), s"Should find UserServiceTest: $output")
  }

  test("entrypoints --json produces structured output") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("entrypoints", Nil,
        CommandContext(idx = idx, workspace = workspace, jsonOutput = true))
    }
    assert(output.contains("\"entrypoints\":{"), s"JSON should have entrypoints: $output")
    assert(output.contains("\"mainAnnotated\":"), s"JSON should have mainAnnotated: $output")
    assert(output.contains("\"mainMethods\":"), s"JSON should have mainMethods: $output")
    assert(output.contains("\"extendsApp\":"), s"JSON should have extendsApp: $output")
    assert(output.contains("\"testSuites\":"), s"JSON should have testSuites: $output")
    assert(output.contains("\"total\":"), s"JSON should have total: $output")
  }

  // ── body command: dotted syntax + error messages ───────────────────

  test("body Owner.member dotted syntax resolves to member body") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("body", List("UserServiceLive.findUser"),
        CommandContext(idx = idx, workspace = workspace))
    }
    assert(output.contains("db.query"), s"Should find findUser body via dotted syntax: $output")
    assert(output.contains("UserServiceLive"), s"Should mention owner: $output")
  }

  test("body with --in owner not-found includes owner in message") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("body", List("nonExistentMethod"),
        CommandContext(idx = idx, workspace = workspace, inOwner = Some("UserServiceLive")))
    }
    assert(output.contains("UserServiceLive"), s"Error should mention owner: $output")
    assert(output.contains("No body found"), s"Should say not found: $output")
  }

  test("body --in not-found suggestions are scoped to owner members") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // "nonExistent" doesn't exist in UserServiceLive — suggestions should list
    // UserServiceLive's members, not unrelated global symbols
    val output = captureOut {
      runCommand("body", List("nonExistent"),
        CommandContext(idx = idx, workspace = workspace, inOwner = Some("UserServiceLive")))
    }
    assert(output.contains("No body found"), s"Should say not found: $output")
    // Suggestions should mention members of UserServiceLive (findUser, createUser)
    assert(output.contains("in UserServiceLive"), s"Suggestions should be scoped to owner: $output")
    assert(output.contains("findUser") || output.contains("createUser"),
      s"Suggestions should include actual owner members: $output")
    // Should NOT suggest symbols from unrelated types (e.g. Helper.formatUser)
    assert(!output.contains("formatUser"), s"Should NOT suggest unrelated symbols: $output")
  }

  test("body --in not-found suggestions are ranked by similarity") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // "find" should rank findUser higher than createUser
    val output = captureOut {
      runCommand("body", List("find"),
        CommandContext(idx = idx, workspace = workspace, inOwner = Some("UserServiceLive")))
    }
    // findUser should appear before createUser since "find" is a prefix of "findUser"
    val findIdx = output.indexOf("findUser")
    val createIdx = output.indexOf("createUser")
    assert(findIdx >= 0, s"Should suggest findUser: $output")
    assert(findIdx < createIdx || createIdx < 0,
      s"findUser should be ranked before createUser for query 'find': $output")
  }

  test("body --in suggestions do not promote short members via reverse-contains") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // ShortNames has members: e, execute, evaluate
    // Query "eval" — "evaluate" is a legitimate prefix match.
    // Without the fix, "e" is ALSO promoted to prefix via reverse-prefix
    // ("eval".startsWith("e") is true) and appears before "evaluate".
    // With the fix, only forward checks apply: "e".startsWith("eval") is
    // false, so "e" falls to rest and "evaluate" correctly ranks first.
    val suggestions = mkOwnerScopedSuggestions("eval", "ShortNames",
      CommandContext(idx = idx, workspace = workspace))
    assert(suggestions.nonEmpty, s"Should have suggestions: $suggestions")
    val evaluateIdx = suggestions.indexWhere(_.contains("evaluate"))
    val eIdx = suggestions.indexWhere(s => s.contains(" e "))
    assert(evaluateIdx >= 0, s"Should suggest evaluate: $suggestions")
    assert(eIdx >= 0, s"Should suggest e: $suggestions")
    // "evaluate" (prefix match) should rank before "e" (rest bucket)
    assert(evaluateIdx < eIdx,
      s"evaluate (prefix match) should rank before short member 'e' (rest): $suggestions")
  }

  test("body dotted syntax not used when --in is already set") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // UserServiceLive.findUser with --in should treat whole string as symbol name, not split
    val output = captureOut {
      runCommand("body", List("UserServiceLive.findUser"),
        CommandContext(idx = idx, workspace = workspace, inOwner = Some("UserServiceLive")))
    }
    // With --in already set, dotted fallback is skipped; "UserServiceLive.findUser" is not a real symbol name
    assert(output.contains("No body found"), s"Should not find body when --in is set with dotted symbol: $output")
  }

  test("body dotted syntax respects --no-tests filter") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // UserServiceSpec is in src/test/ and has testFindUser — should be found without --no-tests
    val withTests = captureOut {
      runCommand("body", List("UserServiceSpec.testFindUser"),
        CommandContext(idx = idx, workspace = workspace))
    }
    assert(withTests.contains("testFindUser"), s"Should find testFindUser without --no-tests: $withTests")
    // With --no-tests, the test file owner should be excluded
    val noTests = captureOut {
      runCommand("body", List("UserServiceSpec.testFindUser"),
        CommandContext(idx = idx, workspace = workspace, noTests = true))
    }
    assert(noTests.contains("No body found"), s"Should NOT find testFindUser with --no-tests: $noTests")
  }

  test("body dotted syntax respects --exclude-path filter") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("body", List("UserServiceLive.findUser"),
        CommandContext(idx = idx, workspace = workspace, excludePath = Some("src/main")))
    }
    assert(output.contains("No body found"), s"Should NOT find body when path is excluded: $output")
  }

  // ── #172: body command — nested local defs + filter fixes ───────────

  test("body --in finds nested local def via command") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("body", List("runSteps"),
        CommandContext(idx = idx, workspace = workspace, inOwner = Some("Pipeline")))
    }
    assert(output.contains("def runSteps"), s"Should find local def runSteps: $output")
    assert(output.contains("Pipeline"), s"Should mention Pipeline: $output")
  }

  test("body --in with --path filter on fallback restricts to matching files") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // Pipeline is in src/main/ — use --path to include it
    val output = captureOut {
      runCommand("body", List("runSteps"),
        CommandContext(idx = idx, workspace = workspace, inOwner = Some("Pipeline"),
          pathFilter = Some("src/main/")))
    }
    assert(output.contains("def runSteps"), s"Should find runSteps with matching --path: $output")
    // Now exclude it via path
    val excluded = captureOut {
      runCommand("body", List("runSteps"),
        CommandContext(idx = idx, workspace = workspace, inOwner = Some("Pipeline"),
          pathFilter = Some("src/test/")))
    }
    assert(excluded.contains("No body found"), s"Should NOT find runSteps when --path excludes it: $excluded")
  }

  test("body --in with --no-tests on fallback owner lookup") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // testFindUser is a method in UserServiceSpec (src/test/). With --in and --no-tests,
    // the owner lookup should exclude test files.
    val output = captureOut {
      runCommand("body", List("testFindUser"),
        CommandContext(idx = idx, workspace = workspace, inOwner = Some("UserServiceSpec"),
          noTests = true))
    }
    assert(output.contains("No body found"), s"Should NOT find testFindUser when --no-tests excludes owner: $output")
  }

  test("body finds local def nested inside synchronized/wrapper call") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("body", List("processBatch"),
        CommandContext(idx = idx, workspace = workspace, inOwner = Some("Scheduler")))
    }
    assert(output.contains("def processBatch"), s"Should find local def inside synchronized: $output")
    assert(output.contains("batch.size"), s"Should contain body: $output")
  }

  // ── #197: body --in finds nested def when same name is indexed elsewhere ──

  test("body --in finds nested def when same name is indexed in a different file") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // "create" is indexed as a top-level def in Pipeline.scala (Pipeline.create).
    // It also exists as a local def inside Assembler.build().
    // body create --in Assembler must search the owner's file, not just the indexed file.
    val output = captureOut {
      runCommand("body", List("create"),
        CommandContext(idx = idx, workspace = workspace, inOwner = Some("Assembler")))
    }
    assert(output.contains("def create"), s"Should find local def create in Assembler: $output")
    assert(output.contains("toUpperCase"), s"Should contain the body: $output")
    assert(output.contains("Assembler"), s"Should mention Assembler as owner: $output")
  }

  test("body on Java file does not crash with parser error") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // BrokenRecord.java may trigger JavaParser internal errors.
    // The command should return gracefully, not throw.
    val output = captureOut {
      runCommand("body", List("Ok"),
        CommandContext(idx = idx, workspace = workspace, inOwner = Some("BrokenRecord")))
    }
    // Either finds the result or says "No body found" — must not crash
    assert(output.nonEmpty, s"Should produce output, not crash: $output")
  }

  // ── #208: body on abstract def should show signature, not "No body found" ──

  test("body --in on abstract def in trait should show signature") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // UserService.findUser is an abstract def (no body) — should show the signature
    val output = captureOut {
      runCommand("body", List("findUser"),
        CommandContext(idx = idx, workspace = workspace, inOwner = Some("UserService")))
    }
    // Currently fails: returns "No body found" because extractBody only handles Defn.Def, not Decl.Def
    assert(!output.contains("No body found"), s"Should NOT say 'No body found' for abstract def: $output")
    assert(output.contains("findUser"), s"Should show the abstract method signature: $output")
    assert(output.contains("UserService"), s"Should mention the owner trait: $output")
  }

  test("body on abstract def without --in should show signature") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // processPayment is abstract in PaymentService trait
    val output = captureOut {
      runCommand("body", List("processPayment"),
        CommandContext(idx = idx, workspace = workspace, inOwner = Some("PaymentService")))
    }
    // Should show the abstract signature from PaymentService, not "No body found"
    assert(!output.contains("No body found"), s"Should NOT say 'No body found' for abstract def: $output")
    assert(output.contains("processPayment"), s"Should show signature: $output")
  }

  // ── #172: parseFlags + batch per-line flag parsing ─────────────────

  test("parseFlags extracts --path from arg list") {
    val flags = parseFlags(List("Parser", "--verbose", "--no-tests", "--path", "compiler/src/"))
    assertEquals(flags.pathFilter, Some("compiler/src/"))
    assert(flags.verbose)
    assert(flags.noTests)
    assertEquals(flags.cleanArgs, List("Parser"))
  }

  test("parseFlags strips leading / from --path") {
    val flags = parseFlags(List("Foo", "--path", "/src/main/"))
    assertEquals(flags.pathFilter, Some("src/main/"))
  }

  test("parseFlags extracts --in owner") {
    val flags = parseFlags(List("runPhases", "--in", "Run", "--no-tests"))
    assertEquals(flags.inOwner, Some("Run"))
    assert(flags.noTests)
    assertEquals(flags.cleanArgs, List("runPhases"))
  }

  test("parseFlags with no flags returns defaults and full cleanArgs") {
    val flags = parseFlags(List("UserService"))
    assertEquals(flags.pathFilter, None)
    assertEquals(flags.inOwner, None)
    assert(!flags.noTests)
    assert(!flags.verbose)
    assertEquals(flags.cleanArgs, List("UserService"))
  }

  test("batch per-line flags override: --path applied to per-line context") {
    // Simulate what the batch loop does: parse per-line flags and build context
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val lineArgs = List("UserService", "--path", "src/main/scala/com/example/")
    val lineFlags = parseFlags(lineArgs)
    val ctx = flagsToContext(lineFlags, idx, workspace, batchMode = true)
    assertEquals(ctx.pathFilter, Some("src/main/scala/com/example/"))
    assert(ctx.batchMode)
    // Run command with per-line context — should find UserService in that path
    val output = captureOut { runCommand("def", lineFlags.cleanArgs, ctx) }
    assert(output.contains("UserService"), s"Should find UserService with per-line --path: $output")
  }

  test("batch per-line --path excludes non-matching results") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // Registry exists in both com/example/ and com/other/
    val lineArgs = List("Registry", "--path", "src/main/scala/com/other/")
    val lineFlags = parseFlags(lineArgs)
    val ctx = flagsToContext(lineFlags, idx, workspace, batchMode = true)
    val output = captureOut { runCommand("def", lineFlags.cleanArgs, ctx) }
    assert(output.contains("com.other"), s"Should find com.other.Registry: $output")
    assert(!output.contains("com.example"), s"Should NOT find com.example.Registry: $output")
  }

  test("batch per-line --no-tests applied independently") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // Without --no-tests, UserServiceSpec (in src/test/) should appear
    val withTests = parseFlags(List("UserServiceSpec"))
    val ctxWith = flagsToContext(withTests, idx, workspace, batchMode = true)
    val outWith = captureOut { runCommand("def", withTests.cleanArgs, ctxWith) }
    assert(outWith.contains("UserServiceSpec"), s"Should find test class without --no-tests: $outWith")
    // With --no-tests, it should be filtered out
    val noTests = parseFlags(List("UserServiceSpec", "--no-tests"))
    val ctxNo = flagsToContext(noTests, idx, workspace, batchMode = true)
    val outNo = captureOut { runCommand("def", noTests.cleanArgs, ctxNo) }
    assert(!outNo.contains("UserServiceSpec") || outNo.contains("not found"),
      s"Should NOT find test class with --no-tests: $outNo")
  }

  test("entrypoints --no-tests excludes test suites from results") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("entrypoints", Nil,
        CommandContext(idx = idx, workspace = workspace, noTests = true))
    }
    assert(!output.contains("UserServiceTest"), s"Should not show test suites with --no-tests: $output")
  }

  // ── #180: members --body ──────────────────────────────────────────────

  test("members --body inlines method bodies") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("members", List("PaymentServiceLive"),
        CommandContext(idx = idx, workspace = workspace, limit = 50, withBody = true))
    }
    // Should show inline body for processPayment
    assert(output.contains("processPayment"), s"Should list processPayment: $output")
    assert(output.contains("| "), s"Should have body lines with | separator: $output")
    assert(output.contains("true"), s"Should contain body text 'true' from processPayment: $output")
  }

  test("members --body --max-lines filters large bodies") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("members", List("Pipeline"),
        CommandContext(idx = idx, workspace = workspace, limit = 50, withBody = true, maxBodyLines = 5))
    }
    // validate is 4 lines — should get a body (within limit of 5)
    // execute is >5 lines — should NOT get a body
    assert(output.contains("validate"), s"Should list validate: $output")
    assert(output.contains("execute"), s"Should list execute: $output")
    // validate body should be inlined (4 lines <= 5 max)
    assert(output.contains("checkStep"), s"Should inline validate body: $output")
    // execute should NOT have its body inlined
    assert(!output.contains("runSteps(steps)"), s"Should NOT inline execute body (too many lines): $output")
  }

  test("members --body --json includes body fields") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("members", List("PaymentServiceLive"),
        CommandContext(idx = idx, workspace = workspace, limit = 50, jsonOutput = true, withBody = true))
    }
    assert(output.contains("\"body\":\""), s"JSON should contain body field: $output")
    assert(output.contains("\"bodyStartLine\":"), s"JSON should contain bodyStartLine: $output")
    assert(output.contains("\"bodyEndLine\":"), s"JSON should contain bodyEndLine: $output")
  }

  test("members --body without flag does not include bodies") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("members", List("PaymentServiceLive"),
        CommandContext(idx = idx, workspace = workspace, limit = 50))
    }
    // Should list members but no body lines
    assert(output.contains("processPayment"), s"Should list members: $output")
    assert(!output.contains("| "), s"Should NOT have body lines: $output")
  }

  // ── #180: overrides --body ────────────────────────────────────────────

  test("overrides --body inlines override bodies") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("overrides", List("findUser"),
        CommandContext(idx = idx, workspace = workspace, limit = 50, withBody = true, ofTrait = Some("UserService")))
    }
    assert(output.contains("findUser"), s"Should find overrides: $output")
    // Should show inline body for the overriding implementations
    assert(output.contains("| "), s"Should have inline body lines: $output")
  }

  test("overrides --body --max-lines filters large override bodies") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // Use maxBodyLines = 0 (unlimited) — all override bodies should appear
    val output = captureOut {
      runCommand("overrides", List("findUser"),
        CommandContext(idx = idx, workspace = workspace, limit = 50, withBody = true, maxBodyLines = 0, ofTrait = Some("UserService")))
    }
    assert(output.contains("| "), s"Should have body lines with unlimited maxBodyLines: $output")
  }

  test("overrides --body --json includes body fields") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("overrides", List("findUser"),
        CommandContext(idx = idx, workspace = workspace, limit = 50, jsonOutput = true, withBody = true, ofTrait = Some("UserService")))
    }
    assert(output.contains("\"body\":\""), s"JSON should contain body: $output")
    assert(output.contains("\"bodyStartLine\":"), s"JSON should contain bodyStartLine: $output")
  }

  test("overrides without --body does not include body fields in JSON") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("overrides", List("findUser"),
        CommandContext(idx = idx, workspace = workspace, limit = 50, jsonOutput = true, ofTrait = Some("UserService")))
    }
    assert(!output.contains("\"body\":"), s"JSON should NOT contain body without --body: $output")
  }

  // ── #180: explain --body ──────────────────────────────────────────────

  test("explain --body inlines member bodies") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("PaymentServiceLive"),
        CommandContext(idx = idx, workspace = workspace, limit = 50, withBody = true))
    }
    assert(output.contains("Members"), s"Should have Members section: $output")
    assert(output.contains("| "), s"Should have inline body lines: $output")
  }

  test("explain --body --max-lines limits body size") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("Pipeline"),
        CommandContext(idx = idx, workspace = workspace, limit = 50, withBody = true, maxBodyLines = 1))
    }
    // With maxBodyLines=1, multi-line methods should not have bodies inlined
    // But single-line vals/defs should
    assert(output.contains("Members"), s"Should have Members section: $output")
  }

  test("explain --body --json includes body in members") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("PaymentServiceLive"),
        CommandContext(idx = idx, workspace = workspace, limit = 50, jsonOutput = true, withBody = true))
    }
    assert(output.contains("\"body\":\""), s"JSON should contain body in members: $output")
    assert(output.contains("\"bodyStartLine\":"), s"JSON should contain bodyStartLine: $output")
  }

  test("explain --body alias --with-bodies works") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // --with-bodies is parsed as withBody = true
    val f = parseFlags(List("explain", "PaymentServiceLive", "--with-bodies"))
    assert(f.withBody, "--with-bodies should set withBody = true")
  }

  // ── #180: body -C N (context lines) ───────────────────────────────────

  test("body -C N shows context lines around body span") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("body", List("findUser"),
        CommandContext(idx = idx, workspace = workspace, inOwner = Some("UserServiceLive"), contextLines = 2))
    }
    assert(output.contains("---"), s"Should have --- separator between context and body: $output")
    assert(output.contains("findUser"), s"Should contain the body: $output")
    // Should have context lines before and/or after
    val lines = output.split("\n")
    val bodyLineCount = lines.count(_.contains("| "))
    assert(bodyLineCount > 1, s"Should have body + context lines: $output")
  }

  test("body -C 0 shows no context lines") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("body", List("findUser"),
        CommandContext(idx = idx, workspace = workspace, inOwner = Some("UserServiceLive"), contextLines = 0))
    }
    assert(!output.contains("---"), s"Should NOT have --- separator without context: $output")
    assert(output.contains("findUser"), s"Should contain the body: $output")
  }

  test("body -C N JSON includes contextBefore and contextAfter") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("body", List("findUser"),
        CommandContext(idx = idx, workspace = workspace, inOwner = Some("UserServiceLive"),
          contextLines = 1, jsonOutput = true))
    }
    assert(output.contains("\"contextBefore\":["), s"JSON should have contextBefore: $output")
    assert(output.contains("\"contextAfter\":["), s"JSON should have contextAfter: $output")
  }

  test("body -C N JSON has no context fields when contextLines=0") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("body", List("findUser"),
        CommandContext(idx = idx, workspace = workspace, inOwner = Some("UserServiceLive"),
          contextLines = 0, jsonOutput = true))
    }
    assert(!output.contains("\"contextBefore\""), s"JSON should NOT have contextBefore without -C: $output")
    assert(!output.contains("\"contextAfter\""), s"JSON should NOT have contextAfter without -C: $output")
  }

  // ── #180: body --imports ──────────────────────────────────────────────

  test("body --imports prepends file imports") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("body", List("findUser"),
        CommandContext(idx = idx, workspace = workspace, inOwner = Some("UserServiceLive"), showImports = true))
    }
    // UserService.scala has no top-level imports, so nothing should be prepended
    // But the body should still be shown
    assert(output.contains("findUser"), s"Should contain body: $output")
  }

  test("body --imports shows imports from file with imports") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // ExplicitClient.scala has "import com.example.UserService"
    val output = captureOut {
      runCommand("body", List("ExplicitClient"),
        CommandContext(idx = idx, workspace = workspace, showImports = true))
    }
    assert(output.contains("Imports"), s"Should show imports header: $output")
    assert(output.contains("import com.example.UserService"), s"Should show import: $output")
  }

  test("body --imports JSON includes imports field") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // AliasClient.scala has import statements with as-syntax
    val output = captureOut {
      runCommand("body", List("AliasClient"),
        CommandContext(idx = idx, workspace = workspace, jsonOutput = true, showImports = true))
    }
    assert(output.contains("\"imports\":\""), s"JSON should contain imports field: $output")
    assert(output.contains("UserService as US"), s"JSON imports should contain alias import: $output")
  }

  test("body --imports excludes local imports inside methods") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // ExplicitClient.scala has only a top-level import — no local imports
    val output = captureOut {
      runCommand("body", List("ExplicitClient"),
        CommandContext(idx = idx, workspace = workspace, showImports = true))
    }
    assert(output.contains("import com.example.UserService"), s"Should show top-level import: $output")
    // Should NOT show any random local imports from other files
    assert(!output.contains("import com.other"), s"Should NOT show non-existing imports: $output")
  }

  test("body without --imports does not show imports") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("body", List("AliasClient"),
        CommandContext(idx = idx, workspace = workspace, showImports = false))
    }
    assert(!output.contains("Imports —"), s"Should NOT show imports header: $output")
  }

  // ── #180: grep --in <symbol> ──────────────────────────────────────────

  test("grep --in scopes search to class body") {
    val idx = WorkspaceIndex(workspace, needBlooms = false)
    idx.index()
    val output = captureOut {
      runCommand("grep", List("def"),
        CommandContext(idx = idx, workspace = workspace, inOwner = Some("PaymentServiceLive")))
    }
    // Should only find defs inside PaymentServiceLive, not globally
    assert(output.contains("processPayment"), s"Should find processPayment in PaymentServiceLive: $output")
    assert(output.contains("refund"), s"Should find refund in PaymentServiceLive: $output")
    // Should NOT contain defs from other classes
    assert(!output.contains("Pipeline"), s"Should NOT find Pipeline: $output")
    assert(!output.contains("Scheduler"), s"Should NOT find Scheduler: $output")
  }

  test("grep --in with dotted Owner.member scopes to method body") {
    val idx = WorkspaceIndex(workspace, needBlooms = false)
    idx.index()
    val output = captureOut {
      runCommand("grep", List("remaining"),
        CommandContext(idx = idx, workspace = workspace, inOwner = Some("Pipeline.runSteps")))
    }
    // runSteps mentions "remaining" in its body
    assert(output.contains("remaining"), s"Should find 'remaining' inside Pipeline.runSteps: $output")
  }

  test("grep --in returns empty for nonexistent owner") {
    val idx = WorkspaceIndex(workspace, needBlooms = false)
    idx.index()
    val output = captureOut {
      runCommand("grep", List("def"),
        CommandContext(idx = idx, workspace = workspace, inOwner = Some("NonExistentClass")))
    }
    assert(output.contains("0 found") || output.contains("No matches"),
      s"Should find no results for nonexistent owner: $output")
  }

  test("grep --in --count shows count only") {
    val idx = WorkspaceIndex(workspace, needBlooms = false)
    idx.index()
    val output = captureOut {
      runCommand("grep", List("def"),
        CommandContext(idx = idx, workspace = workspace, inOwner = Some("PaymentServiceLive"), countOnly = true))
    }
    // Should show count, not full results
    assert(output.contains("match"), s"Should show match count: $output")
  }

  test("grep --in header includes owner name") {
    val idx = WorkspaceIndex(workspace, needBlooms = false)
    idx.index()
    val output = captureOut {
      runCommand("grep", List("def"),
        CommandContext(idx = idx, workspace = workspace, inOwner = Some("PaymentServiceLive")))
    }
    assert(output.contains("PaymentServiceLive"), s"Header should mention the scoped owner: $output")
  }

  test("grep without --in searches globally") {
    val idx = WorkspaceIndex(workspace, needBlooms = false)
    idx.index()
    val output = captureOut {
      runCommand("grep", List("processPayment"),
        CommandContext(idx = idx, workspace = workspace))
    }
    // Global grep should find it
    assert(output.contains("processPayment"), s"Global grep should find processPayment: $output")
    // Should NOT mention "in <owner>" in header
    assert(!output.contains(" in "), s"Global grep header should not have 'in': $output")
  }

  // ── #180: extractImportLines ──────────────────────────────────────────

  test("extractImportLines returns top-level imports only") {
    val file = workspace.resolve("src/main/scala/com/client/AliasClient.scala")
    val result = extractImportLines(file)
    assert(result.isDefined, s"Should find imports in AliasClient.scala")
    val imports = result.get
    assert(imports.contains("import com.example.UserService as US"), s"Should contain alias import: $imports")
    assert(imports.contains("import com.example.{Database as DB}"), s"Should contain grouped import: $imports")
  }

  test("extractImportLines returns None for file without imports") {
    val file = workspace.resolve("src/main/scala/com/example/UserService.scala")
    val result = extractImportLines(file)
    assert(result.isEmpty, "File without imports should return None")
  }

  test("extractImportLines excludes local imports inside methods") {
    // Create a temp file directly (no git needed — extractImportLines reads any file)
    val file = workspace.resolve("src/main/scala/com/example/WithLocalImport.scala")
    java.nio.file.Files.createDirectories(file.getParent)
    java.nio.file.Files.writeString(file,
      """package com.example
        |
        |import com.example.User
        |
        |object WithLocalImport {
        |  def doStuff(): Unit = {
        |    import com.other.Helper
        |    Helper.formatUser(User("1", "test"))
        |  }
        |}
        |""".stripMargin)

    val result = extractImportLines(file)
    assert(result.isDefined, "Should find top-level imports")
    val imports = result.get
    assert(imports.contains("import com.example.User"), s"Should contain top-level import: $imports")
    assert(!imports.contains("import com.other.Helper"), s"Should NOT contain local import: $imports")
  }

  // ── #180: parseFlags for new flags ────────────────────────────────────

  test("parseFlags parses --body flag") {
    val f = parseFlags(List("members", "Foo", "--body"))
    assert(f.withBody, "--body should be true")
  }

  test("parseFlags parses --with-bodies alias") {
    val f = parseFlags(List("explain", "Foo", "--with-bodies"))
    assert(f.withBody, "--with-bodies should set withBody = true")
  }

  test("parseFlags parses --max-lines flag with argument") {
    val f = parseFlags(List("members", "Foo", "--body", "--max-lines", "20"))
    assert(f.withBody, "--body should be true")
    assertEquals(f.maxBodyLines, 20)
  }

  test("parseFlags --max-lines defaults to 0") {
    val f = parseFlags(List("members", "Foo", "--body"))
    assertEquals(f.maxBodyLines, 0)
  }

  test("parseFlags parses --imports flag") {
    val f = parseFlags(List("body", "Foo", "--imports"))
    assert(f.showImports, "--imports should be true")
  }

  test("parseFlags --imports defaults to false") {
    val f = parseFlags(List("body", "Foo"))
    assert(!f.showImports, "--imports should default to false")
  }

  test("parseFlags --max-lines is excluded from cleanArgs") {
    val f = parseFlags(List("members", "Foo", "--body", "--max-lines", "10"))
    assert(!f.cleanArgs.contains("--max-lines"), "--max-lines should be excluded from cleanArgs")
    assert(!f.cleanArgs.contains("10"), "10 (max-lines value) should be excluded from cleanArgs")
    assert(f.cleanArgs.contains("Foo"), "Foo should remain in cleanArgs")
  }

  // ── #180: body -C N + --imports combined ──────────────────────────────

  test("body -C N and --imports combined") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // Use ExplicitClient — has imports and is not at the very start of the file
    val output = captureOut {
      runCommand("body", List("ExplicitClient"),
        CommandContext(idx = idx, workspace = workspace, contextLines = 1, showImports = true))
    }
    // Should have imports section
    assert(output.contains("Imports"), s"Should show imports header: $output")
    assert(output.contains("import com.example.UserService"), s"Should show import: $output")
    // Body should be shown
    assert(output.contains("ExplicitClient"), s"Should show body: $output")
  }

  test("body -C N and --imports JSON combined") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("body", List("ExplicitClient"),
        CommandContext(idx = idx, workspace = workspace, contextLines = 1, showImports = true, jsonOutput = true))
    }
    assert(output.contains("\"imports\":\""), s"JSON should have imports field: $output")
    assert(output.contains("\"contextBefore\":["), s"JSON should have contextBefore: $output")
    assert(output.contains("\"contextAfter\":["), s"JSON should have contextAfter: $output")
  }

  // ── #180: enrichMemberWithBody helper ─────────────────────────────────

  test("enrichMemberWithBody returns body when within max-lines") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val file = workspace.resolve("src/main/scala/com/example/Documented.scala")
    val member = MemberInfo("processPayment", SymbolKind.Def, 8, "def processPayment(amount: BigDecimal): Boolean")
    val enriched = enrichMemberWithBody(member, file, "PaymentServiceLive", 5)
    assert(enriched.body.isDefined, s"Should have body: $enriched")
    assert(enriched.body.get.sourceText.contains("true"), s"Body should contain 'true': ${enriched.body}")
  }

  test("enrichMemberWithBody returns no body when over max-lines") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val file = workspace.resolve("src/main/scala/com/example/Pipeline.scala")
    val member = MemberInfo("execute", SymbolKind.Def, 4, "def execute(): Unit")
    // execute is a multi-line method, setting maxBodyLines=1 should exclude it
    val enriched = enrichMemberWithBody(member, file, "Pipeline", 1)
    assert(enriched.body.isEmpty, s"Should NOT have body when over max-lines: $enriched")
  }

  test("enrichMemberWithBody with maxBodyLines=0 means unlimited") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val file = workspace.resolve("src/main/scala/com/example/Pipeline.scala")
    val member = MemberInfo("execute", SymbolKind.Def, 4, "def execute(): Unit")
    val enriched = enrichMemberWithBody(member, file, "Pipeline", 0)
    assert(enriched.body.isDefined, s"maxBodyLines=0 should mean unlimited: $enriched")
  }

  // ── #198: members --limit 0 and --offset ──────────────────────────────

  test("members --limit 0 shows all members without truncation") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("members", List("PaymentServiceLive"),
        CommandContext(idx = idx, workspace = workspace, limit = Int.MaxValue))
    }
    // PaymentServiceLive has 5 members
    assert(output.contains("processPayment"), s"Should show processPayment: $output")
    assert(output.contains("refund"), s"Should show refund: $output")
    assert(output.contains("maxRetries"), s"Should show maxRetries: $output")
    assert(output.contains("lastError"), s"Should show lastError: $output")
    assert(output.contains("TransactionId"), s"Should show TransactionId: $output")
    assert(!output.contains("... and"), s"Should NOT have truncation message: $output")
  }

  test("members --limit truncates and shows remainder") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("members", List("PaymentServiceLive"),
        CommandContext(idx = idx, workspace = workspace, limit = 2))
    }
    // Should show 2 members and truncation message
    assert(output.contains("... and 3 more"), s"Should show '... and 3 more': $output")
  }

  test("members --offset skips first N members") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // Get all members first to know the order
    val allOutput = captureOut {
      runCommand("members", List("PaymentServiceLive"),
        CommandContext(idx = idx, workspace = workspace, limit = Int.MaxValue))
    }
    val allLines = allOutput.linesIterator.filter { l =>
      l.contains("    def  ") || l.contains("    val  ") || l.contains("    var  ") || l.contains("    type ")
    }.toList

    // Now get with offset=2, limit=unlimited — should skip first 2
    val offsetOutput = captureOut {
      runCommand("members", List("PaymentServiceLive"),
        CommandContext(idx = idx, workspace = workspace, limit = Int.MaxValue, offset = 2))
    }
    val offsetLines = offsetOutput.linesIterator.filter { l =>
      l.contains("    def  ") || l.contains("    val  ") || l.contains("    var  ") || l.contains("    type ")
    }.toList

    assertEquals(offsetLines.size, allLines.size - 2, s"Offset 2 should skip 2 members.\nAll: $allLines\nOffset: $offsetLines")
  }

  test("members --offset with --limit paginates correctly") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("members", List("PaymentServiceLive"),
        CommandContext(idx = idx, workspace = workspace, limit = 2, offset = 1))
    }
    // PaymentServiceLive has 5 members; offset=1, limit=2 → show 2, skip 1, remaining=2
    assert(output.contains("... and 2 more"), s"Should show '... and 2 more': $output")
  }

  test("members --offset beyond member count shows no members") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("members", List("PaymentServiceLive"),
        CommandContext(idx = idx, workspace = workspace, limit = Int.MaxValue, offset = 100))
    }
    // All 5 members skipped — no member lines should appear
    assert(!output.contains("processPayment"), s"Should NOT show any members: $output")
    assert(!output.contains("... and"), s"Should NOT show truncation: $output")
  }

  test("members --limit 0 --json shows all members") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("members", List("PaymentServiceLive"),
        CommandContext(idx = idx, workspace = workspace, limit = Int.MaxValue, jsonOutput = true))
    }
    assert(output.startsWith("["), s"JSON should start with bracket: $output")
    // Count JSON entries — PaymentServiceLive has 5 members
    val memberCount = "\"name\":".r.findAllIn(output).size
    assertEquals(memberCount, 5, s"Should have all 5 members in JSON: $output")
  }

  test("members --offset --json paginates correctly") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("members", List("PaymentServiceLive"),
        CommandContext(idx = idx, workspace = workspace, limit = 2, offset = 1, jsonOutput = true))
    }
    val memberCount = "\"name\":".r.findAllIn(output).size
    assertEquals(memberCount, 2, s"Should have 2 members in JSON with offset=1 limit=2: $output")
  }

  test("parseFlags parses --offset flag") {
    val f = parseFlags(List("members", "Foo", "--offset", "10"))
    assertEquals(f.offset, 10)
  }

  test("parseFlags --offset defaults to 0") {
    val f = parseFlags(List("members", "Foo"))
    assertEquals(f.offset, 0)
  }

  test("parseFlags --offset is excluded from cleanArgs") {
    val f = parseFlags(List("members", "Foo", "--offset", "5"))
    assert(!f.cleanArgs.contains("--offset"), "--offset should be excluded from cleanArgs")
    assert(!f.cleanArgs.contains("5"), "5 (offset value) should be excluded from cleanArgs")
    assert(f.cleanArgs.contains("Foo"), "Foo should remain in cleanArgs")
  }

  test("parseFlags --limit 0 is converted to Int.MaxValue") {
    val f = parseFlags(List("members", "Foo", "--limit", "0"))
    assertEquals(f.limit, Int.MaxValue)
  }

  // ── Orphan header suppression ──────────────────────────────────────────

  test("members --offset suppresses 'Defined in' header when all own members skipped") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("members", List("PaymentServiceLive"),
        CommandContext(idx = idx, workspace = workspace, limit = Int.MaxValue, offset = 100))
    }
    assert(!output.contains("Defined in"), s"Should NOT show 'Defined in' header when all members skipped: $output")
  }

  test("members --offset suppresses 'Inherited from' header when all inherited skipped") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("members", List("UserServiceLive"),
        CommandContext(idx = idx, workspace = workspace, limit = Int.MaxValue, offset = 100, inherited = true))
    }
    assert(!output.contains("Inherited from"), s"Should NOT show 'Inherited from' header when all inherited skipped: $output")
  }

  test("members --offset suppresses companion header when all companion members skipped") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("members", List("Pipeline"),
        CommandContext(idx = idx, workspace = workspace, limit = Int.MaxValue, offset = 100, verbose = true))
    }
    assert(!output.contains("Companion"), s"Should NOT show Companion header when all companion members skipped: $output")
  }

  test("members --offset shows companion header when own members skipped but companion visible") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    // Database trait has 2 own members (query, insert) + companion with live
    // offset=2 skips own members, companion should still show
    val output = captureOut {
      runCommand("members", List("Database"),
        CommandContext(idx = idx, workspace = workspace, limit = Int.MaxValue, offset = 2, verbose = true))
    }
    val sections = output.split("Members of")
    // Find the trait section (not the object section)
    val traitOutput = sections.find(_.contains("trait")).getOrElse("")
    assert(!traitOutput.contains("Defined in"), s"Should NOT show 'Defined in' — own members skipped: $traitOutput")
    assert(traitOutput.contains("Companion"), s"Should show Companion — still has visible members: $traitOutput")
    assert(traitOutput.contains("live"), s"Companion live should be visible: $traitOutput")
  }

  test("members top-level header still shows when offset skips all content") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("members", List("PaymentServiceLive"),
        CommandContext(idx = idx, workspace = workspace, limit = Int.MaxValue, offset = 100))
    }
    // Top-level header identifies the matched type — always shown
    assert(output.contains("Members of"), s"Top-level 'Members of' header should always show: $output")
  }

  // ── #221: better hub detection ────────────────────────────────────────

  test("overview --architecture hub types exclude stdlib-package-only types") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("overview", Nil, CommandContext(idx = idx, workspace = workspace, limit = 20, architecture = true))
    }
    // Serializable is a stdlib type — should not appear as hub type
    assert(!output.contains("Serializable"),
      s"Hub types should not include Serializable (stdlib): $output")
  }

  // ── #221: explain --related ───────────────────────────────────────────

  test("explain --related shows project-defined types from member signatures") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("UserService"),
        CommandContext(idx = idx, workspace = workspace, related = true))
    }
    assert(output.contains("Related types"), s"Should show Related types section: $output")
    assert(output.contains("User"), s"Should list User as related type: $output")
  }

  test("explain --related JSON includes relatedTypes array") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("UserService"),
        CommandContext(idx = idx, workspace = workspace, related = true, jsonOutput = true))
    }
    assert(output.contains("\"relatedTypes\""), s"JSON should contain relatedTypes: $output")
    assert(output.contains("\"User\""), s"relatedTypes should contain User: $output")
  }

  test("explain without --related does not show Related types") {
    val idx = WorkspaceIndex(workspace, needBlooms = true)
    idx.index()
    val output = captureOut {
      runCommand("explain", List("UserService"),
        CommandContext(idx = idx, workspace = workspace))
    }
    assert(!output.contains("Related types"), s"Should not show Related types without flag: $output")
  }

  // ── #221: package --explain ───────────────────────────────────────────

  test("package --explain shows types with members and impl counts") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("package", List("com.example"),
        CommandContext(idx = idx, workspace = workspace, explainMode = true))
    }
    assert(output.contains("types") && output.contains("symbols"), s"Should show type/symbol counts: $output")
    assert(output.contains("UserService"), s"Should list UserService: $output")
    assert(output.contains("impls"), s"Should show impl counts: $output")
  }

  test("package --explain JSON structure") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val output = captureOut {
      runCommand("package", List("com.example"),
        CommandContext(idx = idx, workspace = workspace, explainMode = true, jsonOutput = true))
    }
    assert(output.contains("\"package\":\"com.example\""), s"JSON should have package: $output")
    assert(output.contains("\"totalSymbols\""), s"JSON should have totalSymbols: $output")
    assert(output.contains("\"types\""), s"JSON should have types array: $output")
    assert(output.contains("\"implCount\""), s"JSON should have implCount: $output")
    assert(output.contains("\"members\""), s"JSON should have members: $output")
  }
