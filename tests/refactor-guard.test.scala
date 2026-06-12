import java.nio.file.Path

/** Guards for the dedup refactor: validates that every --json output is
  * structurally valid JSON, and locks behavior the refactor touches
  * (search ranking, overview package deps, grep corrected-hint escaping,
  * Java signature consistency, truncation footers). */
class RefactorGuardSuite extends ScalexTestBase {

  // ── Minimal JSON validator (no external deps) ─────────────────────────────

  private final class JsonParser(s: String) {
    var pos: Int = 0
    private def peek: Char = if pos < s.length then s.charAt(pos) else '\u0000'
    private def skipWs(): Unit = {
      while pos < s.length && s.charAt(pos).isWhitespace do pos += 1
    }
    private def consume(lit: String): Boolean = {
      if s.startsWith(lit, pos) then {
        pos += lit.length
        true
      } else false
    }
    def parseValue(): Boolean = {
      skipWs()
      peek match {
        case '{' => parseObject()
        case '[' => parseArray()
        case '"' => parseString()
        case 't' => consume("true")
        case 'f' => consume("false")
        case 'n' => consume("null")
        case c if c == '-' || c.isDigit => parseNumber()
        case _ => false
      }
    }
    private def parseObject(): Boolean = {
      pos += 1 // consume '{'
      skipWs()
      if peek == '}' then {
        pos += 1
        true
      } else {
        var ok = true
        var done = false
        while ok && !done do {
          skipWs()
          ok = parseString()
          if ok then {
            skipWs()
            ok = peek == ':'
            if ok then pos += 1
          }
          if ok then ok = parseValue()
          if ok then {
            skipWs()
            if peek == ',' then pos += 1
            else if peek == '}' then {
              pos += 1
              done = true
            } else ok = false
          }
        }
        ok
      }
    }
    private def parseArray(): Boolean = {
      pos += 1 // consume '['
      skipWs()
      if peek == ']' then {
        pos += 1
        true
      } else {
        var ok = true
        var done = false
        while ok && !done do {
          ok = parseValue()
          if ok then {
            skipWs()
            if peek == ',' then pos += 1
            else if peek == ']' then {
              pos += 1
              done = true
            } else ok = false
          }
        }
        ok
      }
    }
    private def isHex(c: Char): Boolean =
      c.isDigit || ('a' <= c && c <= 'f') || ('A' <= c && c <= 'F')
    private def parseString(): Boolean = {
      if peek != '"' then false
      else {
        pos += 1
        var ok = true
        var closed = false
        while ok && !closed && pos < s.length do {
          s.charAt(pos) match {
            case '"' =>
              closed = true
              pos += 1
            case '\\' =>
              if pos + 1 >= s.length then ok = false
              else {
                s.charAt(pos + 1) match {
                  case '"' | '\\' | '/' | 'b' | 'f' | 'n' | 'r' | 't' => pos += 2
                  case 'u' =>
                    if pos + 5 < s.length && (2 to 5).forall(i => isHex(s.charAt(pos + i))) then pos += 6
                    else ok = false
                  case _ => ok = false
                }
              }
            case c if c < 0x20 => ok = false
            case _ => pos += 1
          }
        }
        ok && closed
      }
    }
    private def parseNumber(): Boolean = {
      val start = pos
      if peek == '-' then pos += 1
      while pos < s.length && s.charAt(pos).isDigit do pos += 1
      if peek == '.' then {
        pos += 1
        while pos < s.length && s.charAt(pos).isDigit do pos += 1
      }
      if peek == 'e' || peek == 'E' then {
        pos += 1
        if peek == '+' || peek == '-' then pos += 1
        while pos < s.length && s.charAt(pos).isDigit do pos += 1
      }
      pos > start && s.charAt(pos - 1).isDigit
    }
  }

  private def assertValidJson(out: String, label: String): Unit = {
    val trimmed = out.trim
    assert(trimmed.nonEmpty, s"[$label] produced empty output")
    val p = JsonParser(trimmed)
    var ok = p.parseValue()
    var i = p.pos
    while i < trimmed.length && trimmed.charAt(i).isWhitespace do i += 1
    ok = ok && i == trimmed.length
    assert(ok, s"[$label] invalid JSON near position ${p.pos}: ${trimmed.take(400)}")
  }

