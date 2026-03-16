---
name: scalex
description: Scala code intelligence CLI for navigating Scala codebases (Scala 2 and 3). Use this skill whenever you're working in a project with .scala files and need to understand code structure — finding where a class/trait/object is defined, who extends a trait, who uses or imports a symbol, what's in a file, what members a class has, reading scaladoc, getting a codebase overview, searching for files by name, finding annotated symbols, or searching file contents. Trigger on any Scala navigation task like "where is X defined", "who implements Y", "find usages of Z", "what methods does X have", "show me the body/source of X", "what's the inheritance tree", "explain this type", "what changed since last commit", "find types that extend X with method Y", or when you need to understand impact before renaming/refactoring. Also triggers on test navigation: "what tests exist", "is X tested", "show me the test for Y", "list test cases", "find tests that cover Z", "read that test body". Also use proactively when exploring an unfamiliar Scala codebase — scalex is much faster and more structured than grep for Scala-specific queries. Supports fuzzy camelCase search (e.g. "hms" finds HttpMessageService). Always prefer scalex over grep/glob for Scala symbol and file lookups. Use `scalex grep` instead of the Grep tool for searching inside .scala files — it integrates with scalex's --path and --no-tests filters.
---

You have access to `scalex`, a Scala code intelligence CLI that understands Scala syntax (classes, traits, objects, enums, givens, extensions, type aliases, defs, vals). It parses source files via Scalameta — no compiler or build server needed. Works with both Scala 3 and Scala 2 files (tries Scala 3 dialect first, falls back to Scala 2.13).

First run on a project indexes all git-tracked `.scala` files (~3s for 14k files). Subsequent runs use OID-based caching and only re-parse changed files (~300ms).

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

Scalex extracts **top-level declarations** from every git-tracked `.scala` file: classes, traits, objects, enums, defs, vals, types, givens (named only — anonymous givens are skipped), and extension groups. It also extracts **annotations** on these declarations (e.g. `@deprecated`, `@main`, `@tailrec`). It does NOT index local definitions inside method bodies, method parameters, or pattern bindings.

The `refs`, `imports`, `grep`, and `categorize` features work differently — they do text search across files, so they find ALL textual occurrences regardless of whether the symbol is in the index.

## Commands

All commands default to current directory. You can set the workspace with `-w` / `--workspace` (e.g., `scalex def -w /path/to/project MyTrait`) or as a positional argument (e.g., `scalex def /path/to/project MyTrait`). The `-w` flag is preferred — it avoids ambiguity between workspace and symbol. Every command auto-indexes on first run.

### `scalex def <symbol> [--verbose] [--kind K] [--no-tests] [--path PREFIX]` — find definition

Returns where a symbol is defined, including given instances that grep would miss. Use `--verbose` to see the full signature inline — saves a follow-up Read call. Results are ranked: class/trait/object/enum first, non-test before test, shorter paths first.

```bash
scalex def PaymentService --verbose
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

Finds all classes/objects/enums that extend or mix in a trait. Uses the index directly — much faster and more targeted than `refs` when you specifically need concrete implementations.

```bash
scalex impl PaymentService --verbose
scalex impl PaymentService --no-tests --path core/src/
```
```
  class     PaymentServiceLive — .../PaymentServiceLive.scala:43
             class PaymentServiceLive extends PaymentService
```

### `scalex refs <symbol> [--flat] [--category CAT] [--no-tests] [--path PREFIX] [-C N] [--limit N]` — find references

Finds all usages of a symbol using word-boundary text matching. Uses bloom filters to skip files that definitely don't contain the symbol, then reads candidate files. Has a 20-second timeout — on very large codebases with a common symbol, output may say "(timed out — partial results)".

Output is **categorized by default** — groups results into Definition, ExtendedBy, ImportedBy, UsedAsType, Usage, and Comment so you can understand impact at a glance. Use `--category CAT` to filter to a single category (e.g. `--category ExtendedBy`). Use `-C N` to show N lines of context around each reference (like `grep -C`) — reduces follow-up Read calls. Use `--flat` to get a flat list instead.

```bash
scalex refs PaymentService                        # categorized by default
scalex refs PaymentService --category ExtendedBy  # only show ExtendedBy
scalex refs PaymentService --no-tests --path core/src/
scalex refs PaymentService -C 3                   # show 3 lines of context
scalex refs PaymentService --flat                 # flat list (old default)
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

### `scalex imports <symbol> [--no-tests] [--path PREFIX] [--limit N]` — import graph

Returns only import statements for a symbol. Use when you need to know which files depend on something — cleaner than `refs` for dependency analysis. Also has a 20-second timeout.

```bash
scalex imports PaymentService
scalex imports PaymentService --no-tests
```

### `scalex members <symbol> [--verbose] [--inherited] [--kind K] [--no-tests] [--path PREFIX] [--limit N]` — list members

