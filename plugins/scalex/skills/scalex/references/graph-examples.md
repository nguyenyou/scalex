# Graph Command Examples

All examples below are backed by test cases in `tests/graph.test.scala`.

## Render: Simple Edge

```bash
scalex graph --render "A->B"
```
```
 ┌───┐
 │ A │
 └─┬─┘
   │
   v
 ┌───┐
 │ B │
 └───┘
```

Test: `render simple directed graph`

## Render: Chain

```bash
scalex graph --render "A->B, B->C"
```
```
 ┌───┐
 │ A │
 └─┬─┘
   │
   v
 ┌───┐
 │ B │
 └─┬─┘
   │
   v
 ┌───┐
 │ C │
 └───┘
```

Test: `render graph with multiple edges`

## Render: Diamond (Branching + Merging)

```bash
scalex graph --render "A->B, A->C, B->D, C->D"
```
```
  ┌─────┐
  │  A  │
  └┬──┬─┘
   │  │
   │  └──┐
   │     │
   v     v
 ┌───┐ ┌───┐
 │ B │ │ C │
 └──┬┘ └─┬─┘
    │    │
    │ ┌──┘
    │ │
    v v
  ┌─────┐
  │  D  │
  └─────┘
```

Test: `render diamond graph`

## Render: Cycle

Cycles are handled automatically — one edge is reversed to produce a DAG layout.

```bash
scalex graph --render "A->B, B->A"
```
```
 ┌─────┐
 │  B  │
 └─┬───┘
   │ ^
   v │
 ┌───┴─┐
 │  A  │
 └─────┘
```

Test: `render graph with cycle`

## Render: Self-Loop

```bash
scalex graph --render "A->B, A->A"
```
```
    ┌────┐
    v    │
 ┌─────┐ │
 │  A  │ │
 └──┬┬─┘ │
    ││   │
    │└───┘
    v
  ┌───┐
  │ B │
  └───┘
```

Test: `render graph with self-loop`

## Render: Standalone Vertex (No Edges)

```bash
scalex graph --render "Alone"
```
```
 ┌─────┐
 │Alone│
 └─────┘
```

Test: `render singleton vertex`

## Render: Architecture-Style Graph

```bash
scalex graph --render "Controller->Service, Service->Repository, Repository->Database, Controller->Cache, Cache->Database"
```
```
    ┌──────────┐
    │Controller│
    └──┬────┬──┘
       │    │
       │    └────┐
       v         │
   ┌───────┐     │
   │Service│     │
   └───┬───┘     │
       │         │
       v         v
 ┌──────────┐ ┌─────┐
 │Repository│ │Cache│
 └──────┬───┘ └──┬──┘
        │        │
        │  ┌─────┘
        │  │
        v  v
     ┌────────┐
     │Database│
     └────────┘
```

Test: `cmdGraph --render produces output` (verifies the command handler renders correctly)

## Style: ASCII Mode (`--no-unicode`)

```bash
scalex graph --render "A->B" --no-unicode
```
```
 +---+
 | A |
 +---+
   |
   v
 +---+
 | B |
 +---+
```

Test: `render with ASCII mode`

## Style: Rounded Corners (`--rounded`)

```bash
scalex graph --render "A->B" --rounded
```
```
 ╭───╮
 │ A │
 ╰─┬─╯
   │
   v
 ╭───╮
 │ B │
 ╰───╯
```

Test: `render with rounded corners`

## Style: Double Borders (`--double`)

```bash
scalex graph --render "A->B" --double
```
```
 ╔═══╗
 ║ A ║
 ╚═╤═╝
   │
   v
 ╔═══╗
 ║ B ║
 ╚═══╝
```

Test: `render with double borders`

## Style: Horizontal Layout (`--horizontal`)

```bash
scalex graph --render "A->B" --horizontal
```
```
┌─┐  ┌─┐
│ │  │ │
│A├─>│B│
│ │  │ │
└─┘  └─┘
```

Test: `render horizontal layout`

## Parse: Text Output

Pipe rendered output back through `--parse` to extract structure:

```bash
scalex graph --render "A->B, B->C" | scalex graph --parse
```
```
Boxes: A, B, C
Edges:
  B -> C
  A -> B
```

Test: `render then parse round-trip`, `render then parse preserves vertices`

## Parse: JSON Output (`--json`)

```bash
scalex graph --render "A->B, B->C" | scalex graph --parse --json
```
```json
{"boxes":[{"text":"A"},{"text":"B"},{"text":"C"}],"edges":[{"from":"B","to":"C","directed":true},{"from":"A","to":"B","directed":true}]}
```

Test: `diagram to graph conversion`

## Parse: From Existing ASCII Art

Any ASCII or Unicode box diagram can be parsed — it doesn't have to come from `scalex graph --render`:

```bash
cat <<'EOF' | scalex graph --parse
┌───┐
│ A │
└─┬─┘
  │
  v
┌───┐
│ B │
└───┘
EOF
```
```
Boxes: A, B
Edges:
  A -> B
```

Test: `parse simple diagram`, `parse ASCII diagram`, `parse extracts box text`

## Edge List Format

The `--render` input is a comma-separated list of edges and standalone vertices:

| Syntax | Meaning |
|--------|---------|
| `A->B` | Directed edge from A to B |
| `A->B, B->C` | Multiple edges |
| `A->B, C` | Edge A→B plus standalone vertex C |
| `A` | Single vertex, no edges |

## All Flags

| Flag | Default | Effect |
|------|---------|--------|
| `--render <edges>` | — | Render graph from edge list |
| `--parse` | — | Parse ASCII diagram from stdin |
| `--json` | off | JSON output (parse mode) |
| `--unicode` | on | Unicode box-drawing characters |
| `--no-unicode` | off | ASCII characters only (`+`, `-`, `\|`) |
| `--vertical` | on | Top-to-bottom layout |
| `--horizontal` | off | Left-to-right layout |
| `--rounded` | off | Rounded corners (`╭╮╰╯`) |
| `--double` | off | Double-line borders (`╔═╗║╚═╝`) |
