import java.nio.file.{Files, Path}
import java.util.concurrent.{CompletableFuture, Executors, ScheduledFuture, TimeUnit}

// ── Daemon mode ───────────────────────────────────────────────────────────
//
// TERMINATION CONTRACT — every code path MUST lead to eventual JVM exit.
//
// Eight defensive layers (ordered by reliability):
//   1.   Stdin EOF           — pipe closure on parent death (even SIGKILL)
//                              Inactive in --socket mode (daemon blocks on accept, not stdin)
//   1.5  Parent PID exit    — ProcessHandle.onExit() backup (--parent-pid)
//                              Recommended in --socket mode; without it, Layers 2+3 handle cleanup
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

def runDaemon(workspace: Path, idleTimeoutSec: Long, maxLifetimeSec: Long, parentPid: Option[Long] = None, socketMode: Boolean = false): Unit =
  // Socket mode doesn't read stdin, so Layer 1 (stdin EOF) is inactive.
  // --parent-pid is recommended but not required — idle timeout (Layer 2)
  // and max lifetime (Layer 3) are sufficient orphan guards.
  if socketMode && parentPid.isEmpty then
    System.err.println("Warning: --socket without --parent-pid relies on idle/max-lifetime timeouts for cleanup.")

  System.err.println("sdbx daemon starting...")

  // Fail fast: check if a socket daemon is already running before expensive index build
  if socketMode then
    val sockPath = socketPath(workspace)
    if !isStaleSocket(sockPath) && java.nio.file.Files.exists(sockPath) then
      System.err.println(s"Socket already exists and daemon is running: $sockPath")
      System.exit(1)

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

  // Layer 1.5: Parent PID monitoring (required in socket mode)
  parentPid.foreach { pid =>
    val handleOpt = ProcessHandle.of(pid)
    if handleOpt.isEmpty then
      // Parent already dead — die immediately per termination contract
      System.err.println(s"Parent process $pid not found (already exited), shutting down.")
      System.exit(0)
    handleOpt.get().onExit().thenRun { () =>
      System.err.println(s"Parent process $pid exited, shutting down.")
      System.exit(0)
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

  // Set up socket server before ready signal (so it's accepting when clients connect)
  val serverOpt = if socketMode then
    val server = setupSocketServer(workspace)
    Some(server)
  else None

  // Ready signal
  resetIdleTimer()
  println(s"""{"ok":true,"event":"ready","files":${index.fileCount},"symbols":${index.symbolCount},"occurrences":${index.occurrenceCount},"buildTimeMs":${index.buildTimeMs}}""")
  System.out.flush()

  if socketMode then
    runSocketLoop(serverOpt.get, index, workspace, queryExecutor, resetIdleTimer, lastBuildMs)
  else
    // Layer 1: Stdin EOF — main loop
    val reader = java.io.BufferedReader(java.io.InputStreamReader(System.in))
    runStdinLoop(reader, index, workspace, queryExecutor, resetIdleTimer, lastBuildMs)

// ── Stdin loop ───────────────────────────────────────────────────────────

private def runStdinLoop(
  reader: java.io.BufferedReader, index: SemIndex, workspace: Path,
  queryExecutor: java.util.concurrent.ExecutorService,
  resetIdleTimer: () => Unit, initialLastBuildMs: Long,
): Unit =
  var lastBuildMs = initialLastBuildMs
  while true do
    val line = reader.readLine()
    if line == null then
      System.err.println("Stdin closed, exiting.")
      System.exit(0)

    val trimmed = line.trim
    if trimmed.nonEmpty then
      resetIdleTimer()
      SemTimings.reset()
      val result = processQuery(trimmed, index, workspace, lastBuildMs, queryExecutor)
      if result.rebuilt then lastBuildMs = System.currentTimeMillis()
      println(result.json)
      System.out.flush()
      if result.shutdown then System.exit(0)

// ── Socket loop ──────────────────────────────────────────────────────────

private def socketPath(workspace: Path): Path =
  // Unix domain sockets have a 104-byte path limit on macOS.
  // Use /tmp with a hash of the workspace to keep it short.
  val hash = Integer.toHexString(workspace.toAbsolutePath.normalize.toString.hashCode & 0x7fffffff)
  Path.of(System.getProperty("java.io.tmpdir")).resolve(s"sdbx-$hash.sock")

private def cleanupSocket(sockPath: java.nio.file.Path): Unit =
  try java.nio.file.Files.deleteIfExists(sockPath) catch case _: Exception => ()

private def isStaleSocket(sockPath: java.nio.file.Path): Boolean =
  if !java.nio.file.Files.exists(sockPath) then false
  else
    try
      val addr = java.net.UnixDomainSocketAddress.of(sockPath)
      val ch = java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX)
      try
        ch.connect(addr)
        false // connected successfully — daemon is running, NOT stale
      catch
        case _: java.net.ConnectException | _: java.io.IOException =>
          true // can't connect — stale socket file
      finally
        try ch.close() catch case _: Exception => ()
    catch
      case _: Exception => true

private def setupSocketServer(workspace: Path): java.nio.channels.ServerSocketChannel =
  val sockPath = socketPath(workspace)

  if isStaleSocket(sockPath) then
    System.err.println(s"Removing stale socket: $sockPath")
    cleanupSocket(sockPath)
  else if java.nio.file.Files.exists(sockPath) then
    System.err.println(s"Socket already exists and daemon is running: $sockPath")
    System.exit(1)

  java.nio.file.Files.createDirectories(sockPath.getParent)

  val addr = java.net.UnixDomainSocketAddress.of(sockPath)
  val server = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)
  server.bind(addr)

  // Lock down permissions — owner-only (rwx------) to prevent other local users
  // from reading codebase data or sending shutdown commands.
  java.nio.file.Files.setPosixFilePermissions(sockPath,
    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))

  Runtime.getRuntime().addShutdownHook(new Thread {
    override def run(): Unit = cleanupSocket(sockPath)
  })

  System.err.println(s"Listening on socket: $sockPath")
  server

