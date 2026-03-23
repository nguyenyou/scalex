import java.nio.file.Path
import java.util.concurrent.{Executors, ScheduledFuture, TimeUnit}

// ── Daemon mode ───────────────────────────────────────────────────────────

case class DaemonRequest(
  command: String,
  args: List[String] = Nil,
  flags: Map[String, String] = Map.empty,
)

def runDaemon(workspace: Path, idleTimeoutSec: Long, maxLifetimeSec: Long): Unit =
  // 1. Build index with full occurrences
  System.err.println("scalex-sdb daemon starting...")
  val index = SemIndex(workspace)
  index.build(needOccurrences = true)
  var lastBuildMs = System.currentTimeMillis()

  // 2. Self-termination timers
  val scheduler = Executors.newSingleThreadScheduledExecutor { r =>
    val t = Thread(r, "daemon-watchdog")
    t.setDaemon(true)
    t
  }

  @volatile var idleTask: ScheduledFuture[?] = null

  def resetIdleTimer(): Unit =
    if idleTask != null then idleTask.cancel(false)
    idleTask = scheduler.schedule(
      (() => { System.err.println("Idle timeout, exiting."); System.exit(0) }): Runnable,
      idleTimeoutSec, TimeUnit.SECONDS,
    )

  // Max lifetime — non-resettable
  scheduler.schedule(
    (() => { System.err.println("Max lifetime reached, exiting."); System.exit(0) }): Runnable,
    maxLifetimeSec, TimeUnit.SECONDS,
  )

  // 3. Shutdown hook
  val shutdownThread = new Thread:
    override def run(): Unit =
      System.err.println("Daemon shutting down.")
      scheduler.shutdownNow()
  Runtime.getRuntime().addShutdownHook(shutdownThread)

  // 4. Ready signal
  resetIdleTimer()
  println(s"""{"ok":true,"event":"ready","files":${index.fileCount},"symbols":${index.symbolCount},"occurrences":${index.occurrenceCount},"buildTimeMs":${index.buildTimeMs}}""")
  System.out.flush()

  // 5. Main loop
  val reader = java.io.BufferedReader(java.io.InputStreamReader(System.in))
  while true do
    val line = reader.readLine()
    if line == null then
      System.err.println("Stdin closed, exiting.")
      System.exit(0)

    val trimmed = line.trim
    if trimmed.nonEmpty then
      resetIdleTimer()
      SemTimings.reset()
      try
        val response = handleDaemonRequest(trimmed, index, workspace, lastBuildMs)
        // Update lastBuildMs if a rebuild happened
        if response.rebuilt then lastBuildMs = System.currentTimeMillis()
        println(response.json)
      catch
        case e: Exception =>
          System.err.println(s"Error: ${e.getMessage}")
          println(s"""{"ok":false,"error":"internal","message":${jsonStr(e.getMessage)}}""")
      System.out.flush()

// ── Request handling ──────────────────────────────────────────────────────

private case class DaemonResponse(json: String, rebuilt: Boolean = false)

private def handleDaemonRequest(line: String, index: SemIndex, workspace: Path, lastBuildMs: Long): DaemonResponse =
  val req = parseDaemonRequest(line) match
    case Left(err) => return DaemonResponse(s"""{"ok":false,"error":"parse_error","message":${jsonStr(err)}}""")
    case Right(r) => r

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

      // Handle batch separately (uses cleanArgs, not the command dispatch map)
      val result =
        if cmd == "batch" then runBatch(flags.cleanArgs, ctx)
        else
          commands.get(cmd) match
            case Some(handler) => handler(flags.cleanArgs, ctx)
            case None => return DaemonResponse(s"""{"ok":false,"error":"unknown_command","message":${jsonStr(s"Unknown command: $cmd")}}""")

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