Lists member declarations (def, val, var, type) inside a class, trait, object, or enum body. Parses source on-the-fly — NOT stored in the index, so no index bloat. Single file parse is <50ms. Use `--verbose` to see full signatures.

Use `--inherited` to walk the extends chain and include members from parent types — gives the full API surface in one call. Child overrides win when the same member exists in both parent and child.

```bash
scalex members PaymentService --verbose          # show all defs/vals with signatures
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

### `scalex overview [--architecture] [--limit N]` — codebase summary

One-shot architectural summary. Shows symbols by kind, top packages by symbol count, and most-extended traits/classes. All computed from existing in-memory index data — no extra I/O. Use `--limit N` to control "top N" lists (default: 20).

Use `--architecture` to also show package dependency graph (from imports) and hub types (most-extended + most-referenced) — gives a structural understanding of the codebase in one call.

```bash
scalex overview
scalex overview --limit 5
scalex overview --architecture               # + package deps + hub types
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

### `scalex search <query> [--kind K] [--verbose] [--limit N] [--exact] [--prefix] [--definitions-only]` — search symbols

Fuzzy search by name, ranked: exact > prefix > substring > camelCase fuzzy. Supports camelCase abbreviation matching — e.g. `search "hms"` matches `HttpMessageService`, `search "usl"` matches `UserServiceLive`. Use `--kind` to filter by symbol type.

Use `--exact` to only return symbols with exact name match (case-insensitive). Use `--prefix` to only return symbols whose name starts with the query. Both eliminate noise from substring/fuzzy matches on large codebases. Use `--definitions-only` to filter to class/trait/object/enum definitions only — excludes defs and vals whose name happens to match.

```bash
scalex search Service --kind trait --limit 10
scalex search hms       # finds HttpMessageService via camelCase matching
scalex search Auth --prefix    # only exact + prefix matches, no substring/fuzzy
scalex search Auth --exact     # only exact name matches
scalex search Signal --definitions-only  # only class/trait/object/enum, no defs/vals
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

### `scalex hierarchy <symbol> [--up] [--down] [--no-tests] [--path PREFIX]` — type hierarchy

Full inheritance tree using extends clauses. Shows parents (walking up the extends chain) and children (walking down to implementors). External/unknown parents shown as `[external]`.

Flags: `--up` (parents only), `--down` (children only). Default: both directions. Tree-formatted output with `├──`/`└──` prefixes.

```bash
scalex hierarchy UserServiceLive           # both parents and children
scalex hierarchy UserService --down        # only children (implementations)
scalex hierarchy Compiler --up             # only parent chain
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

### `scalex explain <symbol> [--impl-limit N] [--no-tests] [--path PREFIX]` — composite summary

One-shot summary that eliminates 4-5 round-trips per type. Orchestrates: definition + scaladoc + members (top 10) + implementations (top N) + import count.

`--impl-limit N` controls how many implementations to show (default: 5).

```bash
scalex explain UserService                  # full summary
scalex explain UserService --impl-limit 10  # show more implementations
```
```
Explanation of trait UserService (com.example):

  Definition: src/.../UserService.scala:3
  Signature: trait UserService

  Scaladoc: (none)

  Members (top 2):
    def   findUser
    def   createUser

  Implementations (top 2):
    class     UserServiceLive (com.example) — .../UserService.scala:8
    class     OldService (com.example) — .../Annotated.scala:4

  Imported by: 3 files
```

### `scalex deps <symbol>` — dependency graph

Shows what a symbol depends on: file-level imports (cross-referenced with index) and body-level type/term references. Reverse of `refs` — instead of "who uses X", shows "what does X use".

