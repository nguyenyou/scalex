# Scalex — Architecture

All diagrams generated with `scalex graph --render`. Reproduce any diagram by running the command in its HTML comment.

## System Overview

<!-- scalex graph --render "CLI Entry->Flag Parsing, Flag Parsing->Dispatch, Dispatch->Commands, Commands->WorkspaceIndex, WorkspaceIndex->Extraction, WorkspaceIndex->Git Discovery, WorkspaceIndex->Persistence, Commands->CmdResult, CmdResult->Formatter" -->
```
                      ┌─────────┐
                      │CLI Entry│
                      └────┬────┘
                           │
                           v
                    ┌────────────┐
                    │Flag Parsing│
                    └──────┬─────┘
                           │
                           v
                      ┌────────┐
                      │Dispatch│
                      └────┬───┘
                           │
                           v
                      ┌────────┐
                      │Commands│
                      └──┬──┬──┘
                         │  │
              ┌──────────┘  └────────┐
              │                      │
              v                      v
         ┌─────────┐         ┌──────────────┐
         │CmdResult│         │WorkspaceIndex│
         └────┬────┘         └───┬┬──────┬──┘
              │                  ││      │
      ┌───────┘    ┌─────────────┘│      │
      │            │              │      │
      v            v              v      v
 ┌─────────┐ ┌───────────┐ ┌──────────┐ ┌─────────────┐
 │Formatter│ │Persistence│ │Extraction│ │Git Discovery│
 └─────────┘ └───────────┘ └──────────┘ └─────────────┘
```

- **CLI Entry** (`src/cli.scala`) — `@main` entry point, parses `--workspace`, `--limit`, `--json`, and 30+ flags into `ParsedFlags`
- **Flag Parsing** (`src/cli.scala`) — converts flags to `CommandContext`, resolves workspace path
- **Dispatch** (`src/dispatch.scala`) — routes command name to handler function via `commands: Map[String, (List[String], CommandContext) => CmdResult]`
- **Commands** (`src/commands/*.scala`) — 29 command handlers, each returning `CmdResult`
- **WorkspaceIndex** (`src/index.scala`) — lazy in-memory index with symbol lookups, bloom filters, parent/child indexes
- **CmdResult** (`src/model.scala`) — sealed enum with 25+ variants (`SymbolList`, `RefList`, `GraphOutput`, etc.)
- **Formatter** (`src/format.scala`) — pattern-matches on `CmdResult` to produce text or JSON output
- **Extraction** (`src/extraction.scala`) — Scalameta AST parsing, symbol/member/body extraction
- **Git Discovery** (`src/index.scala`) — `git ls-files --stage` for tracked file paths + OIDs
- **Persistence** (`src/index.scala`) — binary cache at `.scalex/index.bin` with string interning

## Query Flow

How a single query like `scalex def UserService` flows through the system:

<!-- scalex graph --render "Query->parseFlags, parseFlags->CommandContext, CommandContext->cmdHandler, cmdHandler->WorkspaceIndex, WorkspaceIndex->CmdResult, CmdResult->render, render->stdout" -->
```
     ┌─────┐
     │Query│
     └──┬──┘
        │
        v
  ┌──────────┐
  │parseFlags│
  └──────┬───┘
         │
         v
 ┌──────────────┐
 │CommandContext│
 └──────┬───────┘
        │
        v
  ┌──────────┐
  │cmdHandler│
  └──────┬───┘
         │
         v
 ┌──────────────┐
 │WorkspaceIndex│
 └──────┬───────┘
        │
        v
   ┌─────────┐
   │CmdResult│
   └────┬────┘
        │
        v
    ┌──────┐
    │render│
    └───┬──┘
        │
        v
    ┌──────┐
    │stdout│
    └──────┘
```

1. CLI parses args into `ParsedFlags`, extracts workspace path
2. `ParsedFlags` → `CommandContext` (carries index, filters, output preferences)
3. Command handler queries `WorkspaceIndex` (e.g. `idx.symbolsByName("UserService")`)
4. Handler applies filters (`--kind`, `--path`, `--no-tests`) and returns a `CmdResult` variant
5. `render()` pattern-matches on the `CmdResult` and prints text or JSON

