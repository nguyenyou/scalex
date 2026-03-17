---
name: scalex
description: "Scala code intelligence CLI for Scala 2/3 codebases. Find definitions, implementations, usages, imports, members, scaladoc, codebase overview, package API surface, files, annotated symbols, file contents. Triggers: \"where is X defined\", \"who implements Y\", \"find usages of Z\", \"what methods does X have\", \"show source of X\", \"inheritance tree\", \"explain this type\", \"what changed since commit\", \"find types extending X with method Y\", \"what does this package export\", or before renaming. Test navigation: \"what tests exist\", \"is X tested\", \"show test for Y\", \"find tests covering Z\". Use proactively exploring unfamiliar Scala code. Supports fuzzy camelCase search (e.g. \"hms\" finds HttpMessageService). Prefer scalex over grep/glob for Scala symbol lookups. Use `scalex grep` for .scala content search — integrates with --path and --no-tests filters."
---

You have access to `scalex`, a Scala code intelligence CLI that understands Scala syntax (classes, traits, objects, enums, givens, extensions, type aliases, defs, vals). It parses source files via Scalameta — no compiler or build server needed. Works with both Scala 3 and Scala 2 files (tries Scala 3 dialect first, falls back to Scala 2.13).

First run on a project indexes all git-tracked `.scala` and `.java` files (~3s for 14k files). Subsequent runs use OID-based caching and only re-parse changed files (~400-500ms). Java files are indexed via regex (class/interface/enum/record).

## Setup

A bootstrap script at `scripts/scalex-cli` (next to this SKILL.md) handles everything automatically — platform detection, downloading the correct native binary from GitHub releases, and caching at `~/.cache/scalex/`. It auto-upgrades when the skill version changes.

**Invocation pattern** — use the absolute path to `scalex-cli` directly in every command. Do NOT use shell variables (`$SCALEX`) — AI agent shells are non-persistent, so variables are lost between commands.

```bash
# Pattern: bash "<path-to-scripts>/scalex-cli" <command> [args] -w <workspace>
bash "/absolute/path/to/skills/scalex/scripts/scalex-cli" def MyTrait --verbose -w /project
bash "/absolute/path/to/skills/scalex/scripts/scalex-cli" impl MyTrait -w /project
echo -e "def Foo\nimpl Foo\nrefs Foo" | bash "/absolute/path/to/skills/scalex/scripts/scalex-cli" batch -w /project
```

Replace `/absolute/path/to/skills/scalex` with the absolute path to the directory containing this SKILL.md. Remember this path and substitute it directly into every command.

## Troubleshooting

- **`permission denied`**: Run `chmod +x /path/to/scalex-cli` once, then retry.
- **macOS quarantine**: `xattr -d com.apple.quarantine ~/.cache/scalex/*`

## What scalex indexes

Scalex extracts **top-level declarations** from every git-tracked `.scala` file: classes, traits, objects, enums, defs, vals, types, givens (named only — anonymous givens are skipped), and extension groups. It also extracts **annotations** on these declarations (e.g. `@deprecated`, `@main`, `@tailrec`). Java files (`.java`) are also indexed — classes, interfaces, enums, and records are extracted via regex. Scalex does NOT index local definitions inside method bodies, method parameters, or pattern bindings.

The `refs`, `imports`, and `grep` commands work differently — they do text search across files, so they find ALL textual occurrences regardless of whether the symbol is in the index.

## Commands

All commands default to current directory. You can set the workspace with `-w` / `--workspace` (e.g., `scalex def -w /path/to/project MyTrait`) or as a positional argument (e.g., `scalex def /path/to/project MyTrait`). The `-w` flag is preferred — it avoids ambiguity between workspace and symbol. Every command auto-indexes on first run.

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

### `scalex impl <trait> [--verbose] [--kind K] [--no-tests] [--path PREFIX] [--limit N]` — find implementations

Finds all classes/objects/enums that extend or mix in a trait. Also finds types that use the symbol as a type argument in extends clauses (e.g. `impl Foo` finds `class Bar extends Mixin[Foo]`). Uses the index directly — much faster and more targeted than `refs` when you specifically need concrete implementations.

