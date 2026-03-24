# Changelog

## [Unreleased]

## [0.8.0] — 2026-03-24

### Changed
- Renamed CLI tool from `sdbx` to `sdbex` — binary, bootstrap script, version constant, wire protocol markers (`SDBEX_OK`/`SDBEX_ERR`), socket path prefix, all documentation and references updated. The module directory (`scalex-semanticdb/`) and plugin name are unchanged.

## [0.7.0] — 2026-03-24

### Changed
- Daemon now returns text output instead of JSON — identical output whether daemon is running or not
- Wire protocol changed from JSON envelope to `SDBEX_OK`/`SDBEX_ERR` text protocol
- Ready signal changed from JSON to human-readable text
- Daemon errors (`SDBEX_ERR`) now cause CLI to exit with code 1

### Removed
- `--json` flag — all output is now text-only
- `renderJson`, `symbolToJson`, `jsonStr` functions
- `jsonOutput` field from `SemCommandContext` and `SemParsedFlags`

## [0.6.0] — 2026-03-24

### Added
- Unix domain socket daemon — `daemon` now listens on a socket at `/tmp/sdbex-<hash>.sock` instead of stdin/stdout. Any process can connect, send a JSON-line query, read a response, and disconnect. Designed for coding agent environments (like Claude Code) where each shell invocation is independent. (#323)
- Transparent daemon forwarding — non-daemon CLI commands auto-detect a running socket daemon and forward queries transparently (<10ms). Falls back to local index loading if no daemon is available. (#323)
- Socket file permissions locked to `rwx------` (owner-only) for security (#323)
- Stale socket detection — daemon cleans up leftover socket files from crashed daemons (#323)
- Fail-fast socket check — if a daemon is already running, new daemon exits immediately before building the index (#323)
- JSON escaping in daemon request forwarding — backslashes, newlines, control chars properly escaped (#323)

### Removed
- `--fifo` flag (superseded by socket daemon) (#323)
- `--parent-pid` flag (idle timeout and max lifetime are sufficient orphan guards) (#323)
- `--socket` flag (daemon is socket-only now, no flag needed) (#323)
- Stdin mode for daemon (socket is the only mode) (#323)

### Changed
- Termination contract reduced from 8 to 7 layers (removed stdin EOF and parent PID monitoring, both unnecessary for socket-only daemon) (#323)
- `limit` and `depth` serialized as JSON numbers (not strings) in daemon forwarding (#323)

## [0.5.0] — 2026-03-24

### Added
- `daemon --fifo <path>` flag — read requests from a named pipe instead of stdin, for non-interactive shells where backgrounding with `&` closes stdin (#317)

## [0.4.0] — 2026-03-24

### Changed
- Renamed CLI tool from `scalex-sdb` to `sdbex` — binary, bootstrap script, version constant, all documentation and references updated. The module directory (`scalex-semanticdb/`) and plugin name are unchanged.
- Added `scalex-semanticdb/CLAUDE.md` documenting the release workflow and feature checklist

## [0.3.0] — 2026-03-24

### Added
- `daemon` command — stdin/stdout JSON-lines server that keeps the index hot in memory. Queries drop from ~3.2s to <10ms. Self-terminates aggressively via 8 defensive layers: stdin EOF, parent PID monitoring, idle timeout (default 5 min), max lifetime (default 30 min), shutdown command, per-query timeout (30s), heap pressure detection, SIGTERM/SIGINT.
- `path <source> <target>` command — BFS shortest call path between two symbols (#297)
- `explain <symbol>` command — one-shot summary: type, callers, callees, parents, members (#297)
- `callers --depth N` — transitive caller tree with cycle detection (#297)
- Disambiguation hints — when multiple symbols match, prints candidates to stderr (#297)
- Auto-staleness detection — query commands detect stale cache by comparing directory mtimes (#298)
- Incremental indexing — only re-converts `.semanticdb` files whose MD5 changed (#298)
- `members` now hides compiler-generated case class synthetics by default (`_N`, `copy`, `copy$default$N`, `productElement`, `productPrefix`, `apply`, `unapply`, etc.). Use `--verbose` to show all. User-overridden methods (`toString`, `equals`, `hashCode`) are never hidden. (#307)
- `--smart` flag now works on `members` (filters synthetics, infrastructure noise, accessors) and `lookup` (excludes generated sources). Previously only worked on callers/callees/flow/explain. (#307)
- `--source-only` flag — hard-exclude generated/compiled sources from `lookup` results (#307)
- `explain` now includes subtypes section for traits and abstract classes — shows first 3 subtype names + total count. Respects `--smart`, `--exclude-test`, `--exclude-pkg`. (#307)
- `--in <scope>` flag — scope symbol resolution to a containing class, file, or package without requiring full FQN. Works with all single-symbol commands (#303)
- `--exclude-test` flag — filter out symbols from test source directories (paths containing `/test/`, `/tests/`, `/it/`, `/spec/`, or filenames ending in `Test.scala`, `Spec.scala`, `Suite.scala`, `Integ.scala`). Works with callers, callees, flow, path, explain (#303)
- `--exclude-pkg "p1,p2,..."` flag — exclude symbols whose FQN starts with any of the given package prefixes (dots auto-converted to `/`). Works with callers, callees, flow, path, explain (#303)
- `--smart` now filters unambiguous effect-system combinators (flatMap, traverse, pure, succeed, attempt, etc.) from callees/flow/callers output. Common names like map, filter, fold are not filtered to avoid hiding domain methods (#303)
- `lookup` multi-match output now shows `[class/trait]` or `[object]` annotation to distinguish member ownership (#303)
- FQN resolution fallback — when exact FQN match fails, tries swapping `#` ↔ `.` separator before falling back to suffix/name match, with a stderr hint (#303)

### Changed
- Discovery now Mill-only: parallel `semanticDbDataDetailed.dest/data/` walk, skip `classes/`, removed sbt/Bloop/generic fallback. Discovery ~44% faster on large projects.
- Removed `--semanticdb-path` flag — Mill's `out/` is the only supported layout. Other build tools will be supported later.
- `depth` parameter changed from `Int = 3` to `Option[Int] = None` — each command supplies its own default
- `callers` flat mode now respects `--smart` and `--no-accessors` flags
- `rebuild` now uses incremental MD5 comparison when a cached index exists

### Fixed
- `batch` now handles quoted FQN arguments — `batch 'callers "com/example/Foo#bar()."'` no longer fails with "not found" (#303)

## [0.2.0] — 2026-03-23

### Added
- `batch` command — run multiple queries in one invocation (#284)
- `--no-accessors` flag for `flow` and `callees` (#284)
- `--exclude "p1,p2,..."` flag for `flow`, `callees`, and `callers` (#284)
- `--smart` flag for `flow` and `callees` — auto-filters infrastructure noise (#284)

### Changed
- `--kind` now narrows symbol resolution in `flow`, `callees`, and `callers`
- `resolveSymbol` now prefers source-defined symbols over generated code

## [0.1.0] — 2026-03-22

Initial release of scalex-semanticdb.

### Added
- SemanticDB file discovery optimized for Mill (`out/`)
- Parallel protobuf parsing via `parallelStream()`
- Binary index persistence at `.scalex/semanticdb.bin` with string interning
- Deduplication of Mill cross-platform shared source copies
- 14 commands: flow, callers, callees, refs, type, related, occurrences, lookup, supertypes, subtypes, members, symbols, index, stats
- JSON output (`--json`), kind filter (`--kind`), role filter (`--role`), timing instrumentation (`--timings`)
- Assembly JAR build (JVM JIT is 11x faster than GraalVM native for protobuf workloads)
