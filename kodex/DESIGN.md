# Kodex Design Plan

**Knowledge exploration tool for coding agents. Index once, query in microseconds.**

From *codex* (Latin: a book of knowledge) + *k* for knowledge + *index*. A codex was the first book format to replace scrolls — organized, indexed, random-access. That's what kodex does for codebases: turns a pile of source files into a queryable book of knowledge that agents can navigate instantly.

## The Problem

Coding agents explore codebases through raw file reads and grep. They lack structured tools to answer questions like *"how does user creation work?"* or *"what implements the permission system?"*. A real exploration of a 13.7k-file Scala codebase showed:

- **scalex alone**: 15 tool calls, ~8000 tokens consumed, high mental assembly
- **sdbex alone**: 5 tool calls, ~2000 tokens, but missing source bodies and docs
- **Both combined**: 6 tool calls, ~2500 tokens, still too many round trips

**kodex target: 2 calls, ~2000 tokens, complete answer.**

### What makes agent exploration different from IDE exploration

| IDE (human) | Agent |
|---|---|
| Opens files, scrolls, reads visually | Needs structured API to query symbols |
| Follows intuition ("this probably calls that") | Needs explicit call graphs and usage traces |
| Can skim 1000-line files in seconds | Context window is limited — needs precise extracts |
| Has the project's mental model | Starts cold every conversation — needs fast orientation |
| Clicks "go to definition" one at a time | Wants batch queries — explore 5 types in one call |

### What Metals MCP (16 tools) still lacks

| Missing | Why it matters for agents |
|---|---|
| Source body reading | Agent can't see implementation logic, only signatures |
| Call hierarchy | "What does `createUser` call internally?" requires manual chaining |
| Type hierarchy | "What implements `Repository`?" requires search + filter |
| Batch exploration | Inspect 5 types = 5 separate tool calls |
| Noise filtering | Every search on a 13k-file project returns hundreds of hits |
| Module-scoped queries | Long `--path modules/order/order/jvm/src` strings |

## Model

kodex operates on a **static snapshot**. The workflow is:

```
Clone (depth=1) → Compile SemanticDB → Index once → Query until done
```

No incremental reindexing, no file watching, no dirty buffers. The codebase is frozen at the cloned commit. This is deliberate — kodex is a **knowledge extraction tool**, not a development tool.

This means:
- **Index once, query many.** Index build (~2-5s) happens once. Every subsequent query is <1ms.
- **No staleness.** The index perfectly matches the source.
- **Deterministic.** Same commit always produces same answers.
- **Disposable.** Clone, extract knowledge, throw away the clone.

## Design Principles

1. **One question = one answer.** Each command answers a complete agent question. No chaining 5 calls to understand one type.
2. **Agent-first output.** Concise, structured, no noise. Every line earns its place in the context window.
3. **Token budgets.** Every output section has a budget. Default `explore` output: ~1800 tokens (vs ~8000 with raw tools).
4. **Smart defaults.** Exclude tests, generated code, stdlib, effect plumbing. `--all` to override.
5. **Source included.** Bodies, not just signatures. Agents need to read logic, not just API surfaces.
6. **Mill-native.** Understands Mill module structure, dependency graph, and SemanticDB output layout.

## Architecture

```
                         INDEX ONCE (~2-5s)
  ┌─────────────────────────────────────────────────┐
  │              Rust Indexer Binary                 │
  │                                                 │
  │  .semanticdb (protobuf)  ──► prost decode       │
  │  .scala (source files)   ──► read raw text      │
  │  Mill build.mill         ──► module metadata     │
  │                                 │               │
  │                    merge + denormalize + filter   │
  │                                 │               │
  │                          rkyv serialize          │
  │                                 ▼               │
  │                           kodex.idx             │
  └─────────────────────────────────────────────────┘
                              │
                              │  mmap
                              ▼
  ┌─────────────────────────────────────────────────┐
  │             Rust Querier Binary                  │
  │                                                 │
  │  mmap("kodex.idx") ──► zero-copy access         │
  │  <1ms per query, no deserialization              │
  │  Composite commands combine multiple lookups     │
  │  Source reads on demand (scaladoc, bodies)        │
  │  no JVM, no daemon, no startup cost              │
  └─────────────────────────────────────────────────┘

                   QUERY MANY TIMES (<1ms each)
```

### Why no parser at all?

