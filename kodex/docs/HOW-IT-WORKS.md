# How kodex works

## 1. The source of truth: SemanticDB

When the Scala compiler runs with `-Xsemanticdb`, it emits a `.semanticdb` file for each `.scala` file it compiles:

```
src/Vehicle.scala          out/.../Vehicle.scala.semanticdb
src/Car.scala         ──►  out/.../Car.scala.semanticdb
src/Engine.scala           out/.../Engine.scala.semanticdb
```

Each `.semanticdb` file is a protobuf `TextDocument` containing two things:

### Symbols — what the compiler resolved

Every class, trait, object, method, field, type, enum, given, parameter, and type parameter gets a `SymbolInformation` entry:

```
SymbolInformation {
  symbol:     "com/example/Car#start()."           ← fully qualified, unambiguous
  kind:       METHOD
  properties: 0x400000                              ← bitmask: OVERRIDE
  displayName: "start"
  signature:  MethodSignature {                     ← structured protobuf, not a string
    params: []
    returnType: TypeRef → Unit
  }
  overriddenSymbols: ["com/example/Vehicle#start()."]  ← what trait methods this overrides
  access:     PUBLIC
}
```

Key: the compiler resolved every type reference, every overload, every implicit. There's no ambiguity — `Car.apply()` (companion) and `Car#apply()` (class) are different symbols with different FQNs.

### Occurrences — where every symbol is used

Every mention of every symbol in source gets a `SymbolOccurrence`:

```
SymbolOccurrence { range: [11:6..11:20], symbol: "com/example/Engine#ignite().", role: REFERENCE }
SymbolOccurrence { range: [10:8..10:13], symbol: "com/example/Car#start().",    role: DEFINITION }
```

This is how kodex knows that line 11 of `Car.scala` calls `engine.ignite()` — the compiler told us, with zero false positives.

### What SemanticDB does NOT contain

- **Source code** — the `.semanticdb` file has positions (line:col) but not the actual text
- **Comments / scaladoc** — stripped during compilation
- **Method bodies** — only the signature structure, not the implementation logic
- **Build dependencies** — no classpath, no dependency graph

This is why kodex stores file paths but not file contents — if you need source code, you read the `.scala` file directly.

## 2. What kodex builds from SemanticDB

kodex reads all `.semanticdb` files in parallel, then computes relationships that aren't directly in SemanticDB:

```
  17,000 .semanticdb files
         │
         │  prost decode (parallel, rayon)
         ▼
  Raw data: 2.3M symbols + 10.8M occurrences
         │
         │  merge + compute relationships
         ▼
  kodex.idx (single file, rkyv zero-copy)
```

### What's stored directly from SemanticDB

| SemanticDB field | kodex index field |
|---|---|
| `SymbolInformation.symbol` | `symbol.fqn` |
| `SymbolInformation.displayName` | `symbol.name` |
| `SymbolInformation.kind` | `symbol.kind` |
| `SymbolInformation.properties` | `symbol.properties` |
| `SymbolInformation.access` | `symbol.access` |
| `SymbolInformation.overriddenSymbols` | `symbol.overridden_symbols` |
| `ClassSignature.parents` | `symbol.parents` |
| `TextDocument.uri` | `file.path` |
| `SymbolOccurrence` (all of them) | `references[]` |

### What's pre-rendered at index time

| SemanticDB field | kodex index field | Why |
|---|---|---|
| `SymbolInformation.signature` (protobuf) | `symbol.type_signature` (string) | The signature is a nested protobuf tree (16 type variants, 4 signature variants). kodex renders it to a human-readable string like `method start(): Unit` at index time, so query time is just a string lookup. |

### What's computed at index time (not in SemanticDB)

These relationships are derived from the raw symbols and occurrences:

```
                    ┌─────────────────────────┐
                    │   Raw SemanticDB data    │
                    │   symbols + occurrences  │
                    └────────┬────────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        ▼                    ▼                     ▼
  ┌──────────┐       ┌──────────────┐      ┌──────────────┐
  │ call     │       │ inheritance  │      │ members      │
  │ graph    │       │ tree         │      │ index        │
  └──────────┘       └──────────────┘      └──────────────┘
  From: occurrences  From: parents[]       From: owner chain
  "line 11 has a     "Car extends          "start's owner
   REFERENCE to       Vehicle" → Vehicle    is Car"
   engine.ignite,     is parent of Car     → start is a
   and line 11 is                           member of Car
   inside start's
   body"
  → start calls
    ignite
```

#### Call graph (forward + reverse)

SemanticDB doesn't have a call graph. kodex builds it from occurrences:

1. For each file, find all DEFINITION occurrences of methods/constructors/fields
2. For each definition, compute its body range (from its line to the next sibling definition with the same owner)
3. Every REFERENCE occurrence within that body range is a callee

