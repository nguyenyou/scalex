# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What is Scalex

Scalex is a Scala code intelligence CLI for AI agents. It provides fast symbol search, find definitions, and find references — without requiring an IDE, build server, or compilation. Designed as a Claude Code plugin.

## Workflow

- Before planning or implementing any feature, first add it to `docs/ROADMAP.md` under the appropriate section
- The roadmap is the source of truth for what's planned and what's done

## Build & Run

```bash
# Run via scala-cli (development)
scala-cli run scalex.scala -- <command> [args...]

# Run tests
scala-cli test scalex.scala scalex.test.scala

# Build GraalVM native image (requires GraalVM + scala-cli)
./build-native.sh
# Output: ./scalex (26MB standalone binary)

# Validate Claude Code plugin structure
claude plugin validate plugin/
```

## Architecture

Single-file implementation at `scalex.scala` (~800 lines, Scala 3.8.2, JDK 21+).

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

### Key design choices

- **Scalameta, not presentation compiler**: Scala 3's PC requires compiled `.class`/`.tasty` on classpath, which reintroduces build server dependency. Scalameta parses source directly.
- **Git OIDs for caching**: Available free from `git ls-files --stage`, no disk reads needed to detect changes.
- **No build server**: AI agents can run `./mill __.compile` directly for error checking.
- **Feature gate question**: "Is this better than grep, or does it introduce a worst case that grep never has?" If a feature risks being slower or less reliable than grep in any scenario, don't add it. The agent can always fall back to grep — scalex must never be the worse option.

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

The bootstrap script `scalex-cli` contains `EXPECTED_VERSION` that must be bumped alongside `ScalexVersion` in `scalex.scala` when releasing.

## Release workflow

### Step 1: Changelog PR (merge first)
1. Move `[Unreleased]` section in `CHANGELOG.md` to the new version with date
2. Create PR, get it merged to main

### Step 2: Version bump + tag
3. Bump `ScalexVersion` in `scalex.scala`
4. Bump `EXPECTED_VERSION` in `plugin/skills/scalex/scripts/scalex-cli`
5. Bump `version` in `.claude-plugin/marketplace.json` (plugin version is only managed here, not in `plugin/.claude-plugin/plugin.json`)
6. Commit, push to main
7. Tag as `vX.Y.Z` and push — GitHub Actions builds native binaries + creates release (done)

## Gotchas

- **Guava group ID**: `com.google.guava:guava`, NOT `com.google.common:guava`
- **GraalVM native image**: Guava needs `--initialize-at-run-time=com.google.common.hash.Striped64,com.google.common.hash.LongAdder,com.google.common.hash.BloomFilter,com.google.common.hash.BloomFilterStrategies` (see `build-native.sh`)
- **No `.par` in Scala 3.8**: Use `list.asJava.parallelStream()` instead of `list.par`
- **Scalameta Tree**: `.collect` doesn't work on Tree in Scala 3 — use manual `traverse` + `visit` pattern
- **Anonymous givens**: Only named givens are indexed; anonymous givens are skipped
- **`refs`/`imports` use text search**: They use bloom filters to shortlist candidate files, then do word-boundary text matching. They are NOT index-based and have a 20-second timeout.
- **Scala 3 indentation in `WorkspaceIndex`**: Deeply nested code can break method boundaries — use brace syntax for nested blocks
- **Test fixture file counts**: Tests hardcode file counts — adding/removing fixtures requires updating all count assertions