```bash
scalex deps ExplicitClient                 # imports + body references
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

### `scalex symbols <file> [--verbose]` — file symbols

Lists everything defined in a file. Use `--verbose` to see signatures.

```bash
scalex symbols src/main/scala/com/example/Service.scala --verbose
```

### `scalex packages` — list packages

```bash
scalex packages
```

### `scalex batch [-w workspace]` — multiple queries, one index load

Reads queries from stdin, loads index once. Use when you need several lookups — avoids re-loading the index for each command. 5 queries in ~1s instead of ~5s. Supports all subcommands: `def`, `impl`, `refs`, `search`, `imports`, `annotated`, `grep`, `symbols`, `packages`, `file`.

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
| `--path PREFIX` | Restrict results to files under PREFIX (e.g. `compiler/src/`) |
| `-C N` | Show N context lines around each reference (refs, grep) |
| `-e PATTERN` | Grep: additional pattern (repeatable); combined with `\|` |
| `--count` | Grep: output match/file count only, no full results |
| `--exact` | Search: only exact name matches (case-insensitive) |
| `--prefix` | Search: only exact + prefix matches |
| `--in OWNER` | Body: restrict to members of the given enclosing type |
| `--of TRAIT` | Overrides: restrict to implementations of the given trait |
| `--impl-limit N` | Explain: max implementations to show (default: 5) |
| `--up` | Hierarchy: show only parents (default: both) |
| `--down` | Hierarchy: show only children (default: both) |
| `--inherited` | Members: include inherited members from parent types |
| `--architecture` | Overview: show package dependency graph and hub types |
| `--has-method NAME` | AST pattern: match types that have a method with NAME |
| `--extends TRAIT` | AST pattern: match types that extend TRAIT |
| `--body-contains PAT` | AST pattern: match types whose body contains PAT |
| `--json` | Output results as JSON — structured output for programmatic parsing |

## Common workflows

**"Where is X defined?"** → `scalex def X --verbose`

**"Who implements trait X?"** → `scalex impl X` (index-based, fast)

**"What's the impact of renaming X?"** → `scalex refs X` (categorized by default — shows all usages grouped by kind)

**"Which files import X?"** → `scalex imports X` (just import lines, clean for dependency analysis)

**"What traits/classes exist named like X?"** → `scalex search X --kind trait`

**"Find a file named like X"** → `scalex file X` (fuzzy camelCase search on filenames)

**"What's in this file?"** → `scalex symbols path/to/File.scala --verbose`

**"What methods does this trait have?"** → `scalex members MyTrait --verbose` (lists all defs/vals/types without reading the file)

**"Show me the docs for X"** → `scalex doc X` (extracts scaladoc comment)

**"Give me an overview of this codebase"** → `scalex overview` (symbols by kind, top packages, most-extended traits)

**"Only show who extends this symbol"** → `scalex refs X --category ExtendedBy` (filter to one category)

**"Search for types only, not defs/vals"** → `scalex search Signal --definitions-only` (class/trait/object/enum only)

**"I need to look up 3+ symbols"** → use `batch` to avoid repeated index loads

**"Too many results / noisy output"** → `--no-tests`, `--path compiler/src/`, or `search --prefix`/`--exact` to filter

**"I want to see the code around each reference"** → `scalex refs X -C 3` shows 3 lines of context

**"Find all deprecated APIs"** → `scalex annotated deprecated` (or any annotation: `@main`, `@tailrec`, etc.)

**"Search for a pattern in Scala files"** → `scalex grep "pattern"` — prefer this over the Grep tool for `.scala` files since it integrates with `--path` and `--no-tests`

**"Search for multiple patterns at once"** → `scalex grep -e "pattern1" -e "pattern2"` — combined with `|`, one process invocation

**"How many files match this pattern?"** → `scalex grep "pattern" --count` — quick triage before reading all results

**"I need grep + def + refs in one shot"** → use `batch`: `echo -e "grep processPayment\ndef PaymentService\nrefs PaymentService" | scalex batch -w /project`

**"I need structured output for scripting"** → append `--json` to any command (e.g. `scalex def X --json`)

**"Show me the source code of method X"** → `scalex body X --in MyClass` (extracts body without reading the whole file)

**"What's the inheritance tree?"** → `scalex hierarchy MyClass` (parents + children tree)

**"Who overrides method X?"** → `scalex overrides X --of MyTrait` (method-level impl search)

**"Give me everything about this type"** → `scalex explain MyTrait` (def + doc + members + impls + import count)

**"What does this class depend on?"** → `scalex deps MyClass` (imports + body refs)

**"What's the context at this line?"** → `scalex context file.scala:42` (enclosing scopes)

**"What symbols changed?"** → `scalex diff HEAD~1` (added/removed/modified symbols)

**"Find types by structure"** → `scalex ast-pattern --extends Trait --has-method process` (structural search)

**"Show me inherited members too"** → `scalex members MyClass --inherited` (own + parent members)

**"Show architecture"** → `scalex overview --architecture` (package deps + hub types)

**"What tests exist?"** → `scalex tests` (lists all test cases with suite + line)

**"Find tests for X / show me tests about X"** → `scalex tests extractBody` (filter by name + show bodies inline — one command, no follow-up)

**"Is this function tested?"** → `scalex coverage extractBody` (refs in test files only, with count + locations)

**"Show me a specific test"** → `scalex body "exact test name" --in MySuite` (when you know the exact name)

## Fallback

If scalex returns "not found", the symbol might be a local definition (not top-level), in a file with parse errors, or not git-tracked. Fall back to Grep/Glob/Read for manual search.

## Why scalex over grep

scalex understands Scala syntax. It finds `given` definitions, `enum` declarations, `extension` groups, and annotated symbols that grep patterns miss. It returns structured output with symbol kind, package name, and line numbers. `--categorize` provides refactoring-ready impact analysis that would require multiple grep passes. And `scalex grep` gives you regex content search with built-in `--no-tests` and `--path` filtering, eliminating the need for the Grep tool on `.scala` files entirely. For any Scala-specific navigation or search, prefer scalex — it's purpose-built for exactly this.
