import munit.FunSuite
import java.nio.file.{Files, Path}
import java.io.{BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** Daemon lifecycle tests — subprocess-based.
  *
  * Each test spawns a real daemon process via `scala-cli run`, sends JSON-lines
  * commands, and asserts termination behavior. These tests verify the termination
  * contract documented in daemon.scala.
  */
class DaemonLifecycleTest extends FunSuite:

  private var workspace: Path = scala.compiletime.uninitialized

  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  override def beforeAll(): Unit =
    workspace = Files.createTempDirectory("sdbx-daemon-test")
    val srcDir = workspace.resolve("src")
    Files.createDirectories(srcDir)
    writeMinimalFixture(srcDir)
    compileWithSemanticdb(srcDir, workspace.resolve("out"))

  override def afterAll(): Unit =
    deleteRecursive(workspace)

  // ── Tests ──────────────────────────────────────────────────────────────

  test("stdin-eof: closing stdin terminates daemon") {
    val proc = startDaemon()
    val reader = waitForReady(proc)
    // Close stdin — daemon must detect EOF and exit
    proc.getOutputStream.close()
    val exited = proc.waitFor(15, TimeUnit.SECONDS)
    assert(exited, "Daemon did not exit within 15s of stdin closure")
    assertEquals(proc.exitValue(), 0)
  }

  test("idle-timeout: daemon exits after idle period") {
    val proc = startDaemon(idleTimeout = 3)
    val reader = waitForReady(proc)
    // Send nothing — daemon must exit after ~3s idle
    val exited = proc.waitFor(15, TimeUnit.SECONDS)
    assert(exited, "Daemon did not exit after idle timeout")
    assertEquals(proc.exitValue(), 0)
  }

  test("max-lifetime: daemon exits despite activity") {
    val proc = startDaemon(idleTimeout = 300, maxLifetime = 4)
    val reader = waitForReady(proc)
    // Send heartbeats to prevent idle timeout
    val heartbeatThread = new Thread:
      override def run(): Unit =
        try
          for _ <- 1 to 10 do
            Thread.sleep(1000)
            sendCommand(proc, """{"command":"heartbeat"}""")
            reader.readLine() // consume response
        catch case _: Exception => () // expected when daemon exits
    heartbeatThread.setDaemon(true)
    heartbeatThread.start()
    // Despite activity, daemon must exit after max lifetime
    val exited = proc.waitFor(15, TimeUnit.SECONDS)
    assert(exited, "Daemon did not exit after max lifetime")
    assertEquals(proc.exitValue(), 0)
  }

  test("shutdown-command: explicit shutdown exits daemon") {
    val proc = startDaemon()
    val reader = waitForReady(proc)
    sendCommand(proc, """{"command":"shutdown"}""")
    val response = readResponse(reader)
    assert(response.contains("\"ok\":true"), s"Expected ok response, got: $response")
    val exited = proc.waitFor(10, TimeUnit.SECONDS)
    assert(exited, "Daemon did not exit after shutdown command")
    assertEquals(proc.exitValue(), 0)
  }

  test("heartbeat-keeps-alive: daemon survives past idle timeout with activity") {
    val proc = startDaemon(idleTimeout = 2)
    val reader = waitForReady(proc)
    // Send heartbeat at ~1s to reset idle timer
    Thread.sleep(1000)
    sendCommand(proc, """{"command":"heartbeat"}""")
    val hbResponse = readResponse(reader)
    assert(hbResponse.contains("\"ok\":true"))
    // At ~3s total (past original idle timeout of 2s), daemon should still be alive
    Thread.sleep(2000)
    assert(proc.isAlive, "Daemon died prematurely despite heartbeat activity")
    // Clean up
    proc.getOutputStream.close()
    proc.waitFor(15, TimeUnit.SECONDS)
  }

  test("query-response: real command returns valid JSON") {
    val proc = startDaemon()
    val reader = waitForReady(proc)
    sendCommand(proc, """{"command":"stats"}""")
    val response = readResponse(reader)
    assert(response.contains("\"ok\":true"), s"Expected ok stats response, got: $response")
    assert(response.contains("\"files\""), s"Expected files in stats, got: $response")
    // Clean up
    proc.getOutputStream.close()
    proc.waitFor(15, TimeUnit.SECONDS)
  }

  test("error-recovery: malformed JSON returns error, daemon stays alive") {
    val proc = startDaemon()
    val reader = waitForReady(proc)
    // Send garbage
    sendCommand(proc, "this is not json")
    val errResponse = readResponse(reader)
    assert(errResponse.contains("\"ok\":false"), s"Expected error response, got: $errResponse")
    assert(errResponse.contains("parse_error"), s"Expected parse_error, got: $errResponse")
    // Daemon should still be alive
    assert(proc.isAlive, "Daemon died after malformed input")
    // Send valid command to prove it's functional
    sendCommand(proc, """{"command":"heartbeat"}""")
    val okResponse = readResponse(reader)
    assert(okResponse.contains("\"ok\":true"), s"Expected ok after recovery, got: $okResponse")
    // Clean up
    proc.getOutputStream.close()
    proc.waitFor(15, TimeUnit.SECONDS)
  }

  // ── FIFO tests ──────────────────────────────────────────────────────

  test("fifo-query-response: daemon reads from named pipe and responds") {
    val fifo = workspace.resolve("sdbx_test.fifo")
    mkfifo(fifo)
    try
      val proc = startDaemon(fifoPath = Some(fifo))
      // Open FIFO for writing (must happen after daemon opens it for reading)
      // Use a thread because opening a FIFO for writing blocks until a reader exists
      val writerThread = new Thread:
        override def run(): Unit =
          val fos = java.io.FileOutputStream(fifo.toFile)
          try
            fos.write(("""{"command":"stats"}""" + "\n").getBytes)
            fos.flush()
            // Keep pipe open long enough for response
            Thread.sleep(3000)
            fos.write(("""{"command":"shutdown"}""" + "\n").getBytes)
            fos.flush()
          finally fos.close()
      writerThread.setDaemon(true)
      writerThread.start()

      val reader = waitForReady(proc)
      val response = readResponse(reader)
      assert(response.contains("\"ok\":true"), s"Expected ok stats response via FIFO, got: $response")
      assert(response.contains("\"files\""), s"Expected files in stats, got: $response")
      val exited = proc.waitFor(15, TimeUnit.SECONDS)
      assert(exited, "Daemon did not exit after shutdown via FIFO")
      assertEquals(proc.exitValue(), 0)
    finally Files.deleteIfExists(fifo)
  }

  test("fifo-eof: closing the named pipe terminates daemon") {
    val fifo = workspace.resolve("sdbx_eof.fifo")
    mkfifo(fifo)
    try
      val proc = startDaemon(fifoPath = Some(fifo))
      // Open FIFO, let daemon read ready signal, then close to trigger EOF
      val writerThread = new Thread:
        override def run(): Unit =
          val fos = java.io.FileOutputStream(fifo.toFile)
          // Give daemon time to emit ready signal
          Thread.sleep(2000)
          fos.close() // EOF → daemon should exit
      writerThread.setDaemon(true)
      writerThread.start()

      val reader = waitForReady(proc)
      val exited = proc.waitFor(15, TimeUnit.SECONDS)
      assert(exited, "Daemon did not exit within 15s of FIFO closure")
      assertEquals(proc.exitValue(), 0)
    finally Files.deleteIfExists(fifo)
  }

  test("fifo-not-found: non-existent FIFO path exits with error") {
    val bogus = workspace.resolve("does_not_exist.fifo")
    val proc = startDaemon(fifoPath = Some(bogus))
    val exited = proc.waitFor(30, TimeUnit.SECONDS)
    assert(exited, "Daemon did not exit for non-existent FIFO")
    assert(proc.exitValue() != 0, s"Expected non-zero exit for missing FIFO, got: ${proc.exitValue()}")
  }

  // ── Helpers ────────────────────────────────────────────────────────────

  private val sdbxSrcDir = Path.of("scalex-semanticdb/src").toAbsolutePath.toString

  private def startDaemon(
    idleTimeout: Int = 300,
    maxLifetime: Int = 1800,
    parentPid: Option[Long] = None,
    fifoPath: Option[Path] = None,
  ): Process =
    val baseArgs = List("scala-cli", "run", sdbxSrcDir, "--")
    val daemonArgs = List("daemon")
    val pidArgs = parentPid.map(p => List("--parent-pid", p.toString)).getOrElse(Nil)
    val fifoArgs = fifoPath.map(f => List("--fifo", f.toString)).getOrElse(Nil)
    val positionalArgs = List(idleTimeout.toString, maxLifetime.toString)
    val wsArgs = List("--workspace", workspace.toString)

    val cmd = baseArgs ++ daemonArgs ++ pidArgs ++ fifoArgs ++ positionalArgs ++ wsArgs
    val pb = ProcessBuilder(cmd*)
    pb.redirectErrorStream(false)
    pb.start()

  private def waitForReady(proc: Process, timeoutSec: Int = 90): BufferedReader =
    val reader = BufferedReader(InputStreamReader(proc.getInputStream))
    val ready = reader.readLine()
    assert(ready != null, "Daemon exited before sending ready signal")
    assert(ready.contains("\"event\":\"ready\""), s"Expected ready signal, got: $ready")
    reader

  private def sendCommand(proc: Process, json: String): Unit =
    val writer = proc.getOutputStream
    writer.write((json + "\n").getBytes)
    writer.flush()

  private def readResponse(reader: BufferedReader): String =
    val line = reader.readLine()
    assert(line != null, "Daemon closed stdout unexpectedly")
    line

  // ── Fixture setup ──────────────────────────────────────────────────────

  private def writeMinimalFixture(srcDir: Path): Unit =
    val file = srcDir.resolve("example/Animal.scala")
    Files.createDirectories(file.getParent)
    Files.writeString(file,
      """package example
        |
        |trait Animal:
        |  def name: String
        |  def sound: String
        |
        |class Dog(val name: String) extends Animal:
        |  def sound: String = "Woof"
        |""".stripMargin)

  private def compileWithSemanticdb(srcDir: Path, outDir: Path): Unit =
    Files.createDirectories(outDir)
    val cmd = List(
      "scala-cli", "compile",
      "--scala", "3.8.2",
      "--scalac-option", "-Xsemanticdb",
      "--scalac-option", "-semanticdb-target",
      "--scalac-option", outDir.toString,
      srcDir.toString,
    )
    val pb = ProcessBuilder(cmd*)
    pb.directory(workspace.toFile)
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val output = String(proc.getInputStream.readAllBytes())
    val exit = proc.waitFor()
    assert(exit == 0, s"Test fixture compilation failed:\n$output")

  private def mkfifo(path: Path): Unit =
    val proc = ProcessBuilder("mkfifo", path.toString).start()
    val exit = proc.waitFor()
    assert(exit == 0, s"mkfifo failed with exit code $exit")

  private def deleteRecursive(path: Path): Unit =
    if Files.isDirectory(path) then
      Files.list(path).iterator().asScala.foreach(deleteRecursive)
    Files.deleteIfExists(path)