private def runSocketLoop(
  server: java.nio.channels.ServerSocketChannel, index: SemIndex, workspace: Path,
  queryExecutor: java.util.concurrent.ExecutorService,
  resetIdleTimer: () => Unit, initialLastBuildMs: Long,
): Unit =
  var lastBuildMs = initialLastBuildMs

  while true do
    val client = server.accept()
    try
      val reader = java.io.BufferedReader(
        java.io.InputStreamReader(java.nio.channels.Channels.newInputStream(client))
      )
      val line = reader.readLine()

      if line != null && line.trim.nonEmpty then
        resetIdleTimer()
        SemTimings.reset()
        val result = processQuery(line.trim, index, workspace, lastBuildMs, queryExecutor)
        if result.rebuilt then lastBuildMs = System.currentTimeMillis()

        val out = java.nio.channels.Channels.newOutputStream(client)
        out.write((result.json + "\n").getBytes("UTF-8"))
        out.flush()

        if result.shutdown then
          try client.close() catch case _: Exception => ()
          System.exit(0)
    catch
      case e: Exception =>
        System.err.println(s"Socket client error: ${e.getMessage}")
    finally
      try client.close() catch case _: Exception => ()

// ── Request handling ──────────────────────────────────────────────────────

private case class DaemonResponse(json: String, rebuilt: Boolean = false, shutdown: Boolean = false)

private case class QueryResult(json: String, rebuilt: Boolean = false, shutdown: Boolean = false)

private def processQuery(
  line: String, index: SemIndex, workspace: Path, lastBuildMs: Long,
  queryExecutor: java.util.concurrent.ExecutorService,
): QueryResult =
  try
    val future = CompletableFuture.supplyAsync(
      { () => handleDaemonRequest(line, index, workspace, lastBuildMs) },
      queryExecutor,
    )
    val response =
      try future.get(QueryTimeoutSec, TimeUnit.SECONDS)
      catch
        case _: java.util.concurrent.TimeoutException =>
          future.cancel(true)
          DaemonResponse(s"""{"ok":false,"error":"timeout","message":"Query timed out after ${QueryTimeoutSec}s"}""")
    QueryResult(response.json, response.rebuilt, response.shutdown)
  catch
    case e: java.util.concurrent.ExecutionException =>
      val cause = if e.getCause != null then e.getCause else e
      System.err.println(s"Error: ${cause.getMessage}")
      QueryResult(s"""{"ok":false,"error":"internal","message":${jsonStr(cause.getMessage)}}""")
    case e: Exception =>
      System.err.println(s"Error: ${e.getMessage}")
      QueryResult(s"""{"ok":false,"error":"internal","message":${jsonStr(e.getMessage)}}""")

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
      DaemonResponse("""{"ok":true}""", shutdown = true)

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
