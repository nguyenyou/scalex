# Changelog

## [1.9.0] — 2026-03-16

### Added
- `search --exact` flag — only return symbols with exact name match (#43)
- `search --prefix` flag — only return symbols whose name starts with the query (#43)
- `-c` short alias for `--categorize` on `refs` — saves tokens in agent workflows (#43)
- Condensed "not found" output in batch mode — single line instead of multi-line hints (#43)

## [1.8.0] — 2026-03-16

### Improved
- `grep` auto-corrects POSIX regex to Java regex — `\|` → `|`, `\(` → `(`, `\)` → `)` with a stderr note; JSON output includes `"corrected"` field (#39)

## [1.7.0] — 2026-03-15

### Added
- `grep -e PATTERN` flag — multi-pattern grep in one call; patterns combined with `|` (#35)
- `grep --count` flag — output match/file count without full results for quick triage (#35)
- Regex syntax hint — when grep returns zero results and pattern contains `\|`, `\(`, or `\)`, shows hint about Java regex syntax (#35)
- Document `grep` support in batch mode (#35)

## [1.6.0] — 2026-03-15

### Added
- `--json` flag on all commands — structured JSON output for programmatic consumption (#32)
- `scalex annotated <annotation>` — find symbols with a specific annotation (e.g. `@deprecated`, `@main`); supports `--kind`, `--path`, `--no-tests` filters (#32)
- `scalex grep <pattern>` — regex search inside `.scala` file contents with `--path`, `--no-tests` filtering and 20s timeout; supports `-C N` context lines (#32)
- Annotation extraction from Scalameta AST during indexing — stored in binary index (format bumped to v5)

## [1.5.0] — 2026-03-15

### Added
- `--kind` filter now works on `def` and `impl` commands — `scalex def Driver --kind class` filters by symbol kind (#29)
- `--no-tests` global flag — excludes test files (`test/`, `tests/`, `testing/`, `bench-*`, `*Spec.scala`, `*Test.scala`, `*Suite.scala`) from results; works on `def`, `search`, `impl`, `refs`, `imports` (#29)
- `--path PREFIX` filter — restricts results to files under a path prefix, e.g. `scalex def Driver --path compiler/src/`; works on all query commands (#29)
- `refs -C N` context lines — shows N lines before/after each reference with line numbers and `>` marker, like `grep -C` (#29)
- Smarter `def` ranking — results sorted by: class/trait/object/enum first, then type/given, then def/val/var; non-test before test; shorter paths first (#29)

## [1.4.0] — 2026-03-15

### Added
- `-w` / `--workspace` flag — named flag for setting workspace path, avoids ambiguity with positional args
- Path-as-symbol hint — when a symbol looks like a filesystem path, suggests correct arg order

### Fixed
- zsh compatibility — bash re-exec guard in `scalex-cli` bootstrap script fixes `(eval):1: permission denied:` when zsh eval's the script (#22)

## [1.3.0] — 2026-03-15

### Added
- `scalex file <query>` — fuzzy search file names with camelCase matching
- Fuzzy camelCase search — `search "hms"` matches `HttpMessageService`, `search "usl"` matches `UserServiceLive`
- `scalex index --verbose` now lists files that had parse errors
- Not-found hint directs users to `scalex index --verbose` to see failed files

## [1.2.0] — 2026-03-15

### Performance
- Lazy bloom filter deserialization — non-bloom commands (`def`, `search`, `impl`, `symbols`, `packages`) skip deserializing blooms, cutting ~45% off index load time
- Skip index save when nothing changed — warm index no longer writes 22MB to disk when all files hit OID cache
- Eliminate double file read — `extractSymbols` reads each file once instead of twice (bloom filter + symbol extraction)
- Single-pass post-index map building — symbol/file maps built in 2 passes instead of 7 separate passes over 200K+ symbols
- Pre-computed search deduplication — `distinctBy` computed once at index time instead of every `search` call
- Adaptive bloom filter capacity — `max(500, source.length / 15)` scales bloom size with file size, reducing false positives for large files

## [1.1.0] — 2026-03-14

### Added
- Confidence annotations for `refs` output (High/Medium/Low) based on import resolution
  - **High**: reference is in the same package or has an explicit import
  - **Medium**: reference has a wildcard import (`import pkg._`) matching the target's package
  - **Low**: no matching import found
- `refs --categorize` groups by confidence level, then by category
- `refs` (non-categorized) sorts by confidence with section headers
- Wildcard import resolution in `imports` command — `import com.example._` now surfaces when searching for symbols in `com.example`
- Import alias tracking — `import X as Y` (Scala 3) and `import {X => Y}` (Scala 2) are now detected and followed
  - `refs X` also finds usages of alias `Y` in files that rename the import
  - Alias imports are classified as High confidence
  - Aliases survive binary cache roundtrip (index format bumped to v4)
- Alias-aware confidence: `resolveConfidence` now checks alias mappings — searching by alias name (e.g. `refs TextAlignE`) returns High confidence instead of Low
- Alias annotation in output: references found via alias show `[via alias Y]` suffix (e.g. `AliasClient.scala:6 — val svc: US = ??? [via alias US]`)

## [1.0.0] — 2025-05-20

### Added
- Initial release
- Symbol search, find definitions, find references, find implementations
- Import graph (`imports` command)
- File symbols and package listing
- Batch mode for multiple queries
- OID-based binary caching with bloom filters
- Categorized references (`--categorize` flag)
- Scala 2 and Scala 3 dialect support
- GraalVM native image build
- Claude Code plugin structure
