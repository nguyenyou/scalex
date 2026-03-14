# How Scalex Works

Scalex is a ~800-line Scala 3 program that gives AI agents instant code navigation across Scala projects. No compiler, no build server, no IDE — just git and a parser.

This document explains every layer of the system, from the ground up.

---

## The Big Picture

Every command follows the same flow:

```
User runs: scalex search MyService

  1. git ls-files --stage          → list all .scala files + their SHA-1 hashes (OIDs)
  2. Load .scalex/index.bin        → check if we have a cached index
  3. Compare OIDs                  → skip files that haven't changed
  4. Scalameta parse changed files → extract symbols, parents, signatures, imports, bloom filters
  5. Build in-memory index         → symbolsByName, parentIndex, filesByPath, packages
  6. Answer the query              → return matching symbols
  7. Save .scalex/index.bin        → persist for next run
```

Steps 2-3 make the second run fast. If nothing changed, no files are parsed — the entire index is loaded from the binary cache.

---

## Layer 1: Git File Discovery

We shell out to `git ls-files --stage` which outputs every tracked file with its Git object ID:

```
100644 a1b2c3d4e5f6... 0	src/main/scala/com/example/UserService.scala
```

We parse each line, extract the OID and path, and filter to `.scala` files only.

Why git, not filesystem walk:
- `git ls-files` is **fast** — ~200ms for 100k files, git already has the list in its index
- OIDs come **free** — no extra disk reads to compute content hashes
- Respects `.gitignore` — skips build output, generated files
- Deterministic — same content always produces the same OID

```scala
case class GitFile(path: Path, oid: String)
```

---

## Layer 2: Symbol Extraction

