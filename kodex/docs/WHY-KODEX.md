# Why kodex

scalex already works. Clone any repo, index 17k files in seconds, start navigating — no compilation, no build server, no setup. So why build kodex?

## The short answer

scalex parses source code. kodex reads what the compiler already resolved. That's the difference between guessing and knowing.

## What scalex sees

scalex uses [Scalameta](https://github.com/scalameta/scalameta) to parse `.scala` files into ASTs. From the AST, it extracts symbols (classes, traits, methods), parents (`extends Foo`), and text-based references (grep with bloom filters). This works remarkably well for navigation — find definitions, find implementations, find usages.

But an AST is just syntax. Consider:

```scala
import com.example.billing.*
import com.example.auth.*

class PaymentService(repo: Repository) extends Config {
  def process(invoice: Invoice): Unit = {
    val result = repo.save(invoice)
    println(result)
  }
}
```

What scalex sees:

| Question | scalex's answer |
|---|---|
| Which `Config`? | `Config` (string match — could be `billing.Config` or `auth.Config`) |
| Which `Repository`? | `Repository` (same ambiguity) |
| What does `process` call? | Can't tell — no call graph |
| Which `println` overload? | `println` (could be any of the 4 overloads) |
| Who calls `process`? | Text search for "process" (catches comments, strings, other methods named `process`) |

## What the compiler knows

When the Scala compiler processes the same file, it resolves everything:

```
com/example/billing/PaymentService#process().
  calls: com/example/persistence/Repository#save().
  calls: scala/Predef.println(+1).

com/example/billing/PaymentService#
  parents: [com/example/billing/Config#, java/lang/Object#]
```

Every type reference, every overload, every implicit — resolved with zero ambiguity. The compiler already did the hard work. SemanticDB is just the format it writes these resolutions to.

kodex reads that output.

## The five things scalex can't do

### 1. Call graphs

scalex has no call graph. It can extract `Term.Name` tokens from method bodies, but these are just names in text — a variable named `save`, a string containing `"save"`, a comment mentioning `save`, and an actual call to `repo.save()` all look the same.

kodex builds a precise forward and reverse call graph from SemanticDB occurrences. Every REFERENCE occurrence inside a method's body range is a call. The compiler already verified it's a real reference, not a string or comment.

This enables commands that are impossible with scalex:

```
kodex callees process        # what does process() call?
kodex callers save           # who calls save()?
kodex flow process --depth 3 # full downstream call tree
kodex trace process save     # shortest call path
```

### 2. Overload and type resolution

scalex matches symbols by name. When your codebase has 47 methods named `apply`, scalex returns all 47. When two packages both define `Config`, scalex can't tell which one `extends Config` means.

kodex resolves to exact FQNs. `com/example/billing/Config#` and `com/example/auth/Config#` are different symbols. `scala/Predef.println(+1).` specifically means the second overload of `println`.

### 3. Impact analysis

"What breaks if I change `Repository#save`?" With scalex, you'd need to:
1. Find usages of `save` (text search, false positives)
2. Find who overrides `save` (scan all classes)
3. Manually trace callers of callers
4. Guess which modules are affected

With kodex:

```
kodex impact save --kind method
```

One call. Direct callers, overrides, test coverage, affected modules — all compiler-precise.

### 4. Cross-module flow tracing

scalex has no module awareness. kodex discovers Mill modules from the `out/` directory structure and annotates every symbol with its module. The `flow` command shows call trees with module boundaries:

```
kodex flow createUser --depth 3

UserController.createUser [api]
├── AuthMiddleware.authenticate [auth]          ← cross-module
│   └── TokenService.validate [auth]
├── UserServiceLive.createUser [core]
│   ├── UserRepository.save [persistence]       ← cross-module
│   └── EmailService.sendWelcome [notifications] ← cross-module
```

### 5. Composite answers

scalex answers atomic questions: "where is X defined?", "who implements Y?", "find usages of Z." An agent exploring a codebase must chain 5-15 of these calls to build a mental model of one type.

kodex answers composite questions in one call:

```
kodex explore UserService
```

Returns signature, members, inheritance tree, callers, callees, and related types — all filtered (no tests, no stdlib, no plumbing like `apply`/`toString`/`hashCode`). ~1800 tokens vs ~8000 for the equivalent scalex session.
