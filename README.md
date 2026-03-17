<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="site/readme-banner-dark.png">
    <source media="(prefers-color-scheme: light)" srcset="site/readme-banner-light.png">
    <img src="site/readme-banner-dark.png" alt="Scalex — Scala code intelligence for AI coding agents" width="839" height="440">
  </picture>
  <br>
  <em>Grep knows text. Scalex knows Scala.</em>
</p>

---

## The Problem

AI coding agents (Claude Code, Cursor, Codex) are powerful, but they're blind in large Scala codebases. When an agent needs to find where `PaymentService` is defined, it has two options:

1. **Grep** — fast, but dumb. Returns raw text. Can't filter by symbol kind, doesn't know a trait from a usage. The agent has to construct regex patterns and parse raw output.

2. **Metals LSP** — smart, but heavy. Requires a build server, full compilation, minutes of startup. Designed for humans in IDEs, not agents making quick tool calls.

What if we took the fast parts of a language server — source-level indexing — and threw away everything that requires compilation? An AI agent doesn't need type inference or completions. It needs **navigation**: definitions, references, implementations. All of this can be done by parsing source directly, without ever running a compiler.

## Design Principles

- **One command = one answer.** No multi-step reasoning, no regex construction.
- **Structured output.** Symbol kind, package, file path, line number. Not raw text.
- **Scala 2 and 3.** Enums, givens, extensions, implicit classes, procedure syntax — auto-detected per file. Java files (`.java`) are also indexed with lightweight regex extraction (class/interface/enum/record).
- **Zero setup.** Point it at a git repo. No build files, no config, no compilation.
- **Honest about limits.** When it can't find something, it tells the agent what to try next.

## How It Works

~3,100 lines of Scala 3 across 8 source files. Here's the architecture:

```
                         ┌─────────────────┐
                         │   scalex CLI    │
                         │                 │
                         │  search · def   │
                         │  impl · refs    │
                         │  imports · file │
                         │  batch          │
                         └────────┬────────┘
                                  │
                         ┌────────▼────────┐
                         │  WorkspaceIndex │
                         │                 │
                         │  symbolsByName  │  ← lazy: O(1) def lookup
                         │  parentIndex    │  ← lazy: trait → [implementors]
                         │  annotationIdx  │  ← lazy: annotation → [symbols]
                         │  filesByPath    │  ← lazy: file → [symbols]
                         │  packages       │  ← lazy: all package names
                         │  indexedFiles   │  ← per-file bloom filters
                         └────────┬────────┘
                                  │
              ┌───────────────────┼───────────────────┐
              │                   │                   │
     ┌────────▼───────┐ ┌─────────▼──────┐ ┌──────────▼─────┐
     │  Git Discovery │ │  Scalameta     │ │  Persistence   │
     │                │ │  Parser        │ │                │
     │  git ls-files  │ │                │ │  .scalex/      │
     │  --stage       │ │  Source → AST  │ │  index.bin     │
     │                │ │  → SymbolInfo  │ │                │
     │  Returns:      │ │  → BloomFilter │ │  Binary format │
     │  path + OID    │ │  → imports     │ │  + string      │
     │  per file      │ │  → parents     │ │  interning     │
     └────────────────┘ └────────────────┘ └────────────────┘
```

### Pipeline

```
  1. git ls-files --stage
     │  Every tracked .scala file with its content hash (OID).
     │  ~40ms for 18k files.
     │
  2. Compare OIDs against cached index
     │  Unchanged files are skipped entirely.
     │  0 changes = 0 parses.
     │
  3. Scalameta parse (parallel)
     │  Source → AST → symbols, bloom filters, imports, parents.
     │  All CPU cores via Java parallel streams.
     │
  4. Save to .scalex/index.bin
     │  Binary format with string interning.
     │  Loads in ~275ms for 200k+ symbols.
     │
  5. Answer the query
     │  Maps build lazily — each query only pays for the indexes it needs.
```

### Performance

| Project | Files | Symbols | Cold Index | Warm Index |
|---|---|---|---|---|
| Production monorepo | 13,979 | 214,301 | 4.6s | 540ms |
| Scala 3 compiler | 17,733 | 203,077 | 2.9s | 412ms |

