# Development guide

Shared contributor instructions for humans and coding agents.

## What is Scalex

Scalex is a Scala code intelligence CLI for coding agents. It provides fast symbol search, find definitions, and find references — without requiring an IDE, build server, or compilation. Available as a shared Codex and Claude Code plugin.

## IMPORTANT: No company references

NEVER mention any company names, internal project names, proprietary codebases, or organization-specific details in any output — including commit messages, PR descriptions, changelogs, roadmaps, documentation, code comments, or conversations. Always use generic examples (e.g. `HttpMessageService`, `UserServiceLive`) instead.

## Search scope

Source code lives in `src/` (scalex production code), `clibase/src/` (CLI base module), `graph/src/` (asciiGraph module), and `tests/` (test suite). When searching for Scala code, scope searches to these directories. Avoid searching repo-wide — `benchmark/` contains ~17.7k Scala files from the scala3 compiler clone that will pollute results.

## Workflow

- Before planning or implementing any feature, first add it to `docs/ROADMAP.md` under the appropriate section
- The roadmap is the source of truth for what's planned and what's done
- **Bug fix workflow**: When receiving a bug report, always write a failing test that reproduces the bug *before* writing the fix. This validates the bug is real and ensures the fix is verifiable. Only then apply the code fix and confirm the test passes.

## Build & Run

Use the checked-in Mill launcher. Separate multiple top-level tasks with `+`.

```bash
# Run via Mill (development)
./mill run <command> [args...]

# Run tests
./mill test

# Run microbenchmarks
./mill bench.run <benchmark> <workspace> [options]

# Build native image (Mill downloads the pinned GraalVM)
./build-native.sh
# Output: ./scalex

# Compile all modules, including tests and benchmarks, and run tests
./mill test + bench.compile

# Validate shared skill frontmatter
./scripts/check-skill-frontmatter.sh

# Optional Claude Code plugin validation
claude plugin validate plugins/scalex/
```

## Architecture

The dependency versions live in `build.mill`; the launcher pins Mill in `mill`, and `.scalafmt.conf` pins the formatter. The application uses Scala 3 and JDK 24. GraalVM Community 24.0.2 is pinned on every platform because it is the newest release that supports macOS Intel.

`clibase` and `graph` are independent libraries. Neither depends on application code.

```text
src/                          package scalex
  model.scala                 Domain records and version
  command-context.scala       Grouped query, output, and command options
  command-result.scala        Typed results shared by handlers and renderers
  CommandRegistry.scala       Command handlers, help, and indexing requirements
  CommandHelpers.scala        Shared command filtering and suggestions
  flags.scala                 Flag declarations and context construction
  cli.scala                   Workspace resolution, batch loop, exit statuses
  dispatch.scala              Dispatch and whole-document output budgets
  analysis.scala              Cross-file hierarchy, dependency, and diff analysis
  index/                      Git, cache persistence, scans, immutable snapshots
  extraction/                 Scala/Java parsing and source extraction
  output/                     Text and JSON rendering by result family
  commands/                   One handler per command
clibase/src/                  Reusable flags, timing, budgets, batch plumbing
graph/src/                   Self-contained asciiGraph rendering and parsing
tests/                       Unit, regression, and subprocess tests
```

Packages in `src/index`, `src/extraction`, `src/output`, and `src/commands` use the matching `scalex.*` namespace. Shared implementation helpers use `private[scalex]` visibility. Keep command handlers concerned with queries and typed results; serialization belongs in `output`.

`WorkspaceIndex.load` returns a fully initialized snapshot. Refresh by loading another snapshot; do not mutate an existing index after its lookup tables have been built. `WorkspaceIndex.empty` supports commands that need no source files, such as graph rendering.

### Request and indexing flow

```text
CLI -> command metadata -> load snapshot -> handler -> typed result -> renderer
                              |
                        Git file discovery
                              |
                     cached OID unchanged? -- yes --> reuse record
                              | no
                        parse source AST
                              |
                     save .scalex/index.bin
```

Git discovers tracked Scala and Java files and their OIDs. Scalameta parses Scala source directly; JavaParser handles Java. The binary cache interns strings and stores per-file bloom filters so text queries can skip files that cannot contain their identifier.

Usage failures exit 2; operational failures exit 1. JSON failures are JSON objects on stdout; text diagnostics use stderr. Batch mode processes subsequent lines and returns the highest failure status. With `--max-output`, oversized JSON is replaced by a complete truncation object. That metadata and the text truncation notice are exempt from the result budget.

### Code style

