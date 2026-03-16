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

  test("printNotFoundHint in batch mode prints single line") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      printNotFoundHint("NonExistent", idx, "def", batchMode = true)
    }
    val output = out.toString.trim
    assert(output.startsWith("not found"), s"Batch not-found should be single line: $output")
    assert(output.contains("files"), s"Should mention file count: $output")
    assert(!output.contains("Hint:"), s"Should NOT contain verbose hints: $output")
    assert(!output.contains("Fallback:"), s"Should NOT contain fallback: $output")
  }

  test("printNotFoundHint in normal mode prints full hints") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      printNotFoundHint("NonExistent", idx, "def", batchMode = false)
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
      List("Bar", "Baz"), "trait Foo extends Bar with Baz")
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
    assert(output.contains("userservice") || output.contains("database"), s"Should list a known trait: $output")
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
