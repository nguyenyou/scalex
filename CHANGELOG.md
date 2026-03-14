# Changelog

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