- **SemanticDB** (from compilation) provides everything semantic: qualified names, types, references, call graphs, overrides
- **Raw source files** (`seek + read`) provide everything textual: scaladoc, method bodies, surrounding lines

No AST parser is needed. The compiler already parsed everything — SemanticDB is the proof.

### Why Rust, not JVM?

| | JVM (current sdbex) | Rust (kodex) |
|---|---|---|
| Startup | ~200-500ms | ~0ms |
| Query (cold) | ~3.2s (load index) | <1ms (mmap, no load) |
| Query (warm) | ~10ms (daemon) | <1ms (no daemon needed) |
| Memory | ~200MB heap | ~0 (OS pages in/out as needed) |
| Daemon needed | Yes (amortize startup) | No |
| Zombie risk | Yes (7 defensive layers in sdbex) | No (no long-running process) |
| Binary size | ~30MB assembly JAR | ~5MB static binary |

The daemon pattern in sdbex exists solely to work around JVM startup. With Rust + mmap, the problem disappears entirely.

## Commands

### Exploration workflow

```
ORIENT  → "what is this project?"
EXPLORE → "tell me everything about X"
FLOW    → "what does X call?"
IMPACT  → "what breaks if I change X?"
TRACE   → "how does A reach B?"
SEARCH  → "find anything related to X"
BODY    → "show me the source code"
```

---

### `kodex orient`

First command on any codebase. Returns the complete project map.

```bash
kodex orient
```

```
Project: my-app (Scala 3.3.4)

Modules (5):
  core        — 45 files, 312 symbols
  api         — 23 files, 156 symbols
  persistence — 18 files, 98 symbols
  auth        — 12 files, 67 symbols
  common      — 8 files, 41 symbols

Module graph:
  api → core → persistence
  api → auth → common
  core → common

Hub types (most referenced):
  trait UserService          — 25 refs, 3 impls
  trait PermissionChecker    — 18 refs, 2 impls
  class AppConfig            — 15 refs

Entry points:
  @main def run              — api/src/.../Main.scala:5

Dependencies: cats-effect 3.5.2, tapir 1.9.0, quill 4.8.0, ...
```

**Data sources**: index metadata + Mill module graph + symbol ref counts.

---

### `kodex explore <symbol>`

The workhorse command. Complete picture of a type or method in **one call**. Replaces 5-6 individual scalex/sdbex calls.

```bash
kodex explore UserService
kodex explore UserService --body           # inline method bodies
kodex explore UserService --expand-impls   # show implementation members
kodex explore UserService --depth 2        # 2 levels of callers
```

```
trait UserService (com.example.users) — src/.../UserService.scala:12

/** Service for user lifecycle management. */

Members:
  def createUser(req: CreateUserRequest): Future[User]
  def findUser(id: UserId): Future[Option[User]]
  def deleteUser(id: UserId): Future[Unit]

Implementations:
  class UserServiceLive — src/.../UserServiceLive.scala:25
    constructor: (repo: UserRepository, email: EmailService)

Callers of createUser (3):
  UserController.postUser        [API endpoint]     — api/src/.../UserController.scala:45
  AdminController.bulkCreate                        — api/src/.../AdminController.scala:78
  UserServiceSpec."creates user"                    — test/.../UserServiceSpec.scala:23

Imported by: 8 files

Related types:
  CreateUserRequest (case class): name: String, email: String, role: Role
  UserId (opaque type)
  UserRepository (trait, 2 impls)
```

For methods, the output includes the **call flow** (callees) as a structured numbered list — this replaces reading the raw method body:

```bash
kodex explore OrderService.createOrder
```