## Index Pipeline

How source files become searchable symbols:

<!-- scalex graph --render "git ls-files->GitFile, GitFile->Scalameta Parse, Scalameta Parse->SymbolInfo, Scalameta Parse->BloomFilter, SymbolInfo->IndexedFile, BloomFilter->IndexedFile, IndexedFile->WorkspaceIndex, WorkspaceIndex->.scalex/index.bin" -->
```
      ┌────────────┐
      │git ls-files│
      └──────┬─────┘
             │
             v
         ┌───────┐
         │GitFile│
         └───┬───┘
             │
             v
     ┌───────────────┐
     │Scalameta Parse│
     └─┬────────────┬┘
       │            │
       v            v
 ┌──────────┐ ┌───────────┐
 │SymbolInfo│ │BloomFilter│
 └─────────┬┘ └┬──────────┘
           │   │
           v   v
       ┌───────────┐
       │IndexedFile│
       └─────┬─────┘
             │
             v
     ┌──────────────┐
     │WorkspaceIndex│
     └───────┬──────┘
             │
             v
    ┌─────────────────┐
    │.scalex/index.bin│
    └─────────────────┘
```

- **git ls-files --stage** returns path + OID per tracked `.scala`/`.java` file
- **OID caching**: on subsequent runs, compares OIDs — skips unchanged files entirely
- **Scalameta** parses each file (Scala 3 first, falls back to Scala 2.13), extracts top-level symbols
- **BloomFilter** (Guava): per-file bloom of identifiers — `refs` and `imports` only read candidate files
- **IndexedFile** bundles symbols, bloom, imports, and aliases for one file
- **WorkspaceIndex** builds lazy lookup maps from all `IndexedFile`s
- **.scalex/index.bin** persists the full index in binary format with string interning

## Data Model

How types flow from extraction through commands to output:

<!-- scalex graph --render "SymbolInfo->IndexedFile, IndexedFile->WorkspaceIndex, WorkspaceIndex->CommandContext, CommandContext->CmdResult, CmdResult->Formatter, SymbolInfo->Reference, Reference->CmdResult, MemberInfo->CmdResult, HierarchyTree->CmdResult, OverrideInfo->CmdResult" --rounded -->
```
                              ╭──────────╮
                              │SymbolInfo│
                              ╰───┬───┬──╯
                                  │   │
                         ╭────────╯   ╰─────────────╮
                         │                          │
                         v                          │
                   ╭───────────╮                    │
                   │IndexedFile│                    │
                   ╰──────┬────╯                    │
                          │                         │
                          v                         │
                  ╭──────────────╮                  │
                  │WorkspaceIndex│                  │
                  ╰───────┬──────╯                  │
                          │                         │
         ╭────────────────╯                         │
         │             ╭────────────────────────────╯
         │             │
         v             v
 ╭──────────────╮ ╭─────────╮ ╭─────────────╮ ╭────────────╮ ╭──────────╮
 │CommandContext│ │Reference│ │HierarchyTree│ │OverrideInfo│ │MemberInfo│
 ╰───────┬──────╯ ╰────┬────╯ ╰─────┬───────╯ ╰──────┬─────╯ ╰─────┬────╯
         │             │            │                │             │
         │             ╰──────────╮ │ ╭──────────────╯             │
         ╰──────────────────────╮ │ │ │ ╭──────────────────────────╯
                                │ │ │ │ │
                                v v v v v
                              ╭───────────╮
                              │ CmdResult │
                              ╰─────┬─────╯
                                    │
                                    v
                               ╭─────────╮
                               │Formatter│
                               ╰─────────╯
```

**Core types** (all in `src/model.scala`):

