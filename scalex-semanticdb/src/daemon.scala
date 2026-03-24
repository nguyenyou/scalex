import java.nio.file.{Files, Path}
import java.util.concurrent.{CompletableFuture, Executors, ScheduledFuture, TimeUnit}

// ── Daemon mode ───────────────────────────────────────────────────────────
//
// TERMINATION CONTRACT — every code path MUST lead to eventual JVM exit.
//
// Eight defensive layers (ordered by reliability):
//   1.   Stdin/FIFO EOF     — pipe closure on parent death (even SIGKILL)
//   1.5  Parent PID exit    — ProcessHandle.onExit() backup (optional --parent-pid)
//   2.   Idle timeout       — no request for N seconds (resettable, default 300)
//   3.   Max lifetime       — hard cap regardless of activity (default 1800)
//   4.   Shutdown command   — explicit {"command":"shutdown"}
//   5.   Per-query timeout  — any query >30s returns error (prevents hung dispatch)
//   6.   Heap pressure      — >85% heap after GC → exit
//   7.   Startup timeout    — 120s to build index or die (exit code 1)
//   8.   Shutdown hook      — SIGTERM/SIGINT cleanup
//
// RULES:
//   - Never add keep-alive logic, reconnection attempts, or retry loops
//   - Never weaken any termination layer
//   - If something goes wrong, the correct behavior is to die
//   - All exits use System.exit(0) except startup failure (exit code 1)
//

case class DaemonRequest(
  command: String,
  args: List[String] = Nil,
  flags: Map[String, String] = Map.empty,
)

private val QueryTimeoutSec = 30L
private val StartupTimeoutSec = 120L
private val HeapCheckIntervalSec = 60L

