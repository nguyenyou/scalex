---
name: scalex
description: Scala code intelligence CLI for navigating Scala codebases (Scala 2 and 3). Use this skill whenever you're working in a project with .scala files and need to understand code structure — finding where a class/trait/object is defined, who extends a trait, who uses or imports a symbol, what's in a file, searching for files by name, or exploring packages. Trigger on any Scala navigation task like "where is X defined", "who implements Y", "find usages of Z", "what traits exist", "find the file for X", or when you need to understand impact before renaming/refactoring. Also use proactively when exploring an unfamiliar Scala codebase — scalex is much faster and more structured than grep for Scala-specific queries. Supports fuzzy camelCase search (e.g. "hms" finds HttpMessageService). Always prefer scalex over grep/glob for Scala symbol and file lookups.
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

Scalex extracts **top-level declarations** from every git-tracked `.scala` file: classes, traits, objects, enums, defs, vals, types, givens (named only — anonymous givens are skipped), and extension groups. It does NOT index local definitions inside method bodies, method parameters, or pattern bindings.

The `refs`, `imports`, and `categorize` features work differently — they do text search with word-boundary matching across files, so they find ALL textual occurrences regardless of whether the symbol is in the index.

## Commands

All commands default to current directory. You can set the workspace with `-w` / `--workspace` (e.g., `scalex def -w /path/to/project MyTrait`) or as a positional argument (e.g., `scalex def /path/to/project MyTrait`). The `-w` flag is preferred — it avoids ambiguity between workspace and symbol. Every command auto-indexes on first run.

### `scalex def <symbol> [--verbose]` — find definition

Returns where a symbol is defined, including given instances that grep would miss. Use `--verbose` to see the full signature inline — saves a follow-up Read call.

```bash
scalex def PaymentService --verbose
```
```
  trait     PaymentService (com.example.payment) — .../PaymentService.scala:16
             trait PaymentService
  given     paymentService (com.example.module) — .../ServiceModule.scala:185
             given paymentService: PaymentService
```

### `scalex impl <trait> [--verbose] [--limit N]` — find implementations

Finds all classes/objects/enums that extend or mix in a trait. Uses the index directly — much faster and more targeted than `refs` when you specifically need concrete implementations.

```bash
scalex impl PaymentService --verbose
```
```
  class     PaymentServiceLive — .../PaymentServiceLive.scala:43
             class PaymentServiceLive extends PaymentService
```

### `scalex refs <symbol> [--categorize] [--limit N]` — find references

Finds all usages of a symbol using word-boundary text matching. Uses bloom filters to skip files that definitely don't contain the symbol, then reads candidate files. Has a 20-second timeout — on very large codebases with a common symbol, output may say "(timed out — partial results)".

Use `--categorize` before refactoring — it groups results into Definition, ExtendedBy, ImportedBy, UsedAsType, Usage, and Comment so you can understand impact at a glance.

```bash
scalex refs PaymentService --categorize
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

Without `--categorize`, returns a flat list (faster for simple lookups).

### `scalex imports <symbol> [--limit N]` — import graph

Returns only import statements for a symbol. Use when you need to know which files depend on something — cleaner than `refs` for dependency analysis. Also has a 20-second timeout.

```bash
scalex imports PaymentService
```

### `scalex search <query> [--kind K] [--verbose] [--limit N]` — search symbols

Fuzzy search by name, ranked: exact > prefix > substring > camelCase fuzzy. Supports camelCase abbreviation matching — e.g. `search "hms"` matches `HttpMessageService`, `search "usl"` matches `UserServiceLive`. Use `--kind` to filter by symbol type.

```bash
scalex search Service --kind trait --limit 10
scalex search hms       # finds HttpMessageService via camelCase matching
```

### `scalex file <query> [--limit N]` — find file

Fuzzy search file names with the same camelCase matching as `search`. Matches against the filename without `.scala` extension, returns relative paths sorted by match quality.

```bash
scalex file PaymentService       # exact/prefix match on filename
scalex file psl                  # camelCase fuzzy: finds PaymentServiceLive.scala
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

Reads queries from stdin, loads index once. Use when you need several lookups — avoids re-loading the index for each command. 5 queries in ~1s instead of ~5s.

The workspace is set on the `batch` subcommand, not per-query. Use `-w` or pass it as a positional arg after `batch`:

```bash
echo -e "def UserService\nimpl UserService\nimports UserService" | scalex batch -w /path/to/project
echo -e "def UserService\nimpl UserService" | scalex batch /path/to/project
```

### `scalex index` — force reindex

Normally not needed — every command auto-reindexes changed files. Use after major branch switches or large merges to get a clean reindex.

## Options

| Flag | Effect |
|---|---|
| `-w`, `--workspace PATH` | Set workspace path (default: current directory) |
| `--verbose` | Show signatures, extends clauses, param types |
| `--categorize` | Group refs into Definition/ExtendedBy/ImportedBy/UsedAsType/Comment/Usage |
| `--limit N` | Max results (default: 20) |
| `--kind K` | Filter search: class, trait, object, def, val, type, enum, given, extension |

## Common workflows

**"Where is X defined?"** → `scalex def X --verbose`

**"Who implements trait X?"** → `scalex impl X` (index-based, fast)

**"What's the impact of renaming X?"** → `scalex refs X --categorize` (shows all usages grouped by kind)

**"Which files import X?"** → `scalex imports X` (just import lines, clean for dependency analysis)

**"What traits/classes exist named like X?"** → `scalex search X --kind trait`

**"Find a file named like X"** → `scalex file X` (fuzzy camelCase search on filenames)

**"What's in this file?"** → `scalex symbols path/to/File.scala --verbose`

**"I need to look up 3+ symbols"** → use `batch` to avoid repeated index loads

## Fallback

If scalex returns "not found", the symbol might be a local definition (not top-level), in a file with parse errors, or not git-tracked. Fall back to Grep/Glob/Read for manual search.

## Why scalex over grep

scalex understands Scala syntax. It finds `given` definitions, `enum` declarations, and `extension` groups that grep patterns miss. It returns structured output with symbol kind, package name, and line numbers. `--categorize` provides refactoring-ready impact analysis that would require multiple grep passes. For any Scala-specific navigation, prefer scalex — it's purpose-built for exactly this.