  // ── JSON validity sweep over every command ────────────────────────────────

  test("every --json command output is structurally valid JSON") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    def ctx(mod: CommandContext => CommandContext = identity): CommandContext =
      mod(CommandContext(idx = idx, workspace = workspace, jsonOutput = true, limit = 50))

    val cases: List[(label: String, cmd: String, args: List[String], ctx: CommandContext)] = List(
      (label = "search", cmd = "search", args = List("User"), ctx = ctx()),
      (label = "search-miss", cmd = "search", args = List("NoSuchSymbolXyz"), ctx = ctx()),
      (label = "def", cmd = "def", args = List("UserService"), ctx = ctx()),
      (label = "def-dotted", cmd = "def", args = List("Outer.Inner"), ctx = ctx()),
      (label = "impl", cmd = "impl", args = List("UserService"), ctx = ctx()),
      (label = "refs-categorized", cmd = "refs", args = List("UserService"), ctx = ctx()),
      (label = "refs-flat", cmd = "refs", args = List("UserService"), ctx = ctx(_.copy(categorize = false))),
      (label = "refs-flat-context", cmd = "refs", args = List("UserService"), ctx = ctx(_.copy(categorize = false, contextLines = 2))),
      (label = "refs-count", cmd = "refs", args = List("UserService"), ctx = ctx(_.copy(countOnly = true))),
      (label = "refs-top", cmd = "refs", args = List("UserService"), ctx = ctx(_.copy(topN = Some(3)))),
      (label = "imports", cmd = "imports", args = List("UserService"), ctx = ctx()),
      (label = "imports-miss", cmd = "imports", args = List("NoSuchSymbolXyz"), ctx = ctx()),
      (label = "members", cmd = "members", args = List("UserServiceLive"), ctx = ctx()),
      (label = "members-inherited", cmd = "members", args = List("UserServiceLive"), ctx = ctx(_.copy(inherited = true))),
      (label = "members-body", cmd = "members", args = List("UserServiceLive"), ctx = ctx(_.copy(withBody = true))),
      (label = "members-java", cmd = "members", args = List("UserRepository"), ctx = ctx()),
      (label = "doc", cmd = "doc", args = List("PaymentService"), ctx = ctx()),
      (label = "overview", cmd = "overview", args = Nil, ctx = ctx()),
      (label = "overview-arch", cmd = "overview", args = Nil, ctx = ctx(_.copy(architecture = true))),
      (label = "overview-concise", cmd = "overview", args = Nil, ctx = ctx(_.copy(concise = true))),
      (label = "overview-focus", cmd = "overview", args = Nil, ctx = ctx(_.copy(focusPackage = Some("com.example")))),
      (label = "symbols", cmd = "symbols", args = List("src/main/scala/com/example/UserService.scala"), ctx = ctx()),
      (label = "symbols-summary", cmd = "symbols", args = List("src/main/scala/com/example/UserService.scala"), ctx = ctx(_.copy(summaryMode = true))),
      (label = "file", cmd = "file", args = List("UserService"), ctx = ctx()),
      (label = "annotated", cmd = "annotated", args = List("deprecated"), ctx = ctx()),
      (label = "grep", cmd = "grep", args = List("findUser"), ctx = ctx()),
      (label = "grep-count", cmd = "grep", args = List("findUser"), ctx = ctx(_.copy(countOnly = true))),
      (label = "grep-in", cmd = "grep", args = List("db"), ctx = ctx(_.copy(inOwner = Some("UserServiceLive")))),
      (label = "grep-each-method", cmd = "grep", args = List("db"), ctx = ctx(_.copy(inOwner = Some("UserServiceLive"), eachMethod = true))),
      (label = "packages", cmd = "packages", args = Nil, ctx = ctx()),
      (label = "package", cmd = "package", args = List("com.example"), ctx = ctx()),
      (label = "package-explain", cmd = "package", args = List("com.example"), ctx = ctx(_.copy(explainMode = true))),
      (label = "index", cmd = "index", args = Nil, ctx = ctx()),
      (label = "body", cmd = "body", args = List("findUser"), ctx = ctx()),
      (label = "body-context-imports", cmd = "body", args = List("findUser"), ctx = ctx(_.copy(contextLines = 2, showImports = true))),
      (label = "tests", cmd = "tests", args = Nil, ctx = ctx()),
      (label = "tests-count", cmd = "tests", args = Nil, ctx = ctx(_.copy(countOnly = true))),
      (label = "coverage", cmd = "coverage", args = List("UserService"), ctx = ctx()),
      (label = "hierarchy", cmd = "hierarchy", args = List("UserServiceLive"), ctx = ctx()),
      (label = "overrides", cmd = "overrides", args = List("findUser"), ctx = ctx(_.copy(ofTrait = Some("UserService")))),
      (label = "explain", cmd = "explain", args = List("UserService"), ctx = ctx()),
      (label = "explain-shallow", cmd = "explain", args = List("UserService"), ctx = ctx(_.copy(shallow = true))),
      (label = "explain-brief", cmd = "explain", args = List("UserService"), ctx = ctx(_.copy(brief = true))),
      (label = "explain-inherited", cmd = "explain", args = List("UserServiceLive"), ctx = ctx(_.copy(inherited = true))),
      (label = "explain-related", cmd = "explain", args = List("UserService"), ctx = ctx(_.copy(related = true))),
      (label = "explain-expand", cmd = "explain", args = List("UserService"), ctx = ctx(_.copy(expandDepth = 1))),
      (label = "explain-miss", cmd = "explain", args = List("NoSuchSymbolXyz"), ctx = ctx()),
      (label = "deps", cmd = "deps", args = List("UserServiceLive"), ctx = ctx()),
      (label = "context", cmd = "context", args = List("src/main/scala/com/example/UserService.scala:8"), ctx = ctx()),
      (label = "diff", cmd = "diff", args = List("HEAD"), ctx = ctx()),
      (label = "ast-pattern", cmd = "ast-pattern", args = Nil, ctx = ctx(_.copy(extendsFilter = Some("UserService")))),
      (label = "api", cmd = "api", args = List("com.example"), ctx = ctx()),
      (label = "summary", cmd = "summary", args = List("com"), ctx = ctx()),
      (label = "entrypoints", cmd = "entrypoints", args = Nil, ctx = ctx()),
    )