```
method createOrder — OrderService
  File: modules/order/.../OrderService.scala:123
  Signature: (params: CreateOrderParams, ctx: RequestContext): Task[CreateOrderResponse]

  Params (from CreateOrderParams):
    name: String
    orgId: OrgId
    metadata: Option[MetadataParams]
    showIndex: Boolean
    showSearch: Boolean

  Service calls (19):
    1. PermissionService.verifyMember [auth]                          — cross-module
    2. OrderBillingOperations.checkEntityHavingOrderPremiumFeatures
    3. TeamService.createNewTeam [auth]                          — cross-module
    4. TeamService.addMember [auth]                              — cross-module
    5. OrderFlowOperations.createOrder
    6. FileService.createSystemFolderForChannel [document]           — cross-module
    7. OrderEmailSenderService.sendEmail
    ...

  Called by (production):
    OrderCoreServer.services [API endpoint]
    OrderAdminPortalService.createOrderOnBehalfUnsafe
    OrderAgentServiceLive.createOrder [AI agent]
    OrderService.duplicateOrder

  Related types:
    OrderPlan (sealed trait):
      OrderProPlan       — ViewOnly, Analytics, AccessControl, Branding
      OrderBusinessPlan  — all features
      OrderEmptyPlan     — free, no premium features
    OrderPremiumFeature (enum):
      ViewOnly, Analytics, AccessControl, Branding, HomePage, CustomDomain
    OrderRole (enum):
      Admin, Member, Guest, Restricted
```

**One call. ~1800 tokens. Complete answer.** Compare: scalex alone consumes ~8000 tokens for the same information across 15 calls.

---

### `kodex flow <symbol> [--depth N]`

Downstream call tree. Shows what happens when a method is called. Module boundaries annotated.

```bash
kodex flow UserController.postUser --depth 3
```

```
UserController.postUser [api]
├── AuthMiddleware.authenticate [auth]                      — cross-module
│   └── TokenService.validate [auth]
├── CreateUserRequest.validate [core]
├── UserServiceLive.createUser [core]
│   ├── UserRepository.findByEmail [persistence]            — cross-module
│   ├── PasswordHasher.hash [auth]                          — cross-module
│   ├── UserRepository.save [persistence]                   — cross-module
│   │   └── Database.transaction [persistence]
│   └── EmailService.sendWelcome [notifications]            — cross-module
│       └── SmtpClient.send [notifications]
└── Response.created [api]
```

---

### `kodex impact <symbol>`

What breaks if this symbol changes. Callers, overrides, test coverage, affected modules.

```bash
kodex impact UserRepository.save
```

```
Direct callers (3):
  UserServiceLive.createUser     — core/src/.../UserServiceLive.scala:35
  UserServiceLive.updateUser     — core/src/.../UserServiceLive.scala:52
  MigrationJob.backfillUsers     — jobs/src/.../MigrationJob.scala:18

Transitive callers (7):
  UserController.postUser → UserServiceLive.createUser → here
  AdminController.bulkCreate → UserServiceLive.createUser → here
  ...

Overrides (2):
  PostgresUserRepository.save    — persistence/src/...
  InMemoryUserRepository.save    — test/src/...

Test coverage: 6 tests across 3 suites

Modules affected: core, api, admin, jobs
```

---

### `kodex trace <from> <to>`

Shortest call path between two symbols. BFS over call graph.

```bash
kodex trace UserController.postUser UserRepository.save
```

```
Call path (3 hops):
  UserController.postUser
  → UserServiceLive.createUser
  → UserRepository.save
```

---

### `kodex search <query>`

Unified search, grouped by domain role and ranked.

```bash
kodex search "permission"
```

```
Services (3):
  trait PermissionChecker    — auth/src/.../PermissionChecker.scala:5
  class PermissionService    — auth/src/.../PermissionService.scala:15
  enum  Permission           — auth/src/.../Permission.scala:3

Methods (2):
  def checkPermission(user, perm): Boolean  — in PermissionChecker:8
  def hasPermission(role, perm): Boolean    — in RoleService:22

In source (2):
  AuthMiddleware.scala:67  — if (!checker.checkPermission(user, required))
  UserController.scala:23  — @requirePermission(Permission.CreateUser)
```

Default: excludes tests, generated code, UI. `--all` to show everything.

**Domain-aware ranking**:
| Tier | Pattern | Examples |
|---|---|---|
| 1 (highest) | `*Service`, `*Operations`, `*Provider` | `OrderService.createOrder` |
| 2 | `*Params`, `*Request`, `*Response`, `*Model` | `CreateOrderParams` |
| 3 | `*Endpoint*`, `*Api*`, `*Server` | `OrderEndpoints.createOrder` |
| 4 | `*Plan`, `*Role`, `*Feature`, `*Config` | `OrderPlan`, `OrderRole` |
| 5 | `*Component`, `*Modal`, `*Page` (UI) | `CreateOrderButton` |
| 6 (lowest) | test paths, `*Spec`, `*Suite`, `*Fixture` | `OrderQuotaInteg` |

---

