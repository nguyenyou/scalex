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

## Table of Contents

- [The Problem](#the-problem)
- [The Idea](#the-idea)
- [The Goal](#the-goal)
- [How It Works](#how-it-works)
- [Quick Start](#quick-start)
- [Run Without Installing](#run-without-installing)
- [Build From Source](#build-from-source)
- [Commands](#commands)
- [Scalex vs Grep/Glob/Read — Honest Comparison](#scalex-vs-grepglobread--honest-comparison)
- [Name](#name)
- [Mascot](#mascot)
- [Credits](#credits)
- [License](#license)

## The Problem

AI coding agents (Claude Code, Cursor, Codex) are powerful, but they're blind in large Scala codebases. When an agent needs to find where `PaymentService` is defined, it has two options:

1. **Grep** — fast, but dumb. Returns raw text. Misses `given` definitions, can't filter by kind, doesn't know what a trait is vs a usage. The agent has to construct regex patterns and parse raw output.

2. **Metals LSP** — smart, but heavy. Requires a running build server (Bloop/sbt/Mill), full compilation, minutes of startup time. Designed for humans in IDEs, not agents making quick tool calls.

Neither works well for an AI agent that just needs to ask: *"Where is this defined? Who uses it? Who extends it?"*

## The Idea

What if we took the fast parts of a language server — the source-level indexing — and threw away everything that requires compilation?

An AI agent doesn't need type inference, completions, or hover info. It needs **navigation**: search, definitions, references, implementations. All of this can be done by parsing source code directly, without ever running a compiler.

## The Goal

Build the fastest possible Scala code navigation tool, designed from the ground up for AI agents:

- **One command = one answer.** No multi-step reasoning, no regex construction.
- **Structured output.** Symbol kind, package name, file path, line number. Not raw text.
- **Understands Scala 2 and 3.** Enums, givens, extensions, type aliases, implicit classes, procedure syntax — all parsed. Auto-detects dialect per file, zero config.
- **Zero setup.** Point it at a git repo. No build files, no config, no compilation.
- **Fallback guidance.** When it can't find something, it tells the agent what to try next.

## How It Works

The entire tool is ~1000 lines of Scala 3 in a single file. Here's the architecture:

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
                         │  symbolsByName  │  ← HashMap for O(1) def lookup
                         │  parentIndex    │  ← trait → [implementing classes]
                         │  annotationIdx  │  ← annotation → [symbols]
                         │  filesByPath    │  ← file → [symbols in that file]
                         │  packages       │  ← all package names
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

### The pipeline

```
  1. git ls-files --stage
     │
     │  Lists every .scala file tracked by git
     │  with its SHA-1 content hash (OID).
     │  ~200ms for 14k files.
     │
  2. Compare OIDs against cached index
     │
     │  If a file's OID matches the cache → skip it.
     │  Only re-parse files that actually changed.
     │  0 files changed = 0 files parsed.
     │
  3. Scalameta parse (parallel)
     │
     │  For each changed file:
     │  - Parse source → AST (Scala 3 first, falls back to 2.13)
     │  - Extract: class, trait, object, def, val,
     │    type, enum, given, extension
     │  - Record: name, kind, line, package, parents,
     │    signature, annotations
     │  - Build bloom filter of all identifiers
     │  Runs on all CPU cores via Java parallel streams.
     │
  4. Build in-memory index
     │
     │  symbolsByName:  Map[name → List[SymbolInfo]]
     │  parentIndex:    Map[trait → List[implementors]]
     │  filesByPath:    Map[path → List[SymbolInfo]]
     │  packages:       Set[packageName]
     │
  5. Save to .scalex/index.bin
     │
     │  Binary format with string interning.
     │  Bloom filters serialized per file.
     │  Loads in ~300ms for 215k symbols.
     │
  6. Answer the query
```

### Performance

| Project | Files | Symbols | Cold Index | Warm Index |
|---|---|---|---|---|
| Small library | 92 | 259 | ~50ms | ~10ms |
| Mill build tool | 1,415 | 12,778 | 214ms | 50ms |
| Production monorepo | 13,970 | 214,154 | 5.2s | 1.0s |
| Scala 3 compiler | 17,731 | 202,916 | 3.3s | 777ms |

## Quick Start

### Claude Code (recommended)

The fastest way — installs the binary + skill (teaches Claude when and how to use scalex) in one step:

```bash
/plugin marketplace add nguyenyou/scalex
/plugin install scalex@scalex-marketplace
```

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

scalex search Service --kind trait      # Find all traits with "Service" in the name
scalex search hms                       # Fuzzy camelCase: finds HttpMessageService
scalex search Auth --prefix             # Only exact + prefix matches, no noise
scalex def UserService --verbose        # Where is it defined? (with signature)
scalex impl UserService                 # Who extends this trait?
scalex refs UserService                 # Who uses it? (categorized by default)
scalex refs UserService --flat          # Flat list (old default)
scalex imports UserService              # Who imports it?
scalex file PaymentService              # Find files by name (fuzzy camelCase)
scalex annotated deprecated             # Find all @deprecated symbols
scalex grep "def.*process" --no-tests   # Regex search in .scala file contents
scalex grep -e "TODO" -e "FIXME" --count # Multi-pattern count
scalex symbols src/main/scala/App.scala # What's in this file?
scalex packages                         # What packages exist?
scalex def UserService --json           # Structured JSON output
```

## Run Without Installing

If you have [scala-cli](https://scala-cli.virtuslab.org/) installed:

```bash
git clone https://github.com/nguyenyou/scalex.git
scala-cli run scalex/scalex.scala -- search /path/to/project MyClass
```

No build step. Downloads dependencies on first run (~5s), then starts in ~1s.

## Build From Source

Requires [scala-cli](https://scala-cli.virtuslab.org/) + [GraalVM](https://www.graalvm.org/):

```bash
git clone https://github.com/nguyenyou/scalex.git
cd scalex
./build-native.sh
# Output: 28MB standalone binary, no JVM needed
cp scalex ~/.local/bin/scalex
```

## Commands

```
scalex search <query>           Search symbols by name          (aka: find symbol)
scalex def <symbol>             Where is this symbol defined?   (aka: find definition)
scalex impl <trait>             Who extends this trait/class?   (aka: find implementations)
scalex refs <symbol>            Who uses this symbol?           (aka: find references)
scalex imports <symbol>         Who imports this symbol?        (aka: import graph)
scalex symbols <file>           What's defined in this file?    (aka: file symbols)
scalex file <query>             Search files by name            (aka: find file)
scalex annotated <annotation>   Find symbols with annotation    (aka: find annotated)
scalex grep <pattern>           Regex search in file contents   (aka: content search)
scalex packages                 What packages exist?            (aka: list packages)
scalex index                    Rebuild the index               (aka: reindex)
scalex batch                    Run multiple queries at once    (aka: batch mode)
```

### Options

| Flag | Effect |
|---|---|
| `--verbose` | Show signatures, extends clauses, param types |
| `--categorize`, `-c` | Group refs by category (default; kept for backwards compatibility) |
| `--flat` | Refs: flat list instead of categorized (overrides default) |
| `--limit N` | Max results (default: 20) |
| `--kind K` | Filter search: class, trait, object, def, val, type, enum, given, extension |
| `--no-tests` | Exclude test files (test/, tests/, testing/, bench-*, *Spec.scala, etc.) |
| `--path PREFIX` | Restrict results to files under PREFIX (e.g. `compiler/src/`) |
| `-C N` | Show N context lines around each reference (refs, grep) |
| `-e PATTERN` | Grep: additional pattern (repeatable); combined with `\|` |
| `--count` | Grep: output match/file count only, no full results |
| `--exact` | Search: only exact name matches (case-insensitive) |
| `--prefix` | Search: only exact + prefix matches |
| `--json` | Output results as JSON — structured output for programmatic parsing |
| `--version` | Print version and exit |

### AI-Friendly Features

- **`--verbose`** on `def` returns the full signature — saves the agent a follow-up Read call
- **Categorized refs by default** — `refs` groups results by relationship (Definition/ExtendedBy/ImportedBy/UsedAsType/Comment/Usage) without any flags; use `--flat` for the old flat list
- **`impl`** finds concrete implementations of a trait — much more targeted than `refs`
- **`imports`** shows dependency relationships — which files depend on this symbol
- **Fuzzy camelCase search** — `search "hms"` finds `HttpMessageService`, `file "psl"` finds `PaymentServiceLive.scala`
- **`file`** command searches file names with the same fuzzy matching — like IntelliJ's file search
- **`annotated`** finds symbols by annotation — `@deprecated`, `@main`, `@tailrec`, etc.
- **`grep`** does regex content search inside `.scala` files with `--path` and `--no-tests` filtering built in; `-e` for multi-pattern, `--count` for quick triage
- **`--json`** flag on all commands produces structured JSON output — eliminates fragile text parsing
- **`--exact` / `--prefix`** on `search` eliminates noise from substring/fuzzy matches — `search Auth --prefix` returns ~20 results instead of 1300+
- **`batch`** mode loads the index once for multiple queries — 5 queries in ~1s instead of ~5s; not-found output is condensed to a single line
- **Fallback hints** on "not found" — tells the agent how many files were searched and suggests using Grep/Glob as fallback
- **20s timeout** on reference/grep search — prevents hangs on massive repos, shows partial results

## Scalex vs Grep/Glob/Read — Honest Comparison

We tested both approaches on the **Scala 3 compiler repo** (17.7k files, 203k symbols) — a real mixed Scala 2/3 codebase.

### Case 1: "Where is `Compiler` defined?"

**With Scalex** (1 tool call):

```bash
scalex def Compiler --verbose
  class     Compiler (dotty.tools) — compiler/src/dotty/tools/dotc/Compiler.scala:16
             class Compiler
  trait     Compiler (scala.quoted) — staging/src/scala/quoted/staging/Compiler.scala:8
             trait Compiler
  object    Compiler (scala.quoted) — staging/src/scala/quoted/staging/Compiler.scala:12
             object Compiler
```

Done. Found 3 definitions — class, trait, and companion object — across different packages. With signatures.

**With Grep** (agent must reason about patterns):

```bash
# Step 1: Agent tries the obvious pattern
Grep pattern="class Compiler|trait Compiler|object Compiler" glob="*.scala"
# Returns ~30 results including "class CompilerCommand", "class CompilerTest",
# "object CompilerSearchVisitor" — lots of noise from substring matches

# Step 2: Agent refines to exact match
Grep pattern="^(class|trait|object) Compiler[\s\[\({]" glob="*.scala"
# Better, but misses indented definitions and requires regex expertise

# Step 3: Agent still doesn't know the package for each result — needs Read calls
```

**Verdict**: Scalex returns structured results (kind + package + signature) in 1 call. Grep requires 2-3 iterations of regex refinement, and the agent still doesn't get package names without additional Read calls.

### Case 2: "Who extends `Compiler`?"

**With Scalex** (1 tool call):

```bash
scalex impl Compiler --verbose
  class     ExpressionCompiler (dotty.tools.debug) — .../ExpressionCompiler.scala:18
             class ExpressionCompiler extends Compiler
  class     TASTYCompiler (dotty.tools) — .../TASTYCompiler.scala:9
             class TASTYCompiler extends Compiler
  class     InteractiveCompiler (dotty.tools) — .../InteractiveCompiler.scala:10
             class InteractiveCompiler extends Compiler
  class     ReplCompiler (dotty.tools) — .../ReplCompiler.scala:34
             class ReplCompiler extends Compiler
```

**With Grep**:

```bash
Grep pattern="extends Compiler" glob="*.scala"
# Returns lines with "extends Compiler" — works! But also matches
# "extends CompilerCommand" and "extends CompilerPhase" (substring).
# Agent must filter manually. No package info. No signatures.
```

**Verdict**: Scalex's `impl` is purpose-built for this — exact parent matching from the AST, no substring noise. Grep works but returns false positives.

### Case 3: "What are all the traits in the `dotty.tools.dotc.transform` package?"

**With Scalex** (1 tool call):

```bash
scalex search transform --kind trait --limit 10
```

Returns only traits, ranked by relevance, with package names.

**With Glob + Grep** (multi-step):

```bash
# Step 1: Find files in that package
Glob pattern="**/transform/**/*.scala"
# Step 2: Grep each file for trait declarations
Grep pattern="^\s*trait " path="compiler/src/dotty/tools/dotc/transform/"
# Step 3: Parse results to extract trait names
```

**Verdict**: Scalex does this in one call with `--kind` filtering. The agent doesn't need to know the directory structure.

### Case 4: "Find all references to `Compiler` and categorize them"

**With Scalex** (1 tool call):

```bash
scalex refs Compiler --limit 5
  Definition:
    .../Compiler.scala:16 — class Compiler {
  ExtendedBy:
    .../ExpressionCompiler.scala:18 — class ExpressionCompiler extends Compiler
  ImportedBy:
    .../InteractiveDriver.scala:5 — import dotty.tools.dotc.Compiler
  UsedAsType:
    .../Run.scala:12 — val compiler: Compiler
  Comment:
    .../Compiler.scala:3 — /** The Compiler class ...
```

**With Grep**: Returns a flat list of all 278 lines containing "Compiler". The agent must read each line and decide whether it's a definition, import, extends, type usage, or comment. That's 278 lines of reasoning.

**Verdict**: `--categorize` gives the agent structured understanding in one call. This is something grep fundamentally cannot do.

### The Honest Truth

**Grep/Glob/Read are faster** (~0.1s vs ~1s). For simple text search ("does this string exist?"), grep wins hands down.

**Scalex is smarter**. It returns structured results with symbol kinds, packages, signatures, parent classes, and categorized references. For Scala-specific navigation, this saves the agent 2-5 follow-up tool calls per query.

**What AI agents actually prefer**: It depends on the task.

- **"Find a file by name"** → Both work. Glob for exact patterns, `scalex file` for fuzzy camelCase matching.
- **"Does this string appear anywhere?"** → Grep wins. Faster, simpler.
- **"Where is this trait defined?"** → Scalex wins. Finds givens, shows package + signature.
- **"Who implements this trait?"** → Scalex wins. `impl` vs multi-step grep + filter.
- **"Understand impact before refactoring"** → Scalex wins. `--categorize` gives structured view.
- **"Explore unfamiliar codebase"** → Scalex wins. `search --kind`, `packages`, `symbols --verbose`.

**Best approach**: Use both. Scalex for Scala-aware navigation, Grep/Glob for everything else. The skill's fallback hint even suggests this — when scalex can't find something, it tells the agent to try Grep.

## Name

**Scalex** = **Scala** + **ex** (explore, extract, index). A tool that explores Scala codebases, extracts symbols, and indexes them for instant lookup.

The name also nods to "Scala" itself — Italian for "staircase" or "scale" — and the idea of scaling up code navigation to massive repos without scaling up complexity.

## Mascot

<p align="center">
  <img src="site/Kestrel.png" alt="The Scalex Kestrel" width="256">
</p>

The Scalex mascot is a **kestrel** — the smallest falcon. It was chosen because it mirrors the tool's core qualities:

- **Smallest falcon** — reflects Scalex's lightweight, minimal design (~1000 lines, single 28MB binary)
- **Incredible eyesight** — spots symbols across 14k files, like a kestrel spots prey from 50 meters
- **Hovers before diving** — systematically scans an area before striking, like indexing before querying
- **Lives alongside people** — kestrels thrive near humans, like Scalex works alongside AI agents

See [MASCOT.md](site/MASCOT.md) for the full design brief.

## Credits

Scalex is built on ideas from [Metals](https://scalameta.org/metals/) — the Scala language server by the [Scalameta](https://scalameta.org/) team.

Specifically, the **MBT (Metal Build Tool) subsystem** in the `main-v2` branch (Databricks fork) pioneered the approach of using git OIDs for cache invalidation, bloom filters for reference pre-screening, and parallel source-level indexing without a build server. Scalex reimplements these ideas in ~1000 lines of Scala 3.

- **From Metals v2 MBT**: git-based file discovery, OID caching, bloom filter search, parallel indexing
- **From Scalameta**: the parser that makes source-level symbol extraction possible
- **From Guava**: bloom filter implementation

See [INSPIRATION.md](docs/INSPIRATION.md) for the full story of what we learned.

Metals is [Apache 2.0](https://github.com/scalameta/metals/blob/main/LICENSE). Scalex does not contain code copied from Metals — we reimplemented the ideas independently.

## Built With Claude

This entire project — research, design, implementation, testing, documentation — was built in a **single session** with [Claude Code](https://claude.ai/code) powered by the **Claude Opus 4.6 model with 1M context window**.

## License

MIT
