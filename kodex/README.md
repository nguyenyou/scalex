# kodex

> **Experimental — not ready for use.**

A knowledge index for Scala codebases — from *codex* (the first book format to replace scrolls: organized, indexed, random-access) — that turns compiled SemanticDB data into a queryable knowledge base coding agents can navigate in microseconds.

## The idea

When the Scala compiler compiles a `.scala` file, it can also emit a `.semanticdb` file — a protobuf containing everything the compiler *resolved*:

```
Source (.scala)                    SemanticDB (.semanticdb)
┌──────────────────────┐           ┌──────────────────────────────────┐
│                      │  compile  │                                  │
│  trait Vehicle {     │ ───────►  │  Vehicle#                        │
│    def start(): Unit │           │    kind: TRAIT                   │
│  }                   │           │    parents: [Object#]            │
│                      │           │                                  │
│  class Car(          │           │  Car#                            │
│    engine: Engine    │           │    kind: CLASS                   │
│  ) extends Vehicle { │           │    parents: [Vehicle#]           │
│    def start() =     │           │    overrides: [Vehicle#start()]  │
│      engine.ignite() │           │                                  │
│  }                   │           │  Occurrences:                    │
│                      │           │    Vehicle [3:8]  DEFINITION     │
│  val c = Car(v8)     │           │    Car     [7:8]  DEFINITION     │
│  c.start()           │           │    Vehicle [9:14] REFERENCE      │
│                      │           │    engine  [11:6] REFERENCE      │
│                      │           │    Car     [14:10] REFERENCE     │
│                      │           │    start   [15:4] REFERENCE      │
└──────────────────────┘           └──────────────────────────────────┘
```