### `kodex body <symbol> [--in <owner>]`

Show source code of a method or type. Reads directly from `.scala` files.

```bash
kodex body createUser --in UserServiceLive
```

```
Body of createUser — UserServiceLive — src/.../UserServiceLive.scala:30:
  def createUser(req: CreateUserRequest): Future[User] = {
    for {
      existing <- repo.findByEmail(req.email)
      _ <- if (existing.isDefined) Future.failed(DuplicateEmail(req.email))
           else Future.unit
      hashed <- hasher.hash(req.password)
      user = User(id = UserId.generate(), name = req.name, email = req.email)
      saved <- repo.save(user)
      _ <- email.sendWelcome(saved)
    } yield saved
  }
```

---

### `kodex batch`

Multiple queries, one index load.

```bash
echo -e "explore UserService\nexplore Permission\nflow UserController.postUser" | kodex batch
```

---

### Command Summary

| Command | Agent question | Token budget | Needs source files |
|---|---|---|---|
| `orient` | What is this project? | ~500 | No |
| `explore <sym>` | Tell me everything about X | ~1800 | Yes (scaladoc, bodies) |
| `search <query>` | Find anything related to X | ~800 | No |
| `body <sym>` | Show me the source code | varies | Yes |
| `flow <sym>` | What does X call? | ~600 | No |
| `trace <a> <b>` | How does X reach Y? | ~200 | No |
| `impact <sym>` | What breaks if I change X? | ~800 | No |
| `batch` | Multiple queries | sum | Depends |

## Agent-First Features

### R1: Smart symbol disambiguation

When a name is ambiguous (e.g., `createOrder` matches 16 symbols), kodex auto-selects using ranking:
1. `*Service#method` > `*Operations#method` > `*Endpoint.val` > test paths > locals
2. Prefer non-test, non-generated, server-side sources
3. Prefer methods over vals/types when the name looks like a verb (`createX`, `getX`, `handleX`)

When multiple candidates survive:
```
3 matches for "createOrder":
  1. OrderService.createOrder     — modules/order/.../OrderService.scala:123
  2. OrderAgentServiceLive.createOrder — modules/order/.../OrderAgentServiceLive.scala:280
  3. OrderFlowOperations.createOrder   — modules/order/.../OrderFlowOperations.scala:36
Use: kodex explore createOrder --pick 2
```

### R2: Inline small enums and sealed hierarchies

When `explore` encounters a related type that is an enum (<=20 values) or sealed trait (<=5 subtypes), auto-inline:

```
Related types:
  OrderPlan (sealed trait):
    OrderProPlan       — ViewOnly, Analytics, AccessControl, Branding
    OrderBusinessPlan  — all features
    OrderEmptyPlan     — free, no premium features
```

No follow-up `body` calls needed for small domain types.

### R3: Module-scoped queries

```bash
kodex explore createOrder --module order
kodex search "permission" --module auth
kodex explore createOrder --module "order.*"  # glob for sub-modules
```

kodex parses Mill's module structure at index time and maps module names to source paths.

### R4: Noise filtering defaults

Excluded from all output by default:
- **Test paths**: `/test/`, `/it/`, `/spec/`, `*Test.scala`, `*Spec.scala`
- **Generated code**: protobuf, gRPC, buildinfo, routes (detected by path patterns and file markers)
- **Standard library**: `scala/`, `java/lang/`, `java/util/` prefixes in call graphs
- **Effect plumbing**: `map`, `flatMap`, `filter`, `foreach`, `apply`, `unapply`, `toString`, `hashCode`, `equals`
- **E2E/simulator**: `e2e`, `simulator`, `fixture` paths in callers/callees

Override: `--all`, `--include-tests`, `--include-stdlib`, `--no-filter`.

### R5: Entry point annotations in caller trees

```
Called by (depth 2):
  OrderCoreServer.services [API endpoint]
    AppServer.commonServices [server boot]
  OrderAdminPortalService.createOrderOnBehalfUnsafe
    OrderAdminPortalServer.services [API endpoint]
  OrderAgentServiceLive.createOrder [AI agent]
```

Detection: classes extending `*Server`, `*Endpoint`, `*Controller`, symbols with `@main`, naming heuristics.

### R6: Context-efficient output (token budgets)