## Quick Start

### Claude Code (recommended)

Installs the binary + skill (teaches Claude when and how to use scalex) in one step:

```bash
/plugin marketplace add nguyenyou/scalex
/plugin install scalex@scalex-marketplace
```

Then try:

> *"use scalex to explore how Signal propagation works in this codebase"*

### Other AI agents / manual install

**1. Install the binary** to `~/.local/bin`:

```bash
mkdir -p ~/.local/bin

# macOS Apple Silicon
curl -fsSL https://github.com/nguyenyou/scalex/releases/latest/download/scalex-macos-arm64 -o ~/.local/bin/scalex && chmod +x ~/.local/bin/scalex

# macOS Intel
curl -fsSL https://github.com/nguyenyou/scalex/releases/latest/download/scalex-macos-x64 -o ~/.local/bin/scalex && chmod +x ~/.local/bin/scalex

# Linux x64
curl -fsSL https://github.com/nguyenyou/scalex/releases/latest/download/scalex-linux-x64 -o ~/.local/bin/scalex && chmod +x ~/.local/bin/scalex
```

**2. Download the skill** (teaches your AI agent how to use scalex):

```bash
mkdir -p scalex
curl -fsSL https://raw.githubusercontent.com/nguyenyou/scalex/main/plugin/skills/scalex/SKILL.md -o scalex/SKILL.md
```

Place the `scalex/` folder wherever your agent reads skills from.

### Use it

```bash
cd /path/to/your/scala/project

# Discover
scalex search Service --kind trait         # Find traits by name
scalex search hms                          # Fuzzy camelCase: finds HttpMessageService
scalex search find --returns Boolean       # Filter by return type
scalex file PaymentService                 # Find files by name (like IntelliJ)
scalex packages                            # List all packages
scalex package com.example                 # Explore a specific package
scalex api com.example                     # What does this package export?
scalex api com.example --used-by com.web   # Coupling: what does web use from example?
scalex summary com.example                 # Sub-packages with symbol counts
scalex entrypoints                         # Find @main, def main, extends App, test suites

# Understand
scalex def UserService --verbose           # Definition with signature
scalex def UserService.findUser            # Owner.member dotted syntax
scalex explain UserService --verbose       # One-shot: def + doc + signatures + impls
scalex explain UserService --inherited    # Include inherited members from parents
scalex members UserService --inherited     # Full API surface including parents
scalex hierarchy UserService               # Inheritance tree (parents + children)

# Navigate
scalex refs UserService                    # Categorized references
scalex refs UserService --count            # Summary: "12 importers, 4 extensions, ..."
scalex refs UserService --top 10          # Top 10 files by reference count
scalex impl UserService                    # Who extends this?
scalex imports UserService                 # Who imports this?
scalex grep "def.*process" --no-tests      # Regex content search
scalex body findUser --in UserServiceLive  # Extract method body without Read

# Refine
scalex members Signal                      # Signatures by default + companion hint
scalex members Signal --brief              # Names only
scalex refs Cache --strict                 # No underscore/dollar false positives
scalex deps Phase --depth 2                # Transitive dependencies
```

All commands support `--json`, `--path PREFIX`, `--exclude-path PREFIX`, `--no-tests`, and `--limit N`.

## Run Without Installing