```
File: Car.scala

  line 7:  class Car(engine: Engine) extends Vehicle {
  line 10:   def start() =         ← DEFINITION of start
  line 11:     engine.ignite()     ← REFERENCE to ignite (inside start's body)
  line 13:   def stop() =          ← DEFINITION of stop (next sibling → start's body ends)
  line 14:     engine.shutdown()   ← REFERENCE to shutdown (inside stop's body)

  Result: start calls [ignite]
          stop calls [shutdown]
```

This is stored as two sorted edge lists:
- `call_graph_forward[start] → [ignite]`
- `call_graph_reverse[ignite] → [start]`

#### Inheritance tree (forward + reverse)

Built from `ClassSignature.parents`:

```
SemanticDB says: Car's parents = [Vehicle#, java/lang/Object#]

  inheritance_forward[Vehicle] → [Car, Truck, Bike]  "who extends Vehicle?"
  inheritance_reverse[Car]     → [Vehicle, Object]   "what does Car extend?"
```

#### Members index

Built from the FQN owner chain:

```
  symbol_owner("com/example/Car#start().") = "com/example/Car#"

  members[Car] → [start, stop, engine]
```

#### Overrides index

Built from `SymbolInformation.overriddenSymbols`:

```
  SemanticDB says: Car#start overrides Vehicle#start

  overrides[Vehicle#start] → [Car#start, Truck#start, Bike#start]
```

### What's computed for search performance

| Index | Purpose | How it's built |
|---|---|---|
| `name_trigrams[]` | Fast substring search | For each symbol name, extract 3-char sliding windows → posting lists |
| `name_hash_buckets[]` | O(1) exact name lookup | Hash each display name → bucket of symbol IDs |
| `file.is_test` | Noise filtering | Path pattern matching at index time |
| `file.is_generated` | Noise filtering | Path pattern matching at index time |
| `file.module_id` | Module annotations | Inferred from `out/` directory structure |

## 3. The index format: rkyv zero-copy

The index is serialized with [rkyv](https://rkyv.org), a zero-copy deserialization framework. This means:

```
                   Traditional approach
  ┌──────────┐     read      ┌──────────┐    deserialize    ┌──────────┐
  │   disk   │ ──────────►   │  buffer  │ ──────────────►   │  structs │
  │  (file)  │               │  (bytes) │                   │  (heap)  │
  └──────────┘               └──────────┘                   └──────────┘
                                                  ~1.5s for 372MB


                      rkyv approach
  ┌──────────┐     mmap      ┌──────────────────────────────────────────┐
  │   disk   │ ──────────►   │  The file IS the in-memory structure     │
  │  (file)  │               │  Cast a pointer and start reading        │
  └──────────┘               │  No allocation. No copying. No parsing.  │
                             └──────────────────────────────────────────┘
                                                  ~0ms
```

The OS pages in only the parts of the file that are actually accessed per query. A `kodex callers start` might touch 10KB of the 600MB file — the rest stays on disk.

## 4. Query execution

Every query follows the same pattern:

```
  1. mmap kodex.idx                          (~0ms, OS does lazy paging)
  2. Resolve symbol name → symbol ID         (~0ms, hash bucket or trigram intersection)
  3. Look up edges / references              (~0ms, binary search on sorted edge lists)
  4. Format and print                        (~0ms)
  ─────────────────────────────────────────
  Total: ~60ms (dominated by mmap page faults, not computation)
```

### Symbol resolution

When you type `kodex callers start`, kodex needs to find which of the 1.4M symbols you mean:

1. **Hash index** (O(1)): hash "start" → bucket → check display names in bucket → exact matches
2. **Trigram index** (O(log n)): extract trigrams ["sta","tar","art"] → binary search posting lists → intersect → candidate set → verify
3. **Linear fallback**: for very short queries (< 3 chars) or FQN queries with `/` separators

### Edge lookup

All edge lists (call graph, inheritance, members, overrides) are sorted by `from` ID at build time. Query uses binary search:

```
  call_graph_reverse = [
    { from: 42,  to: [10, 15, 99] },
    { from: 107, to: [42, 200] },     ← binary search finds this
    { from: 200, to: [42] },
  ]

  edges_from(call_graph_reverse, 107) → [42, 200]
```

## 5. What's NOT in the index (yet)

| Missing | Why | Future plan |
|---|---|---|
| Source code / method bodies | Keeps index lean (~600MB vs ~3GB with source) | `body` command: store byte offsets, `seek+read` from `.scala` at query time |
| Scaladoc comments | Not in SemanticDB | Same approach: store byte offsets in source files |
| Module dependency graph | Requires parsing `build.mill` | Parse Mill build file at index time |
| Trigram index over file contents | Would enable `kodex search "engine ignite"` | Build trigram posting lists from source text |
| Sorted string table | Would enable binary search on FQN lookup | Sort + rewrite all StringIds at build time |
