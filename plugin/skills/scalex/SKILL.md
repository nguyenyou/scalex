---
name: scalex
description: "Scala/Java code intelligence CLI for Scala 2/3 and Java codebases. Find definitions, implementations, usages, imports, members, scaladoc, codebase overview, package API surface, files, annotated symbols, file contents. Render directed graphs as ASCII/Unicode art and parse diagrams. Triggers: \"where is X defined\", \"who implements Y\", \"find usages of Z\", \"what methods does X have\", \"show source of X\", \"inheritance tree\", \"explain this type\", \"what changed since commit\", \"find types extending X with method Y\", \"what does this package export\", \"draw a graph\", or before renaming. Test navigation: \"what tests exist\", \"is X tested\". Use proactively exploring unfamiliar Scala code. Supports fuzzy camelCase search. Prefer scalex over grep/glob for Scala symbol lookups."
---

You have access to `scalex`, a Scala/Java code intelligence CLI that understands Scala syntax (classes, traits, objects, enums, givens, extensions, type aliases, defs, vals) and Java syntax (classes, interfaces, enums, records, methods, fields). It parses Scala source files via Scalameta and Java files via JavaParser — no compiler or build server needed. Works with both Scala 3 and Scala 2 files (tries Scala 3 dialect first, falls back to Scala 2.13).

First run on a project indexes all git-tracked `.scala` and `.java` files (~3s for 14k files). Subsequent runs use OID-based caching and only re-parse changed files (~400-500ms). Java files are indexed via JavaParser AST — classes, interfaces, enums, records, methods, and fields are extracted with full member support (constructors, nested types, enum constants, `@Override` detection).

Scalex only works on **git-tracked files** in **Scala/Java projects**. Do not use it for non-git directories, non-JVM languages, or files that haven't been `git add`ed yet.

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

Scalex extracts **top-level declarations** from every git-tracked `.scala` file: classes, traits, objects, enums, defs, vals, types, givens (named only — anonymous givens are skipped), and extension groups. It also extracts **annotations** on these declarations (e.g. `@deprecated`, `@main`, `@tailrec`). Java files (`.java`) are indexed via JavaParser AST — classes, interfaces, enums, records, methods, and fields are extracted with annotations. Java `members`, `body`, and `body --in` work the same as for Scala types. Scalex does NOT index local definitions inside method bodies, method parameters, or pattern bindings.

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

### `scalex members <symbol> [--verbose] [--brief] [--body] [--max-lines N] [--inherited] [--kind K] [--no-tests] [--path PREFIX] [--limit N] [--offset N]` — list members

Lists member declarations (def, val, var, type) inside a class, trait, object, or enum body. Parses source on-the-fly — NOT stored in the index, so no index bloat. Single file parse is <50ms. Shows full signatures by default; use `--brief` for names only.

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

### `scalex grep <pattern> [--in <symbol>] [-e PAT]... [--count] [--no-tests] [--path PREFIX] [-C N] [--limit N]` — content search

Regex search inside `.scala` and `.java` file contents. This is the scalex equivalent of grep, but with integrated `--path` and `--no-tests` filtering — use it instead of the Grep tool when searching inside Scala/Java files. Has a 20-second timeout for large codebases.

The pattern is a **Java regex** (not POSIX) — use `|` for alternation (not `\|`), `( )` for grouping (not `\( \)`). If you get zero results, check for POSIX-style escapes. Scalex will print a hint if it detects this.

Use `-e` to search multiple patterns in one call — they're combined with `|`. Use `--count` to get match/file counts without full output (great for triaging before reading all results). Use `-C N` to show context lines around each match. Use `--in <symbol>` to scope the grep to a specific class or method body — supports `Owner.member` dot syntax.

```bash
scalex grep "def.*process" --no-tests          # find method-like patterns
scalex grep "ctx\.settings" --path compiler/src/ -C 2  # with context
scalex grep "TODO|FIXME|HACK"                  # find code markers
scalex grep -e "Ystop" -e "stopAfter" --path compiler/src/  # multi-pattern
scalex grep "isRunnable" --count               # count only: "31 matches across 15 files"
scalex grep "ctx.settings" --in Run.compileUnits  # search within a specific method
```
```
  src/main/scala/Service.scala:45 — def processPayment(amount: BigDecimal): Unit =
  src/main/scala/Handler.scala:12 — override def processRequest(req: Request): Response =
```

### `scalex body <symbol> [--in <owner>] [-C N] [--imports] [--no-tests] [--path PREFIX] [--limit N]` — show source body

Extracts the full source body of a def, val, var, type, class, trait, object, or enum from the file using Scalameta spans. Eliminates ~50% of follow-up Read calls by giving the agent the actual source code inline.

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

### `scalex explain <symbol> [--verbose] [--brief] [--body] [--max-lines N] [--shallow] [--no-doc] [--inherited] [--impl-limit N] [--members-limit N] [--expand N] [--no-tests] [--path PREFIX] [--exclude-path PREFIX]` — composite summary

One-shot summary that eliminates 4-5 round-trips per type. Orchestrates: definition + scaladoc + members (top 10) + companion object/class + implementations (top N) + import files. Supports **package-qualified names** (e.g. `explain com.example.Cache`) and **Owner.member dotted syntax** (e.g. `explain MyService.findUser`).