| Section | Budget | What fits |
|---|---|---|
| Signature | ~100 tokens | Method signature with param types |
| Scaladoc | ~200 tokens | First paragraph only |
| Params | ~300 tokens | Field names + types + one-line doc |
| Service calls (callees) | ~500 tokens | Numbered list with module tags |
| Callers | ~300 tokens | Production callers with entry point annotations |
| Related types (inlined) | ~400 tokens | Enum values, sealed subtypes |
| **Total explore output** | **~1800 tokens** | Complete picture |

`--body` lifts the body section budget. `--verbose` lifts all budgets.

### R7: Configurable via `.kodex.conf`

```properties
# .kodex.conf
exclude.paths = **/generated/**, **/protobuf/**, legacy/src/**
exclude.packages = com.example.generated, com.example.legacy
noise.prefixes = zio/, cats/effect/
```

## Technology Stack

Rust Edition 2024 (stable since Rust 1.85.0, Feb 2025).

### Core

| Crate | Version | Purpose |
|---|---|---|
| **rkyv** | 0.8 | Zero-copy deserialization. mmap'd file IS the in-memory representation. No deserialization step. |
| **prost** | 0.14 | Decode SemanticDB `.proto` files at index time via `prost-build`. |
| **memmap2** | latest | Cross-platform mmap for read-only `kodex.idx` access. |
| **rayon** | 1.11 | Parallel indexing of thousands of `.semanticdb` files. |
| **clap** | 4.5+ | CLI argument parsing with derive macros. |

### Supporting

| Crate | Purpose |
|---|---|
| **bstr** | Byte string handling for raw source file reads |
| **rustc-hash** (FxHash) | Fast, non-cryptographic hashing for symbol tables |
| **memchr** | SIMD-accelerated byte search for source text scanning |
| **walkdir** | Directory traversal for Mill `out/` discovery |
| **anyhow** | Error handling in CLI layer |
| **tikv-jemallocator** | jemalloc allocator for indexer (better parallel perf) |

### Build & Distribution

| Tool | Purpose |
|---|---|
| **cargo** | Build system |
| **cross** | Cross-compilation: linux-x86_64, linux-aarch64, darwin-x86_64, darwin-aarch64 |
| **cargo-deny** | License and vulnerability auditing |
| **GitHub Actions** | CI/CD, release binaries |

Static linking with musl on Linux — single binary, no runtime dependencies.

## Index Format

### Design principles

1. **Zero-copy**: The on-disk format IS the in-memory format (via rkyv). mmap, cast pointers, query.
2. **No deserialization**: Never allocate, never copy, never parse the index at query time.
3. **String interning**: All strings stored once in a sorted table. Everything else uses `u32` offsets.
4. **Cache-friendly**: Related data stored contiguously. Symbol lookups hit sequential memory.
5. **Single file**: One `kodex.idx` — simple, atomic.

### Schema (rkyv-serialized)

