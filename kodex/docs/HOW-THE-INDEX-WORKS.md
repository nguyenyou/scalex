# How the index works

A detailed walkthrough of how kodex turns `.semanticdb` files into a queryable binary index, and how queries execute against it.

## Overview

The index pipeline has four stages:

```
  Discovery → Parsing → Merging → Serialization
       │           │          │           │
  find files    decode     9-phase      rkyv
  in out/      protobuf    build     zero-copy
```

The result is a single file — `.scalex/kodex.idx` — that can be memory-mapped and queried without deserialization. On the scala3 compiler (17k files, 2.3M symbols, 10.8M occurrences), the full pipeline takes ~2-5 seconds and produces a ~600MB index.

## Stage 1: Discovery

**Code**: `src/ingest/discovery.rs`

kodex discovers `.semanticdb` files from Mill's `out/` directory. The search is two-phase:

**Phase 1** — Walk `<root>/out/` up to depth 8, looking for directories named `semanticDbDataDetailed.dest`. For each one found, check if `data/META-INF/semanticdb/` exists underneath:

```
out/
├── modules/
│   ├── billing/billing/jvm/
│   │   └── semanticDbDataDetailed.dest/
│   │       └── data/META-INF/semanticdb/    ← found
│   └── auth/auth/jvm/
│       └── semanticDbDataDetailed.dest/
│           └── data/META-INF/semanticdb/    ← found
└── platform/database/
    └── semanticDbDataDetailed.dest/
        └── data/META-INF/semanticdb/        ← found
```

**Phase 2** — Walk each discovered `semanticdb/` directory in parallel (rayon) collecting all `.semanticdb` files.

**Fallback** — Also checks `out/META-INF/semanticdb/` directly for projects using `-semanticdb-target`.

## Stage 2: Parsing

**Code**: `src/ingest/semanticdb.rs`, `src/ingest/printer.rs`

Each `.semanticdb` file is a protobuf-encoded `TextDocuments` message (defined in `proto/semanticdb.proto`, compiled by prost at build time via `build.rs`). Files are decoded in parallel using rayon.

For each `TextDocument`, kodex extracts two things:

### Symbols → `IntermediateSymbol`

