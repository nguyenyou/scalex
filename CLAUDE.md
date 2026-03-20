# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What is Scalex

Scalex is a Scala code intelligence CLI for coding agents. It provides fast symbol search, find definitions, and find references — without requiring an IDE, build server, or compilation. Designed as a Claude Code plugin.

## IMPORTANT: No company references

NEVER mention any company names, internal project names, proprietary codebases, or organization-specific details in any output — including commit messages, PR descriptions, changelogs, roadmaps, documentation, code comments, or conversations. Always use generic examples (e.g. `HttpMessageService`, `UserServiceLive`) instead.

## Search scope

Source code lives in `src/` (production) and `tests/` (test suite). When searching for Scala code, scope searches to these directories. Avoid searching repo-wide — `benchmark/` contains ~17.7k Scala files from the scala3 compiler clone that will pollute results.

## Workflow

- Before planning or implementing any feature, first add it to `docs/ROADMAP.md` under the appropriate section
- The roadmap is the source of truth for what's planned and what's done

## Build & Run

```bash
# Run via scala-cli (development)
scala-cli run src/ -- <command> [args...]

# Run tests
scala-cli test src/ tests/

# Build GraalVM native image (requires GraalVM + scala-cli)
./build-native.sh
# Output: ./scalex (26MB standalone binary)

# Validate Claude Code plugin structure
claude plugin validate plugin/
```

## Architecture

Source code is in `src/`, tests in `tests/` (Scala 3.8.2, JDK 21+). When searching the codebase, scope to these directories to avoid hitting benchmark data or build artifacts.

```
src/                           # Production source code
├── project.scala              # scala-cli directives only
├── model.scala                # Data types, enums, version constant
├── extraction.scala           # AST parsing & single-file extraction functions
├── index.scala                # Git integration, persistence, WorkspaceIndex, filtering
├── analysis.scala             # Cross-index analysis (hierarchy, overrides, deps, diff, ast-pattern)
├── format.scala               # JSON + text formatters for symbols and references
├── cli.scala                  # Arg parsing, workspace resolution, @main entry point
├── command-helpers.scala      # Shared filters: filterSymbols, filterRefs, mkNotFoundWithSuggestions
├── dispatch.scala             # Command map + runCommand
└── commands/                  # One file per command (ls to discover all 25 commands)
    ├── definition.scala       # cmdDef
    ├── search.scala           # cmdSearch
    ├── refs.scala             # cmdRefs
    ├── hierarchy.scala        # cmdHierarchy
    ├── overview.scala         # cmdOverview
    └── ...                    # 25 commands total

tests/                         # Test suite
├── test-base.test.scala       # Shared test fixture (workspace setup)
├── extraction.test.scala      # Extraction tests
├── index.test.scala           # Index/search/persistence tests
├── analysis.test.scala        # Analysis tests (hierarchy, overrides, deps, etc.)
└── cli.test.scala             # CLI/formatting/command output tests

benchmark/                     # Benchmark data (gitignored)
├── scala3/                    # Shallow clone of scala/scala3 for benchmarks
└── results/                   # Hyperfine JSON exports
```

### Pipeline

```
git ls-files --stage → Scalameta parse → in-memory index → query
                              ↓
                    .scalex/index.bin (binary cache, OID-keyed, bloom filters)
```

1. **Git discovery**: `git ls-files --stage` returns all tracked `.scala` files with their OIDs
2. **Symbol extraction**: Scalameta parses source ASTs (Scala 3 first, falls back to Scala 2.13), extracts top-level symbols (class/trait/object/def/val/type/enum/given/extension)
3. **OID caching**: On subsequent runs, compares OIDs — skips unchanged files entirely
4. **Persistence**: Binary format with string interning at `.scalex/index.bin`
5. **Bloom filters**: Per-file bloom filter of identifiers — `refs` and `imports` only read candidate files

### Code style

- **Named tuples**: Never use unnamed tuples. Whenever a tuple is needed — return types, local variables, collection elements — always use named tuples. E.g. `(results: List[Reference], timedOut: Boolean)` not `(List[Reference], Boolean)`.

### Key design choices

- **Scalameta, not presentation compiler**: Scala 3's PC requires compiled `.class`/`.tasty` on classpath, which reintroduces build server dependency. Scalameta parses source directly.
- **Git OIDs for caching**: Available free from `git ls-files --stage`, no disk reads needed to detect changes.
- **No build server**: Coding agents can run `./mill __.compile` directly for error checking.
- **No backwards compatibility**: This is a tool, not a library. Do the right thing and do it consistently. If the current way of printing data, formatting JSON, or structuring output is inconsistent, fix it to match the up-to-date pattern — don't preserve old behavior for backwards compatibility. Consistency across commands matters more than not breaking hypothetical consumers.
- **Feature gate question**: "Is this better than grep, or does it introduce a worst case that grep never has?" If a feature risks being slower or less reliable than grep in any scenario, don't add it. The agent can always fall back to grep — scalex must never be the worse option.
- **Performance budget**: Every new feature must be benchmarked before/after on a large codebase (e.g. scala3 compiler, 17.7k files). Measure: index size (`.scalex/index.bin`), cold index time, warm index time, and query latency. Accept <5% regression on index times, 0% index size growth for non-index features, <10% if index schema changes. Prefer on-the-fly source reads over index bloat for infrequent queries (e.g. `members`, `doc`).

