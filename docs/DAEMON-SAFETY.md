# Daemon Safety: Research & Design

Research on safe daemon management across the Scala/JVM ecosystem and beyond, applied to scalex-sdb's daemon mode.

---

## 1. Horror Stories: Why This Matters

Every major JVM build tool has shipped daemon bugs that burned users. These are not edge cases — they are the default outcome of careless daemon design.

| Tool | Incident | Impact |
|------|----------|--------|
| **Gradle** | Incompatible JVM args spawn new daemons; 100-daemon limit hit on shared CI | 100+ JVMs × 1-2 GB each. OOM cascades. ([gradle forums](https://discuss.gradle.org/t/tons-of-gradle-daemons-exhausting-memory/20579)) |
| **Gradle** | Kotlin compiler daemons multiply (3+ instances, each 1.5 GB+) | 4.5 GB just for compilation ([gradle/gradle#34755](https://github.com/gradle/gradle/issues/34755)) |
| **Gradle** | OOM does not terminate daemon — continues in broken state | Silent corruption ([gradle/gradle#948](https://github.com/gradle/gradle/issues/948)) |
| **sbt** | `fork := true` with Ctrl-C orphans child JVMs indefinitely | No cleanup, no `shutdownall` command ([sbt/sbt#7468](https://github.com/sbt/sbt/issues/7468)) |
| **sbt** | Memory leaks — classes not disposed between builds | OOM on subsequent refreshes ([sbt/sbt#7074](https://github.com/sbt/sbt/issues/7074)) |
| **Metals** | Zombie JVMs accumulate over weeks after VS Code closes | Month-old processes consuming RAM ([scalameta/metals#869](https://github.com/scalameta/metals/issues/869)) |
| **Metals** | Race condition in CompilerJobQueue during shutdown | Presentation Compiler thread hangs at `waitForMoreWork` ([scalameta/metals#2820](https://github.com/scalameta/metals/issues/2820)) |
| **Bloop** | Opening/closing VS Code workspaces leaves orphan Java processes | Zombie lock errors ([scalameta/metals-vscode#385](https://github.com/scalameta/metals-vscode/issues/385), [VirtusLab/scala-cli#2899](https://github.com/VirtusLab/scala-cli/issues/2899)) |
| **rust-analyzer** | VS Code quitting leaves running rustc/cargo jobs | Build directory locks held ([rust-lang/rust-analyzer#5258](https://github.com/rust-lang/rust-analyzer/issues/5258)) |
| **rust-analyzer** | Excessive CPU/memory consumption without bound | "Killed my computer" ([rust-lang/rust-analyzer#8451](https://github.com/rust-lang/rust-analyzer/issues/8451)) |

**Common thread**: Every one of these could have been prevented by defense-in-depth termination. No single mechanism is sufficient — each has failure modes that the others cover.

---

## 2. Ecosystem Patterns

### 2.1 LSP Lifecycle (Gold Standard)

The [LSP specification](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/) defines a clear lifecycle:

1. Client sends `initialize` with `processId` (the client's PID)
2. Server monitors this PID — "if the parent process is not alive then the server should exit"
3. Client sends `shutdown` request (stop accepting work)
4. Client sends `exit` notification (server process exits)
5. Exit code 0 if `shutdown` was received, 1 otherwise

**Key insight**: The spec mandates parent PID monitoring as a safety net, not just a clean shutdown protocol. Metals' zombie bug (#869) was fixed by implementing this check.

### 2.2 stdin Pipe as Primary Death Signal

The most portable and reliable parent-death detection mechanism:

- Parent creates a pipe; child reads from it in a background thread
- When parent dies (**even via SIGKILL**), the pipe's write end closes
- Child detects EOF and exits immediately

Adopted by:
- **Dask distributed** ([PR #1345](https://github.com/dask/distributed/pull/1345)): daemon thread reads pipe; `EOFError` → `os._exit(-1)`
- **LSP4J** ([eclipse-lsp4j/lsp4j#161](https://github.com/eclipse-lsp4j/lsp4j/issues/161)): "exiting when System.in closes" was the simplest cross-platform approach
- **MCP servers**: stdin/stdout JSON-lines protocol with EOF-based termination

**Why this is the best primary mechanism**: Works on all platforms, zero polling overhead, and — critically — detects parent SIGKILL, which no signal-based mechanism can.

### 2.3 Idle Timeout Patterns

| Tool | Default | Notes |
|------|---------|-------|
| gopls | 1 min | Shortest — aggressive for shared daemon |
| sbt server | 5 min | `serverIdleTimeout` setting |
| scalex-sdb | 5 min | Our current default |
| Gradle | **3 hours** | Too long — the root cause of many daemon accumulation bugs |

**Lesson**: Shorter is better. A 5-minute idle timeout means at most 5 minutes of wasted memory after the agent session ends. Gradle's 3-hour timeout is directly responsible for their daemon proliferation problems.

### 2.4 Max Lifetime Cap

Unique to scalex-sdb among the tools surveyed. Most daemons rely only on idle timeout, which fails when:
- A buggy client sends periodic heartbeats but never actually uses the daemon
- The agent session runs for hours with sporadic queries (idle timer keeps resetting)

The hard 30-minute cap ensures termination regardless. This is a pattern we should keep.

### 2.5 Process Supervision (systemd/launchd)

For long-lived system services (not our use case, but informative):

- **systemd watchdog**: Service sends `sd_notify("WATCHDOG=1")` every N seconds; missed heartbeat → SIGABRT + restart
- **launchd**: Process group cleanup — kills all processes with same PGID when job dies
- **supervisord**: SIGCHLD-based — parent receives signal when child dies; linear backoff on restart

**What we take from this**: The watchdog heartbeat pattern is relevant — if a daemon stops responding, external monitoring should detect it. For us, stdin EOF already serves this purpose.

### 2.6 Parent PID Monitoring (OS-Specific)

| Platform | Mechanism | Reliability |
|----------|-----------|-------------|
| Linux | `prctl(PR_SET_PDEATHSIG, SIGTERM)` | High, but fires when the **thread** that forked dies, not the process. TOCTOU race if parent dies between fork and prctl. |
| macOS | `kqueue` with `EVFILT_PROC` + `NOTE_EXIT` on parent PID | Reliable for direct parent |
| JVM (Java 9+) | `ProcessHandle.of(parentPid).get().onExit()` | Cross-platform, returns `CompletableFuture` |
| Cross-platform | Poll `getppid() == 1` (reparented to init) | Works but has latency equal to poll interval |

**For JVM daemons**, `ProcessHandle.onExit()` is the cleanest approach — no JNI, no platform-specific code.

### 2.7 PID File Anti-Patterns

From [Guido Flohr's analysis](https://www.guido-flohr.net/never-delete-your-pid-file/):

- **Never delete PID files** — creates race conditions because `unlink()` separates directory entry from inode
- **Race condition**: Two processes can hold file descriptors to different files with the same name, both acquiring exclusive locks
- **Correct pattern**: Open and lock; never delete; let kernel reclaim on exit

**For scalex-sdb**: We don't use PID files, which is correct. stdin/stdout pipes are strictly superior for our use case.

---

## 3. Our Current Implementation: Audit

### Eight Layers (from `daemon.scala`)

| Layer | Mechanism | Triggers | Status |
|-------|-----------|----------|--------|
| 1 | Stdin EOF | `readLine() == null` → `System.exit(0)` | ✅ Solid |
| 2 | Idle timeout | Scheduled task, reset on each request (default 300s) | ✅ Solid |
| 3 | Max lifetime | Non-resettable scheduled task (default 1800s) | ✅ Solid |
| 4 | Shutdown command | `{"command":"shutdown"}` → `System.exit(0)` | ✅ Solid |
| 5 | Shutdown hook | `Runtime.addShutdownHook` for SIGTERM/SIGINT cleanup | ✅ Solid |

### Gaps Identified

| Gap | Risk | Severity | Mitigation |
|-----|------|----------|------------|
| **No parent PID monitoring** | If stdin is somehow kept open after parent dies (e.g., pipe inherited by grandchild), daemon lives until idle timeout | Low | Add `ProcessHandle.of(parentPid).onExit()` as Layer 1.5 |
| **No memory pressure detection** | Long-running daemon could accumulate memory (SemanticDB index grows, GC pressure) | Medium | Add heap monitoring: if used heap > 80% of max after GC, self-terminate |
| **`readLine()` blocking hides errors** | If stdin is in a weird state (not closed but not readable), daemon hangs on `readLine()` forever | Very Low | stdin pipe closure is reliable on all platforms |
| **No process group cleanup** | If daemon spawns child processes (it currently doesn't), they'd be orphaned | N/A | Not applicable today; document the constraint |
| **Scheduler thread leak on OOM** | If `System.exit()` fails or is blocked, scheduler thread prevents JVM exit — but `setDaemon(true)` handles this | Very Low | Already mitigated: thread is daemon thread |
| **No startup timeout** | If index build hangs (e.g., corrupt `.semanticdb` files), daemon never sends ready signal | Low | Add startup timeout with `System.exit(1)` |

---

## 4. Recommended Improvements

### 4.1 Add Parent PID Monitoring (Layer 1.5)

Use Java 9+ `ProcessHandle` API as a backup for stdin EOF:

```scala
// Accept parent PID from caller (e.g., the agent's PID)
// Monitor it in background — if parent exits, we exit
private def monitorParentPid(parentPid: Long): Unit =
  ProcessHandle.of(parentPid).ifPresent { handle =>
    handle.onExit().thenRun { () =>
      System.err.println(s"Parent process $parentPid exited, shutting down.")
      System.exit(0)
    }
  }
```

**Protocol change**: The agent passes its PID via the first message or a `--parent-pid` flag. If not provided, skip this layer (graceful degradation).

**Why**: This covers the edge case where stdin is inherited by a grandchild process, keeping the pipe open even after the direct parent dies. `ProcessHandle.onExit()` is non-blocking, cross-platform, and adds zero polling overhead.

### 4.2 Add Heap Pressure Detection

Detect when the JVM is running out of memory and self-terminate before OOM:

```scala
private def startHeapMonitor(scheduler: ScheduledExecutorService): Unit =
  scheduler.scheduleAtFixedRate(
    { () =>
      val runtime = Runtime.getRuntime()
      val used = runtime.totalMemory() - runtime.freeMemory()
      val max = runtime.maxMemory()
      if used.toDouble / max > 0.90 then
        System.gc()
        val usedAfterGc = runtime.totalMemory() - runtime.freeMemory()
        if usedAfterGc.toDouble / max > 0.85 then
          System.err.println(s"Heap pressure too high (${usedAfterGc / 1024 / 1024}MB / ${max / 1024 / 1024}MB), exiting.")
          System.exit(0)
    }: Runnable,
    60, 60, TimeUnit.SECONDS
  )
```

**Why**: Gradle's OOM-but-not-dying bug (#948) showed that JVM daemons can enter a broken state where GC thrashing makes them useless but `OutOfMemoryError` doesn't trigger `System.exit()`. Proactive detection prevents this.

### 4.3 Add Startup Timeout

If index building hangs, the agent waits forever for the `ready` signal:

```scala
// In runDaemon, before index.build():
val startupTimer = scheduler.schedule(
  (() => {
    System.err.println("Startup timeout (60s), exiting.")
    System.exit(1)
  }): Runnable,
  60, TimeUnit.SECONDS,
)
// After successful build:
startupTimer.cancel(false)
```

**Why**: Corrupt `.semanticdb` files, huge codebases, or disk I/O issues could cause `index.build()` to hang. A 60-second startup timeout ensures the agent gets a clear failure signal.

### 4.4 Document Invariants as Code

Add a comment block at the top of `daemon.scala` that codifies the termination contract:

```
// TERMINATION CONTRACT — every code path MUST lead to eventual JVM exit.
//
// Seven defensive layers (ordered by reliability):
//   1. Stdin EOF          — pipe closure on parent death (even SIGKILL)
//   1.5 Parent PID exit   — ProcessHandle.onExit() backup
//   2. Idle timeout       — no request for 5 min (resettable)
//   3. Max lifetime       — 30 min hard cap (non-resettable)
//   4. Shutdown command   — explicit {"command":"shutdown"}
//   5. Heap pressure      — >85% heap after GC → exit
//   6. Shutdown hook      — SIGTERM/SIGINT cleanup
//   7. Startup timeout    — 60s to build index or die
//
// RULES:
//   - Never add keep-alive logic, reconnection attempts, or retry loops
//   - Never weaken any termination layer
//   - If something goes wrong, the correct behavior is to die
//   - All exits use System.exit(0) except startup failure (exit code 1)
```

---

## 5. Testing Strategy

### 5.1 Test Matrix

Every termination layer must have a dedicated test. The daemon is useless if it works perfectly but can't be trusted to die.

| Test | What It Proves | How |
|------|----------------|-----|
| **Stdin EOF** | Daemon exits when parent closes stdin | Start daemon subprocess, close its stdin, assert exit within 5s |
| **Idle timeout** | Daemon exits after configured idle period | Start with `idleTimeoutSec=3`, send nothing, assert exit within 5s |
| **Max lifetime** | Daemon exits after hard cap | Start with `maxLifetimeSec=3`, send heartbeats every 1s, assert exit within 5s |
| **Shutdown command** | Clean explicit shutdown | Send `{"command":"shutdown"}`, assert exit within 2s |
| **SIGTERM** | Signal-based shutdown | Send SIGTERM, assert exit within 2s |
| **Heartbeat keeps alive** | Daemon doesn't die prematurely | Start with `idleTimeoutSec=2`, send heartbeat at 1s, assert still alive at 3s |
| **Parent PID exit** | Daemon exits when monitored parent exits | Start with parent PID of a short-lived process, assert daemon exits after it |
| **Heap pressure** | Daemon exits under memory pressure | (Hard to unit test; integration test with `-Xmx32m` and large index) |
| **Startup timeout** | Daemon exits if build hangs | (Mock/fault injection on index build) |
| **Orphan check** | No zombies after test suite | After all tests, `pgrep -f scalex-sdb` finds zero processes |

### 5.2 Implementation Pattern

Tests should use **real subprocess spawning**, not mocks:

```scala
class DaemonLifecycleTest extends munit.FunSuite:
  // Helper: start daemon as real subprocess
  private def startDaemon(
    idleTimeout: Int = 300,
    maxLifetime: Int = 1800,
  ): Process =
    ProcessBuilder(
      "scala-cli", "run", "scalex-semanticdb/src/", "--",
      "daemon", idleTimeout.toString, maxLifetime.toString,
    )
      .directory(workspaceDir.toFile)
      .redirectErrorStream(false)
      .start()

  test("stdin-eof-terminates-daemon"):
    val proc = startDaemon()
    // Wait for ready signal
    val reader = BufferedReader(InputStreamReader(proc.getInputStream))
    val ready = reader.readLine()
    assert(ready.contains("\"event\":\"ready\""))
    // Close stdin → daemon must exit
    proc.getOutputStream.close()
    val exited = proc.waitFor(5, TimeUnit.SECONDS)
    assert(exited, "Daemon did not exit within 5s of stdin closure")
    assertEquals(proc.exitValue(), 0)

  test("idle-timeout-terminates-daemon"):
    val proc = startDaemon(idleTimeout = 3)
    val reader = BufferedReader(InputStreamReader(proc.getInputStream))
    reader.readLine() // consume ready
    // Send nothing — daemon must exit after ~3s
    val exited = proc.waitFor(6, TimeUnit.SECONDS)
    assert(exited, "Daemon did not exit after idle timeout")

  test("max-lifetime-terminates-daemon"):
    val proc = startDaemon(idleTimeout = 300, maxLifetime = 3)
    val reader = BufferedReader(InputStreamReader(proc.getInputStream))
    val writer = BufferedWriter(OutputStreamWriter(proc.getOutputStream))
    reader.readLine() // consume ready
    // Send heartbeats to prevent idle timeout
    Thread:
      for _ <- 1 to 5 do
        Thread.sleep(1000)
        writer.write("{\"command\":\"heartbeat\"}\n")
        writer.flush()
    .start()
    // Despite activity, daemon must exit after max lifetime
    val exited = proc.waitFor(6, TimeUnit.SECONDS)
    assert(exited, "Daemon did not exit after max lifetime")

  // After all tests: verify no orphan daemons
  override def afterAll(): Unit =
    val result = ProcessBuilder("pgrep", "-f", "scalex-sdb.*daemon")
      .start().waitFor()
    assertEquals(result, 1, "Orphan daemon processes detected after test suite!")
```

### 5.3 Chaos Testing Checklist

Run these manually or in CI periodically:

- [ ] Start daemon, `kill -9` the parent shell — daemon exits within idle timeout
- [ ] Start daemon, `kill -TERM` the daemon — exits cleanly, no orphan threads
- [ ] Start daemon with `-Xmx64m`, query a huge codebase — daemon exits on heap pressure, no hang
- [ ] Start two daemons on the same workspace — second one handles contention gracefully
- [ ] Start daemon, corrupt `.semanticdb` files mid-session, send rebuild — daemon handles error, stays alive or exits cleanly
- [ ] Start daemon, unplug network drive where workspace lives — daemon detects I/O error, exits

### 5.4 CI Integration

Add to CI pipeline:

```yaml
# After running daemon tests:
- name: Check for orphan processes
  run: |
    if pgrep -f 'scalex-sdb.*daemon'; then
      echo "FAIL: Orphan daemon processes found"
      pkill -f 'scalex-sdb.*daemon'
      exit 1
    fi
```

---

## 6. Anti-Patterns Checklist

Things we must **never** add to the daemon:

| Anti-Pattern | Why | Who Got Burned |
|--------------|-----|----------------|
| Keep-alive / reconnection | Daemon should die, not reconnect | General principle |
| Retry loops on failure | Masks bugs; zombie state | Gradle OOM (#948) |
| Long-lived PID files | Race conditions on lock + unlink | [Flohr analysis](https://www.guido-flohr.net/never-delete-your-pid-file/) |
| Daemon outliving all clients without timeout | Accumulates zombies | Bloop, Metals (#869) |
| Signal-based IPC | PID reuse → signal wrong process | Unix anti-pattern |
| Spawning incompatible daemon instances | Resource multiplication | Gradle (100+ daemons) |
| Trusting only one termination layer | Every mechanism has failure modes | Everyone |
| `Runtime.halt()` for normal exit | Skips shutdown hooks | — |
| Blocking I/O in shutdown hooks | Hangs JVM exit | Metals (#2820) |
| 3-hour idle timeout | Too long; wastes resources for hours | Gradle |

---

## 7. How Other Tools Manage Daemon Lifecycle

### Comparison Matrix

| Feature | scalex-sdb | sbt server | Bloop | Metals | Gradle | gopls | rust-analyzer |
|---------|-----------|------------|-------|--------|--------|-------|---------------|
| stdin EOF detection | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Parent PID monitoring | ✅ | ❌ | ❌ | ✅ (fixed) | ❌ | ❌ | ❌ |
| Idle timeout | ✅ 5min | ✅ 5min | ❌ | ❌ | ✅ 3hr | ✅ 1min | ❌ |
| Max lifetime cap | ✅ 30min | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Explicit shutdown | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Shutdown hook | ✅ | ✅ | ✅ | ✅ | ✅ | N/A | N/A |
| Heap monitoring | ✅ | ❌ | ❌ | ❌ | ✅ | N/A | N/A |
| Lifecycle tests | ✅ | ❌ | Partial | Partial | Partial | ❌ | ❌ |

scalex-sdb has more termination layers (8) than any tool surveyed, with full lifecycle test coverage.

---

## 8. Design Principles (Summary)

1. **Die eagerly**: When in doubt, exit. A dead daemon is restarted in seconds; a zombie daemon wastes resources for hours.

2. **Defense in depth**: No single termination mechanism is sufficient. Layer at least 5 independent kill paths.

3. **No keep-alive**: If the pipe breaks, the parent dies, or something goes wrong — exit. Never reconnect, never retry.

4. **Prefer pipe over poll**: stdin EOF is instant and zero-cost. PID polling has latency. Use pipes as primary, PID monitoring as backup.

5. **Short timeouts**: 5-minute idle, 30-minute lifetime. Users restart in seconds; zombies waste hours.

6. **Test every kill path**: If you can't prove it dies, assume it doesn't. Every termination layer needs a dedicated test with a real subprocess.

7. **Monitor resources**: A daemon that runs out of memory and enters GC thrashing is worse than a daemon that exits cleanly. Detect pressure early.

8. **Daemon threads only**: All background threads must be `setDaemon(true)` so they don't prevent JVM exit if `System.exit()` is somehow bypassed.

---

## References

- [LSP Specification 3.17 — Lifecycle](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/)
- [Dask Distributed — Pipe-based parent death detection (PR #1345)](https://github.com/dask/distributed/pull/1345)
- [LSP4J — stdin closure as shutdown (Issue #161)](https://github.com/eclipse-lsp4j/lsp4j/issues/161)
- [Metals — Zombie JVM processes (Issue #869)](https://github.com/scalameta/metals/issues/869)
- [Metals — Dangling JVM on shutdown (Issue #2820)](https://github.com/scalameta/metals/issues/2820)
- [sbt — Orphan forked processes (Issue #7468)](https://github.com/sbt/sbt/issues/7468)
- [Gradle Daemon Documentation](https://docs.gradle.org/current/userguide/gradle_daemon.html)
- [Gradle — Multiple daemons exhausting memory](https://discuss.gradle.org/t/tons-of-gradle-daemons-exhausting-memory/20579)
- [Gradle — OOM not terminating daemon (Issue #948)](https://github.com/gradle/gradle/issues/948)
- [Bloop Server Reference](https://scalacenter.github.io/bloop/docs/server-reference)
- [libdaemon-jvm](https://github.com/scala-cli/libdaemon-jvm)
- [Gopls Daemon Mode](https://go.dev/gopls/daemon)
- [Mill Process Architecture](https://mill-build.org/mill/depth/process-architecture.html)
- [Never Delete Your PID File (Guido Flohr)](https://www.guido-flohr.net/never-delete-your-pid-file/)
- [Reflections on Writing Unix Daemons (Laurence Tratt, 2024)](https://tratt.net/laurie/blog/2024/some_reflections_on_writing_unix_daemons.html)
- [systemd Watchdog (Lennart Poettering)](http://0pointer.de/blog/projects/watchdog.html)
- [Dealing with Process Termination in Linux (iximiuz)](https://iximiuz.com/en/posts/dealing-with-processes-termination-in-Linux/)
- [Killing a Process and All Its Descendants](https://morningcoffee.io/killing-a-process-and-all-of-its-descendants)
- [Java ProcessHandle API (JDK 9+)](https://docs.oracle.com/javase/9/docs/api/java/lang/ProcessHandle.html)
- [JVM Shutdown Hooks Best Practices (Baeldung)](https://www.baeldung.com/jvm-shutdown-hooks)
- [rust-analyzer — Leftover processes (Issue #5258)](https://github.com/rust-lang/rust-analyzer/issues/5258)
- [rust-analyzer — Resource consumption (Issue #8451)](https://github.com/rust-lang/rust-analyzer/issues/8451)
- [VirtusLab/scala-cli — Zombie lock error (Issue #2899)](https://github.com/VirtusLab/scala-cli/issues/2899)
