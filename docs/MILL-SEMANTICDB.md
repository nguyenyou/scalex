# Mill SemanticDB Layout

How Mill produces `.semanticdb` files and how scalex-sdb discovers them.

## Generating SemanticDB files

```bash
./mill __.semanticDbData
```

This compiles every module with SemanticDB enabled. Mill handles Scala 2 and 3 differently:

| | Scala 3 | Scala 2 |
|---|---|---|
| **Flag** | `-Xsemanticdb` (built-in) | `-Yrangepos` + `-P:semanticdb:sourceroot:...` |
| **Plugin** | None | `semanticdb-scalac` (auto-resolved by Mill) |
| **Config** | Nothing extra | `def semanticDbVersion` must be set in `build.mill` |

Java modules use the `com.sourcegraph:semanticdb-javac` plugin automatically.

## `out/` directory layout

Each module produces a `semanticDbDataDetailed.dest` directory under `out/`:

```
out/
├── modules/
│   ├── core/
│   │   └── semanticDbDataDetailed.dest/
│   │       ├── data/
│   │       │   └── META-INF/
│   │       │       └── semanticdb/
│   │       │           └── modules/core/src/           ← mirrors source tree
│   │       │               ├── Main.scala.semanticdb
│   │       │               └── Config.scala.semanticdb
│   │       └── classes/
│   │           ├── META-INF/semanticdb/...             ← duplicate of data/
│   │           ├── Main.class
│   │           └── Main$.class
│   │
│   ├── billing/
│   │   ├── jvm/
│   │   │   └── semanticDbDataDetailed.dest/
│   │   │       └── data/META-INF/semanticdb/...
│   │   └── js/
│   │       └── semanticDbDataDetailed.dest/
│   │           └── data/META-INF/semanticdb/...
│   │
│   └── shared/
│       └── jvm/
│           └── jsSharedSources.dest/                   ← generated copy of shared sources
│               └── ...
```

### Key facts

- **One dest per module**: `out/<module-path>/semanticDbDataDetailed.dest/`
- **Two copies**: `.semanticdb` files appear in both `data/` and `classes/` — scalex-sdb only reads `data/` (skips `classes/` entirely)
- **Source-relative paths**: Files under `META-INF/semanticdb/` mirror the source tree relative to the workspace root
- **Persistent**: Mill marks the task `persistent = true` — SemanticDB files survive failed compilations
- **Incremental**: Only modified sources get new `.semanticdb` files. Mill cleans stale ones whose source was deleted

### Cross-platform modules

Mill's cross-platform modules (JVM/JS/Native) compile shared sources separately for each platform, producing copies:

```
out/modules/foo/js/jsSharedSources.dest/com/example/Shared.scala
out/modules/foo/jvm/jvmSharedSources.dest/com/example/Shared.scala
modules/foo/shared/src/com/example/Shared.scala        ← the real source
```

scalex-sdb detects these generated-source markers and keeps only the real source:
- `jsSharedSources.dest/`
- `jvmSharedSources.dest/`
- `nativeSharedSources.dest/`

## How scalex-sdb discovers files

Discovery uses a fast targeted search of `out/`:

```
1. Walk out/ (max depth 8) looking for directories named
   "semanticDbDataDetailed.dest"

2. Walk dest/data/META-INF/semanticdb/ in each — parallel across modules
   (classes/ is skipped entirely — no deduplication needed)

3. Collect all *.semanticdb files, track max mtime

4. Index-level deduplication removes cross-platform copies
   (jsSharedSources.dest, jvmSharedSources.dest, nativeSharedSources.dest)
```

**Performance**: ~2s for 15k files on the targeted Mill path. The targeted search avoids walking `classes/` directories full of `.class` files.

### Staleness detection

After the first discovery, scalex-sdb saves a manifest of discovered `semanticDbDataDetailed.dest` directories at `.scalex/semanticdb-dirs.txt`. On subsequent runs:

1. Check if any manifest directory has a newer mtime than the cache
2. If stale: re-discover and incrementally rebuild (only parse changed files)
3. If fresh: load from `.scalex/semanticdb.bin` cache (~1.5s)

This avoids the full directory walk on every invocation.

## What's inside a `.semanticdb` file

Each `.semanticdb` file is a protobuf `TextDocuments` message containing:

- **SymbolInformation**: Every symbol defined in the source file — class, method, field, type, etc. Includes fully-qualified name, kind, properties (abstract, sealed, etc.), resolved type signature, parent types, and annotations
- **SymbolOccurrence**: Every reference and definition site — exact position (line, column, end line, end column), the symbol's FQN, and role (DEFINITION or REFERENCE)

scalex-sdb parses these into `SemSymbol` and `SemOccurrence` records, builds reverse indexes (occurrencesBySymbol, memberIndex, subtypeIndex, definitionRanges), and persists to `.scalex/semanticdb.bin`.

## Forcing a clean rebuild

```bash
# Regenerate all SemanticDB data
./mill __.semanticDbData

# Force scalex-sdb to re-index (ignores cache)
scalex-sdb index -w /project
```

If `.semanticdb` files seem stale after refactoring, run both commands.