| Type | Purpose |
|------|---------|
| `SymbolInfo` | name, kind, file, line, package, parents, signature, annotations |
| `IndexedFile` | path, OID, symbols, bloom filter, imports, aliases |
| `Reference` | file, line, context line, alias info |
| `MemberInfo` | name, kind, line, signature, isOverride, body |
| `HierarchyTree` | recursive tree of parent/child type relationships |
| `OverrideInfo` | file, line, enclosing class, signature |
| `CommandContext` | index + all CLI flags (limit, filters, output mode) |
| `CmdResult` | sealed enum — 25+ variants, one per output shape |

## File Layout

```
src/
├── project.scala              scala-cli directives
├── model.scala                SymbolInfo, CmdResult, CommandContext, all data types
├── cli.scala                  @main, flag parsing, workspace resolution
├── dispatch.scala             command name → handler map
├── command-helpers.scala      filterSymbols, rankSymbols, mkNotFoundWithSuggestions
├── format.scala               render(CmdResult) → text/JSON output
├── extraction.scala           Scalameta AST → SymbolInfo, MemberInfo, BodyInfo
├── index.scala                WorkspaceIndex, git integration, persistence
├── analysis.scala             hierarchy, overrides, deps, diff, ast-pattern
├── commands/                  one file per command (29 files)
│   ├── definition.scala         cmdDef
│   ├── search.scala             cmdSearch
│   ├── refs.scala               cmdRefs
│   ├── graph.scala              cmdGraph
│   └── ...                      25 more
└── graph/                     ASCII graph library (ported from ascii-graphs)
    ├── common.scala             Point, Region, Dimension, Direction
    ├── graph-model.scala        Graph[V], GraphUtils, DiagramToGraphConvertor
    ├── diagram-model.scala      Diagram, DiagramBox, DiagramEdge, EdgeType
    ├── diagram-parser.scala     DiagramParser (box/edge/label parsing)
    ├── layout.scala             GraphLayout entry point
    ├── layout-prefs.scala       LayoutPrefs, RendererPrefs
    ├── layout-cycles.scala      CycleRemover, CycleRemovalResult
    ├── layout-layering.scala    Layering, LayeringCalculator, vertex ordering
    ├── layout-coord.scala       Layouter, VertexInfo, EdgeInfo, PortNudger
    ├── layout-drawing.scala     Drawing, Renderer, Grid, KinkRemover, EdgeElevator
    └── util.scala               Utils, QuadTree, Lens
```

## Graph Subsystem

