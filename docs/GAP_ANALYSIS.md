# Gap Analysis: Scalex vs Metals v2 MBT

This document compares Scalex against the MBT (Metal Build Tool) subsystem in Metals v2's `main-v2` branch — the fast indexing engine that inspired our design. We focus on v2, not v1 (v1 is slow, relies on BSP, we know).

## What we both do (same approach)

| Feature | Metals v2 MBT | Scalex |
|---|---|---|
| Git file discovery | `git ls-files --stage` | `git ls-files --stage` |
| OID caching | SHA-1 content hash, skip unchanged files | SHA-1 from git, skip unchanged files |
| Symbol extraction | mtags (Scalameta-based) | Scalameta directly |
| Bloom filter pre-screening | Guava BloomFilter per file | Guava BloomFilter per file |
| Parallel indexing | ForkJoinPool + ParArray | Java parallelStream |
| Persistent index | Protobuf binary format | Custom binary v3 + string interning |
| Find implementations | Iterative hierarchy search | AST extends/with parsing |
| Time-boxed search | 20s timeout, 100-file chunks | 20s timeout, parallel stream |
| No build server required | Works without BSP | Works without BSP |
| No compilation required | Source-level only | Source-level only |

The core architecture is identical. We reimplemented the same ideas in ~1,500 lines of Scala 3.

## What Metals v2 MBT does that we don't

### 1. `-sourcepath` integration (Scala 2 only)

Metals v2 feeds MBT's `documentsByPackage` index into the Scala 2 presentation compiler via `LogicalSourcePath`. This lets the compiler resolve symbols lazily from source files — no compilation needed.

**Impact for us: None.** Scala 3's dotty compiler has no `-sourcepath` mechanism. This feature only works for Scala 2.

### 2. SemanticDB-based precise references

After bloom filter pre-screening, Metals v2 can use the presentation compiler to generate SemanticDB for candidate files. This gives **scope-aware** reference resolution — it can distinguish `Foo.name` from `Bar.name`.

**Impact for us: The only real gap.** Our `refs` command uses text-based word-boundary matching. If two unrelated classes both have a method called `name`, we return all of them. Metals v2 returns only the one you asked about.

**Can we close it?** No, without a compiler. This is the fundamental limitation of source-level analysis.

**Practical impact:** For unique names like `PaymentService`, no impact — all matches are correct. For common names like `name` or `apply`, noisy results. The `--categorize` flag helps by grouping results so the agent can focus on the relevant category.

### 3. Google Turbine Java compilation

Metals v2 uses Turbine for fast header-only Java compilation, providing Java symbol resolution.

**Impact for us: None.** We focus on Scala. Java files are not indexed.

### 4. MBT as fallback classpath

When BSP is unavailable, Metals v2 uses MBT's index as a fallback classpath for the presentation compiler.

**Impact for us: None.** We don't have a presentation compiler, so we don't need a classpath.

### 5. `documentsByPackage` index for `LogicalSourcePath`

Maps SemanticDB package symbols to all files in that package. Powers the Scala 2 sourcepath trick.

**Impact for us: None.** Only used by the Scala 2 presentation compiler integration.

## The Scala 2 vs Scala 3 filter

Most of the v2 MBT features we're "missing" are Scala 2 or Java specific:

| Missing feature | Relevant? |
|---|---|
| `-sourcepath` integration | No — Scala 3 has no `-sourcepath` |
| `LogicalSourcePath` | No — Scala 2 compiler internals |
| `documentsByPackage` for sourcepath | No — only used by Scala 2 PC |
| Turbine Java compilation | No — we focus on Scala |
| MBT as fallback classpath | No — only useful with a PC |
| SemanticDB precise references | **Yes — the only real gap** |

## The one real gap: Precise references

| | Scalex | Metals v2 |
|---|---|---|
| `refs Decoder` | All lines with "Decoder" as a word | Only lines referencing the specific `Decoder` you selected |
| Overloaded names | Returns all `name` across all classes | Returns only `Foo.name` |
| Scope awareness | None — text matching | Full — SemanticDB |

**Why we can't close it:** Requires the Scala compiler to generate SemanticDB, which requires compiled classpath, which requires a build server — the thing we're avoiding.

**Mitigation:** `--categorize` groups results by Definition/ExtendedBy/ImportedBy/UsedAsType/Comment/Usage, so the agent can quickly focus on the relevant category instead of scanning a flat list.

## What Scalex does that Metals v2 MBT doesn't

| Feature | Scalex | Metals v2 MBT |
|---|---|---|
| Standalone CLI | Yes — single binary | No — embedded in Metals LSP server |
| Claude Code skill | Yes — teaches AI agent to use it | No |
| GraalVM native image | Yes — 28MB, instant startup | No — runs on JVM inside Metals |
| Zero config | Yes — just run in a git repo | Needs Metals setup + IDE |
| Scala 2 + 3 support | Yes — auto-detect dialect per file | Scala 2 only for sourcepath features |
| `--verbose` signatures | Yes — shows extends/params inline | No — returns Location only (LSP) |
| `--categorize` refs | Yes — Definition/ExtendedBy/ImportedBy/UsedAsType/Comment | No |
| `imports` command | Yes — show only import statements | No — bundled into refs |
| `batch` mode | Yes — multiple queries, one index load | No — LSP processes one request at a time |
| Fallback hints | Yes — suggests Grep/Glob on "not found" | No |

## Summary

The gap between Scalex and Metals v2 MBT is **one feature**: precise (scope-aware) references, which requires a compiler.

Everything else is either matched or exceeded:
- **Core indexing** — identical (git OIDs, Scalameta, bloom filters, binary persistence)
- **Find implementations** — both support it (different approaches, same result)
- **Time-boxed search** — both support it
- **Scala 2 + 3** — Scalex auto-detects dialect; Metals v2 MBT's sourcepath trick is Scala 2 only
- **AI-agent features** — Scalex adds `--verbose`, `--categorize`, `imports`, `batch`, fallback hints — none of which exist in Metals

**For AI agent workflows, Scalex exceeds Metals v2 MBT's source-level capabilities. The only thing Metals can do that we can't is precise references — and that requires a running compiler.**