- Use brace syntax for all Scala blocks; keep imports at the top of each file and use imported short names.
- Prefer small concrete functions and records over wrappers or single-use abstractions.
- Run the pinned formatter before finishing: `./mill mill.scalalib.scalafmt/reformatAll '__.sources'`. Check without changing files with `./mill mill.scalalib.scalafmt/checkFormatAll '__.sources'`. Keep ignored benchmark and build directories excluded in `.scalafmt.conf`.

- **Named tuples**: Never use unnamed tuples. Whenever a tuple is needed — return types, local variables, collection elements — always use named tuples. E.g. `(results: List[Reference], timedOut: Boolean)` not `(List[Reference], Boolean)`.
- **No `return` statements**: Never use `return` anywhere — not in methods, not in lambdas, not in `for`/`foreach`. Use `scala.util.boundary` + `boundary.break` for early exit, or restructure with `match`/`if-else`. The `return` keyword is deprecated in Scala 3 inside lambdas and is a footgun everywhere else. The codebase is `return`-free — keep it that way.

### Key design choices

- **Scalameta, not presentation compiler**: Scala 3's PC requires compiled `.class`/`.tasty` on classpath, which reintroduces build server dependency. Scalameta parses source directly.
- **Git OIDs for caching**: Available free from `git ls-files --stage`, no disk reads needed to detect changes.
- **No build server**: Coding agents can run `./mill __.compile` directly for error checking.
- **No backwards compatibility**: This is a tool, not a library. Do the right thing and do it consistently. If the current way of printing data, formatting JSON, or structuring output is inconsistent, fix it to match the up-to-date pattern — don't preserve old behavior for backwards compatibility. Consistency across commands matters more than not breaking hypothetical consumers.
- **Feature gate question**: "Is this better than grep, or does it introduce a worst case that grep never has?" If a feature risks being slower or less reliable than grep in any scenario, don't add it. The agent can always fall back to grep — scalex must never be the worse option.
- **Performance budget**: Every new feature must be benchmarked before/after on a large codebase (e.g. scala3 compiler, 17.7k files). Measure: index size (`.scalex/index.bin`), cold index time, warm index time, and query latency. Accept <5% regression on index times, 0% index size growth for non-index features, <10% if index schema changes. Prefer on-the-fly source reads over index bloat for infrequent queries (e.g. `members`, `doc`).

### Dependencies

Production dependencies are Scalameta, Guava, and JavaParser. Tests additionally use MUnit and ujson. Keep exact versions in `build.mill` rather than duplicating them in this guide.

### Native performance measurements

Use `hyperfine` for end-to-end native latency, including process startup. `--timings` reports exclusive phase durations (nested work is subtracted per thread), including `command` and `render`, plus the elapsed `request-total`. The request total includes flag parsing and otherwise uninstrumented work, but excludes runtime startup before the application entry point. Concurrent phases may overlap; do not add their durations to infer wall time. Each batch query gets its own request total after the initial shared index load. Measure cold indexes by removing only a backed-up `index.bin`, and restore the original cache afterward. Keep private workspace reports out of tracked files.

For repeated queries, use `batch` to share index loading and lookup tables. For large full rebuilds, test `./scalex -Xms1g -Xmx4g index /path/to/workspace` as an opt-in heap configuration. It can reduce native Serial GC pauses at the cost of higher resident memory. Compare both latency and peak RSS against defaults on the target machine; do not apply these settings globally to short queries or memory-constrained environments. The portable default heap and worker count remain unchanged.

## Plugin structure

```
plugins/
└── scalex/                        # scalex plugin
    ├── .claude-plugin/plugin.json # Claude Code manifest
    ├── .codex-plugin/plugin.json  # Codex CLI/desktop manifest
    └── skills/scalex/
        ├── SKILL.md
        ├── references/
        └── scripts/scalex-cli     # Bootstrap: downloads + caches binary, forwards args
```

The bootstrap script `scalex-cli` contains `EXPECTED_VERSION` that must be bumped alongside `ScalexVersion` in `src/model.scala` when releasing.

## Release workflow

### Step 1: Release PR (merge first)
1. Move `[Unreleased]` section in `CHANGELOG.md` to the new version with date
2. Bump `ScalexVersion` in `src/model.scala`
3. Create PR, get it merged to main

### Step 2: Tag + release
4. Tag as `vX.Y.Z` and push — GitHub Actions builds native binaries + creates release

