# How Scalex Works

Scalex is a ~1,500-line Scala 3 program that gives AI agents instant code navigation across Scala projects. No compiler, no build server, no IDE — just git and a parser.

This document explains every layer of the system, from the ground up.

---

## The Big Picture

Every command follows the same flow:

```
User runs: scalex search MyService

  1. git ls-files --stage          → list all .scala files + their SHA-1 hashes (OIDs)
  2. Load .scalex/index.bin        → check if we have a cached index
  3. Compare OIDs                  → skip files that haven't changed
  4. Scalameta parse changed files → extract symbols, parents, signatures, annotations, imports, aliases, bloom filters
  5. Build in-memory index         → symbolsByName, parentIndex, annotationIndex, filesByPath, packages, packageToSymbols
  6. Answer the query              → return matching symbols
  7. Save .scalex/index.bin        → persist for next run
```

Steps 2-3 make the second run fast. If nothing changed, no files are parsed — the entire index is loaded from the binary cache.

Some commands (`members`, `doc`) bypass the index entirely — they parse source files on-the-fly for the specific symbol requested.

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

For each `.scala` file, we use [Scalameta](https://scalameta.org/) to parse the source into an AST. We try the **Scala 3 dialect** first, and if that fails, fall back to **Scala 2.13** — this handles mixed codebases automatically.

| AST Node | Symbol Kind | Example |
|---|---|---|
| `Defn.Class` | Class | `class UserService` |
| `Defn.Trait` | Trait | `trait Serializable` |
| `Defn.Object` | Object | `object Main` |
| `Defn.Def` | Def | `def apply(...)` |
| `Defn.Val` | Val | `val defaultTimeout` |
| `Defn.Var` | Var | `var count` |
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
    signature: String,      // e.g., "class UserServiceLive extends UserService"
    annotations: List[String] // e.g., List("deprecated", "main")
)
```

The `parents` field is extracted from `extends`/`with` clauses in the AST — this powers the `impl` command. The `signature` field captures the full declaration — this powers the `--verbose` flag. The `annotations` field extracts `@annotation` names from modifier lists — this powers the `annotated` command.

We also extract all `import` statements per file (powers the `imports` command) and **import aliases** like `import X as Y` or `import {X => Y}` (powers alias-aware reference tracking).

```scala
def extractSymbols(file: Path): (List[SymbolInfo], BloomFilter[CharSequence], List[String], Map[String, String])
//                                symbols          bloom filter               imports       aliases
```

### Dual-dialect fallback

```scala
def parseFile(path: Path): Option[Source] =
  // Try Scala 3 first
  try input.parse[Source].get
  catch _ =>
    // Fall back to Scala 2.13 (procedure syntax, implicit class, etc.)
    try input.parse[Source].get
    catch _ => None
```

The `Scala3` dialect is critical — without it, Scalameta can't parse `enum`, `given`, or `extension` syntax. The `Scala213` fallback handles older syntax like procedure-style `def foo {}` and `implicit class`.

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
val expected = math.max(500, source.length / 15)  // adaptive capacity
val bloom = BloomFilter.create(Funnels.unencodedCharsFunnel(), expected, 0.01)
// Scan source text, put every identifier ≥ 2 chars into the filter
```

The capacity scales with file size — larger files get larger filters to maintain the false positive rate. Small files default to 500 entries.

The payoff for a `refs` query on 14k files:

```
Without bloom filter: read 13,958 files from disk
With bloom filter:    read ~100 files (only candidates)
```

---

## Layer 4: Binary Persistence

The index is stored at `.scalex/index.bin` in a custom binary format (version 5):

```
┌─────────────────────────────────────┐
│ Header                              │
│   Magic number: 0x53584458 ("SXDX") │
│   Version: 5 (byte)                 │
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
│     Annotation count: short         │
│     Annotations: int[] (str tbl id) │
│   Import count: short               │
│   Imports: int[] (string tbl idx)   │
│   Alias count: short                │
│   Aliases (repeated):               │
│     Original: int (string tbl idx)  │
│     Alias: int (string tbl idx)     │
│   Bloom filter:                     │
│     Length: int                     │
│     Data: byte[]                    │
└─────────────────────────────────────┘
```

**String interning** — many symbols share the same name (`apply`, `toString`...) and the same package. We collect all unique strings into a table and reference them by 4-byte integer IDs instead of repeating the strings.

**Versioning** — the magic number and version byte detect stale caches. If the format changes, old caches are automatically rebuilt.

**Lazy bloom deserialization** — commands that don't need blooms (`def`, `search`, `impl`, `symbols`, `packages`, `annotated`, `members`, `doc`, `overview`) skip deserializing the bloom filter bytes, cutting ~45% off index load time.

---

## Layer 5: OID-Based Cache Invalidation

