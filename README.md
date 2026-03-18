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

## Try It Now

Install the Scalex skill in Claude Code:

```bash
/plugin marketplace add nguyenyou/scalex
/plugin install scalex@scalex-marketplace
```

Clone a project and ask Claude to explore it:

| Project | Clone | Prompt |
|---|---|---|
| **Scala 3 compiler** | `git clone --depth 1 https://github.com/scala/scala3.git` | *"Use scalex to explore how the Scala 3 compiler turns source file into bytecode."* |
| **Scala.js** | `git clone --depth 1 https://github.com/scala-js/scala-js.git` | *"Use scalex to explore how Scala.js turns Scala code into JavaScript."* |

Scalex will index the codebase in seconds, then navigate definitions, trace implementations, and explore the architecture — all without a build server or compilation. See [Quick Start](#quick-start) for installation details.

https://github.com/user-attachments/assets/6c7ab644-6541-4e77-b354-53d47bd19af0

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

Here's the architecture (generated with `scalex graph --render`):

<!-- scalex graph --render "scalex CLI->WorkspaceIndex, WorkspaceIndex->git ls-files, WorkspaceIndex->Scalameta AST, WorkspaceIndex->.scalex/index.bin" -->
```
                   ┌──────────┐
                   │scalex CLI│
                   └─────┬────┘
                         │
                         v
                 ┌──────────────┐
                 │WorkspaceIndex│
                 └───┬────┬──┬──┘
                     │    │  │
        ┌────────────┘    │  └──────────────┐
        │                 │                 │
        v                 v                 v
 ┌─────────────┐ ┌─────────────────┐ ┌────────────┐
 │Scalameta AST│ │.scalex/index.bin│ │git ls-files│
 └─────────────┘ └─────────────────┘ └────────────┘
```

- **scalex CLI** — 30 commands: search, def, impl, refs, imports, members, graph, ...
- **WorkspaceIndex** — lazy indexes: symbolsByName, parentIndex, filesByPath, bloom filters
- **git ls-files** — `--stage` returns path + OID per tracked file (change detection)
- **Scalameta AST** — Source → AST → SymbolInfo, BloomFilter, imports, parents
- **.scalex/index.bin** — binary cache with string interning (skip unchanged files)

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

> *"use scalex to explore how authentication works in this codebase"*

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
scalex graph --render "A->B, B->C, A->D"  # Render directed graph as ASCII/Unicode art
scalex graph --parse < diagram.txt         # Parse ASCII diagram into boxes + edges

# Understand
scalex def UserService --verbose           # Definition with signature
scalex def UserService.findUser            # Owner.member dotted syntax
scalex explain UserService --verbose       # One-shot: def + doc + signatures + impls
scalex explain UserService --inherited     # Include inherited members from parents
scalex explain UserService --no-doc       # Skip Scaladoc section
scalex explain UserService --brief        # Definition + top 3 members only
scalex members UserService --inherited     # Full API surface including parents
scalex hierarchy UserService               # Inheritance tree (parents + children)

# Navigate
scalex refs UserService                    # Categorized references
scalex refs UserService --count            # Summary: "12 importers, 4 extensions, ..."
scalex refs UserService --top 10           # Top 10 files by reference count
scalex impl UserService                    # Who extends this?
scalex imports UserService                 # Who imports this?
scalex grep "def.*process" --no-tests      # Regex content search
scalex body findUser --in UserServiceLive  # Extract method body without Read
scalex body findUser --in UserServiceLive -C 3  # Body with 3 context lines
scalex body findUser --in UserServiceLive --imports  # Body with file imports
scalex grep "ctx.settings" --in Run        # Grep within a class body

# Refine
scalex members Signal                      # Signatures by default + companion hint
scalex members Signal --brief              # Names only
scalex members Signal --body --max-lines 10  # Inline bodies ≤ 10 lines
scalex refs Cache --strict                 # No underscore/dollar false positives
scalex deps Phase --depth 2                # Transitive dependencies
```

All commands support `--json`, `--path PREFIX`, `--exclude-path PREFIX`, `--no-tests`, and `--limit N` (0 = unlimited).

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
scalex graph --render "A->B"   Render directed graph as ASCII/Unicode art
scalex graph --parse           Parse ASCII diagram from stdin into boxes+edges
```

All commands support `--json`, `--path PREFIX`, `--exclude-path PREFIX`, `--no-tests`, and `--limit N` (0 = unlimited). See the full [command reference and options](plugin/skills/scalex/SKILL.md) for detailed usage, examples, and all flags.

### What Makes It AI-Friendly

The biggest cost for an AI agent isn't latency — it's the number of tool calls. Each call costs tokens, reasoning, and context window space. Scalex is designed to maximize information per call.

**One call, not five.** Most agent tasks require chaining grep → read → grep → read. Scalex collapses these chains:

- `explain` replaces 4-5 calls — definition + scaladoc + members + companion + implementations + import count, all in one response. `--expand N` recursively shows each implementation's members. `--body` inlines source code. `--inherited` merges parent members
- `body` extracts source directly — no follow-up Read call needed. `--in Owner` disambiguates, `-C N` adds context, `--imports` prepends the file's import block
- `members --body` inlines method bodies into the member listing — replaces N separate `body` calls
- `batch` amortizes the ~400ms index load across multiple queries — 5 queries in ~600ms instead of ~2.5s
- `refs --count` gives category counts in one line — fast impact triage before committing to a full read
- `refs --top N` ranks files by reference count — surfaces the heaviest users first

**Semantic, not textual.** Scalex parses Scala ASTs, so it understands things grep fundamentally cannot:

- `refs` **categorizes** results by relationship (Definition / ExtendedBy / ImportedBy / UsedAsType / Usage / Comment) and **ranks by confidence** — high (explicit import), medium (wildcard import), low (no matching import)
- `imports` resolves **wildcard imports** — `import dotty.tools.dotc.*` counts as importing `Compiler`. On the scala3 compiler, this finds 1,205 importers vs grep's 17 explicit mentions
- `hierarchy` shows the **transitive inheritance tree** — parents up, children down, with depth control. Grep can only find direct `extends` mentions
- `impl` finds **parametric inheritance** — `impl Foo` matches `class Bar extends Mixin[Foo]` via type-param parent indexing
- `overrides` finds every implementation of a method across the class hierarchy — `overrides run --of Phase --body` shows each override's source inline
- `ast-pattern` does **structural search** — find types that extend a trait AND have a specific method AND whose body contains a pattern, in one query

**Precision filters.** Large codebases produce hundreds of results. Every command supports filtering at the source:

- `--kind class`, `--path compiler/src/`, `--exclude-path sbt-test/`, `--no-tests` — composable filters on all commands
- `--exact` / `--prefix` on search — `search Auth --prefix` returns ~20 results instead of 1300+
- `--definitions-only` — only class/trait/object/enum, no val/def name collisions
- `--category ExtendedBy` on refs — targeted impact analysis for a single relationship type

**Self-correcting output.** Scalex is designed for agents that can't ask clarifying questions:

- Every result includes **symbol kind, package name, file path, and line number** — no ambiguity about what was found
- Disambiguation prints **ready-to-run commands** — when `explain Compiler` matches 8 types, stderr shows `scalex explain dotty.tools.Compiler`, `scalex explain scala.quoted.Compiler`, etc.
- Package-qualified lookup — `def com.example.Cache` or partial `def cache.Cache` resolves without follow-up
- Owner.member dotted syntax — `def MyService.findUser` navigates directly to the member
- Fuzzy camelCase matching — `search hms` finds `HttpMessageService`
- **Fallback hints** on "not found" — suggests `scalex grep` or Grep/Glob tools as alternatives
- `--json` on all commands for programmatic parsing

## Scalex vs Grep — Honest Comparison

Tested on the **Scala 3 compiler** (17.7k files, 203k symbols).

### "Where is `Compiler` defined?"

**Scalex** — 1 call, **2 results**:
```
scalex def Compiler --kind class
  class  Compiler (dotty.tools)    — compiler/src/.../Compiler.scala:16
  class  Compiler (dotty.tools.pc) — .../CompletionValue.scala:127
```

**Grep** — 1 call, **24 results**: `class Compiler|trait Compiler|object Compiler` matches `CompilerOptions`, `CompilerHang`, `CompilerTest`, `CompilerCommand` (substring noise). No package info, no kind filtering. Agent must write follow-up regex to exclude substrings.

**Why scalex wins**: Exact name matching + `--kind` filter + package disambiguation. One call, done.

### "Show the full inheritance tree of `Compiler`"

**Scalex** — 1 call, **full tree with transitive children**:
```
scalex hierarchy Compiler
  Children:
    ├── ExpressionCompiler — .../ExpressionCompiler.scala:18
    ├── residentCompiler   — .../Resident.scala:28
    ├── TASTYCompiler      — .../TASTYCompiler.scala:9
    │   └── TASTYDecompiler    — .../TASTYDecompiler.scala:11
    │       └── PartialTASTYDecompiler — .../PartialTASTYDecompiler.scala:9
    ├── InteractiveCompiler — .../InteractiveCompiler.scala:10
    ├── ReplCompiler        — .../ReplCompiler.scala:34
    └── QuoteCompiler       — .../QuoteCompiler.scala:35
```

**Grep** — **impossible**. `extends Compiler` returns 23 results — 17 are false positives (`extends CompilerTest`, `extends CompilerCommand`). Even after manual filtering, grep only finds *direct* subclasses. `TASTYDecompiler → PartialTASTYDecompiler` (extends `TASTYCompiler`, not `Compiler`) is invisible to grep. Agent needs 3+ follow-up calls to walk the tree manually.

**Why scalex wins**: Transitive hierarchy from the AST. Grep cannot do this at any depth.

### "What's the impact of changing `Compiler`?"

**Scalex** — 1 call, **283 references**, auto-categorized and confidence-ranked:
```
scalex refs Compiler --limit 5
  High confidence (import-matched):
    Definition:  class Compiler {                          (107 total)
    ExtendedBy:  class ExpressionCompiler extends Compiler (12 total)
    ImportedBy:  import dotty.tools.dotc.Compiler          (17 total)
    UsedAsType:  val compiler: Compiler                    (20 total)
    Usage:       new Compiler                              (56 total)
    Comment:     /** Compiler that takes...                (20 total)
  Medium confidence (wildcard import):  ...
  Low confidence (no matching import):  ...
```

**Grep** — 1 call, **1,143 lines**, flat and unsorted. Agent sees definitions, imports, type annotations, instantiations, and comments all mixed together. Needs multiple follow-up calls to classify.

**Why scalex wins**: Categories tell the agent *how* a symbol is used (extended? imported? instantiated?), and confidence tiers surface the most relevant references first. An agent using grep needs 3-5 follow-up calls to achieve the same understanding.

### "Who imports `Compiler`?"

**Scalex** — 1 call, **1,205 files**:
```
scalex imports Compiler
  .../ExpressionCompiler.scala:3 — import dotty.tools.dotc.Compiler       (explicit)
  .../Run.scala:5                — import dotty.tools.dotc.{Driver, Run, Compiler}
  .../WeakHashSet.scala:9        — import dotty.tools.*                    (wildcard)
```

**Grep** — 1 call, **17 files**: `import.*\bCompiler\b` only finds explicit imports. Files using `import dotty.tools.dotc.*` or `import scala.quoted.staging.*` are invisible — that's **98.6% of importers missed**.

**Why scalex wins**: Wildcard import resolution. This is critical for impact analysis — you need to know *every* file that has `Compiler` in scope, not just the ones that spell it out.

### When to use which

| Task | Use | Why |
|------|-----|-----|
| "Does this string exist?" | **Grep** | Faster, no index needed |
| "Find this error message" | **Grep** | Text search, not a symbol |
| Config values, flag names | **Grep** | Not Scala symbols |
| Non-`.scala` files | **Grep** | Scalex only indexes Scala |
| "Where is X defined?" | **Scalex** | Exact match + kind + package |
| "Who implements trait X?" | **Scalex** | AST parent matching, no substring noise |
| "Show the class hierarchy" | **Scalex** | Transitive tree — grep can't do this |
| "What's the impact of changing X?" | **Scalex** | Categorized refs with confidence tiers |
| "Who imports X?" | **Scalex** | Wildcard import resolution |
| "What does this file/package export?" | **Scalex** | `overview` and `members` commands |

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