```bash
scalex impl PaymentService --verbose
scalex impl PaymentService --no-tests --path core/src/
```
```
  class     PaymentServiceLive — .../PaymentServiceLive.scala:43
             class PaymentServiceLive extends PaymentService
```

### `scalex refs <symbol> [--flat] [--count] [--top N] [--strict] [--category CAT] [--no-tests] [--path PREFIX] [-C N] [--limit N]` — find references

Finds all usages of a symbol using word-boundary text matching. Uses bloom filters to skip files that definitely don't contain the symbol, then reads candidate files. Has a 20-second timeout — on very large codebases with a common symbol, output may say "(timed out — partial results)".

Output is **categorized by default** — groups results into Definition, ExtendedBy, ImportedBy, UsedAsType, Usage, and Comment so you can understand impact at a glance. Use `--category CAT` to filter to a single category (e.g. `--category ExtendedBy`). Use `-C N` to show N lines of context around each reference (like `grep -C`) — reduces follow-up Read calls. Use `--flat` to get a flat list instead. Use `--count` to get category counts without full file lists — fast impact triage. Use `--top N` to rank files by reference count descending — shows the N heaviest users first for impact analysis.

```bash
scalex refs PaymentService                        # categorized by default
scalex refs PaymentService --count               # summary: "12 importers, 4 extensions, 30 usages"
scalex refs PaymentService --category ExtendedBy  # only show ExtendedBy
scalex refs PaymentService --no-tests --path core/src/
scalex refs PaymentService -C 3                   # show 3 lines of context
scalex refs PaymentService --flat                 # flat list (old default)
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

### `scalex imports <symbol> [--strict] [--no-tests] [--path PREFIX] [--limit N]` — import graph

Returns only import statements for a symbol. Use when you need to know which files depend on something — cleaner than `refs` for dependency analysis. Also has a 20-second timeout.

```bash
scalex imports PaymentService
scalex imports PaymentService --no-tests
```

### `scalex members <symbol> [--verbose] [--brief] [--inherited] [--kind K] [--no-tests] [--path PREFIX] [--limit N]` — list members

Lists member declarations (def, val, var, type) inside a class, trait, object, or enum body. Parses source on-the-fly — NOT stored in the index, so no index bloat. Single file parse is <50ms. Shows full signatures by default; use `--brief` for names only.

**Companion-aware**: automatically shows companion object/class members alongside the primary symbol — no follow-up query needed.

Use `--inherited` to walk the extends chain and include members from parent types — gives the full API surface in one call. Own members that shadow parent members are marked `[override]` in text output (JSON: `"isOverride":true`). Child overrides win when the same member exists in both parent and child.

```bash
scalex members PaymentService                    # show all defs/vals with signatures (default)
scalex members PaymentService --brief            # names only, no signatures
scalex members PaymentService --no-tests         # exclude test definitions
scalex members PaymentServiceLive --inherited    # own members + inherited from parents
```
```
Members of trait PaymentService (com.example) — src/.../PaymentService.scala:3:
  Defined in PaymentService:
    def   def processPayment(amount: BigDecimal): Boolean   :4
    def   def refund(id: String): Unit                      :5
```

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

### `scalex overview [--architecture] [--focus-package PKG] [--include-tests] [--path PREFIX] [--exclude-path PREFIX] [--limit N]` — codebase summary

One-shot architectural summary. Shows symbols by kind, top packages by symbol count, and most-extended traits/classes with one-line signatures. Hub types are sorted by distinct-extending-package count (not just raw count) and single-character names are filtered out. All computed from existing in-memory index data — no extra I/O. Use `--limit N` to control "top N" lists (default: 20).

**Defaults to `--no-tests`** — production code is almost always the intent. Use `--include-tests` to opt in to test files.

Use `--architecture` to also show package dependency graph (from imports) and hub types (most-extended + most-referenced) — gives a structural understanding of the codebase in one call.

Use `--focus-package PKG` to scope the dependency graph to a single package — shows direct dependencies and direct dependents only. Auto-enables `--architecture` when used.

Use `--path PREFIX` to scope the entire overview to a subtree — hub types, package deps, and all counts are restricted to files under the prefix. Useful in monorepos.

```bash
scalex overview
scalex overview --limit 5
scalex overview --architecture               # + package deps + hub types
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