    cases.foreach { c =>
      val out = captureOut { runCommand(c.cmd, c.args, c.ctx) }
      assertValidJson(out, c.label)
    }
  }

  test("graph --render --json output is valid JSON") {
    val idx = WorkspaceIndex(workspace, needBlooms = false)
    val ctx = CommandContext(idx = idx, workspace = workspace, jsonOutput = true)
    val out = captureOut { render(cmdGraph(List("--render", "A->B,B->C"), ctx), ctx) }
    assertValidJson(out, "graph-render")
  }

  // ── grep --json corrected-hint escaping (bug fix) ─────────────────────────

  test("grep --json escapes auto-corrected pattern containing backslashes") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val ctx = CommandContext(idx = idx, workspace = workspace, jsonOutput = true)
    // POSIX alternation triggers the auto-correct path; \d survives into the
    // corrected pattern and must be escaped in the JSON hint
    val out = captureOut { runCommand("grep", List("""findUser\|\d+"""), ctx) }
    assertValidJson(out, "grep-corrected-backslash")
    assert(out.contains(""""corrected":"findUser|\\d+""""), s"corrected hint should be JSON-escaped: $out")
  }

  test("grep --json escapes auto-corrected pattern containing quotes") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val ctx = CommandContext(idx = idx, workspace = workspace, jsonOutput = true)
    val out = captureOut { runCommand("grep", List("""say "hi"\|findUser"""), ctx) }
    assertValidJson(out, "grep-corrected-quote")
  }

  test("grep --count --json escapes auto-corrected pattern") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val ctx = CommandContext(idx = idx, workspace = workspace, jsonOutput = true, countOnly = true)
    val out = captureOut { runCommand("grep", List("""findUser\|\d+"""), ctx) }
    assertValidJson(out, "grep-count-corrected")
  }

  // ── Search ranking locks ──────────────────────────────────────────────────

  test("search ranks exact matches before prefix matches") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val names = idx.search("UserService").map(_.name)
    assert(names.nonEmpty, "search should find results")
    assertEquals(names.head, "UserService")
    val lastExact = names.lastIndexOf("UserService")
    val firstPrefix = names.indexOf("UserServiceLive")
    assert(firstPrefix > lastExact, s"prefix matches should come after exact: $names")
  }

  test("search camelCase fuzzy matches rank after substring matches") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val names = idx.search("usl").map(_.name)
    assert(names.contains("UserServiceLive"), s"camelCase fuzzy should match UserServiceLive: $names")
  }

  test("searchFiles ranks exact filename match first") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val results = idx.searchFiles("UserService")
    assert(results.nonEmpty)
    assert(results.head.endsWith("UserService.scala"), s"exact filename should rank first: $results")
  }

  // ── Overview architecture deps lock (import source swap) ─────────────────

  test("overview --architecture computes package deps from imports") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val ctx = CommandContext(idx = idx, workspace = workspace, jsonOutput = true, limit = 50, architecture = true)
    val out = captureOut { runCommand("overview", Nil, ctx) }
    // com.client files import com.example (explicit, wildcard, and renaming);
    // no other cross-package imports exist in the fixture
    assert(out.contains(""""packageDependencies":{"com.client":["com.example"]}"""),
      s"package deps should be exactly com.client -> com.example: $out")
  }

  test("overview --focus-package filters dependency graph") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val ctx = CommandContext(idx = idx, workspace = workspace, limit = 50, focusPackage = Some("com.example"))
    val out = captureOut { runCommand("overview", Nil, ctx) }
    assert(out.contains("com.client"), s"dependents of com.example should include com.client: $out")
  }

  // ── Java extraction consistency lock ──────────────────────────────────────

  test("java method signatures identical between file symbols and members") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val file = workspace.resolve("src/main/java/com/example/UserRepository.java")
    val memberSigs = extractMembers(file, "UserRepository")
      .filter(m => m.kind == SymbolKind.Def && m.name != "<init>").map(_.signature).toSet
    val symbolSigs = idx.fileSymbols("src/main/java/com/example/UserRepository.java")
      .filter(_.kind == SymbolKind.Def).map(_.signature).toSet
    assertEquals(memberSigs, symbolSigs)
    assert(memberSigs.contains("def findById(id: String): Optional<String>"), s"sigs: $memberSigs")
  }

  test("java field signatures identical between file symbols and members") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val file = workspace.resolve("src/main/java/com/example/GenericRepository.java")
    val memberSigs = extractMembers(file, "GenericRepository")
      .filter(m => m.kind == SymbolKind.Val || m.kind == SymbolKind.Var).map(_.signature).toSet
    val symbolSigs = idx.fileSymbols("src/main/java/com/example/GenericRepository.java")
      .filter(s => s.kind == SymbolKind.Val || s.kind == SymbolKind.Var).map(_.signature).toSet
    assertEquals(memberSigs, symbolSigs)
    assert(memberSigs.contains("val tableName: String"), s"sigs: $memberSigs")
    assert(memberSigs.contains("var maxResults: int"), s"sigs: $memberSigs")
  }

  // ── Truncation footer lock ────────────────────────────────────────────────

  test("search text output shows truncation footer when over limit") {
    val idx = WorkspaceIndex(workspace)
    idx.index()
    val total = idx.search("User").size
    assert(total > 2, "fixture should have more than 2 User matches")
    val ctx = CommandContext(idx = idx, workspace = workspace, limit = 2)
    val out = captureOut { runCommand("search", List("User"), ctx) }
    assert(out.contains(s"... and ${total - 2} more"), s"should show footer: $out")
  }

  // ── Context-line rendering lock (content, not alignment) ─────────────────

  test("refs -C output marks the matching line and shows neighbors") {
    val ref = Reference(workspace.resolve("src/main/scala/com/example/UserService.scala"), 4, "def findUser(id: String): Option[User]")
    val s = formatRefWithContext(ref, workspace, 1)
    assert(s.contains("| trait UserService {"), s"should show line before: $s")
    assert(s.contains("|   def findUser(id: String): Option[User]"), s"should show match line: $s")
    val markedLines = s.linesIterator.filter(_.trim.startsWith("> ")).toList
    assertEquals(markedLines.size, 1, s"exactly one marked line: $s")
    assert(markedLines.head.contains("findUser"), s"marker on match line: $s")
  }
}