```
1. git ls-files --stage       → current (path, oid) pairs
2. Load .scalex/index.bin     → cached entries
3. For each current file:
   - OID matches cache → reuse cached entry (skip parsing)
   - OID changed or new file → parse, build bloom filter
   - File deleted → drop from cache
4. Save updated index (only if files were re-parsed)
```

`git ls-files --stage` gives us OIDs without reading file contents. If the OID hasn't changed, the file content is identical — guaranteed by SHA-1.

The save is skipped entirely when `parsedCount == 0` — avoids rewriting 22-28MB to disk on every warm run.

Real-world performance on a 14k-file monorepo:

| Scenario | Time |
|---|---|
| First run (parse all files) | 3.3s |
| No changes (all cached) | 364ms |
| 1 file changed | ~400ms |

---

## Layer 6: In-Memory Index

After parsing, we build several maps in a single pass over all symbols:

```scala
class WorkspaceIndex:
  var symbolsByName: Map[String, List[SymbolInfo]]      // O(1) def lookup
  var parentIndex: Map[String, List[SymbolInfo]]         // trait → [implementing classes]
  var annotationIndex: Map[String, List[SymbolInfo]]     // annotation → [annotated symbols]
  var filesByPath: Map[Path, List[SymbolInfo]]            // file → [symbols in that file]
  var packages: Set[String]                               // all package names
  var packageToSymbols: Map[String, Set[String]]          // package → symbol names
  var distinctSymbols: List[SymbolInfo]                    // deduplicated for search
  var aliasIndex: Map[String, List[(IndexedFile, String)]] // import alias tracking
  var indexedByPath: Map[String, IndexedFile]              // path → IndexedFile (for confidence)
```

All maps are built in **two passes** — one over symbols, one over files — instead of separate passes for each map.

---

## Layer 7: Query Engine

### `search` — fuzzy symbol search

Compares query (case-insensitive) against all symbol names, ranked in four tiers: exact → prefix → substring → camelCase fuzzy. The fuzzy tier matches camelCase initials — `"hms"` matches `HttpMessageService`.

Flags: `--kind` filter, `--limit N`, `--exact` (only exact name matches), `--prefix` (only exact + prefix), `--definitions-only` (only class/trait/object/enum).

### `def` — find definition

O(1) hash map lookup in `symbolsByName`. Returns all symbols with matching name, including `given` instances that grep would miss. With `--verbose`, shows the full signature inline. Results ranked: class/trait/object/enum first, non-test before test, shorter paths first.

### `impl` — find implementations

Looks up the trait/class name in `parentIndex` — a reverse map built during indexing from the `parents` field of each symbol. Returns all classes/objects/enums that `extends` or `with` the given trait.

### `refs` — find references

1. Bloom filter pre-screen — check each file's filter
2. Also check `aliasIndex` for files that alias the target (e.g., `import X as Y`)
3. Read candidate files from disk (parallel)
4. Word-boundary match on each line (also matches aliases)
5. **20s timeout** — stops if the deadline is exceeded, returns partial results

Output is **categorized by default** — groups results into Definition, ExtendedBy, ImportedBy, UsedAsType, Usage, and Comment. Use `--category CAT` to filter to a single category. Use `--flat` for a flat list.

Each reference is annotated with a **confidence level** based on imports:
- **High**: same package, explicit import, or import alias
- **Medium**: wildcard import (`import pkg._`/`import pkg.*`) matching the target's package
- **Low**: no matching import found

The word-boundary check:

```scala
private def containsWord(line: String, word: String): Boolean =
  // Finds "Foo" in "new Foo()" but NOT in "FooBar"
  // Checks that characters before/after are not alphanumeric
```

### `imports` — import graph

Same as `refs` but only matches lines starting with `import`. Also resolves wildcard imports (`import pkg._`) by checking if the target symbol's package matches. Time-boxed at 20s.

### `members` — list member declarations

On-the-fly command — NOT stored in the index. Finds the symbol via `findDefinition`, filters to class/trait/object/enum, then parses each source file with `parseFile()` to extract `templ.stats` one level deep:

```scala
case class MemberInfo(name: String, kind: SymbolKind, line: Int, signature: String, annotations: List[String])
```

Extracts: `Defn.Def`, `Defn.Val`, `Defn.Var`, `Defn.Type`, `Decl.Def`, `Decl.Val`, `Decl.Type`, and nested class/trait/object/enum definitions.

### `doc` — show scaladoc

On-the-fly command. Finds the symbol, then scans backwards from the symbol's line in the source file to find a `/** ... */` block. Handles multi-line, single-line, and blank lines between doc and symbol. Returns `(no scaladoc)` if none found.

### `overview` — codebase summary

Computed entirely from existing in-memory data — no extra I/O:
- **Symbols by kind**: groups `symbols` by `SymbolKind`, counts
- **Top packages**: sorts `packageToSymbols` by set size
- **Most extended**: sorts `parentIndex` by list size

### `annotated` — find annotated symbols