If you have [scala-cli](https://scala-cli.virtuslab.org/) installed:

```bash
git clone https://github.com/nguyenyou/scalex.git
scala-cli run scalex/src/ -- search /path/to/project MyClass
```

No build step. Downloads dependencies on first run (~5s), then starts in ~1s.

## Build From Source

Requires [scala-cli](https://scala-cli.virtuslab.org/) + [GraalVM](https://www.graalvm.org/):

```bash
git clone https://github.com/nguyenyou/scalex.git
cd scalex
./build-native.sh
# Output: ~30MB standalone binary, no JVM needed
cp scalex ~/.local/bin/scalex
```

## Commands

```
scalex search <query>           Search symbols by name          (aka: find symbol)
scalex def <symbol>             Where is this symbol defined?   (aka: find definition)
scalex impl <trait>             Who extends this trait/class?   (aka: find implementations)
scalex refs <symbol>            Who uses this symbol?           (aka: find references)
scalex imports <symbol>         Who imports this symbol?        (aka: import graph)
scalex members <symbol>         What's inside this class/trait? (aka: list members)
scalex doc <symbol>             Show scaladoc for a symbol      (aka: show docs)
scalex overview                 Codebase summary                (aka: project overview)
scalex symbols <file>           What's defined in this file?    (aka: file symbols)
scalex file <query>             Search files by name            (aka: find file)
scalex annotated <annotation>   Find symbols with annotation    (aka: find annotated)
scalex grep <pattern>           Regex search in file contents   (aka: content search)
scalex packages                 What packages exist?            (aka: list packages)
scalex package <pkg>            Symbols in a package            (aka: explore package)
scalex index                    Rebuild the index               (aka: reindex)
scalex batch                    Run multiple queries at once    (aka: batch mode)
scalex body <symbol>            Extract method/val/class body   (aka: show source)
scalex hierarchy <symbol>       Full inheritance tree           (aka: type hierarchy)
scalex overrides <method>       Find override implementations   (aka: find overrides)
scalex explain <symbol>         Composite one-shot summary      (aka: explain symbol)
scalex deps <symbol>            Show symbol dependencies        (aka: dependency graph)
scalex context <file:line>      Show enclosing scopes at line   (aka: scope chain)
scalex diff <git-ref>           Symbol-level diff vs git ref    (aka: symbol diff)
scalex ast-pattern              Structural AST search           (aka: pattern search)
scalex tests                    List test cases structurally    (aka: find tests)
scalex coverage <symbol>        Is this symbol tested?          (aka: test coverage)
scalex api <package>            Public API surface of a package (aka: exported symbols)
scalex summary <package>        Sub-packages with symbol counts   (aka: package breakdown)
scalex entrypoints              Find @main, def main, extends App, test suites
```

All commands support `--json`, `--path PREFIX`, `--exclude-path PREFIX`, `--no-tests`, and `--limit N`. See the full [command reference and options](plugin/skills/scalex/SKILL.md) for detailed usage, examples, and all flags.

### What Makes It AI-Friendly

**Fewer round-trips.** The biggest cost for an AI agent isn't latency — it's the number of tool calls. Each call costs tokens, reasoning, and context window space.

- `explain` replaces 4-5 calls (def + doc + members + impl + imports) with one; auto-shows companion object/class members; `--verbose` shows full signatures
- `explain --expand N` recursively expands implementations — shows each subtype's members in one call
- `def pkg.Name` resolves by package-qualified name — no ambiguity, no follow-up disambiguation
- `def Owner.member` navigates directly to a member — `def MyService.findUser` resolves without `body --in`
- `impl Foo` finds `class Bar extends Mixin[Foo]` — type-param parent indexing discovers parametric inheritance
- `api` shows a package's public API surface — `--used-by` filters to a specific consumer package
- `members` auto-shows companion object/class members alongside the primary type
- `refs --count` gives category counts in one line — fast impact triage without reading full file lists
- `refs --top N` ranks files by reference count — surfaces heaviest users first for impact analysis
- `entrypoints` finds all application entry points (`@main`, `def main`, `extends App`, test suites) in one call — useful for onboarding
- `members --inherited` marks overrides with `[override]` — shows which own members shadow parent definitions
- `body` extracts source without a Read call — eliminates ~50% of follow-up file reads
- `refs` returns categorized results (Definition/ExtendedBy/ImportedBy/UsedAsType) — no post-processing
- `search` ranks by import popularity; `--returns` / `--takes` filter by signature
- `overview` defaults to `--no-tests` — production code is almost always the intent
- `hierarchy` shows the full inheritance tree in one call — parents up, children down
- `batch` loads the index once for multiple queries — 5 queries in ~1s instead of ~5s

**Less noise.** Large codebases produce hundreds of results. Scalex gives the agent tools to cut through:

- `--kind`, `--path`, `--exclude-path`, `--no-tests` — filter at the source, not after
- `--exact` / `--prefix` — `search Auth --prefix` returns ~20 results instead of 1300+
- `--definitions-only` — only class/trait/object/enum, no val/def name collisions (works on `search` and `package`)
- `summary` — sub-package breakdown with symbol counts; drill-down from overview to package
- `--category` on refs — `refs Signal --category ExtendedBy` for targeted impact analysis

**Structured, not raw.** Every result includes symbol kind, package name, file path, and line number. `--json` on all commands for programmatic parsing. Fallback hints on "not found" suggest Grep/Glob as alternatives.

## Scalex vs Grep — Honest Comparison

Tested on the **Scala 3 compiler** (17.7k files, 203k symbols).

### "Where is `Compiler` defined?"

**Scalex** (1 call):
```
scalex def Compiler --verbose --kind class
  class  Compiler (dotty.tools) — compiler/src/.../Compiler.scala:16
  class  Compiler (dotty.tools.pc) — .../CompletionValue.scala:127
```
Filtered to classes only. Without `--kind`, returns 31 results (vals, defs, test fixtures) — still structured with kind and package, but noisier. Grep has the same noise problem without the structure.

**Grep** (2-3 calls): `class Compiler|trait Compiler|object Compiler` returns 24 results including `CompilerOptions`, `CompilerHang`, `CompilerTest` (substring matches). Needs regex refinement, still no package info.

### "Who extends `Compiler`?"

**Scalex** (1 call):
```
scalex impl Compiler
  ExpressionCompiler, residentCompiler, TASTYCompiler,
  InteractiveCompiler, ReplCompiler, QuoteCompiler
```
6 results. Exact parent matching from the AST. No substring noise.

**Grep**: `extends Compiler` returns 23 results — but 17 are false positives like `extends CompilerTest`, `extends CompilerCommand`, `extends CompilerCallback`. Agent must filter manually.

### "Find references and categorize them"

**Scalex** (1 call):
```
scalex refs Compiler --limit 5
  Definition:  given Compiler = Compiler.make(...)       (108 total)
  ExtendedBy:  class QuoteCompiler extends Compiler      (12 total)
  ImportedBy:  import dotty.tools.dotc.Compiler          (16 total)
  UsedAsType:  val compiler: Compiler                    (23 total)
  Usage:       val compiler = new Compiler               (53 total)
  Comment:     /** Compiler that takes...                (19 total)
```
278 references, auto-categorized by relationship, with import-based confidence levels.

**Grep**: `grep Compiler` returns 1,130 lines, flat and unsorted. Agent must classify each one.

### The Honest Truth

**Grep is faster** (~0.1s vs ~0.5s). For "does this string exist?" — use grep.

**Scalex is smarter.** For "where is this defined?", "who implements this?", "what's the impact of changing this?" — scalex saves 2-5 follow-up tool calls per query.

**Best approach: use both.** Scalex for Scala-aware navigation, Grep for text search. The skill's fallback hint even suggests this — when scalex can't find something, it tells the agent to try Grep.

## Credits

Scalex is built on ideas from [Metals](https://scalameta.org/metals/) — the Scala language server by the [Scalameta](https://scalameta.org/) team. Specifically, the **MBT subsystem** in the `main-v2` branch (Databricks fork) pioneered git OIDs for cache invalidation, bloom filters for reference pre-screening, and parallel source-level indexing without a build server.

- **From Metals v2 MBT**: git-based file discovery, OID caching, bloom filter search, parallel indexing
- **From Scalameta**: the parser that makes source-level symbol extraction possible
- **From Guava**: bloom filter implementation

Metals is [Apache 2.0](https://github.com/scalameta/metals/blob/main/LICENSE). Scalex does not contain code copied from Metals — the ideas were reimplemented independently.

Built with [Claude Code](https://claude.ai/code) powered by **Claude Opus 4.6** (1M context).

## Name

**Scalex** = **Scala** + **ex** (explore, extract, index).

## Mascot

<p align="center">
  <img src="site/Kestrel.png" alt="The Scalex Kestrel" width="256">
</p>

A **kestrel** — the smallest falcon. Fast, sharp-eyed, lightweight, hovers before diving. See [MASCOT.md](site/MASCOT.md) for the full design brief.

## License

MIT
