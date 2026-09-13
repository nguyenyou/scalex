# Scalex command reference

Read only the section relevant to the current query. Examples abbreviate the bundled script invocation as `scalex`; provide `-w /repo` for the workspace. See [SKILL.md](../SKILL.md) for cache freshness and interpretation limits. Graph syntax and examples are in [graph-examples.md](graph-examples.md).

## Contents

[def](#def) · [impl](#impl) · [refs](#refs) · [imports](#imports) · [members](#members) · [doc](#doc) · [search](#search) · [grep](#grep) · [body](#body) · [hierarchy](#hierarchy) · [overrides](#overrides) · [explain](#explain) · [tests](#tests) · [overview](#overview) · [file](#file) · [annotated](#annotated) · [package](#package) · [api](#api) · [summary](#summary) · [deps](#deps) · [context](#context) · [diff](#diff) · [ast-pattern](#ast-pattern) · [entrypoints](#entrypoints) · [coverage](#coverage) · [batch](#batch) · [symbols](#symbols) · [index](#index) · [Options](#options)

<a id="def"></a>

### `scalex def <symbol> [--verbose] [--kind K] [--no-tests] [--path PREFIX]` — find definition

Returns where a symbol is defined, including given instances that grep would miss. Use `--verbose` to see the full signature inline — saves a follow-up Read call. Results are ranked: class/trait/object/enum first, non-test before test, shorter paths first. Supports **package-qualified names** — `def com.example.Cache` or partial `def cache.Cache` disambiguates by package. Also supports **Owner.member dotted syntax** — `def MyService.findUser` resolves to the `findUser` member inside `MyService`.

```bash
scalex def PaymentService --verbose
scalex def com.example.payment.PaymentService  # fully-qualified lookup
scalex def payment.PaymentService              # partial qualification
scalex def PaymentService.processPayment       # Owner.member dotted syntax
scalex def Driver --kind class              # only class definitions
scalex def Driver --no-tests --path compiler/src/  # exclude tests, restrict to subtree
```
```
  trait     PaymentService (com.example.payment) — .../PaymentService.scala:16
             trait PaymentService
  given     paymentService (com.example.module) — .../ServiceModule.scala:185
             given paymentService: PaymentService
```

<a id="impl"></a>

### `scalex impl <trait> [--verbose] [--kind K] [--in-package PKG] [--no-tests] [--path PREFIX] [--limit N]` — find implementations

Finds all classes/objects/enums that extend or mix in a trait. Also finds types that use the symbol as a type argument in extends clauses (e.g. `impl Foo` finds `class Bar extends Mixin[Foo]`). Uses indexed extends clauses rather than textual references. A type-argument match is not necessarily a subtype.

```bash
scalex impl PaymentService --verbose
scalex impl PaymentService --no-tests --path core/src/
```
```
  class     PaymentServiceLive — .../PaymentServiceLive.scala:43
             class PaymentServiceLive extends PaymentService
```

<a id="refs"></a>

### `scalex refs <symbol> [--flat] [--count] [--top N] [--strict] [--category CAT] [--in-package PKG] [--no-tests] [--path PREFIX] [-C N] [--limit N]` — find references

Finds candidate references using word-boundary text matching; this does not resolve symbol identity. Uses bloom filters to skip files that definitely don't contain the symbol, then reads candidate files. Has a 20-second timeout — on very large codebases with a common symbol, output may say "(timed out — partial results)".

Output is **categorized by default** — groups results into Definition, ExtendedBy, ImportedBy, UsedAsType, Usage, and Comment so you can understand impact at a glance. Use `--category CAT` to filter to a single category (e.g. `--category ExtendedBy`). Use `--in-package PKG` to filter results to files whose package matches PKG prefix when package structure differs from directory layout. Use `-C N` to show N lines of context around each reference (like `grep -C`) — reduces follow-up Read calls. Use `--flat` to get a flat list instead. Use `--count` to get category counts without full file lists — fast impact triage. Use `--top N` to rank files by reference count descending — shows the N heaviest users first for impact analysis.

```bash
scalex refs PaymentService                        # categorized by default
scalex refs PaymentService --count               # summary: "12 importers, 4 extensions, 30 usages"
scalex refs PaymentService --category ExtendedBy  # only show ExtendedBy
scalex refs PaymentService --no-tests --path core/src/
scalex refs PaymentService -C 3                   # show 3 lines of context
scalex refs PaymentService --flat                 # flat list (old default)
scalex refs PaymentService --in-package com.example  # only refs from com.example package files
scalex refs PaymentService --top 10              # top 10 files by reference count
```
```
  Definition:
    .../PaymentService.scala:16 — trait PaymentService {
  ExtendedBy:
    .../PaymentServiceLive.scala:54 — ) extends PaymentService {
  ImportedBy:
    .../ServiceModule.scala:8 — import com.example.payment.{PaymentService, ...}
  UsedAsType:
    .../AppModule.scala:68 — def paymentService: PaymentService
  Comment:
    .../PaymentServiceLive.scala:38 — /** Live implementation of PaymentService ...
```

<a id="imports"></a>

### `scalex imports <symbol> [--strict] [--no-tests] [--path PREFIX] [--limit N]` — import graph

Returns only import statements for a symbol. Use when you need to know which files depend on something — cleaner than `refs` for dependency analysis. Also has a 20-second timeout.

```bash
scalex imports PaymentService
scalex imports PaymentService --no-tests
```

<a id="members"></a>

### `scalex members <symbol> [--verbose] [--brief] [--body] [--max-lines N] [--inherited] [--kind K] [--no-tests] [--path PREFIX] [--limit N] [--offset N]` — list members

Lists member declarations (def, val, var, type) inside a class, trait, object, or enum body. Parses source on-the-fly — NOT stored in the index, so no index bloat.  Shows full signatures by default; use `--brief` for names only.

**Companion-aware**: automatically shows companion object/class members alongside the primary symbol — no follow-up query needed.

Use `--inherited` to walk the extends chain and include members from parent types — gives the full API surface in one call. Own members that shadow parent members are marked `[override]` in text output (JSON: `"isOverride":true`). Child overrides win when the same member exists in both parent and child.

Use `--body` to inline method bodies into the listing — eliminates N follow-up `body --in` calls. Use `--max-lines N` to only inline bodies ≤ N lines (0 = unlimited).

```bash
scalex members PaymentService                    # show all defs/vals with signatures (default)
scalex members PaymentService --brief            # names only, no signatures
scalex members PaymentService --no-tests         # exclude test definitions
scalex members PaymentServiceLive --inherited    # own members + inherited from parents
scalex members Compiler --body --max-lines 20    # inline method bodies ≤ 20 lines
scalex members Trees --limit 0                   # show all members (no truncation)
scalex members Trees --offset 20 --limit 20      # paginate: show members 21-40
```
```
Members of trait PaymentService (com.example) — src/.../PaymentService.scala:3:
  Defined in PaymentService:
    def   def processPayment(amount: BigDecimal): Boolean   :4
    def   def refund(id: String): Unit                      :5
```

<a id="doc"></a>

### `scalex doc <symbol> [--kind K] [--no-tests] [--path PREFIX] [--limit N]` — show scaladoc

Extracts the leading scaladoc comment (`/** ... */`) attached to a symbol. Scans backwards from the symbol's line to find the doc block. Returns "(no scaladoc)" if none found.

```bash
scalex doc PaymentService                        # show scaladoc
scalex doc PaymentService --kind trait            # only trait definition's doc
```
```
trait PaymentService (com.example) — src/.../PaymentService.scala:7:
/**
 * A service for processing payments.
 * Handles credit cards and bank transfers.
 */
```

<a id="search"></a>

### `scalex search <query> [--kind K] [--verbose] [--limit N] [--exact] [--prefix] [--definitions-only] [--returns TYPE] [--takes TYPE] [--in-package PKG]` — search symbols

Fuzzy search by name, ranked: exact > prefix > substring > camelCase fuzzy. Supports camelCase abbreviation matching — e.g. `search "hms"` matches `HttpMessageService`, `search "usl"` matches `UserServiceLive`. Use `--kind` to filter by symbol type. Results are ranked by import popularity — symbols from heavily-imported types surface first.

Use `--exact` to only return symbols with exact name match (case-insensitive). Use `--prefix` to only return symbols whose name starts with the query. Both eliminate noise from substring/fuzzy matches on large codebases. Use `--definitions-only` to filter to class/trait/object/enum definitions only — excludes defs and vals whose name happens to match.

Use `--returns TYPE` to filter to symbols whose return type contains TYPE. Use `--takes TYPE` to filter to symbols whose parameters contain TYPE. Both are substring matches on the signature.

```bash
scalex search Service --kind trait --limit 10
scalex search hms       # finds HttpMessageService via camelCase matching
scalex search Auth --prefix    # only exact + prefix matches, no substring/fuzzy
scalex search Auth --exact     # only exact name matches
scalex search Signal --definitions-only  # only class/trait/object/enum, no defs/vals
scalex search find --returns Boolean     # methods named "find" returning Boolean
scalex search process --takes String     # methods named "process" taking String
```

<a id="grep"></a>

### `scalex grep <pattern> [--in <symbol>] [--each-method] [-e PAT]... [--count] [--no-tests] [--path PREFIX] [-C N] [--limit N]` — content search

Regex search inside `.scala` and `.java` file contents. Use this for integrated source filters or symbol-scoped searches; ordinary text tools remain appropriate for literal searches and fresh edits. Has a 20-second timeout for large codebases.

The pattern is a **Java regex** — `\(` matches a literal paren, `|` is alternation. If a pattern is invalid Java regex, scalex auto-corrects it: POSIX-style patterns (e.g. `\|` for alternation) are converted to Java regex; otherwise the pattern is treated as a literal string. Either way, a hint is printed.

Use `-e` to search multiple patterns in one call — they're combined with `|`. Use `--count` to get match/file counts without full output (great for triaging before reading all results). Use `-C N` to show context lines around each match. Use `--in <symbol>` to scope the grep to a specific class or method body — supports `Owner.member` dot syntax. Use `--each-method` with `--in` to grep each method body independently and report which methods matched — answers "which methods in this class contain X?" in one call.

```bash
scalex grep "def.*process" --no-tests          # find method-like patterns
scalex grep "ctx\.settings" --path compiler/src/ -C 2  # with context
scalex grep "TODO|FIXME|HACK"                  # find code markers
scalex grep -e "Ystop" -e "stopAfter" --path compiler/src/  # multi-pattern
scalex grep "isRunnable" --count               # count only: "31 matches across 15 files"
scalex grep "ctx.settings" --in Run.compileUnits  # search within a specific method
scalex grep "test(" --in ParseSuite --each-method  # which methods call test()?
```
```
  src/main/scala/Service.scala:45 — def processPayment(amount: BigDecimal): Unit =
  src/main/scala/Handler.scala:12 — override def processRequest(req: Request): Response =
```

<a id="body"></a>

### `scalex body <symbol> [--in <owner>] [-C N] [--imports] [--no-tests] [--path PREFIX] [--limit N]` — show source body

Extracts the full source body of a def, val, var, type, class, trait, object, or enum from the file using Scalameta spans. Returns source code inline.

Use `--in <owner>` to restrict to members of a specific enclosing type — essential when the same method name exists in multiple classes. `--in` matches the **immediate enclosing type** (class/trait/object/enum); it finds nested defs at any method depth within that type, but does NOT cross into inner classes. For inner class members, use `--in InnerClass` directly. Use `-C N` to show N lines of context above and below the body span. Use `--imports` to prepend the file's import block.

**Also works with test cases** — pass the exact test name string to extract a test body. Matches `test("name")`, `it("name")`, `describe("name")`, `"name" in { }`, and `"name" >> { }` patterns. Use `--in SuiteName` to scope to a specific suite.

```bash
scalex body findUser --in UserServiceLive    # method body in specific class
scalex body UserService                       # full trait body
scalex body "findUser returns None" --in UserServiceTest  # test case body
scalex body doCompile --in Driver -C 5       # body with 5 context lines
scalex body doCompile --in Driver --imports  # body with file imports prepended
```
```
Body of findUser returns None — UserServiceTest — src/.../UserServiceTest.scala:4:
  4    |   test("findUser returns None") {
  5    |     val svc = UserServiceLive(Database.live)
  6    |     assertEquals(svc.findUser("unknown"), None)
  7    |   }
```

<a id="hierarchy"></a>

### `scalex hierarchy <symbol> [--up] [--down] [--depth N] [--no-tests] [--path PREFIX]` — type hierarchy

Full inheritance tree using extends clauses. Shows parents (walking up the extends chain) and children (walking down to implementors). External/unknown parents shown as `[external]`.

Flags: `--up` (parents only), `--down` (children only), `--depth N` (max tree depth; hierarchy default: 5, no cap; deps default: 1, max: 5). Default: both directions. Tree-formatted output with `├──`/`└──` prefixes.

```bash
scalex hierarchy UserServiceLive           # both parents and children
scalex hierarchy UserService --down        # only children (implementations)
scalex hierarchy Compiler --up             # only parent chain
scalex hierarchy Phase --depth 2           # limit tree to 2 levels deep
```
```
Hierarchy of class UserServiceLive (com.example) — .../UserService.scala:8:
  Parents:
    └── trait UserService (com.example) — .../UserService.scala:3
  Children:
    (none)
```

<a id="overrides"></a>

### `scalex overrides <method> [--of <trait>] [--body] [--max-lines N] [--limit N]` — find overrides

Finds all implementations of a specific method across classes — checks each implementor's members for the matching method name.

Use `--of <trait>` to restrict to implementations of a specific trait. Without it, searches all types. Use `--body` to show each override's source body inline. Use `--max-lines N` to only inline bodies ≤ N lines.

```bash
scalex overrides findUser --of UserService  # implementations of findUser in UserService impls
scalex overrides process                    # all types with a method named "process"
scalex overrides run --of Phase --body --max-lines 30  # show each override's body
```
```
Overrides of findUser (in implementations of UserService) — 2 found:
  UserServiceLive (com.example) — .../UserService.scala:9
    def findUser(id: String): Option[User]
  OldService (com.example) — .../Annotated.scala:4
    def findUser(id: String): Option[User]
```

<a id="explain"></a>

### `scalex explain <symbol> [--verbose] [--brief] [--body] [--max-lines N] [--shallow] [--no-doc] [--related] [--inherited] [--impl-limit N] [--members-limit N] [--expand N] [--no-tests] [--path PREFIX] [--exclude-path PREFIX]` — composite summary

Combined summary of a type. Orchestrates: definition + scaladoc + members (top 10) + companion object/class + implementations (top N) + import files. Supports **package-qualified names** (e.g. `explain com.example.Cache`) and **Owner.member dotted syntax** (e.g. `explain MyService.findUser`).

Flag reference:
- `--verbose`: show member signatures instead of just names
- `--brief`: definition + top 3 members only — no doc, companion, inherited, impls, or imports; pairs with `batch` for lightweight multi-explore
- `--body`: inline method bodies into the member listing; combine with `--max-lines N` to cap body size
- `--shallow`: skip implementations and import refs (definition + members + companion only)
- `--no-doc`: suppress the Scaladoc section — useful when exploring many types rapidly
- `--related`: show project-defined types referenced in member signatures (param types, return types, field types) — tells you what to explore next
- `--inherited`: merge parent members into output with provenance markers — full API surface
- `--impl-limit N`: max implementations to show (default: 5)
- `--members-limit N`: max members per type (default: 10); sorted by kind: classes/traits first, then defs, vals, types
- `--expand N`: recursively expand each implementation N levels deep with their members

Auto-shows **companion** object/class — duplicate members are collapsed. Fuzzy fallback: if the exact symbol isn't found, tries a fuzzy match and auto-shows the best type match. If the symbol matches a package name, falls back to `summary`. When multiple definitions match, disambiguation prints ready-to-run `scalex explain pkg.Name` commands on stderr.

```bash
scalex explain UserService                  # full summary with companion
scalex explain UserService --verbose        # member signatures inline
scalex explain UserService --shallow        # definition + members only, no impls
scalex explain com.example.UserService      # package-qualified lookup
scalex explain UserService.findUser         # Owner.member dotted syntax
scalex explain UserService --impl-limit 10  # show more implementations
scalex explain UserService --expand 1       # expand impls with their members
scalex explain UserService --inherited     # include inherited members from parents
scalex explain UserService --no-doc       # skip Scaladoc section
scalex explain UserService --brief        # definition + top 3 members only
scalex explain UserService --related     # show related project types from signatures
```
```
Explanation of trait UserService (com.example):

  Definition: src/.../UserService.scala:3
  Signature: trait UserService

  Scaladoc: (none)

  Members (top 2):
    def   findUser
    def   createUser

  Companion object UserService — src/.../UserService.scala:13
    val   default

  Implementations (top 2):
    class     UserServiceLive (com.example) — .../UserService.scala:8
    class     OldService (com.example) — .../Annotated.scala:4

  Imported by (3 files):
    src/.../ServiceModule.scala:2
    src/.../AppModule.scala:5
    src/.../TestHelper.scala:1
```

<a id="tests"></a>

### `scalex tests [<pattern>] [--verbose] [--count] [--path PREFIX] [--json]` — list test cases structurally

Extract test names from common Scala test frameworks: MUnit `test("...")`, ScalaTest `it("...")` / `describe("...")` / `"name" in { }`, specs2 `"name" >> { }`. Scans test files only (including `*.test.scala`). On-the-fly parse, no bloom filters needed.

Pass a `<pattern>` to filter tests by name (case-insensitive substring match). **When filtering, full test bodies are shown inline** — this is the fastest way to find and read a specific test in one command, no follow-up needed.

```bash
scalex tests                                    # List all test cases (names + lines)
scalex tests --count                            # Summary: "M tests across N suites" + dynamic site count
scalex tests extractBody                        # Filter + show bodies inline
scalex tests "bloom filter"                     # Multi-word filter works too
scalex tests --path src/test/scala/com/auth/    # Tests under a specific path
scalex tests --verbose                          # Show body for every test (no filter needed)
scalex tests --json                             # Structured JSON output
```


<a id="overview"></a>

### `scalex overview [--architecture] [--concise] [--focus-package PKG] [--include-tests] [--path PREFIX] [--exclude-path PREFIX] [--limit N]` — codebase summary

One-shot architectural summary. Shows symbols by kind, top packages by symbol count, and most-extended traits/classes with one-line signatures. Hub types are sorted by distinct-extending-package count (not just raw count) and single-character names are filtered out. All computed from existing in-memory index data — no extra I/O. Use `--limit N` to control "top N" lists (default: 20).

**Defaults to `--no-tests`.** Use `--include-tests` to opt in to test files.

Use `--architecture` to also show package dependency graph (from imports) and hub types (most-extended + most-referenced) — gives a structural understanding of the codebase in one call.

Use `--focus-package PKG` to scope the dependency graph to a single package — shows direct dependencies and direct dependents only. Auto-enables `--architecture` when used.

Use `--concise` to get a fixed-size summary (~60 lines) regardless of codebase size — compact header, inline symbols, top packages, dependency stats (not the full graph), hub types, and drill-down hints. Implies `--architecture`. Ideal for initial exploration of large codebases (10k+ files) where the full `--architecture` output can grow to ~1MB.

Use `--path PREFIX` to scope the entire overview to a subtree — hub types, package deps, and all counts are restricted to files under the prefix. Useful in monorepos.

```bash
scalex overview
scalex overview --limit 5
scalex overview --architecture               # + package deps + hub types
scalex overview --concise                    # fixed-size ~60-line summary (implies --architecture)
scalex overview --focus-package com.example   # scoped dependency view
scalex overview --include-tests              # include test files
scalex overview --path compiler/src/         # scope to subtree
```
```
Project overview (14,000 files, 215,000 symbols):

Symbols by kind:
  Class      45,200
  Trait      12,800
  ...

Top packages (by symbol count):
  dotty.tools.dotc.ast           1,245
  ...

Most extended (by implementation count):
  miniphase                      42 impl
  phase                          38 impl
  ...
```

<a id="file"></a>

### `scalex file <query> [--limit N]` — find file

Fuzzy search file names with the same camelCase matching as `search`. Matches against the filename without `.scala` extension, returns relative paths sorted by match quality.

```bash
scalex file PaymentService       # exact/prefix match on filename
scalex file psl                  # camelCase fuzzy: finds PaymentServiceLive.scala
```

<a id="annotated"></a>

### `scalex annotated <annotation> [--kind K] [--no-tests] [--path PREFIX] [--limit N]` — find annotated symbols

Finds all symbols that have a specific annotation. Useful for finding deprecated APIs, entry points (`@main`), or framework-specific annotations. The `@` prefix is optional — `annotated deprecated` and `annotated @deprecated` both work. Annotation matching is case-insensitive.

```bash
scalex annotated deprecated --verbose          # all @deprecated symbols
scalex annotated main                          # all @main entry points
scalex annotated deprecated --kind class       # only @deprecated classes
scalex annotated tailrec --path core/src/      # @tailrec defs in core
```
```
  class     OldService (com.example) — .../OldService.scala:12
  def       legacyProcess (com.example) — .../Legacy.scala:45
```

<a id="package"></a>

### `scalex package <pkg> [--verbose] [--kind K] [--definitions-only] [--no-tests] [--path PREFIX] [--limit N]` — explore package

Lists all symbols in a package, grouped by kind (Class, Trait, Object, Enum, etc.). Fills the gap between `overview` (top packages) and `symbols` (per-file) — enables top-down exploration: overview → package → explain.

Package name is fuzzy matched: exact → suffix (`.example` matches `com.example`) → substring. On not-found, suggests matching package names.

Use `--definitions-only` to filter to class/trait/object/enum — hides val/def noise on large packages.

```bash
scalex package com.example                  # all symbols in com.example
scalex package example                      # fuzzy match: resolves to com.example
scalex package com.example --kind trait     # only traits
scalex package com.example --definitions-only  # only class/trait/object/enum
scalex package com.example --no-tests       # exclude test symbols
scalex package com.example --verbose        # show signatures
```
```
Package com.example (45 symbols):

  Traits (3):
    UserService                    src/main/.../UserService.scala:3
    Database                       src/main/.../Database.scala:3
    PaymentService                 src/main/.../Documented.scala:7

  Classes (5):
    UserServiceLive                src/main/.../UserService.scala:8
    ...
```

<a id="api"></a>

### `scalex api <package> [--used-by PKG] [--kind K] [--no-tests] [--path PREFIX] [--limit N]` — public API surface

Shows which symbols in a package are actually imported by other packages — an import-based view, not a compiler-verified public API inventory. Cross-references stored import data with the package's symbol list. Symbols sorted by external importer count (descending). Internal-only symbols (never imported externally) listed at the bottom.

Use `--used-by PKG` to filter importers to only those from a specific package — answers "which types from package A are used by package B" (coupling analysis).

Package name is fuzzy matched (same as `package` command): exact → suffix → substring. Zero index change — pure in-memory query.

```bash
scalex api com.example                              # public API surface of com.example
scalex api example                                  # fuzzy match on package name
scalex api com.example --used-by com.example.web    # coupling: what does web use from example?
scalex api com.example --kind trait                 # only traits in the API surface
scalex api com.example --no-tests                   # exclude test symbols
scalex api com.example --json                       # structured JSON output
```
```
API surface of com.example (8 of 15 symbols imported externally):

  UserServiceLive           class     12 importers  src/.../UserServiceLive.scala:8
  User                      class      9 importers  src/.../Model.scala:3
  UserService               trait      8 importers  src/.../UserService.scala:7

  Not imported externally (7): Role, UserId, userOrdering, ...
```

<a id="summary"></a>

### `scalex summary <package> [--no-tests] [--path PREFIX]` — package breakdown

Sub-package view with symbol counts. Middle ground between `overview` (project-wide) and `package` (single-package symbols). Use for top-down drill-down: overview → summary → package → explain.

Package name is fuzzy matched (same as `package` command).

```bash
scalex summary com.example                  # sub-packages with counts
scalex summary example                      # fuzzy match on package name
scalex summary com.example --no-tests       # exclude test symbols
```
```
Summary of com.example (245 symbols):

  .backend.emitter      89
  .frontend.parser      67
  .core.types           45
  (root)                24
  .util                 20
```

<a id="deps"></a>

### `scalex deps <symbol> [--depth N]` — dependency graph

Shows what a symbol depends on: file-level imports (cross-referenced with index) and body-level type/term references. Reverse of `refs` — instead of "who uses X", shows "what does X use". Use `--depth N` to enable transitive dependency expansion (default: 1 = direct only, max: 5).

```bash
scalex deps ExplicitClient                 # imports + body references (direct only)
scalex deps ExplicitClient --depth 2       # transitive deps up to 2 levels deep
```
```
Dependencies of "ExplicitClient":

  Imports:
    trait     UserService — .../UserService.scala:3

  Body references:
    trait     UserService — .../UserService.scala:3
```

<a id="context"></a>

### `scalex context <file:line>` — enclosing scopes

Shows the scope chain at a given line: walk up the Scalameta tree from position to find enclosing package, class, method, etc.

```bash
scalex context src/main/scala/App.scala:42  # package → class → def chain
```
```
Context at src/main/scala/App.scala:42:
  package   com.example (line 1)
  class     AppService (line 10)
  def       processRequest (line 38)
```

<a id="diff"></a>

### `scalex diff <git-ref> [--path PREFIX] [--exclude-path PREFIX] [--no-tests] [--limit N]` — symbol-level diff

Shows added/removed/modified Scala declarations compared to a Git ref. Parses working-tree source and `git show ref:path` directly; unstaged edits are included without relying on cached symbol data.

`Modified` compares the complete declaration text (including its body) and package, independently of line numbers. Moving an unchanged declaration does not mark it modified. Formatting or comments inside a declaration count as changes; this is a source-text comparison, not a semantic diff. An edited method also changes its enclosing class/object declaration, while untouched sibling methods remain unchanged.

Same-named declarations are matched within their enclosing types. Unchanged overloads are matched first, then changed overloads prefer the same signature. Ambiguous simultaneous signature changes use source order; this is not compiler-level symbol identity.

`--path`, `--exclude-path`, and `--no-tests` filter files before analysis and the changed-file count. Every matching changed file is examined. `--limit` only limits displayed symbols per added/removed/modified group; use `--limit 0` for complete groups. This command describes supported declarations, so use Git diff to review changes outside that scope.

```bash
scalex diff HEAD~1                          # changes since last commit
scalex diff main                            # changes since main branch
scalex diff HEAD --path src/ --limit 0       # all declaration changes under src/
```
```
Symbol changes compared to HEAD~1 (3 files changed):

  Added (1):
    + class     NewHandler — src/handler/NewHandler.scala:5

  Removed (1):
    - def       oldMethod — src/legacy/Legacy.scala:12

  Modified (1):
    ~ class     AppService — src/main/AppService.scala:8
```

<a id="ast-pattern"></a>

### `scalex ast-pattern [--has-method NAME] [--extends TRAIT] [--body-contains PAT] [--no-tests] [--path PREFIX] [--limit N]` — structural AST search

Structural search with composable predicates. Filters types (class/trait/object/enum) by:
- `--extends TRAIT`: parent type name
- `--has-method NAME`: has a member with that name
- `--body-contains PAT`: body source text contains pattern

All predicates are ANDed together.

```bash
scalex ast-pattern --extends UserService --has-method findUser  # types extending UserService with findUser
scalex ast-pattern --body-contains "db.query"                   # types whose body contains "db.query"
```
```
Types matching AST pattern (extends=UserService, has-method=findUser) — 2 found:
  class     UserServiceLive (com.example) — .../UserService.scala:8
  class     OldService (com.example) — .../Annotated.scala:4
```

<a id="entrypoints"></a>

### `scalex entrypoints [--no-tests] [--path PREFIX] [--json]` — find entry points

Find all application entry points in the workspace: `@main` annotated functions, objects with `def main(...)`, objects that `extends App`, and test suites (MUnit, ScalaTest, specs2). Results are grouped by category. Use `--no-tests` to exclude test suites.

```bash
scalex entrypoints                           # all entry points
scalex entrypoints --no-tests                # skip test suites
scalex entrypoints --path src/main/          # only production code
scalex entrypoints --json                    # structured JSON output
```
```
Entrypoints — 5 found:

  @main annotated (2):
    def       run — src/Main.scala:3
    def       serve — src/Server.scala:1

  def main(...) methods (1):
    object    MyApp — src/MyApp.scala:5

  extends App (1):
    object    Legacy — src/Legacy.scala:1

  Test suites (1):
    class     UserSpec — tests/UserSpec.scala:3
```

<a id="coverage"></a>

### `scalex coverage <symbol>` — mentions in test files

Shorthand for "find references in test files only". Shows how many test files reference the symbol and where. This is not runtime coverage or proof of assertions. This command currently omits the underlying scan timeout status; use refs when completeness matters.

```bash
scalex coverage UserService                     # Test-file mentions
scalex coverage extractBody -w .                # Test-file mentions
scalex coverage UserService --json              # JSON: testFileCount, referenceCount, references
```

<a id="batch"></a>

### `scalex batch [-w workspace]` — multiple queries, one index load

Reads queries from stdin, loads index once. Use when you need several lookups — avoids re-loading the index for each command. Batch lines split on whitespace without shell quote parsing; run arguments containing spaces separately. Output includes command separators, even for JSON query results.

The workspace is set on the `batch` subcommand, not per-query. Use `-w` or pass it as a positional arg after `batch`:

```bash
echo -e "def UserService\nimpl UserService\nimports UserService" | scalex batch -w /path/to/project
echo -e "def UserService\ngrep processPayment\nimpl UserService" | scalex batch /path/to/project
```

<a id="symbols"></a>

### `scalex symbols <file> [--verbose] [--summary]` / `scalex packages`

`symbols` lists everything defined in a file (`--verbose` for signatures). `--summary` shows grouped counts by kind (e.g. "12 classes, 3 traits, 45 defs") instead of listing each symbol — useful for large files. `packages` lists all packages in the index.

<a id="index"></a>

### `scalex index` — index statistics

Loads the index through the normal OID cache and reports counts, parse failures, and cache statistics. This does not force a rebuild; unstaged edits may leave cached data stale. See SKILL.md.

---

## Options

| Flag | Effect |
|---|---|
| `-w`, `--workspace PATH` | Set workspace path (default: current directory) |
| `--verbose` | Show signatures, extends clauses, param types |
| `--categorize`, `-c` | Group refs by category (default; kept for backwards compatibility) |
| `--flat` | Refs: flat list instead of categorized (overrides default) |
| `--definitions-only` | Search: only return class/trait/object/enum definitions |
| `--category CAT` | Refs: filter to a single category (Definition/ExtendedBy/ImportedBy/UsedAsType/Usage/Comment) |
| `--limit N` | Max results (default: 20, 0 = unlimited) |
| `--offset N` | Members: skip first N results for pagination (default: 0) |
| `--kind K` | Filter by kind: class, trait, object, def, val, type, enum, given, extension |
| `--no-tests` | Exclude test files (test/, tests/, testing/, bench-*, *Spec.scala, etc.) |
| `--include-tests` | Override --no-tests default for overview command |
| `--path PREFIX` | Restrict results to files under PREFIX (e.g. `compiler/src/`) |
| `--exclude-path PREFIX` | Exclude files under PREFIX (e.g. `--exclude-path sbt-test/`) |
| `-C N` | Show N context lines around each reference (refs, grep, body) |
| `-e PATTERN` | Grep: additional pattern (repeatable); combined with `|` |
| `--count` | Grep/refs/tests: show counts only, no full results. Tests also reports dynamic test sites |
| `--top N` | Refs: rank top N files by reference count (impact analysis) |
| `--exact` | Search: only exact name matches (case-insensitive) |
| `--prefix` | Search: only exact + prefix matches |
| `--in OWNER` | Body/grep: restrict to members of the given enclosing type |
| `--each-method` | Grep: with `--in`, report which methods match (per-method grep) |
| `--of TRAIT` | Overrides: restrict to implementations of the given trait |
| `--body` | Members/overrides/explain: inline method bodies into output |
| `--max-lines N` | Members/overrides/explain: only inline bodies ≤ N lines (0 = unlimited) |
| `--imports` | Body: prepend file's import block to output |
| `--shallow` | Explain: skip implementations and import refs (definition + members only) |
| `--impl-limit N` | Explain: max implementations to show (default: 5) |
| `--members-limit N` | Explain: max members to show per type (default: 10) |
| `--expand N` | Explain: recursively expand implementations N levels deep |
| `--up` | Hierarchy: show only parents (default: both) |
| `--down` | Hierarchy: show only children (default: both) |
| `--depth N` | Hierarchy/deps: max tree depth (hierarchy default: 5, no cap; deps default: 1, max: 5) |
| `--brief` | Members: names only; Explain: definition + top 3 members only |
| `--summary` | Symbols: grouped counts by kind instead of full listing |
| `--strict` | Refs/imports: treat `_` and `$` as word characters (stricter matching) |
| `--no-doc` | Explain: suppress Scaladoc section |
| `--inherited` | Members/explain: include inherited members from parent types |
| `--architecture` | Overview: show package dependency graph and hub types |
| `--concise` | Overview: fixed-size summary (~60 lines) with top packages, hub types, dep stats (implies `--architecture`) |
| `--focus-package PKG` | Overview: scope dependency graph to a single package |
| `--has-method NAME` | AST pattern: match types that have a method with NAME |
| `--extends TRAIT` | AST pattern: match types that extend TRAIT |
| `--body-contains PAT` | AST pattern: match types whose body contains PAT |
| `--used-by PKG` | API: filter importers to only those from PKG |
| `--returns TYPE` | Search: filter to symbols whose signature returns TYPE |
| `--takes TYPE` | Search: filter to symbols whose signature takes TYPE |
| `--json` | Output results as JSON — structured output for programmatic parsing |
| `--max-output N` | Limit result output to N characters (0 = unlimited); oversized JSON becomes valid truncation metadata; metadata is exempt from the budget |
| `--in-package PKG` | Filter results to files whose package matches PKG prefix |
| `--timings` | Print exclusive phase timings, command/render work, and elapsed request total to stderr (runtime startup excluded) |
| `--version` | Print version and exit |