The `scalex graph` command uses a Sugiyama-style layered layout algorithm ported from [ascii-graphs](https://github.com/scalameta/ascii-graphs). The code lives in `src/graph/` under `package asciiGraph`.

### Render Pipeline

`Graph[V]` → ASCII/Unicode string, via 7 pipeline stages:

<!-- scalex graph --render "Graph->CycleRemover, CycleRemover->LayeringCalculator, LayeringCalculator->LayerOrderingCalculator, LayerOrderingCalculator->Layouter, Layouter->KinkRemover, KinkRemover->EdgeElevator, EdgeElevator->RedundantRowRemover, RedundantRowRemover->Renderer" -->
```
         ┌─────┐
         │Graph│
         └───┬─┘
             │
             v
      ┌────────────┐
      │CycleRemover│
      └──────┬─────┘
             │
             v
   ┌──────────────────┐
   │LayeringCalculator│
   └─────────┬────────┘
             │
             v
 ┌───────────────────────┐
 │LayerOrderingCalculator│
 └───────────┬───────────┘
             │
             v
        ┌────────┐
        │Layouter│
        └───┬────┘
            │
            v
      ┌───────────┐
      │KinkRemover│
      └──────┬────┘
             │
             v
      ┌────────────┐
      │EdgeElevator│
      └─────┬──────┘
            │
            v
  ┌───────────────────┐
  │RedundantRowRemover│
  └──────────┬────────┘
             │
             v
        ┌────────┐
        │Renderer│
        └────────┘
```

| Stage | What it does |
|-------|-------------|
| **CycleRemover** | Removes self-loops, reverses back-edges to produce a DAG |
| **LayeringCalculator** | Assigns vertices to layers by longest-path-to-sink; inserts dummy vertices for multi-layer edges |
| **LayerOrderingCalculator** | Reorders vertices within each layer to minimize edge crossings (barycenter heuristic) |
| **Layouter** | Assigns (row, column) coordinates to every vertex and edge bend point |
| **KinkRemover** | Straightens edges by removing unnecessary bends |
| **EdgeElevator** | Raises horizontal edge segments as high as possible without collisions |
| **RedundantRowRemover** | Deletes rows that contain only vertical edge segments |
| **Renderer** | Draws vertices and edges into a character grid using Unicode or ASCII characters |

### Parse Pipeline

ASCII/Unicode diagram → structured `Diagram` → `Graph[String]`:

<!-- scalex graph --render "ASCII Text->DiagramParser, DiagramParser->BoxParser, DiagramParser->EdgeParser, DiagramParser->LabelParser, DiagramParser->Diagram, Diagram->DiagramToGraphConvertor, DiagramToGraphConvertor->Graph" -->
```
                 ┌──────────┐
                 │ASCII Text│
                 └─────┬────┘
                       │
                       v
                ┌─────────────┐
                │DiagramParser│
                └──┬─┬──────┬┬┘
                   │ │      ││
          ┌────────┘ │      │└──────┐
          │          │      │       │
          v          │      │       │
      ┌───────┐      │      │       │
      │Diagram│      │      │       │
      └───┬───┘      │      │       │
          │          │      │       │
          │          │      └───────┼──┐
          │          └─────────┐    │  │
          v                    │    │  │
   ┌───────────────────────┐   │    │  │
   │DiagramToGraphConvertor│   │    │  │
   └┬──────────────────────┘   │    │  │
    │                          │    │  │
    │           ┌──────────────┘    │  │
    │           │            ┌──────┘  │
    │           │            │         │
    v           v            v         v
 ┌─────┐ ┌───────────┐ ┌──────────┐ ┌─────────┐
 │Graph│ │LabelParser│ │EdgeParser│ │BoxParser│
 └─────┘ └───────────┘ └──────────┘ └─────────┘
```

- **BoxParser** — detects rectangular boxes (`+---+` or `┌───┐` style), handles nesting
- **EdgeParser** — follows edges in both ASCII (`-`, `|`, `+`) and Unicode (`─`, `│`, `┌`) styles
- **LabelParser** — finds `[label]` annotations adjacent to edges
- **DiagramToGraphConvertor** — maps boxes to vertex names, edges to directed pairs

### Round-Trip Type Flow

<!-- scalex graph --render "DiagramParser->Diagram, Diagram->Graph, Graph->CycleRemovalResult, CycleRemovalResult->Layering, Layering->Drawing, Drawing->Renderer, Renderer->String" --horizontal -->
```
┌─────────────┐  ┌───────┐  ┌─────┐  ┌──────────────────┐  ┌────────┐  ┌───────┐  ┌────────┐  ┌──────┐
│             │  │       │  │     │  │                  │  │        │  │       │  │        │  │      │
│DiagramParser├─>│Diagram├─>│Graph├─>│CycleRemovalResult├─>│Layering├─>│Drawing├─>│Renderer├─>│String│
│             │  │       │  │     │  │                  │  │        │  │       │  │        │  │      │
└─────────────┘  └───────┘  └─────┘  └──────────────────┘  └────────┘  └───────┘  └────────┘  └──────┘
```

Render → parse is a round-trip: `Graph` → `String` → `Diagram` → `Graph`.

## Commands

30 commands organized by category:

| Category | Commands |
|----------|----------|
| **Search** | `search`, `def`, `impl`, `refs`, `imports`, `symbols`, `file`, `packages`, `package`, `annotated`, `grep` |
| **Understand** | `members`, `doc`, `body`, `explain`, `overview` |
| **Navigate** | `hierarchy`, `overrides`, `deps`, `context` |
| **Analyze** | `diff`, `ast-pattern`, `tests`, `coverage`, `api`, `summary`, `entrypoints` |
| **Infrastructure** | `index`, `batch`, `graph` |

All commands return `CmdResult` and support `--json` for structured output.