Every `SymbolInformation` in the document becomes an `IntermediateSymbol`. Using the [SemanticDB guide](https://github.com/scalameta/scalameta/blob/main/semanticdb/guide.md)'s `Test.scala` example:

```scala
object Test {
  def main(args: Array[String]): Unit = {
    println("hello world")
  }
}
```

The compiler produces three `SymbolInformation` entries:

```
_empty_/Test.              => final object Test extends AnyRef { +1 decls }
_empty_/Test.main().       => method main(args: Array[String]): Unit
_empty_/Test.main().(args) => param args: Array[String]
```

For each one, kodex extracts:

| Field | Source | Example (`main`) |
|---|---|---|
| `fqn` | `SymbolInformation.symbol` | `_empty_/Test.main().` |
| `display_name` | `SymbolInformation.display_name` | `main` |
| `kind` | `SymbolInformation.kind` (enum → `SymbolKind`) | `Method` |
| `properties` | `SymbolInformation.properties` (bitmask) | `0` |
| `owner` | Computed from FQN by `symbol_owner()` | `_empty_/Test.` |
| `signature` | Pretty-printed by `printer::print_info()` | `method main(args: Array[String]): Unit` |
| `parents` | Extracted from `ClassSignature.parents` | `[]` (methods have none) |
| `overridden_symbols` | `SymbolInformation.overridden_symbols` | `[]` |
| `access` | `SymbolInformation.access` (oneof → `Access` enum) | `Public` |

### Type signature rendering

The `signature` field deserves special attention. In SemanticDB, signatures are a deeply nested protobuf tree — a `MethodSignature` contains `Scope` (type parameters), multiple `Scope` (parameter lists, each with `SymbolInformation` hardlinks or symlinks), and a `Type` (return type). The `Type` itself is a 16-variant oneof (`TypeRef`, `SingleType`, `IntersectionType`, `UnionType`, `ByNameType`, `RepeatedType`, `MatchType`, `LambdaType`, etc.).

Rather than storing this tree in the index and paying deserialization cost at query time, `printer.rs` renders it to a human-readable string at index time. The printer (`InfoPrinter`) walks the protobuf tree and emits text:

```
MethodSignature { params: [args: Array[String]], returnType: Unit }
    → "method main(args: Array[String]): Unit"

ClassSignature { parents: [AnyRef], decls: 1 }
    → "final object Test extends AnyRef { +1 decls }"
```

The printer resolves symlinks (symbol references within the same document) using a local `symtab` built per-document. This means `Array[String]` shows up as readable names, not FQN-encoded types.

### Occurrences → `IntermediateOccurrence`

Every `SymbolOccurrence` in the document becomes an `IntermediateOccurrence`:

```
[0:7..0:11)  <= _empty_/Test.                 ← DEFINITION
[1:6..1:10)  <= _empty_/Test.main().          ← DEFINITION
[1:11..1:15) <= _empty_/Test.main().(args)    ← DEFINITION
[1:17..1:22) => scala/Array#                  ← REFERENCE
[1:23..1:29) => scala/Predef.String#          ← REFERENCE
[1:33..1:37) => scala/Unit#                   ← REFERENCE
[2:4..2:11)  => scala/Predef.println(+1).     ← REFERENCE
```

Each one stores: file URI, symbol FQN, role (Definition/Reference), and the range (start_line, start_col, end_line, end_col).

### FQN owner extraction

**Code**: `src/symbol.rs`

The `owner` of a symbol is computed purely from its FQN string — no tree traversal needed. SemanticDB FQNs use a structured format where `/` separates packages, `#` separates class members, and `.` separates object members:

```
_empty_/Test.main().       → owner: _empty_/Test.
_empty_/Test.              → owner: _empty_/
_empty_/Test.main().(args) → owner: _empty_/Test.main().
```

The algorithm strips the trailing descriptor (`.`, `#`, `/`, or `().`), then scans backwards to the previous separator character.

## Stage 3: Merging (9 phases)

**Code**: `src/ingest/merge.rs`

This is the core of index construction. All `IntermediateDoc` values are merged into a single `KodexIndex` through 9 sequential phases.

### String interning

Before anything else, a string intern table is created. Every string in the index (file paths, symbol names, FQNs, type signatures) is stored exactly once in a `Vec<String>`. All other fields reference strings by `StringId` (a `u32` index). This massively reduces memory and serialized size — a FQN like `com/example/billing/BillingService#processPayment().` appears once regardless of how many symbols and references mention it.

```
strings[0] = "modules/billing/src/BillingService.scala"
strings[1] = "processPayment"
strings[2] = "com/example/billing/BillingService#processPayment()."
strings[3] = "method processPayment(invoice: Invoice): ZIO[Any, Throwable, Receipt]"
...
```

### Phase 1: File registration

Iterate all documents. For each unique URI, assign a sequential `file_id` and create a `FileEntry`:

```rust
FileEntry {
    path: StringId,       // interned file path
    module_id: u32,       // which Mill module (or u32::MAX)
    is_test: bool,        // pre-classified by path patterns
    is_generated: bool,   // pre-classified by path patterns
}
```

**Test classification** — a file is a test if its path contains `/test/`, `/tests/`, `/it/`, `/spec/`, or ends with `test.scala`, `spec.scala`, `suite.scala`, `integ.scala`.

**Generated classification** — a file is generated if its path contains `compilescalapb.dest`, `compilepb.dest`, `/generated/`, `/src_managed/`, or ends with `.pb.scala`, `grpc.scala`, `buildinfo.scala`.

**Module detection** — the Mill module name is extracted from the URI path by skipping common prefixes (`modules/`, `platform/`, `services/`) and taking the next meaningful segment. For example, `modules/billing/billing/jvm/src/...` → module `billing`.

### Phase 2: Symbol registration

Iterate all documents again. For each symbol with a unique FQN, assign a sequential `symbol_id` and create a `Symbol`:

```rust
Symbol {
    id: u32,
    name: StringId,              // display name, e.g. "main"
    fqn: StringId,               // fully qualified, e.g. "_empty_/Test.main()."
    kind: SymbolKind,            // Method, Class, Trait, Object, Field, ...
    file_id: u32,                // which file this symbol is defined in
    line: u32,                   // definition line (from DEFINITION occurrence)
    col: u32,                    // definition column
    type_signature: StringId,    // pre-rendered string
    owner: u32,                  // resolved in phase 3 (u32::MAX for now)
    properties: u32,             // bitmask: abstract, final, sealed, case, ...
    access: Access,              // Public, Private, Protected, ...
    parents: Vec<StringId>,      // parent type FQNs (interned)
    overridden_symbols: Vec<StringId>,  // overridden method FQNs (interned)
}
```

The definition location (line, col) comes from scanning the document's occurrences for a `DEFINITION` occurrence matching this symbol's FQN. If no definition occurrence exists (external symbols), line/col default to 0.

Deduplication: if a symbol FQN already appeared in a previous document, it's skipped. This handles the case where the same source file produces multiple `.semanticdb` files (e.g., cross-compilation).

### Phase 3: Owner resolution

Now that all symbols have IDs, resolve the `owner` field. For each symbol, compute the owner FQN from the symbol's FQN string, then look it up in `sym_map`:

```
symbol: _empty_/Test.main().
  owner FQN: _empty_/Test.
  owner ID:  sym_map["_empty_/Test."] → 0

symbol: _empty_/Test.
  owner FQN: _empty_/
  owner ID:  (not in index — stays u32::MAX)
```

### Phase 4: References index

Iterate all occurrences across all documents. For each occurrence whose symbol FQN exists in `sym_map`, create a `Reference` and group by `symbol_id`:

```rust
ReferenceList {
    symbol_id: u32,
    refs: Vec<Reference>,   // each: { file_id, line, col, role }
}
```

For the `Test.scala` example, `scala/Predef.println(+1).` would get a reference entry at (file_id=0, line=2, col=4, role=Reference). If println were defined in this project, it would also have a Definition reference elsewhere.

### Phase 5: Inheritance index

For each symbol, iterate its stored `parent_fqns`. If the parent FQN exists in `sym_map` (i.e., it's an in-project symbol, not external like `java/lang/Object#`), create bidirectional edges:

```
inheritance_forward[parent_id] → [child_id, ...]   "who extends me?"
inheritance_reverse[child_id]  → [parent_id, ...]   "what do I extend?"
```

External parents (stdlib, third-party) are stored on the symbol's `parents` field as `StringId`s but don't appear in the edge lists.

### Phase 6: Members index

For each symbol whose `owner != u32::MAX`, add an edge:

```
members[owner_id] → [member_id, ...]
```

This gives O(1) "what members does this class have?" queries. For `Test`:

```
members[Test] → [main]
```

### Phase 7: Overrides index

For each symbol, iterate its `overridden_symbols` FQNs. If the base FQN exists in `sym_map`, add an edge:

```
overrides[base_id] → [overrider_id, ...]
```

This answers "who overrides this method?". For a trait method `Vehicle#start()` overridden by `Car#start()` and `Truck#start()`:

```
overrides[Vehicle#start] → [Car#start, Truck#start]
```

### Phase 8: Call graph

This is the most complex phase. SemanticDB doesn't contain a call graph — kodex builds it from occurrences using a body-range heuristic.

**Step 1** — Group all occurrences by file URI.

**Step 2** — For each file, collect all DEFINITION occurrences of methods, constructors, and fields (skipping `local*` definitions). Sort by line number:

```
File: Car.scala

  line 7:  class Car(engine: Engine) extends Vehicle {
  line 10:   def start() =         ← DefInfo { sid=42, owner=Car, start_line=10 }
  line 13:   def stop() =          ← DefInfo { sid=43, owner=Car, start_line=13 }
```

**Step 3** — Compute body ranges. For each definition, its body extends from its definition line to the start line of the **next sibling definition with the same owner**. If there's no next sibling, the body extends to the end of the file (u32::MAX):

```
  start's body: line 10 → line 13 (next sibling stop)
  stop's body:  line 13 → u32::MAX (last sibling)
```

The "same owner" constraint is critical — nested definitions inside a method's body (local classes, lambdas) don't end the enclosing method's body range. Only siblings at the same level do.

**Step 4** — For each REFERENCE occurrence, find the enclosing definition. The enclosing def is the one whose `start_line <= ref_line < body_end`, scanning in reverse. On the definition line itself, only references after the def's end column count (to handle single-line methods like `def x = foo.bar`):

```
  line 11: engine.ignite()    ← REFERENCE to Engine#ignite
    enclosing def: start (line 10..13)
    → edge: start calls ignite
```

**Step 5** — Record bidirectional edges, excluding self-calls:

```
call_graph_forward[start] → [ignite]       "start calls ignite"
call_graph_reverse[ignite] → [start]        "ignite is called by start"
```

### Edge list finalization

All six edge maps (call_fwd, call_rev, inh_fwd, inh_rev, members, overrides) go through the same finalization:

1. **Dedup** — sort each `to` list and remove duplicates (a method may reference the same callee multiple times)
2. **Sort by `from`** — sort the outer list by the `from` ID so queries can use binary search

```rust
EdgeList { from: u32, to: Vec<u32> }

// Sorted by `from`:
[
    EdgeList { from: 5,  to: [12, 30] },
    EdgeList { from: 42, to: [10, 15, 99] },    ← binary search finds this
    EdgeList { from: 107, to: [42, 200] },
]
```

### Phase 9: Name indexes

Two indexes are built for fast symbol resolution at query time:

#### Trigram index

For each symbol, extract all 3-character sliding windows (trigrams) from both the display name and the last FQN segment, lowercased:

```
"processPayment" → ["pro", "roc", "oce", "ces", "ess", "ssP", "sPa", "Pay", "aym", "yme", "men", "ent"]
                    (lowercased: ["pro", "roc", "oce", "ces", "ess", "ssp", "spa", "pay", "aym", "yme", "men", "ent"])
```

Each trigram is packed into a `u32` key (3 bytes, little-endian): `byte0 | byte1<<8 | byte2<<16`.

The result is a sorted list of `TrigramEntry { key: u32, symbol_ids: Vec<u32> }`. Sorted by key so queries can use binary search on the posting lists.

#### Hash index

A simple hash-map-style index for O(1) exact display name lookup. Each symbol's display name is hashed (case-insensitive, multiply-and-add hash with factor 31) into one of `N` buckets where `N = max(symbol_count / 4, 1024)`:

```rust
HashBucket { symbol_ids: Vec<u32> }

// bucket_count = 350000 (for 1.4M symbols)
// hash("main") % 350000 = 12345
// name_hash_buckets[12345].symbol_ids = [0, 4521, 89102, ...]
```

## Stage 4: Serialization

**Code**: `src/index/writer.rs`

The `KodexIndex` is serialized using [rkyv](https://rkyv.org) — a zero-copy deserialization framework. rkyv writes the in-memory struct layout directly to bytes, with pointer fixups so the file can be `mmap`ed and used as-is without any parsing or allocation.

The write is atomic: write to `kodex.idx.tmp`, then rename to `kodex.idx`.

## Reading the index

**Code**: `src/index/reader.rs`

`IndexReader` opens the file with `mmap` (via `memmap2`), then casts the mapped memory directly to `&ArchivedKodexIndex`:

```rust
let mmap = unsafe { Mmap::map(&file) };
let index: &ArchivedKodexIndex = unsafe {
    access::<ArchivedKodexIndex, _>(&mmap)
};
```

The `ArchivedKodexIndex` is rkyv's "archived" (zero-copy) view of the data. All fields are directly readable from the mapped memory — `Vec` becomes a relative-pointer slice, `String` becomes a length-prefixed byte slice, `u32` becomes `u32_le` (little-endian). No heap allocation occurs.

A version check ensures the index was written by a compatible kodex version (currently `KODEX_INDEX_VERSION = 3`).

The OS pages in only the parts of the file actually accessed. A `kodex callers main` on a 600MB index might touch 10KB — the string table entry for "main", the hash bucket, one or two edge lists, and the referenced symbol records.

## Query execution

### Symbol resolution

**Code**: `src/query/symbol.rs`

When you type `kodex callers main`, kodex needs to map the string `"main"` to one or more symbol IDs. It tries four strategies in order:

**1. Exact FQN match** — if the query looks like an FQN (contains `/`), use trigram index to narrow candidates, then check for exact match:

```
query: "_empty_/Test.main()."
trigrams: ["_em", "emp", "mpt", "pty", "ty_", "y_/", "_/T", "/Te", "Tes", "est", "st.", "t.m", ".ma", "mai", "ain", "in(", "n()"]
→ intersect posting lists → candidates
→ exact match on FQN string → found
```

**2. FQN suffix match** — if no exact FQN match, check if query matches the end of any FQN:

```
query: "Test.main()."
→ any FQN ending with "Test.main()."? → yes
```

**3. Exact display name match (hash index)** — O(1) lookup:

```
query: "main"
hash("main") % bucket_count → bucket 12345
→ scan bucket: check each symbol's display name (lowercased) against "main"
→ [_empty_/Test.main()., com/example/App.main()., ...]
```

**4. Substring match (trigram index)** — for partial name queries:

```
query: "Payment"
trigrams: ["pay", "aym", "yme", "men", "ent"]
→ intersect posting lists → candidate symbol IDs
→ verify: display_name.contains("payment") (case-insensitive)
→ [processPayment, validatePayment, PaymentService, ...]
```

For queries shorter than 3 characters, trigrams can't help — kodex falls back to a linear scan of all symbols.

### Edge lookup

**Code**: `src/query/symbol.rs` — `edges_from()`

All edge lists are sorted by `from` ID at build time. Looking up edges for a symbol is a binary search:

```rust
fn edges_from(edge_lists: &[ArchivedEdgeList], from_id: u32) -> Vec<u32> {
    match edge_lists.binary_search_by_key(&from_id, |el| el.from.into()) {
        Ok(idx) => edge_lists[idx].to.iter().collect(),
        Err(_) => vec![],
    }
}
```

For the `callers` command, the lookup is:

```
kodex callers main
  1. resolve "main" → symbol_id 42
  2. edges_from(call_graph_reverse, 42) → [10, 15, 99]
  3. for each caller ID, look up symbol and format
```

The `callers` command is also **trait-aware**: if the target symbol overrides a trait method, it also looks up callers of the base trait method. Code that calls `vehicle.start()` (referencing `Vehicle#start`) is included in the callers of `Car#start`.

### Noise filtering

**Code**: `src/query/filter.rs`

Several commands (`explore`, `flow`, `impact`) filter out noise by default:

- **Standard library** — symbols with FQNs starting with `scala/`, `java/lang/`, etc.
- **Test files** — using the pre-classified `is_test` flag on `FileEntry`
- **Generated code** — using the pre-classified `is_generated` flag
- **Plumbing methods** — `apply`, `map`, `flatMap`, `toString`, `hashCode`, etc.
- **Val/var accessors** — dependency reads, not real method calls
- **Case class synthetics** — `_1`, `_2`, `$default$`, etc.

Users can add custom exclusions with `--exclude pattern`, which does substring matching on FQN, name, and owner name.

## The data model

For reference, here is the complete `KodexIndex` structure:

```
KodexIndex
├── version: u32                              // KODEX_INDEX_VERSION = 3
├── strings: Vec<String>                      // deduplicated string table
├── files: Vec<FileEntry>                     // source files
│   └── { path, module_id, is_test, is_generated }
├── symbols: Vec<Symbol>                      // all definitions
│   └── { id, name, fqn, kind, file_id, line, col,
│          type_signature, owner, properties, access,
│          parents, overridden_symbols }
├── references: Vec<ReferenceList>            // symbol → [locations]
│   └── { symbol_id, refs: [{ file_id, line, col, role }] }
├── call_graph_forward: Vec<EdgeList>         // caller → [callees]
├── call_graph_reverse: Vec<EdgeList>         // callee → [callers]
├── inheritance_forward: Vec<EdgeList>        // parent → [children]
├── inheritance_reverse: Vec<EdgeList>        // child → [parents]
├── members: Vec<EdgeList>                    // owner → [members]
├── overrides: Vec<EdgeList>                  // base → [overriders]
├── modules: Vec<MillModule>                  // Mill module metadata
│   └── { name, source_paths, file_count, symbol_count }
├── name_trigrams: Vec<TrigramEntry>          // trigram → [symbol_ids]
│   └── { key: u32, symbol_ids: Vec<u32> }
├── name_hash_buckets: Vec<HashBucket>        // hash bucket → [symbol_ids]
│   └── { symbol_ids: Vec<u32> }
└── name_hash_size: u32                       // number of hash buckets
```

All `StringId` fields (`name`, `fqn`, `type_signature`, `path`) are indices into the `strings` table. All edge lists are sorted by `from` for binary search. All symbol IDs are indices into the `symbols` vector.