```rust
#[derive(Archive, Serialize)]
struct KodexIndex {
    version: u32,

    // --- Core tables ---
    strings: Vec<String>,                // Sorted, deduplicated string table
    files: Vec<FileEntry>,               // All source files
    symbols: Vec<Symbol>,                // All symbols

    // --- Relationships (all bidirectional) ---
    references: Vec<ReferenceList>,      // symbol_id → [reference locations]
    call_graph_forward: Vec<EdgeList>,   // caller → [callees]
    call_graph_reverse: Vec<EdgeList>,   // callee → [callers]
    inheritance_forward: Vec<EdgeList>,  // parent → [children]
    inheritance_reverse: Vec<EdgeList>,  // child → [parents]
    members: Vec<EdgeList>,              // owner → [members]
    overrides_forward: Vec<EdgeList>,    // base → [overriders]
    overrides_reverse: Vec<EdgeList>,    // overrider → [base]

    // --- Structural ---
    packages: Vec<PackageNode>,          // Package tree
    modules: Vec<MillModule>,            // Mill module metadata
    module_graph: Vec<EdgeList>,         // Module dependency edges

    // --- Search ---
    trigrams: TrigramIndex,              // 3-char → [file_ids] for text search

    // --- Metadata ---
    scala_version: StringId,
    project_root: StringId,
}

#[derive(Archive, Serialize)]
struct FileEntry {
    path: StringId,
    git_oid: [u8; 20],
    module_id: u32,          // which Mill module this file belongs to
    is_test: bool,           // pre-classified at index time
    is_generated: bool,      // pre-classified at index time
}

#[derive(Archive, Serialize)]
struct Symbol {
    id: u32,
    name: StringId,           // short name ("apply")
    fqn: StringId,            // fully qualified ("com/example/Foo#apply().")
    kind: SymbolKind,
    file_id: u32,
    line: u32,
    col: u32,
    end_line: u32,            // for body extraction
    end_col: u32,
    type_signature: StringId, // pre-rendered type string from SemanticDB
    doc_offset: u32,          // byte offset in source file (scaladoc)
    doc_length: u32,
    body_offset: u32,         // byte offset for method/class body
    body_length: u32,
    owner: u32,               // parent symbol id
    access: Access,
    properties: SymbolProperties, // bitflags: abstract, final, sealed, case, implicit, etc.
}

#[derive(Archive, Serialize)]
enum SymbolKind {
    Class, Trait, Object, Def, Val, Var, Type, Enum, Given, Extension, Package,
}

#[derive(Archive, Serialize)]
struct Reference {
    file_id: u32,
    line: u32,
    col: u32,
    role: ReferenceRole,
    enclosing_symbol: u32,    // the method/class containing this reference
}

#[derive(Archive, Serialize)]
enum ReferenceRole {
    Definition, Reference, Import, ImplicitReference,
}

#[derive(Archive, Serialize)]
struct MillModule {
    name: StringId,           // e.g. "order", "core"
    source_paths: Vec<StringId>,
    symbol_count: u32,
    file_count: u32,
}

#[derive(Archive, Serialize)]
struct TrigramIndex {
    trigrams: Vec<[u8; 3]>,   // sorted for binary search
    file_ids: Vec<Vec<u32>>,  // parallel array
}
```

### Key additions over v1 design

- **`MillModule`**: Module metadata for `--module` scoping and `orient` output
- **`module_graph`**: Module dependency edges for module graph visualization
- **`is_test` / `is_generated`** on FileEntry: Pre-classified at index time for instant noise filtering
- **`body_offset` / `body_length`** on Symbol: For `body` command — seek + read from source
- **`end_line` / `end_col`** on Symbol: For precise body extraction
- **`enclosing_symbol`** on Reference: For callers — resolve which method contains the call site
- **`properties`** bitflags: sealed, case, abstract, etc. — for inline enum/sealed hierarchy detection
- **`type_signature`** pre-rendered: The ~200 lines of Rust type printer runs at index time, stores plain strings. Query time just prints them.

### Estimated index size

Based on sdbex benchmarks (13.7k-file Scala codebase):

| Data | Estimated size |
|---|---|
| String table | ~20MB |
| Symbols (~500k) | ~35MB |
| References (~5M) | ~70MB |
| Call graph edges (~2M) | ~16MB |
| Inheritance + overrides | ~5MB |
| Module metadata | ~1MB |
| Trigram index | ~15MB |
| **Total** | **~160MB** |

Fits comfortably in memory. OS pages in only what's needed per query.

## SemanticDB Ingestion

### Discovery (Mill-only)

```
out/**/semanticDbDataDetailed.dest/data/META-INF/semanticdb/**/*.semanticdb
```

Walk Mill's `out/` in parallel with rayon.

### Proto schema

Located at `scalameta/semanticdb/shared/src/main/proto/semanticdb.proto`. Vendored into `proto/semanticdb.proto`. Rust bindings generated at build time via `prost-build`.

### Type signature rendering

SemanticDB stores type signatures as structured protobuf (16 type variants, 4 signature variants). At **index time**, kodex renders these to human-readable strings and stores the result. This avoids needing a type printer at query time.

The Rust type printer handles:

| Type | Rendering | Frequency |
|---|---|---|
| TypeRef | `prefix.Name[args]` | ~90% of all types |
| SingleType | `prefix.Name.type` | Common |
| ByNameType | `=> T` | Common |
| RepeatedType | `T*` | Common |
| UnionType | `A \| B` | Scala 3 |
| IntersectionType | `A & B` | Scala 3 |
| ThisType | `this.type` | Occasional |
| ConstantType | `42`, `"hello"` | Occasional |
| WithType | `A with B` | Scala 2 |
| AnnotatedType | `@ann T` | Rare |
| ExistentialType, UniversalType, StructuralType, LambdaType, MatchType | `<complex>` (v1) | Rare |