O(1) hash map lookup in `annotationIndex`. Finds all symbols with a given annotation (`@deprecated`, `@main`, etc.). The `@` prefix is optional; matching is case-insensitive.

### `grep` — regex content search

Reads all git-tracked `.scala` files and applies a Java regex pattern to each line. Uses parallel streams for speed. Integrates `--path` and `--no-tests` filtering. Supports `-e` for multiple patterns and `--count` for quick triage. Auto-corrects common POSIX regex mistakes (`\|` → `|`). Time-boxed at 20s.

### `file` — find file by name

Fuzzy search on file names (without `.scala` extension) using the same four-tier ranking as `search`: exact → prefix → substring → camelCase fuzzy. `"psl"` matches `PaymentServiceLive.scala`.

### `symbols` — file symbols

Looks up the file path in `filesByPath` map.

### `packages` — list packages

Sorted set of all `packageName` values.

### `batch` — multiple queries, one index load

Reads commands from stdin, one per line. Loads the index once and answers all queries. Eliminates the ~1s index-load overhead per command. Supports all subcommands. Not-found output is condensed to a single line to reduce noise.

### Fallback hints

When a command returns no results, scalex prints:
- How many files are indexed
- Whether any files had parse errors
- A suggestion to use Grep/Glob/Read as fallback

This prevents the AI agent from assuming a symbol doesn't exist just because scalex missed it.

---

## Layer 8: Parallelism

Two operations run in parallel via Java's `parallelStream()`:

1. **Symbol extraction** during indexing — parsing thousands of files
2. **Reference/grep search** — reading hundreds of candidate files from disk

```scala
gitFiles.asJava.parallelStream().forEach { gf =>
  val (syms, bloom, imports, aliases) = extractSymbols(gf.path)
  queue.add(IndexedFile(rel, gf.oid, syms, bloom, imports, aliases))
}
```

Results are collected in a `ConcurrentLinkedQueue` (thread-safe, lock-free). We use Java parallel streams instead of Scala parallel collections because `.par` is not available in Scala 3.8.

---

## Layer 9: Native Image

We compile to a GraalVM native image — a **28MB standalone binary**, no JVM needed.

| | JVM | Native Image |
|---|---|---|
| Startup | ~500ms | ~10ms |
| Cold index (14k files) | 5.1s | 3.3s |
| Warm index (14k files) | 672ms | 364ms |
| Size | needs JVM (~200MB) | 28MB |

Build:

```bash
scala-cli package --native-image scalex.scala \
  -o scalex --force -- --no-fallback \
  --initialize-at-run-time=com.google.common.hash.Striped64,...
```

The `--initialize-at-run-time` flags are needed because Guava uses `java.util.Random` during class initialization, which GraalVM doesn't allow at build time.

---

## Layer 10: Claude Code Integration

Scalex ships as a **Claude Code plugin**:

```
plugin/
├── .claude-plugin/plugin.json    # Plugin metadata
└── skills/scalex/
    ├── SKILL.md                  # Teaches Claude when/how to use scalex
    └── scripts/
        └── scalex-cli            # Bootstrap: downloads + caches binary, forwards args
```

The **SKILL.md** is the key — it tells Claude Code:
- **When** to use scalex (navigating Scala code, finding definitions, understanding impact, exploring codebases)
- **How** to call each command (exact bash commands with examples)
- **What** the output looks like (so Claude can parse results)
- **What to do** if scalex is not installed (download the right binary for the platform)

The bootstrap script `scalex-cli` handles platform detection, downloading the correct native binary from GitHub releases, and caching at `~/.cache/scalex/`. It auto-upgrades when the skill version changes.

---

## What Scalex Does NOT Do

| Feature | Why not |
|---|---|
| **Type checking / diagnostics** | Requires the Scala compiler + classpath. The AI agent runs `./mill __.compile` directly. |
| **Completions** | Requires the presentation compiler. AI agents complete code themselves. |
| **Hover / type info** | Requires compiled artifacts. The AI reads source to understand types. |
| **Java files** | Scope limited to Scala. |
| **Precise references** | We use text matching, not SemanticDB. May match an unrelated `Foo` in a different scope. Acceptable trade-off for zero-compilation speed. Confidence annotations help mitigate this. |
| **Dotted member doc** | `doc Foo.bar` not supported — only top-level symbol doc. Follow-up feature. |

---

## The Numbers

| Project | Files | Symbols | Cold Index | Warm Index |
|---|---|---|---|---|
| Small library | 92 | 259 | ~50ms | ~10ms |
| Mill build tool | 1,415 | 12,778 | 214ms | 50ms |
| Production monorepo | 13,958 | 214,803 | 3.3s | 364ms |
| Scala 3 compiler | 17,731 | 202,916 | 3.1s | 723ms |

Dependencies: **2 libraries** (Scalameta for parsing, Guava for bloom filters).

Total implementation: **~2,600 lines** of Scala 3 in a single file.