def runDaemon(workspace: Path, idleTimeoutSec: Long, maxLifetimeSec: Long, parentPid: Option[Long] = None, fifoPath: Option[Path] = None): Unit =
  System.err.println("sdbx daemon starting...")

  // Fail fast: validate FIFO path before any expensive work
  fifoPath.foreach { fifo =>
    if !Files.exists(fifo) then
      System.err.println(s"FIFO not found: $fifo")
      System.exit(1)
    if Files.isRegularFile(fifo) then
      System.err.println(s"Not a FIFO (regular file): $fifo")
      System.exit(1)
  }

  // Self-termination timers (created early so startup timeout works)
  val scheduler = Executors.newSingleThreadScheduledExecutor { r =>
    val t = Thread(r, "daemon-watchdog")
    t.setDaemon(true)
    t
  }

  // Layer 7: Startup timeout — 120s to build index or die
  val startupTimer = scheduler.schedule(
    (() => { System.err.println("Startup timeout, exiting."); System.exit(1) }): Runnable,
    StartupTimeoutSec, TimeUnit.SECONDS,
  )

  // Build index with full occurrences
  val index = SemIndex(workspace)
  index.build(needOccurrences = true)
  startupTimer.cancel(false)
  var lastBuildMs = System.currentTimeMillis()

  // Layer 1.5: Parent PID monitoring (optional)
  parentPid.foreach { pid =>
    ProcessHandle.of(pid).ifPresent { handle =>
      handle.onExit().thenRun { () =>
        System.err.println(s"Parent process $pid exited, shutting down.")
        System.exit(0)
      }
    }
  }

  // Layer 2: Idle timeout (resettable)
  @volatile var idleTask: ScheduledFuture[?] = null

  def resetIdleTimer(): Unit =
    if idleTask != null then idleTask.cancel(false)
    idleTask = scheduler.schedule(
      (() => { System.err.println("Idle timeout, exiting."); System.exit(0) }): Runnable,
      idleTimeoutSec, TimeUnit.SECONDS,
    )

  // Layer 3: Max lifetime — non-resettable
  scheduler.schedule(
    (() => { System.err.println("Max lifetime reached, exiting."); System.exit(0) }): Runnable,
    maxLifetimeSec, TimeUnit.SECONDS,
  )

  // Layer 6: Heap pressure monitoring — exit if memory is critically low
  // Runs GC on a separate thread to avoid blocking the scheduler (which also
  // runs idle-timeout and max-lifetime tasks on its single thread).
  scheduler.scheduleAtFixedRate(
    { () =>
      val runtime = Runtime.getRuntime()
      val used = runtime.totalMemory() - runtime.freeMemory()
      val max = runtime.maxMemory()
      if max > 0 && used.toDouble / max > 0.90 then
        val gcThread = new Thread:
          override def run(): Unit =
            System.gc()
            val usedAfterGc = runtime.totalMemory() - runtime.freeMemory()
            if usedAfterGc.toDouble / max > 0.85 then
              System.err.println(s"Heap pressure critical (${usedAfterGc / 1024 / 1024}MB / ${max / 1024 / 1024}MB after GC), exiting.")
              System.exit(0)
        gcThread.setDaemon(true)
        gcThread.start()
    }: Runnable,
    HeapCheckIntervalSec, HeapCheckIntervalSec, TimeUnit.SECONDS,
  )

  // Single-thread executor for query dispatch — serializes all queries to prevent
  // concurrent mutation of SemIndex. If a query times out, the background thread
  // keeps running, but the next query queues behind it (same thread), so no two
  // queries ever mutate the index simultaneously.
  val queryExecutor = Executors.newSingleThreadExecutor { r =>
    val t = Thread(r, "daemon-query")
    t.setDaemon(true)
    t
  }

  // Layer 8: Shutdown hook
  val shutdownThread = new Thread:
    override def run(): Unit =
      System.err.println("Daemon shutting down.")
      scheduler.shutdownNow()
      queryExecutor.shutdownNow()
  Runtime.getRuntime().addShutdownHook(shutdownThread)

  // Ready signal
  resetIdleTimer()
  println(s"""{"ok":true,"event":"ready","files":${index.fileCount},"symbols":${index.symbolCount},"occurrences":${index.occurrenceCount},"buildTimeMs":${index.buildTimeMs}}""")
  System.out.flush()

  // Layer 1: Stdin/FIFO EOF — main loop
  val reader = fifoPath match
    case Some(fifo) =>
      System.err.println(s"Reading from FIFO: $fifo")
      java.io.BufferedReader(java.io.InputStreamReader(java.io.FileInputStream(fifo.toFile)))
    case None =>
      java.io.BufferedReader(java.io.InputStreamReader(System.in))
  while true do
    val line = reader.readLine()
    if line == null then
      System.err.println(s"${if fifoPath.isDefined then "FIFO" else "Stdin"} closed, exiting.")
      System.exit(0)

    val trimmed = line.trim
    if trimmed.nonEmpty then
      resetIdleTimer()
      SemTimings.reset()
      try
        // Layer 5: Per-query timeout — 30s max per query
        // Uses dedicated single-thread queryExecutor (not ForkJoinPool) to serialize
        // all queries and prevent concurrent mutation of SemIndex on timeout.
        val future = CompletableFuture.supplyAsync(
          { () => handleDaemonRequest(trimmed, index, workspace, lastBuildMs) },
          queryExecutor,
        )
        val response =
          try future.get(QueryTimeoutSec, TimeUnit.SECONDS)
          catch
            case _: java.util.concurrent.TimeoutException =>
              future.cancel(true)
              DaemonResponse(s"""{"ok":false,"error":"timeout","message":"Query timed out after ${QueryTimeoutSec}s"}""")
        if response.rebuilt then lastBuildMs = System.currentTimeMillis()
        println(response.json)
      catch
        case e: java.util.concurrent.ExecutionException =>
          val cause = if e.getCause != null then e.getCause else e
          System.err.println(s"Error: ${cause.getMessage}")
          println(s"""{"ok":false,"error":"internal","message":${jsonStr(cause.getMessage)}}""")
        case e: Exception =>
          System.err.println(s"Error: ${e.getMessage}")
          println(s"""{"ok":false,"error":"internal","message":${jsonStr(e.getMessage)}}""")
      System.out.flush()

// ── Request handling ──────────────────────────────────────────────────────

private case class DaemonResponse(json: String, rebuilt: Boolean = false)

private def handleDaemonRequest(line: String, index: SemIndex, workspace: Path, lastBuildMs: Long): DaemonResponse =
  parseDaemonRequest(line) match
    case Left(err) =>
      DaemonResponse(s"""{"ok":false,"error":"parse_error","message":${jsonStr(err)}}""")
    case Right(req) =>
      dispatchDaemonCommand(req, index, workspace, lastBuildMs)