~200 lines of Rust. Mostly string concatenation. Runs once at index time.

### Extraction pipeline

```
Phase 1: Parallel SemanticDB decode (rayon)
  for each .semanticdb file:
    1. prost::decode → TextDocuments
    2. for each TextDocument:
        a. Extract symbols → Symbol entries
        b. Render type signatures → pre-rendered strings
        c. Extract occurrences → Reference entries (with enclosing_symbol)
        d. Build caller→callee edges from occurrence ranges
        e. Classify file: is_test, is_generated, module_id
    3. Append to thread-local buffers

Phase 2: Merge + enrich (single-threaded)
    4. Merge all buffers
    5. Sort + deduplicate string table
    6. Rewrite all StringIds to sorted offsets
    7. Build reverse indexes (callee→callers, child→parents, overrider→base)
    8. Scan source files for scaladoc positions + body ranges
    9. Build trigram index from source file content
   10. Parse Mill module structure from build.mill + out/ directory layout
   11. Detect entry points (extends *Server, @main, etc.)

Phase 3: Write
   12. rkyv::to_bytes → write kodex.idx
```

### Scaladoc + body strategy

SemanticDB has positions but not source text. At index time:
1. For each symbol, record `doc_offset`/`doc_length` (nearest `/** ... */` before the symbol)
2. Record `body_offset`/`body_length` (from symbol start to end)
3. At query time, `seek + read` from `.scala` files (~10us per read)

This keeps the index lean — rarely-accessed text stays in source files.

## Noise Mitigation

### Generated code detection (layered)

| Signal | Examples |
|---|---|
| Path patterns | `**/generated/**`, `**/src_managed/**`, `**/protobuf/**` |
| File markers | `// Generated by`, `@Generated`, `DO NOT EDIT` |
| Mill conventions | Files under `out/**/generatedSources/` |
| Filename patterns | `*.pb.scala`, `*Grpc.scala`, `BuildInfo.scala` |

Excluded from all output by default. `--include-generated` to show.

### Standard library / effect plumbing filtering

**Filtered symbol prefixes** (excluded from call graphs and related types):
```
scala/, java/lang/, java/util/, java/io/, java/net/
```

**Filtered method names** (excluded from flow/callees):
```
apply, unapply, toString, hashCode, equals, copy,
map, flatMap, filter, foreach, collect, foldLeft, foldRight,
get, getOrElse, orElse, isEmpty, nonEmpty, isDefined,
mkString, +, ++, ::, :+, +=
```

These are true facts but useless noise. `flow createUser` shows `repo.findByEmail` and `email.sendWelcome`, not `flatMap` and `map` from the for-comprehension.

### Project boundary

All commands default to project code only. The call graph stops at the project boundary — if `createUser` calls `Future.successful`, the flow output shows `createUser` as a leaf.

## Performance Targets

| Operation | Target | How |
|---|---|---|
| Index build (13k files) | <5s | Parallel rayon decode + merge |
| Binary startup | <1ms | No JVM, no runtime init |
| Symbol lookup | <0.1ms | Binary search on sorted string table |
| `explore` (composite) | <5ms | Multiple index lookups + source reads |
| `flow` (depth 3) | <1ms | BFS over call graph edges |
| `impact` (depth 2) | <2ms | Reverse BFS + override lookup |
| `trace` (BFS) | <1ms | Bidirectional BFS over call graph |
| `search` (trigram) | <5ms | Trigram index → candidate files → verify |
| Source read (scaladoc/body) | <0.1ms | seek + read from .scala file |

## Project Structure

