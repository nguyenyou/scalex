# Inspiration

Scalex is heavily inspired by [Metals](https://scalameta.org/metals/) — the Scala language server by the Scalameta team. Specifically, the **MBT (Metal Build Tool) subsystem** introduced in the `main-v2` branch (Databricks fork).

## What we learned from Metals v2

### Git as the source of truth

Metals v1 relies on BSP build servers (Bloop, sbt, Mill) for file discovery and symbol indexing. Metals v2 introduced a radical shift: use `git ls-files --stage` instead. This is:
- **Fast**: ~200ms for 100k files
- **Free**: OIDs (SHA-1 hashes) come with the listing, no extra disk reads
- **Universal**: works without any build server running

We adopted this directly. Our `gitLsFiles()` function is a simplified port of `GitVCS.scala`.

### OID-based cache invalidation

Metals v2's `OID.scala` computes content hashes identical to Git's blob format (`blob <size>\0<content>`). On reindex, files with unchanged OIDs are skipped entirely.

We use the same approach — compare OIDs from `git ls-files --stage` against our cached index. Changed files get re-parsed, unchanged files are loaded from cache.

### Bloom filters for reference search

Metals v2's `IndexedDocument.scala` stores a Guava bloom filter per file, populated with all identifiers found in that file. When searching for references, the bloom filter pre-screens files — only files whose bloom filter says "this identifier might be here" get read from disk.

We ported this idea. Each `IndexedFile` carries a bloom filter built during indexing. Our `findReferences()` checks the bloom filter first, skipping ~99% of files.

### Parallel indexing

Metals v2 uses `ForkJoinPool` with `ParArray` for parallel symbol extraction. We use Java's `parallelStream()` (simpler, no extra dependency on Scala parallel collections which aren't available for Scala 3.8).

### Source-level symbol extraction

Metals uses `mtags` (meta + tags) — a fast symbol extractor that parses Scala source files without full compilation. It extracts top-level symbols: classes, traits, objects, methods, types.

We use Scalameta's parser directly instead of mtags as a library. Same result (top-level symbol extraction from AST), fewer dependencies.

## What we didn't take from Metals

### Presentation compiler

Metals wraps the Scala compiler as a "presentation compiler" for completions, hover, diagnostics, and type info. We deliberately excluded this because:

1. **Scala 3's presentation compiler requires compiled artifacts** (`.class`/`.tasty` files) on the classpath — this reintroduces the build server dependency
2. **Scala 2 had `-sourcepath`** which allowed the compiler to lazily resolve symbols from source without compilation. Scala 3 has no equivalent.
3. **AI agents don't need it** — they can run the build tool directly for error checking and read source code to understand types

### BSP (Build Server Protocol)

Metals communicates with build servers (Bloop, sbt, Mill) via BSP for classpath resolution, compilation, and diagnostics. We skipped this entirely — our tool works without any build server.

### LSP (Language Server Protocol)

Metals is fundamentally an LSP server designed for IDE integration. We ship as a CLI + Claude Code skill instead. AI agents call tools via Bash, not LSP.

### SemanticDB

Metals uses SemanticDB (compiler-generated `.semanticdb` files) for precise symbol resolution. This requires compilation. Our reference search uses source-level text matching with bloom filter pre-screening — less precise but requires zero compilation.

### H2 database

Metals v1 persists its index in an H2 SQL database (`.metals/metals.mv.db`). We use a custom binary format with string interning — simpler, faster for our use case.

### Turbine (Java header compiler)

Metals v2 uses Google's Turbine for fast Java header-only compilation. We don't support Java at all — Scala 3 only.

## The key insight

Metals v2's MBT subsystem proved that **useful code navigation doesn't require compilation**. Git-based file discovery + source-level parsing + bloom filter indexing gives you search, definitions, and references — fast enough for interactive use, with zero build server overhead.

We took that insight and built the simplest possible tool around it: a single-file CLI that an AI agent can call via Bash.

## License

Metals is [Apache 2.0](https://github.com/scalameta/metals/blob/main/LICENSE). Scalex does not contain any code copied directly from Metals — we reimplemented the ideas in ~800 lines of Scala 3.