private def dispatchDaemonCommand(req: DaemonRequest, index: SemIndex, workspace: Path, lastBuildMs: Long): DaemonResponse =
  req.command match
    case "heartbeat" =>
      DaemonResponse("""{"ok":true}""")

    case "shutdown" =>
      println("""{"ok":true}""")
      System.out.flush()
      System.exit(0)
      DaemonResponse("""{"ok":true}""") // unreachable

    case "rebuild" | "index" =>
      index.rebuild()
      val stats = s"""{"ok":true,"event":"rebuilt","files":${index.fileCount},"symbols":${index.symbolCount},"occurrences":${index.occurrenceCount},"buildTimeMs":${index.buildTimeMs}}"""
      DaemonResponse(stats, rebuilt = true)

    case "stats" =>
      val stats = s"""{"ok":true,"result":{"files":${index.fileCount},"symbols":${index.symbolCount},"occurrences":${index.occurrenceCount},"buildTimeMs":${index.buildTimeMs},"cached":${index.cachedLoad},"parsedCount":${index.parsedCount},"skippedCount":${index.skippedCount}}}"""
      DaemonResponse(stats)

    case cmd =>
      // Validate command exists before doing any work
      if cmd != "batch" && !commands.contains(cmd) then
        DaemonResponse(s"""{"ok":false,"error":"unknown_command","message":${jsonStr(s"Unknown command: $cmd")}}""")
      else
        // Check staleness before query (~7ms)
        val rebuilt = if index.isStale(lastBuildMs) then
          System.err.println("Index stale, rebuilding...")
          index.rebuild()
          true
        else false

        // Translate daemon request to CLI-style args and dispatch
        val argList = flagsToArgList(req.flags) ++ req.args
        val flags = parseFlags(argList)
        val ctx = flagsToContext(flags, index, workspace).copy(jsonOutput = true)

        val result =
          if cmd == "batch" then runBatch(flags.cleanArgs, ctx)
          else commands(cmd)(flags.cleanArgs, ctx)

        // Capture JSON output
        val buf = java.io.ByteArrayOutputStream()
        Console.withOut(buf) {
          Console.withErr(java.io.ByteArrayOutputStream()) {
            renderJson(result, ctx)
          }
        }
        val jsonResult = buf.toString("UTF-8").trim
        DaemonResponse(s"""{"ok":true,"result":$jsonResult}""", rebuilt = rebuilt)

// ── Flag translation ──────────────────────────────────────────────────────

private def flagsToArgList(flags: Map[String, String]): List[String] =
  flags.flatMap { (k, v) =>
    val flag = if k.startsWith("--") then k else s"--$k"
    if v.isEmpty || v == "true" then List(flag)
    else List(flag, v)
  }.toList

// ── JSON request parsing (no library, regex-based) ────────────────────────

private val cmdPattern = """"command"\s*:\s*"([^"]+)"""".r
private val argsPattern = """"args"\s*:\s*\[([^\]]*)\]""".r
private val strInArray = """"([^"]+)"""".r
private val flagsPattern = """"flags"\s*:\s*\{([^}]*)\}""".r
private val strKvPattern = """"([^"]+)"\s*:\s*"([^"]*)"""".r
private val numKvPattern = """"([^"]+)"\s*:\s*(\d+)""".r

private def parseDaemonRequest(line: String): Either[String, DaemonRequest] =
  cmdPattern.findFirstMatchIn(line).map(_.group(1)) match
    case None => Left("missing 'command' field")
    case Some(command) =>
      val args = argsPattern.findFirstMatchIn(line).map { m =>
        strInArray.findAllMatchIn(m.group(1)).map(_.group(1)).toList
      }.getOrElse(Nil)

      val flags = flagsPattern.findFirstMatchIn(line).map { m =>
        val content = m.group(1)
        val strFlags = strKvPattern.findAllMatchIn(content).map(fm => (fm.group(1), fm.group(2))).toMap
        val numFlags = numKvPattern.findAllMatchIn(content)
          .map(fm => (fm.group(1), fm.group(2)))
          .filterNot((k, _) => strFlags.contains(k))
          .toMap
        strFlags ++ numFlags
      }.getOrElse(Map.empty)

      Right(DaemonRequest(command, args, flags))