```
kodex/
├── Cargo.toml
├── build.rs                      # prost codegen for semanticdb.proto
├── proto/
│   └── semanticdb.proto          # Vendored from scalameta (pinned)
├── src/
│   ├── main.rs                   # CLI entry point (clap derive)
│   ├── model.rs                  # Index types (rkyv-derivable)
│   ├── ingest/
│   │   ├── mod.rs
│   │   ├── discovery.rs          # Walk Mill out/ for .semanticdb files
│   │   ├── semanticdb.rs         # prost decode → intermediate model
│   │   ├── types.rs              # Type signature renderer (~200 lines)
│   │   ├── source.rs             # Scaladoc + body range extraction
│   │   ├── mill.rs               # Mill module structure parsing
│   │   ├── classify.rs           # Test/generated file classification
│   │   ├── trigram.rs            # Build trigram index
│   │   └── merge.rs              # Merge all data, build reverse indexes
│   ├── index/
│   │   ├── mod.rs
│   │   ├── writer.rs             # rkyv serialize → kodex.idx
│   │   └── reader.rs             # mmap + zero-copy access
│   ├── query/
│   │   ├── mod.rs
│   │   ├── disambiguate.rs       # Smart symbol disambiguation + ranking
│   │   ├── filter.rs             # Noise filtering (test, generated, stdlib)
│   │   ├── symbol.rs             # def, search, members, type
│   │   ├── refs.rs               # refs, imports
│   │   ├── graph.rs              # callers, callees, flow, trace, impact
│   │   ├── text.rs               # Trigram text search
│   │   └── source.rs             # Body + scaladoc reads from .scala files
│   ├── commands/
│   │   ├── mod.rs
│   │   ├── orient.rs             # Project overview
│   │   ├── explore.rs            # Composite: members + callers + callees + related
│   │   ├── flow.rs               # Call tree with module boundaries
│   │   ├── impact.rs             # Callers + overrides + test coverage
│   │   ├── trace.rs              # Shortest call path (BFS)
│   │   ├── search.rs             # Domain-ranked search
│   │   ├── body.rs               # Source code display
│   │   └── batch.rs              # Multi-query dispatch
│   └── format.rs                 # Output formatting + token budgets
├── tests/
│   ├── fixtures/                 # Small .semanticdb files + source
│   └── integration.rs
└── .github/
    └── workflows/
        └── release.yml           # Cross-compile + release binaries
```

## Example Session

An agent exploring "how does user creation work?" in an unfamiliar 13k-file codebase:

```bash
# One-time setup
git clone --depth=1 git@github.com:org/my-app.git && cd my-app
./mill __.semanticDbData
kodex index

# Explore (2 calls → complete understanding)

kodex orient
# → 5 modules, module graph, hub types, entry points

kodex explore UserService.createUser
# → signature, params with types, 19 service calls (numbered with module tags),
#   5 production callers with entry point annotations,
#   related types with inlined enums (Plan, Role, PremiumFeature),
#   ~1800 tokens total
```

**2 calls. ~2300 tokens. Complete answer.**

Compare: scalex alone = 15 calls, ~8000 tokens. sdbex alone = 5 calls, ~2000 tokens but missing docs/enums/bodies.

## Migration from scalex + sdbex

### What kodex replaces

| Current | kodex | Improvement |
|---|---|---|
| `scalex explain` + `sdbex explain` | `kodex explore` | One call, both engines merged |
| `sdbex callees --smart` | `kodex flow` / `kodex explore` (service calls section) | Module boundaries annotated |
| `sdbex callers --depth N` | `kodex impact` | Entry point annotations, test coverage |
| `sdbex path A B` | `kodex trace A B` | Same BFS, faster engine |
| `scalex search` (1687 results) | `kodex search` (~20 ranked) | Domain-aware ranking, noise filtered |
| `scalex body` x4 for enums | `kodex explore` (auto-inlined) | Zero follow-up calls |
| `scalex overview` | `kodex orient` | Module graph, hub types, entry points |

### What kodex does NOT replace

- **scalex on uncompiled projects**: kodex requires SemanticDB (compilation). For zero-setup exploration, scalex remains useful.
- **scalex as a Claude Code plugin**: kodex could also be a plugin, but the use cases differ — scalex for quick navigation, kodex for deep knowledge extraction.

## Open Questions

1. **Java files in mixed projects**: SemanticDB is Scala-only. Options: read `.class` files, use javac's `-Xplugin`, or just index Java symbols from SemanticDB cross-references.
2. **Dependency library exploration**: Should `kodex explore` work on dependency types (from classpath JARs), or only project code? Metals' `inspect` tool does this via the Presentation Compiler. kodex could index classpath SemanticDB if available.
3. **MCP / Claude Code plugin**: Ship as a Claude Code plugin with a skill file? The binary is self-contained and fast enough.
4. **Multi-repo**: For monorepos or multi-repo setups, can we produce a merged `kodex.idx` across repos?
5. **Prerequisite chain expansion**: R9 from requirements — `kodex explore CreateOrderParams --expand-prereqs` to show entity → plan → features chain. High value but requires heuristic call graph + body analysis. Defer to v2?