Flag reference:
- `--verbose`: show member signatures instead of just names
- `--brief`: definition + top 3 members only — no doc, companion, inherited, impls, or imports; pairs with `batch` for lightweight multi-explore
- `--body`: inline method bodies into the member listing; combine with `--max-lines N` to cap body size
- `--shallow`: skip implementations and import refs (definition + members + companion only)
- `--no-doc`: suppress the Scaladoc section — useful when exploring many types rapidly
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

## Additional commands

These commands are fully documented in `references/commands.md` (next to this SKILL.md). Read it when you need detailed syntax or examples.

| Command | Purpose | Key flags |
|---|---|---|
| `overview` | Codebase summary: symbols by kind, top packages, most-extended types (hidden in `--architecture` mode — hub types supersedes) | `--architecture`, `--focus-package` |
| `file <query>` | Find files by name (fuzzy camelCase match) | |
| `annotated <ann>` | Find symbols with a specific annotation | `--kind K` |
| `package <pkg>` | All symbols in a package, grouped by kind | `--definitions-only`, `--verbose` |
| `api <pkg>` | Public API surface (externally imported symbols) | `--used-by PKG` |
| `summary <pkg>` | Sub-packages with symbol counts | |
| `deps <symbol>` | What does this symbol depend on? (reverse of `refs`) | `--depth N` |
| `context <file:line>` | Enclosing scopes at a line (package > class > def) | |
| `diff <git-ref>` | Symbol-level diff vs a git ref | |
| `ast-pattern` | Structural search: `--extends`, `--has-method`, `--body-contains` | |
| `entrypoints` | Find `@main`, `def main`, `extends App`, test suites | `--no-tests` |
| `coverage <symbol>` | References in test files only (is this tested?) | |
| `batch` | Multiple queries, one index load (stdin) | |
| `symbols <file>` | What's defined in this file? | `--summary` |
| `packages` | List all packages | |
| `index` | Force reindex (rarely needed) | |
| `graph --render "A->B"` | Render directed graph as ASCII/Unicode art | `--unicode`, `--vertical`, `--rounded`, `--double` |
| `graph --parse` | Parse ASCII diagram from stdin into boxes+edges | `--json` |

Full options table is also in `references/commands.md`. Graph command examples with rendered output are in `references/graph-examples.md`.

## Common workflows

Most commands are self-explanatory from their name — `scalex def X`, `scalex members X`, `scalex doc X`. These workflows cover the non-obvious choices:

**"What's the impact of renaming X?"** → `scalex refs X` (categorized by default — groups by Definition/ExtendedBy/ImportedBy/UsedAsType/Usage/Comment)

**"What's in this package?"** → `scalex package com.example` — all symbols grouped by kind; fuzzy match on package name

**"How is this package structured?"** → `scalex summary com.example` — sub-packages with symbol counts for top-down exploration

**"What does this package export?"** → `scalex api com.example` — shows symbols imported by other packages, sorted by importer count

**"Too many results / noisy output"** → combine `--no-tests`, `--path compiler/src/`, `--kind class`, or `search --prefix`/`--exact`

**"I need to look up 3+ symbols"** → use `batch` to load the index once: `echo -e "def Foo\nimpl Foo\nrefs Foo" | scalex batch -w /project`

**"Search for a pattern in Scala files"** → `scalex grep "pattern"` — prefer this over the Grep tool for `.scala` files because it integrates with `--path` and `--no-tests`

**"Show me the source code of method X"** → `scalex body X --in MyClass` — use `--in` when the name exists in multiple classes

**"Give me everything about this type"** → `scalex explain MyTrait` — one-shot composite: def + doc + members + companion + impls + import count (saves 4-5 round-trips). Use `--expand 1` to also see each implementation's members. Use `--brief` for condensed output (definition + top 3 members). Use `--body` to inline method bodies

**"Show me a class with all method bodies"** → `scalex members Compiler --body --max-lines 20` — inline bodies ≤ 20 lines; eliminates N follow-up `body --in` calls

**"How do different types implement method X?"** → `scalex overrides run --of Phase --body` — show each override's source body inline

**"Search within a specific class"** → `scalex grep "pattern" --in ClassName` — restrict grep to the class body; supports `Owner.member` dot syntax

**"Quick-explore 3-5 types"** → `echo -e "explain Foo --brief\nexplain Bar --brief\nexplain Baz --brief" | scalex batch` — lightweight multi-explain in one call

**"Disambiguate a common name"** → `scalex def com.example.cache.Cache` — package-qualified lookup; partial qualification also works: `scalex def cache.Cache`. When `explain` hits multiple matches, it prints ready-to-run `scalex explain pkg.Name` commands on stderr — just copy-paste

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

scalex understands Scala and Java syntax. It finds `given` definitions, `enum` declarations, `extension` groups, Java interfaces/records, and annotated symbols that grep patterns miss. It returns structured output with symbol kind, package name, and line numbers. `--categorize` provides refactoring-ready impact analysis that would require multiple grep passes. And `scalex grep` gives you regex content search with built-in `--no-tests` and `--path` filtering, eliminating the need for the Grep tool on `.scala`/`.java` files entirely. For any Scala/Java-specific navigation or search, prefer scalex — it's purpose-built for exactly this.