### `scalex search <query> [--kind K] [--verbose] [--limit N] [--exact] [--prefix] [--definitions-only] [--returns TYPE] [--takes TYPE]` — search symbols

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

### `scalex file <query> [--limit N]` — find file

Fuzzy search file names with the same camelCase matching as `search`. Matches against the filename without `.scala` extension, returns relative paths sorted by match quality.

```bash
scalex file PaymentService       # exact/prefix match on filename
scalex file psl                  # camelCase fuzzy: finds PaymentServiceLive.scala
```

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

### `scalex grep <pattern> [-e PAT]... [--count] [--no-tests] [--path PREFIX] [-C N] [--limit N]` — content search

Regex search inside `.scala` file contents. This is the scalex equivalent of grep, but with integrated `--path` and `--no-tests` filtering — use it instead of the Grep tool when searching inside Scala files. Has a 20-second timeout for large codebases.

The pattern is a **Java regex** (not POSIX) — use `|` for alternation (not `\|`), `( )` for grouping (not `\( \)`). If you get zero results, check for POSIX-style escapes. Scalex will print a hint if it detects this.

Use `-e` to search multiple patterns in one call — they're combined with `|`. Use `--count` to get match/file counts without full output (great for triaging before reading all results). Use `-C N` to show context lines around each match.

```bash
scalex grep "def.*process" --no-tests          # find method-like patterns
scalex grep "ctx\.settings" --path compiler/src/ -C 2  # with context
scalex grep "TODO|FIXME|HACK"                  # find code markers
scalex grep -e "Ystop" -e "stopAfter" --path compiler/src/  # multi-pattern
scalex grep "isRunnable" --count               # count only: "31 matches across 15 files"
```
```
  src/main/scala/Service.scala:45 — def processPayment(amount: BigDecimal): Unit =
  src/main/scala/Handler.scala:12 — override def processRequest(req: Request): Response =
```

### `scalex body <symbol> [--in <owner>] [--no-tests] [--path PREFIX] [--limit N]` — show source body

Extracts the full source body of a def, val, var, type, class, trait, object, or enum from the file using Scalameta spans. Eliminates ~50% of follow-up Read calls by giving the agent the actual source code inline.

Use `--in <owner>` to restrict to members of a specific enclosing type — essential when the same method name exists in multiple classes.

**Also works with test cases** — pass the exact test name string to extract a test body. Matches `test("name")`, `it("name")`, `describe("name")`, `"name" in { }`, and `"name" >> { }` patterns. Use `--in SuiteName` to scope to a specific suite.

```bash
scalex body findUser --in UserServiceLive    # method body in specific class
scalex body UserService                       # full trait body
scalex body "findUser returns None" --in UserServiceTest  # test case body
```
```
Body of findUser returns None — UserServiceTest — src/.../UserServiceTest.scala:4:
  4    |   test("findUser returns None") {
  5    |     val svc = UserServiceLive(Database.live)
  6    |     assertEquals(svc.findUser("unknown"), None)
  7    |   }
```

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

### `scalex overrides <method> [--of <trait>] [--limit N]` — find overrides

Finds all implementations of a specific method across classes — checks each implementor's members for the matching method name.

Use `--of <trait>` to restrict to implementations of a specific trait. Without it, searches all types.

```bash
scalex overrides findUser --of UserService  # implementations of findUser in UserService impls
scalex overrides process                    # all types with a method named "process"
```
```
Overrides of findUser (in implementations of UserService) — 2 found:
  UserServiceLive (com.example) — .../UserService.scala:9
    def findUser(id: String): Option[User]
  OldService (com.example) — .../Annotated.scala:4
    def findUser(id: String): Option[User]
```