### Step 3: Plugin version bump
5. Bump `EXPECTED_VERSION` in `plugins/scalex/skills/scalex/scripts/scalex-cli`
6. Update `CHECKSUM_scalex_*` values in `scalex-cli` — get hashes from individual `.sha256` release assets (iterate: `gh release view vX.Y.Z --json assets --jq '.assets[] | select(.name | endswith(".sha256")) | .name'` then download each with `gh release download vX.Y.Z -p <name> -O -`)
7. Bump `version` in `.claude-plugin/marketplace.json` and `plugins/scalex/.codex-plugin/plugin.json` alongside the bootstrap version
8. Commit, create PR, merge to main (main is protected — cannot push directly)

Marketplace catalogs: Claude Code uses `.claude-plugin/marketplace.json`; Codex uses `.agents/plugins/marketplace.json`. Both point to `plugins/scalex/` and share its skill and bootstrap. Codex's version is in its plugin manifest, not its marketplace catalog.

## Feature checklist

When adding or changing commands/flags:
- Flags: declare once in `src/flags.scala` (spellings, value shape, default, help text) and add to the `scalexFlags` registry — the parser and the help `Options:` section derive from it automatically. Wire the value into `CommandContext` via `flagsToContext` in the same file. Registry order = help display order.
- Commands: add the handler under `src/commands/` and register it once in `src/CommandRegistry.scala`. Help, dispatch, workspace defaults, and bloom/index requirements derive from that metadata.
- Keep detailed command signatures, examples, and options in `plugins/scalex/skills/scalex/references/commands.md`. Update `plugins/scalex/skills/scalex/SKILL.md` when query selection, interpretation, or invocation guidance changes; do not duplicate the command manual there. Description must be double-quoted YAML and under 1024 chars (for GitHub Copilot CLI compatibility). **Always run `./scripts/check-skill-frontmatter.sh` after editing SKILL.md** to validate
- Update `docs/ROADMAP.md`
- Update `CHANGELOG.md`
- Update `README.md` (commands block, Coding-Agent-Friendly Features, "Use it" examples). README does NOT duplicate the options table — it links to the command reference
- Update `site/index.html` (command grid, command count heading)

## Gotchas

- **Protected main branch**: Cannot push directly to main — all changes require a PR
- **Zero warnings and deprecations**: `build.mill` enables fatal compiler warnings, deprecation checks, and brace syntax. Run `./mill test + bench.compile`; do not filter compiler output through a command that hides the build exit status. PR CI runs these checks and shared skill validation.

- **Guava group ID**: `com.google.guava:guava`, NOT `com.google.common:guava`
- **GraalVM native image**: Guava needs `--initialize-at-run-time=com.google.common.hash.Striped64,com.google.common.hash.LongAdder,com.google.common.hash.BloomFilter,com.google.common.hash.BloomFilterStrategies` (see `build-native.sh`)
- **JavaParser native validation**: `resources/META-INF/native-image/scalex/reflect-config.json` retains the five AST fields read reflectively by the non-empty-list validator. The metadata test checks coverage against the installed JavaParser version; `bash scripts/check-native.sh <binary>` exercises those constructs in the compiled executable. PR CI and every release platform run this check.
- **No `.par` in Scala 3**: Use `list.asJava.parallelStream()` instead of `list.par`
- **No non-local `return` in Scala 3**: `return` inside lambdas/closures is deprecated. Use `scala.util.boundary` + `boundary.break` instead. This includes `return` inside `.foreach`, `.map`, `try`/`catch` blocks inside lambdas, etc.
- **Scalameta `Pkg.children` wraps stats in `PkgBody`**: Use `pkg.stats` to access direct children (Import, Defn.Class, etc.), not `pkg.children` which nests them inside a `PkgBody` wrapper node.
- **Scalameta Tree**: `.collect` doesn't work on Tree in Scala 3 — use manual `traverse` + `visit` pattern
- **Anonymous givens**: Only named givens are indexed; anonymous givens are skipped
- **`refs`/`imports` use text search**: They use bloom filters to shortlist candidate files, then do word-boundary text matching. They are NOT index-based and have a 20-second timeout.
- **Scala 3 indentation in `WorkspaceIndex`**: Deeply nested code can break method boundaries — use brace syntax for nested blocks
- **Test fixture file counts**: Tests hardcode file counts — adding/removing fixtures requires updating all count assertions
- **GitHub Actions SHA pinning**: All actions in `release.yml` are pinned to commit SHAs. To verify/update: `git ls-remote --refs https://github.com/<owner>/<repo>.git refs/tags/<tag>`. Never trust SHAs from memory or LLM output without verifying.
