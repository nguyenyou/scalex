---
name: scalex
description: "Explore and navigate Git-tracked Scala 2/3 and Java source with Scalex. Use for symbol definitions, implementations, members, source bodies, test discovery, package structure, and reference candidates before edits. Prefer it for Scala/Java symbol navigation; use ordinary file or text tools for untracked files, other languages, and literal searches. Also supports ASCII graph rendering or parsing when explicitly requested."
---

# Scalex

Use Scalex to locate relevant code, then read enough source to answer the question or make the requested change. It parses source without compilation or a build server. Its results describe written syntax, not compiler-resolved semantics.

## Run

The bundled `scripts/scalex-cli` downloads and caches the pinned native binary when needed. Resolve its absolute path relative to this skill and supply the repository root explicitly:

```bash
bash /absolute/path/to/scalex/scripts/scalex-cli def UserService -w /repo
```

Examples below abbreviate this invocation as `scalex`. Substitute the bundled script invocation unless the user selected another binary. Keep using the same binary and workspace throughout the task.

Commands discover Git-tracked `.scala` and `.java` files and maintain `.scalex/index.bin`. New, untracked files are absent; read or search them directly. Do not stage user files just to make them discoverable.

## Choose the smallest useful query

| Question | Start with |
|---|---|
| Where is a known symbol? | `def UserService` |
| I only know part of the name | `search Service --kind trait --limit 10` |
| Which definition is meant? | `def com.example.UserService` or `def UserService.findUser` |
| What does this type expose? | `members UserService` |
| How does this method work? | `body findUser --in UserServiceLive` |
| Give me a short introduction to a type | `explain UserService --brief` |
| Show implementations or parent types | `impl UserService` or `hierarchy UserService --up` |
| What code mentions this symbol? | `refs UserService` |
| Which imports mention it? | `imports UserService` |
| What tests are declared? | `tests "bloom filter"` |
| Which test files mention it? | `coverage UserService` |
| Where should I start in this repository? | `overview --concise`, then `summary com.example` |
| What is declared in this file? | `symbols src/main/scala/UserService.scala` |
| Search within a known class or method | `grep 'process' --in UserServiceLive` |

Use `explain` without `--brief` when its combined definition, documentation, members, companion, implementations, and imports are useful. Use `members --body --max-lines 20` to inspect several short implementations together. Avoid loading a whole package or many full bodies for a single lookup.

For detailed syntax, less common commands, and supported flags, read the relevant section of [commands.md](references/commands.md). It covers package/API analysis, overrides, dependencies, diffs, structural searches, and output options. Use `help` to check the invoked binary's interface if documentation and behavior differ.

## Interpret the evidence

- **Definitions and members are syntactic.** No inferred types, implicit/given resolution, macro expansion, or overload resolution. Local definitions are not generally indexed; read a known enclosing body or use text search when a lookup misses.
- **References are candidates.** `refs` uses word-boundary text matching, including comments and unrelated symbols with the same name. Categories and import-based confidence are heuristics. `--strict` tightens identifier boundaries; it does not resolve symbol identity. Before a rename, inspect the matches and validate the edit with the project's compiler/tests.
- **Implementations need interpretation.** `impl Foo` also includes types with `Foo` as a type argument in an extends clause, such as `extends Mixin[Foo]`; these are not necessarily subtypes of `Foo`.
- **Test mentions are not coverage.** `coverage` finds textual references in test files, not executed lines or assertions. `tests` discovers supported test declaration patterns; dynamic names and unsupported frameworks may be absent.
- **API results are import-based.** `api` estimates externally imported symbols; it is not a compiler-verified public API inventory.
- **A miss is not proof of absence.** Check spelling, qualification, scope, file tracking, parse failures, and cache freshness. Fall back to direct source reads or `rg` when those better answer the question.

## Keep scope and output deliberate

Start with a qualified symbol or the relevant path when known. Use `--path`, `--kind`, and command-supported package filters to reduce noise. Use `--no-tests` only when excluding tests fits the question; retain tests during impact analysis. `overview` excludes tests by default; add `--include-tests` when needed.

Prefer `--limit N` for readable samples and `--count` for counts. `--max-output N` limits result characters; with `--json`, oversized output becomes a valid truncation object instead of the requested result payload. Inspect truncation metadata before consuming JSON. A display limit is not a guarantee of less scanning work.

`refs`, `imports`, and `grep` have scan deadlines and can return partial results. Read stderr and timeout indicators; never present an incomplete scan as exhaustive. `coverage` currently discards the underlying timeout indicator, so use `refs` and inspect test-file matches when scan completeness matters.

Use ordinary text search for literal strings, configuration, unsupported files, or fresh edits. `scalex grep` is useful for symbol-scoped bodies and integrated source filters; it is not a required replacement for `rg`. Its patterns use Java regex syntax and invalid patterns may be rewritten with a diagnostic; check that diagnostic when matching punctuation.

## Cache freshness after edits

The current cache compares Git index OIDs, not working-tree content hashes. Unstaged edits can leave cached symbols, imports, and bloom filters stale. Text reads may therefore disagree with cached navigation, and stale bloom filters can hide newly added names.

After editing, verify affected code directly. `index` reports the normally loaded index; it does **not** force a clean rebuild. Do not stage changes or delete caches merely to make a query work. Use the project's compile/test workflow to validate code changes.

## Several independent lookups

Use `batch` to share one index load when several queries are already known:

```bash
printf '%s\n' 'def UserService' 'members UserService' 'impl UserService' |
  bash /absolute/path/to/scalex/scripts/scalex-cli batch -w /repo
```

Batch lines are split on whitespace, without shell-style quote parsing. Run queries containing spaced arguments, such as test names, as separate commands. Set the workspace on the outer `batch` invocation. Batch output includes command separators; do not parse the entire stream as one JSON document. The index is shared for that batch, so start a new invocation after edits.

## Failures and diagrams

Usage errors exit 2; operational failures exit 1. JSON-mode failures use an error object on stdout; text diagnostics use stderr. Batch continues after failed queries and returns the highest failure status. Report a tool failure separately from an empty successful result.

Run `graph --render` or `graph --parse` only for an explicit diagram request. Read [graph-examples.md](references/graph-examples.md) for syntax. Do not add a graph step to ordinary navigation; `hierarchy`, `deps`, and `explain` already format their results.