### `scalex explain <symbol> [--verbose] [--shallow] [--no-doc] [--inherited] [--impl-limit N] [--members-limit N] [--expand N] [--no-tests] [--path PREFIX] [--exclude-path PREFIX]` — composite summary

One-shot summary that eliminates 4-5 round-trips per type. Orchestrates: definition + scaladoc + members (top 10) + companion object/class + implementations (top N) + import files. Supports **package-qualified names** (e.g. `explain com.example.Cache`) and **Owner.member dotted syntax** (e.g. `explain MyService.findUser`).

`--verbose` shows member signatures instead of just names. `--shallow` skips implementations and import refs entirely (definition + members + companion only — useful for understanding a type's API without output blowup). `--no-doc` suppresses the Scaladoc section — useful when exploring many types rapidly and doc dominates output. `--inherited` merges parent members into the output with provenance markers — shows the full API surface including inherited members. `--impl-limit N` controls how many implementations to show (default: 5); when more exist, shows "(showing N of M — use --impl-limit to adjust)". `--members-limit N` controls how many members to show per type (default: 10). Members are sorted by kind: classes/traits first, then defs, then vals, then types. `--expand N` recursively expands each implementation N levels deep, showing their members and sub-implementations — eliminates N follow-up explains. Auto-shows **companion** object/class with its members when applicable; companion members that duplicate primary members are collapsed. When import count <= 10, the actual importing files are shown inline; otherwise shows count + hint. If the exact symbol isn't found, `explain` tries a fuzzy match and auto-shows the best type match with a hint. If the symbol matches a package name instead, falls back to `summary` automatically. When multiple definitions match, a disambiguation hint is shown on stderr.

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

### `scalex diff <git-ref>` — symbol-level diff

Shows added/removed/modified symbols compared to a git ref. Parses current source + `git show ref:path` for old source, compares symbol lists.

```bash
scalex diff HEAD~1                          # changes since last commit
scalex diff main                            # changes since main branch
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

### `scalex api <package> [--used-by PKG] [--kind K] [--no-tests] [--path PREFIX] [--limit N]` — public API surface

Shows which symbols in a package are actually imported by other packages — the public API surface. Cross-references stored import data with the package's symbol list. Symbols sorted by external importer count (descending). Internal-only symbols (never imported externally) listed at the bottom.

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

### `scalex symbols <file> [--verbose] [--summary]` / `scalex packages` — file symbols / list packages

`symbols` lists everything defined in a file (`--verbose` for signatures). `--summary` shows grouped counts by kind (e.g. "12 classes, 3 traits, 45 defs") instead of listing each symbol — useful for large files. `packages` lists all packages in the index.

### `scalex batch [-w workspace]` — multiple queries, one index load

Reads queries from stdin, loads index once. Use when you need several lookups — avoids re-loading the index for each command. 5 queries in ~1s instead of ~5s. Supports all commands.

The workspace is set on the `batch` subcommand, not per-query. Use `-w` or pass it as a positional arg after `batch`:

```bash
echo -e "def UserService\nimpl UserService\nimports UserService" | scalex batch -w /path/to/project
echo -e "def UserService\ngrep processPayment\nimpl UserService" | scalex batch /path/to/project
```

### `scalex tests [<pattern>] [--verbose] [--path PREFIX] [--json]` — list test cases structurally

Extract test names from common Scala test frameworks: MUnit `test("...")`, ScalaTest `it("...")` / `describe("...")` / `"name" in { }`, specs2 `"name" >> { }`. Scans test files only (including `*.test.scala`). On-the-fly parse, no bloom filters needed.

Pass a `<pattern>` to filter tests by name (case-insensitive substring match). **When filtering, full test bodies are shown inline** — this is the fastest way to find and read a specific test in one command, no follow-up needed.

```bash
scalex tests                                    # List all test cases (names + lines)
scalex tests extractBody                        # Filter + show bodies inline
scalex tests "bloom filter"                     # Multi-word filter works too
scalex tests --path src/test/scala/com/auth/    # Tests under a specific path
scalex tests --verbose                          # Show body for every test (no filter needed)
scalex tests --json                             # Structured JSON output
```
```
ScalexSuite — scalex.test.scala:10:
  test  "extractBody finds method body in a class"  :1798
    1798 |   test("extractBody finds method body in a class") {
    1799 |     val file = workspace.resolve("src/main/.../UserService.scala")
    1800 |     val results = extractBody(file, "findUser", None)
    1801 |     assert(results.nonEmpty, "Should find findUser body")
    1802 |     ...
    1806 |   }
```

### `scalex coverage <symbol>` — is this symbol tested?

Shorthand for "find references in test files only". Shows how many test files reference the symbol and where. Faster than `refs X` followed by manual test-file filtering.

```bash
scalex coverage UserService                     # Is UserService tested?
scalex coverage extractBody -w .                # Is extractBody tested?
scalex coverage UserService --json              # JSON: testFileCount, referenceCount, references
```

### `scalex index` — force reindex

Normally not needed — every command auto-reindexes changed files. Use after major branch switches or large merges to get a clean reindex.

## Options

| Flag | Effect |
|---|---|
| `-w`, `--workspace PATH` | Set workspace path (default: current directory) |
| `--verbose` | Show signatures, extends clauses, param types |
| `--categorize`, `-c` | Group refs by category (default; kept for backwards compatibility) |
| `--flat` | Refs: flat list instead of categorized (overrides default) |
| `--definitions-only` | Search: only return class/trait/object/enum definitions |
| `--category CAT` | Refs: filter to a single category (Definition/ExtendedBy/ImportedBy/UsedAsType/Usage/Comment) |
| `--limit N` | Max results (default: 20) |
| `--kind K` | Filter by kind: class, trait, object, def, val, type, enum, given, extension |
| `--no-tests` | Exclude test files (test/, tests/, testing/, bench-*, *Spec.scala, etc.) |
| `--include-tests` | Override --no-tests default for overview command |
| `--path PREFIX` | Restrict results to files under PREFIX (e.g. `compiler/src/`) |
| `--exclude-path PREFIX` | Exclude files under PREFIX (e.g. `--exclude-path sbt-test/`) |
| `-C N` | Show N context lines around each reference (refs, grep) |
| `-e PATTERN` | Grep: additional pattern (repeatable); combined with `\|` |
| `--count` | Grep/refs: show counts only, no full results |
| `--top N` | Refs: rank top N files by reference count (impact analysis) |
| `--exact` | Search: only exact name matches (case-insensitive) |
| `--prefix` | Search: only exact + prefix matches |
| `--in OWNER` | Body: restrict to members of the given enclosing type |
| `--of TRAIT` | Overrides: restrict to implementations of the given trait |
| `--shallow` | Explain: skip implementations and import refs (definition + members only) |
| `--impl-limit N` | Explain: max implementations to show (default: 5) |
| `--members-limit N` | Explain: max members to show per type (default: 10) |
| `--expand N` | Explain: recursively expand implementations N levels deep |
| `--up` | Hierarchy: show only parents (default: both) |
| `--down` | Hierarchy: show only children (default: both) |
| `--depth N` | Hierarchy/deps: max tree depth (hierarchy default: 5, no cap; deps default: 1, max: 5) |
| `--brief` | Members: show names only (default shows signatures) |
| `--summary` | Symbols: grouped counts by kind instead of full listing |
| `--strict` | Refs/imports: treat `_` and `$` as word characters (stricter matching) |
| `--no-doc` | Explain: suppress Scaladoc section |
| `--inherited` | Members/explain: include inherited members from parent types |
| `--architecture` | Overview: show package dependency graph and hub types |
| `--focus-package PKG` | Overview: scope dependency graph to a single package |
| `--has-method NAME` | AST pattern: match types that have a method with NAME |
| `--extends TRAIT` | AST pattern: match types that extend TRAIT |
| `--body-contains PAT` | AST pattern: match types whose body contains PAT |
| `--used-by PKG` | API: filter importers to only those from PKG |
| `--returns TYPE` | Search: filter to symbols whose signature returns TYPE |
| `--takes TYPE` | Search: filter to symbols whose signature takes TYPE |
| `--json` | Output results as JSON — structured output for programmatic parsing |
| `--timings` | Print per-phase timing breakdown to stderr |
| `--version` | Print version and exit |

## Common workflows

Most commands are self-explanatory from their name — `scalex def X`, `scalex members X`, `scalex doc X`. These workflows cover the non-obvious choices:

**"What's the impact of renaming X?"** → `scalex refs X` (categorized by default — groups by Definition/ExtendedBy/ImportedBy/UsedAsType/Usage/Comment)

**"What's in this package?"** → `scalex package com.example` — all symbols grouped by kind; fuzzy match on package name

**"How is this package structured?"** → `scalex summary com.example` — sub-packages with symbol counts for top-down exploration

**"What does this package export?"** → `scalex api com.example` — shows symbols imported by other packages, sorted by importer count

**"Too many results / noisy output"** → combine `--no-tests`, `--path compiler/src/`, `--kind class`, or `search --prefix`/`--exact`

**"I need to look up 3+ symbols"** → use `batch` to load the index once: `echo -e "def Foo\nimpl Foo\nrefs Foo" | scalex batch -w /project`

**"Quick overview of multiple types"** → use `batch` with `explain --shallow --no-doc --members-limit 3`: `echo -e "explain --shallow --no-doc --members-limit 3 Foo\nexplain --shallow --no-doc --members-limit 3 Bar" | scalex batch -w /project`

**"Search for a pattern in Scala files"** → `scalex grep "pattern"` — prefer this over the Grep tool for `.scala` files because it integrates with `--path` and `--no-tests`

**"Show me the source code of method X"** → `scalex body X --in MyClass` — use `--in` when the name exists in multiple classes

**"Give me everything about this type"** → `scalex explain MyTrait` — one-shot composite: def + doc + members + companion + impls + import count (saves 4-5 round-trips). Use `--expand 1` to also see each implementation's members

**"Disambiguate a common name"** → `scalex def com.example.cache.Cache` — package-qualified lookup; partial qualification also works: `scalex def cache.Cache`

**"Find types using Foo in extends clause"** → `scalex impl Foo` — also finds `class Bar extends Mixin[Foo]` via type-param parent indexing

**"Find tests for X / show me tests about X"** → `scalex tests extractBody` — filter by name + show bodies inline in one command

**"Is this function tested?"** → `scalex coverage extractBody` — refs in test files only, with count + locations

**"How many places reference X?"** → `scalex refs X --count` — category counts without full file lists

**"Which files use X the most?"** → `scalex refs X --top 10` — rank files by reference count for impact analysis

**"Navigate to a specific method"** → `scalex def MyService.findUser` — Owner.member dotted syntax, faster than `body --in`

**"What from package A does package B use?"** → `scalex api com.example --used-by com.example.web` — coupling analysis

**"Find methods that return/take a type"** → `scalex search process --returns Boolean` or `scalex search convert --takes String`

**"Where are the entry points?"** → `scalex entrypoints` — finds `@main`, `def main(...)`, `extends App`, and test suites in one call

**"I need structured output"** → append `--json` to any command

## Fallback

If scalex returns "not found", the symbol might be a local definition (not top-level), in a file with parse errors, or not git-tracked. Fall back to Grep/Glob/Read for manual search.

## Why scalex over grep

scalex understands Scala syntax. It finds `given` definitions, `enum` declarations, `extension` groups, and annotated symbols that grep patterns miss. It returns structured output with symbol kind, package name, and line numbers. `--categorize` provides refactoring-ready impact analysis that would require multiple grep passes. And `scalex grep` gives you regex content search with built-in `--no-tests` and `--path` filtering, eliminating the need for the Grep tool on `.scala` files entirely. For any Scala-specific navigation or search, prefer scalex — it's purpose-built for exactly this.