### Dependencies

- `org.scalameta::scalameta:4.15.2` — AST parsing
- `com.google.guava:guava:33.5.0-jre` — bloom filters
- `org.scalameta::munit:1.2.4` — test framework (test only)

## Plugin structure

```
plugin/
├── .claude-plugin/plugin.json    # Manifest
└── skills/scalex/
    ├── SKILL.md                  # Teaches Claude when/how to use scalex
    └── scripts/
        └── scalex-cli            # Bootstrap: downloads + caches binary, forwards args
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
5. Bump `EXPECTED_VERSION` in `plugin/skills/scalex/scripts/scalex-cli`
6. Update `CHECKSUM_scalex_*` values in `scalex-cli` — get hashes from individual `.sha256` release assets (iterate: `gh release view vX.Y.Z --json assets --jq '.assets[] | select(.name | endswith(".sha256")) | .name'` then download each with `gh release download vX.Y.Z -p <name> -O -`)
7. Bump `version` in `.claude-plugin/marketplace.json` (plugin version is only managed here, not in `plugin/.claude-plugin/plugin.json`)
8. Commit, push to main

Note: `marketplace.json` is at the repo root (`.claude-plugin/marketplace.json`), NOT inside `plugin/`.

## Feature checklist

When adding or changing commands/flags in `src/cli.scala`:
- Update help text in the `main` function
- Update `plugin/skills/scalex/SKILL.md` (commands, options table, common workflows, description frontmatter) and `plugin/skills/scalex/references/commands.md` (command signature, description, examples, options table). Description must be double-quoted YAML and under 1024 chars (for GitHub Copilot CLI compatibility). **Always run `./scripts/check-skill-frontmatter.sh` after editing SKILL.md** to validate
- Update `docs/ROADMAP.md`
- Update `CHANGELOG.md`
- Update `README.md` (commands block, Coding-Agent-Friendly Features, "Use it" examples). README does NOT duplicate the options table — it links to SKILL.md
- Update `site/index.html` (command grid, command count heading)

## Gotchas

- **Protected main branch**: Cannot push directly to main — all changes require a PR
- **Zero warnings policy**: Before creating a PR, run `scala-cli compile src/ 2>&1 | grep -i warn` and verify no output. Do not ship compiler warnings.
- **Zero deprecations policy**: Also verify with `scala-cli compile --scalac-option "-deprecation" src/ 2>&1 | grep -i warn`. Do not use deprecated APIs — find and use the replacement immediately.

- **Guava group ID**: `com.google.guava:guava`, NOT `com.google.common:guava`
- **GraalVM native image**: Guava needs `--initialize-at-run-time=com.google.common.hash.Striped64,com.google.common.hash.LongAdder,com.google.common.hash.BloomFilter,com.google.common.hash.BloomFilterStrategies` (see `build-native.sh`)
- **No `.par` in Scala 3.8**: Use `list.asJava.parallelStream()` instead of `list.par`
- **No non-local `return` in Scala 3.8**: `return` inside lambdas/closures is deprecated. Use `scala.util.boundary` + `boundary.break` instead. This includes `return` inside `.foreach`, `.map`, `try`/`catch` blocks inside lambdas, etc.
- **Scalameta `Pkg.children` wraps stats in `PkgBody`**: Use `pkg.stats` to access direct children (Import, Defn.Class, etc.), not `pkg.children` which nests them inside a `PkgBody` wrapper node.
- **Scalameta Tree**: `.collect` doesn't work on Tree in Scala 3 — use manual `traverse` + `visit` pattern
- **Anonymous givens**: Only named givens are indexed; anonymous givens are skipped
- **`refs`/`imports` use text search**: They use bloom filters to shortlist candidate files, then do word-boundary text matching. They are NOT index-based and have a 20-second timeout.
- **Scala 3 indentation in `WorkspaceIndex`**: Deeply nested code can break method boundaries — use brace syntax for nested blocks
- **Test fixture file counts**: Tests hardcode file counts — adding/removing fixtures requires updating all count assertions
- **GitHub Actions SHA pinning**: All actions in `release.yml` are pinned to commit SHAs. To verify/update: `git ls-remote --refs https://github.com/<owner>/<repo>.git refs/tags/<tag>`. Never trust SHAs from memory or LLM output without verifying.
