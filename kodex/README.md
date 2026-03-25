# kodex

> **Experimental — not ready for use.**

kodex is a knowledge-base extractor for [Mill](https://mill-build.org)-powered Scala codebases. It reads [SemanticDB](https://scalameta.org/docs/semanticdb/guide.html) — the compiler's output where every connection, every reference, every type is resolved with zero ambiguity. If [scalex](../README.md) isn't enough and you need the most powerful tool to understand a Scala codebase, this is it.

The tradeoff is clear:

| | **scalex** | **kodex** |
|---|---|---|
| **Input** | `.scala` source files | `.semanticdb` compiler output |
| **How** | Parses source to AST (Scalameta) | Reads what the compiler already resolved |
| **Setup** | None — works on raw source | Requires ` ./mill __.semanticDbData` first |
| **Precision** | Name-based (ambiguous overloads, unresolved types) | Compiler-precise (exact FQNs, resolved implicits) |
| **Call graph** | Not available | Full forward + reverse, trait-aware |
| **Impact analysis** | Manual chaining | One command |
| **Build tool** | Any (git-based) | Mill only |
| **Speed** | ~10ms warm (JVM daemon) | <1ms (zero-copy mmap, no JVM) |

scalex parses. kodex *knows*. Use scalex for instant zero-setup exploration of any Scala project. Use kodex when the project compiles with Mill and you need compiler-precise call graphs, impact analysis, and composite answers.

## How it works

When the Scala compiler runs with `-Xsemanticdb`, it emits a `.semanticdb` file alongside each `.scala` file — a protobuf containing everything the compiler *resolved*. Consider this program from the [SemanticDB guide](https://github.com/scalameta/scalameta/blob/main/semanticdb/guide.md):

```scala
object Test {
  def main(args: Array[String]): Unit = {
    println("hello world")
  }
}
```

The compiler produces a `.semanticdb` file alongside the source:

```
$ tree
.
├── META-INF
│   └── semanticdb
│       └── Test.scala.semanticdb
└── Test.scala
```

The `.semanticdb` file is a protobuf with two sections:

**Symbols** — every definition, fully resolved:

```
_empty_/Test.              => final object Test extends AnyRef { +1 decls }
_empty_/Test.main().       => method main(args: Array[String]): Unit
_empty_/Test.main().(args) => param args: Array[String]
```

**Occurrences** — every identifier in source, linked to its definition:

```
[0:7..0:11)  <= _empty_/Test.                   ← "Test" is defined here
[1:6..1:10)  <= _empty_/Test.main().             ← "main" is defined here
[1:11..1:15) <= _empty_/Test.main().(args)       ← "args" is defined here
[1:17..1:22) => scala/Array#                     ← "Array" references scala/Array
[1:23..1:29) => scala/Predef.String#             ← "String" references scala/Predef.String
[1:33..1:37) => scala/Unit#                      ← "Unit" references scala/Unit
[2:4..2:11)  => scala/Predef.println(+1).        ← "println" references the second overload
```

Every type reference, every overload, every implicit — resolved by the compiler with zero ambiguity. `[2:4..2:11) => scala/Predef.println(+1).` tells us the identifier `println` on line 3 (zero-based) refers to the second overload of `println` from `scala.Predef`. No grep can give you that.

## What kodex builds on top

SemanticDB gives you symbols and occurrences. kodex reads all `.semanticdb` files in a project, then computes relationships the compiler doesn't directly emit:

```
  .semanticdb files (thousands)
         │
         │  parallel decode + merge
         ▼
  kodex.idx (single file, rkyv zero-copy mmap)
```

| Relationship | How it's computed |
|---|---|
| **Call graph** | REFERENCE occurrences inside a method's body range → that method calls those symbols |
| **Inheritance tree** | `ClassSignature.parents` → parent/child edges |
| **Members index** | FQN owner chain → "which symbols belong to this class" |
| **Overrides index** | `overriddenSymbols` → "who overrides this method" |
| **Trigram search** | 3-char sliding windows over names → fast substring matching |

The index is serialized with [rkyv](https://rkyv.org) (zero-copy deserialization). The OS mmaps the file and pages in only what each query touches — a `kodex callers main` on a 600MB index might read 10KB.

## Requirements

- A Scala project compiled with SemanticDB (`-Xsemanticdb` for Scala 3, `semanticdb-scalac` plugin for Scala 2)
- Mill build tool (kodex discovers `.semanticdb` files from Mill's `out/` directory)