For each `.scala` file, we use [Scalameta](https://scalameta.org/) to parse the source into an AST using the **Scala 3 dialect**, then walk the tree to extract symbol definitions:

| AST Node | Symbol Kind | Example |
|---|---|---|
| `Defn.Class` | Class | `class UserService` |
| `Defn.Trait` | Trait | `trait Serializable` |
| `Defn.Object` | Object | `object Main` |
| `Defn.Def` | Def | `def apply(...)` |
| `Defn.Val` | Val | `val defaultTimeout` |
| `Defn.Type` | Type | `type UserId = String` |
| `Defn.Enum` | Enum | `enum Color` |
| `Defn.Given` / `Defn.GivenAlias` | Given | `given userOrdering: Ordering[User]` |
| `Defn.ExtensionGroup` | Extension | `extension (s: String)` |

For each symbol we record:

```scala
case class SymbolInfo(
    name: String,           // e.g., "UserService"
    kind: SymbolKind,       // e.g., Trait
    file: Path,             // absolute path
    line: Int,              // 1-based line number
    packageName: String,    // e.g., "com.example"
    parents: List[String],  // extends/with — e.g., List("UserService", "Serializable")
    signature: String       // e.g., "class UserServiceLive extends UserService"
)
```

The `parents` field is extracted from `extends`/`with` clauses in the AST — this powers the `impl` command. The `signature` field captures the full declaration — this powers the `--verbose` flag.

We also extract all `import` statements per file — this powers the `imports` command.

### AST traversal

```scala
given scala.meta.Dialect = scala.meta.dialects.Scala3

def traverse(t: Tree): Unit =
  visit(t)                         // check if this node is a symbol definition
  t.children.foreach(traverse)     // recurse into children
```

The `Scala3` dialect is critical — without it, Scalameta can't parse `enum`, `given`, or `extension` syntax.

### Why Scalameta, not the Scala compiler?

Scalameta is a **parser**, not a compiler. It reads source text and produces an AST — no type checking, no classpath, no compilation. This means:
- Works on **any Scala file**, even if the project doesn't compile
- Needs **zero configuration**
- 10-100x faster than compilation
- Trade-off: no type information (but we don't need it for navigation)

---

## Layer 3: Bloom Filters

When you run `scalex refs Decoder`, we need to find every file that mentions `Decoder`. Reading all 14k files from disk is slow.

During indexing, we build a **Guava bloom filter** for each file — a probabilistic set that answers "does this file contain this identifier?"

- **No** → the file **definitely** does not contain it (skip it)
- **Yes** → it **probably** does (1% false positive rate — read the file to confirm)

```scala
val bloom = BloomFilter.create(Funnels.unencodedCharsFunnel(), 500, 0.01)
// Scan source text, put every identifier ≥ 2 chars into the filter
```

The payoff for a `refs` query on 14k files:

```
Without bloom filter: read 13,958 files from disk
With bloom filter:    read ~100 files (only candidates)
```

---

## Layer 4: Binary Persistence

The index is stored at `.scalex/index.bin` in a custom binary format:

```
┌─────────────────────────────────────┐
│ Header                              │
│   Magic number: 0x53584458 ("SXDX") │
│   Version: 3 (byte)                 │
├─────────────────────────────────────┤
│ String Table                        │
│   Count: int                        │
│   Strings: UTF string × count       │
├─────────────────────────────────────┤
│ Files (repeated)                    │
│   Path: int (string table index)    │
│   OID: int (string table index)     │
│   Symbol count: short               │
│   Symbols (repeated):               │
│     Name: int (string table index)  │
│     Kind: byte (0-10)               │
│     Line: int                       │
│     Package: int (string tbl index) │
│     Signature: int (string tbl idx) │
│     Parent count: short             │
│     Parents: int[] (string tbl idx) │
│   Import count: short               │
│   Imports: int[] (string tbl idx)   │
│   Bloom filter:                     │
│     Length: int                     │
│     Data: byte[]                    │
└─────────────────────────────────────┘
```

**String interning** — many symbols share the same name (`apply`, `toString`...) and the same package. We collect all unique strings into a table and reference them by 4-byte integer IDs instead of repeating the strings.

**Versioning** — the magic number and version byte detect stale caches. If the format changes, old caches are automatically rebuilt.

---

## Layer 5: OID-Based Cache Invalidation

```
1. git ls-files --stage       → current (path, oid) pairs
2. Load .scalex/index.bin     → cached (path, oid, symbols, bloom, imports) entries
3. For each current file:
   - OID matches cache → reuse cached entry (skip parsing)
   - OID changed or new file → parse, build bloom filter
   - File deleted → drop from cache
4. Save updated index
```

`git ls-files --stage` gives us OIDs without reading file contents. If the OID hasn't changed, the file content is identical — guaranteed by SHA-1.

Real-world performance on a 14k-file monorepo:

| Scenario | Time |
|---|---|
| First run (parse all files) | 3.3s |
| No changes (all cached) | 364ms |
| 1 file changed | ~400ms |

---

## Layer 6: Query Engine

### `search` — fuzzy symbol search

Compares query (case-insensitive) against all symbol names, ranked in three tiers: exact → prefix → substring. Optional `--kind` filter and `--limit`.

### `def` — find definition

O(1) hash map lookup in `symbolsByName`. Returns all symbols with matching name, including `given` instances that grep would miss. With `--verbose`, shows the full signature inline.

### `impl` — find implementations

Looks up the trait/class name in `parentIndex` — a reverse map built during indexing from the `parents` field of each symbol. Returns all classes/objects/enums that `extends` or `with` the given trait.

### `refs` — find references

1. Bloom filter pre-screen — check each file's filter
2. Read candidate files from disk (parallel)
3. Word-boundary match on each line
4. **20s timeout** — stops if the deadline is exceeded, returns partial results
5. Optional `--categorize` groups results into: Definition, ExtendedBy, ImportedBy, UsedAsType, Usage, Comment

The word-boundary check:

```scala
private def containsWord(line: String, word: String): Boolean =
  // Finds "Foo" in "new Foo()" but NOT in "FooBar"
  // Checks that characters before/after are not alphanumeric
```

### `imports` — import graph

Same as `refs` but only matches lines starting with `import`. Also time-boxed at 20s.

### `symbols` — file symbols

Looks up the file path in `filesByPath` map.

### `packages` — list packages

Sorted set of all `packageName` values.

### `batch` — multiple queries, one index load

Reads commands from stdin, one per line. Loads the index once and answers all queries. Eliminates the ~1s index-load overhead per command.

### Fallback hints

When a command returns no results, scalex prints:
- How many files are indexed
- Whether any files had parse errors
- A suggestion to use Grep/Glob/Read as fallback

This prevents the AI agent from assuming a symbol doesn't exist just because scalex missed it.

---

## Layer 7: Parallelism

Two operations run in parallel via Java's `parallelStream()`:

1. **Symbol extraction** during indexing — parsing thousands of files
2. **Reference search** — reading hundreds of candidate files from disk

```scala
gitFiles.asJava.parallelStream().forEach { gf =>
  val (syms, bloom, imports) = extractSymbols(gf.path)
  queue.add(IndexedFile(rel, gf.oid, syms, bloom, imports))
}
```

Results are collected in a `ConcurrentLinkedQueue` (thread-safe, lock-free). We use Java parallel streams instead of Scala parallel collections because `scala-parallel-collections` is not available for Scala 3.8.

---

## Layer 8: Native Image

We compile to a GraalVM native image — a **26MB standalone binary**, no JVM needed.

| | JVM | Native Image |
|---|---|---|
| Startup | ~500ms | ~10ms |
| Cold index (14k files) | 5.1s | 3.3s |
| Warm index (14k files) | 672ms | 364ms |
| Size | needs JVM (~200MB) | 26MB |

Build:

```bash
scala-cli package --native-image scalex.scala \
  -o scalex --force -- --no-fallback \
  --initialize-at-run-time=com.google.common.hash.Striped64,...
```

The `--initialize-at-run-time` flags are needed because Guava uses `java.util.Random` during class initialization, which GraalVM doesn't allow at build time.

---

## Layer 9: Claude Code Integration

Scalex ships as a **Claude Code plugin**:

```
plugin/
├── .claude-plugin/plugin.json    # Plugin metadata
├── skills/scalex/SKILL.md        # Teaches Claude when/how to use scalex
└── scripts/scalex                # Launcher (PATH lookup → scala-cli fallback)
```

The **SKILL.md** is the key — it tells Claude Code:
- **When** to use scalex (navigating Scala code, finding definitions, understanding impact)
- **How** to call each command (exact bash commands with examples)
- **What** the output looks like (so Claude can parse results)
- **What to do** if scalex is not installed (download the right binary for the platform)

The launcher script checks if `scalex` is on PATH first, then falls back to `scala-cli` if the binary isn't installed.

---

## What Scalex Does NOT Do

| Feature | Why not |
|---|---|
| **Type checking / diagnostics** | Requires the Scala compiler + classpath. The AI agent runs `./mill __.compile` directly. |
| **Completions** | Requires the presentation compiler. AI agents complete code themselves. |
| **Hover / type info** | Requires compiled artifacts. The AI reads source to understand types. |
| **Java files** | Scope limited to Scala 3. |
| **Precise references** | We use text matching, not SemanticDB. May match an unrelated `Foo` in a different scope. Acceptable trade-off for zero-compilation speed. |

---

## The Numbers

| Project | Files | Symbols | Cold Index | Warm Index |
|---|---|---|---|---|
| circe-sanely-auto | 92 | 259 | ~50ms | ~10ms |
| mill | 1,415 | 12,778 | 214ms | 50ms |
| large monorepo | 13,958 | 214,803 | 3.3s | 364ms |

Dependencies: **2 libraries** (Scalameta for parsing, Guava for bloom filters).

Total implementation: **~800 lines** of Scala 3 in a single file.
