# Scalex — Additional Commands & Options Reference

This file contains full documentation for commands not covered inline in SKILL.md, plus the complete options table.

## Table of Contents

- [overview](#scalex-overview)
- [file](#scalex-file)
- [annotated](#scalex-annotated)
- [package](#scalex-package)
- [api](#scalex-api)
- [summary](#scalex-summary)
- [deps](#scalex-deps)
- [context](#scalex-context)
- [diff](#scalex-diff)
- [ast-pattern](#scalex-ast-pattern)
- [entrypoints](#scalex-entrypoints)
- [coverage](#scalex-coverage)
- [batch](#scalex-batch)
- [symbols / packages](#scalex-symbols--packages)
- [index](#scalex-index)
- [Options table](#options)

---

### `scalex overview [--architecture] [--concise] [--focus-package PKG] [--include-tests] [--path PREFIX] [--exclude-path PREFIX] [--limit N]` — codebase summary

One-shot architectural summary. Shows symbols by kind, top packages by symbol count, and most-extended traits/classes with one-line signatures. Hub types are sorted by distinct-extending-package count (not just raw count) and single-character names are filtered out. All computed from existing in-memory index data — no extra I/O. Use `--limit N` to control "top N" lists (default: 20).

**Defaults to `--no-tests`** — production code is almost always the intent. Use `--include-tests` to opt in to test files.

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

### `scalex coverage <symbol>` — is this symbol tested?

Shorthand for "find references in test files only". Shows how many test files reference the symbol and where. Faster than `refs X` followed by manual test-file filtering.

```bash
scalex coverage UserService                     # Is UserService tested?
scalex coverage extractBody -w .                # Is extractBody tested?
scalex coverage UserService --json              # JSON: testFileCount, referenceCount, references
```

### `scalex batch [-w workspace]` — multiple queries, one index load

Reads queries from stdin, loads index once. Use when you need several lookups — avoids re-loading the index for each command. 5 queries in ~1s instead of ~5s. Supports all commands.

The workspace is set on the `batch` subcommand, not per-query. Use `-w` or pass it as a positional arg after `batch`:

```bash
echo -e "def UserService\nimpl UserService\nimports UserService" | scalex batch -w /path/to/project
echo -e "def UserService\ngrep processPayment\nimpl UserService" | scalex batch /path/to/project
```

### `scalex symbols <file> [--verbose] [--summary]` / `scalex packages`

`symbols` lists everything defined in a file (`--verbose` for signatures). `--summary` shows grouped counts by kind (e.g. "12 classes, 3 traits, 45 defs") instead of listing each symbol — useful for large files. `packages` lists all packages in the index.

### `scalex index` — force reindex

Normally not needed — every command auto-reindexes changed files. Use after major branch switches or large merges to get a clean reindex.

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
| `--max-output N` | Truncate output at N characters (0 = unlimited); works on all commands |
| `--in-package PKG` | Filter results to files whose package matches PKG prefix |
| `--timings` | Print per-phase timing breakdown to stderr |
| `--version` | Print version and exit |
