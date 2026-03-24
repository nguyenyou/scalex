import munit.FunSuite
import java.nio.file.{Files, Path}
import java.io.{BufferedReader, InputStreamReader}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** Daemon lifecycle tests — subprocess-based.
  *
  * Each test spawns a real daemon process via `scala-cli run`, communicates
  * via Unix domain socket, and asserts termination behavior. These tests
  * verify the termination contract documented in daemon.scala.
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
    try Files.deleteIfExists(socketPath(workspace)) catch case _: Exception => ()
    deleteRecursive(workspace)

  // ── Tests ──────────────────────────────────────────────────────────────

  test("query-response: daemon accepts socket connection and responds") {
    val proc = startDaemon()
    waitForReady(proc)
    try
      val sockPath = socketPath(workspace)
      assert(Files.exists(sockPath), s"Socket file not created at $sockPath")
      val response = socketQuery(sockPath, """{"command":"stats"}""")
      assert(response.startsWith("SDBX_OK"), s"Expected SDBX_OK stats response, got: $response")
      assert(response.contains("Files:"), s"Expected Files: in stats, got: $response")
    finally
      sendSocketCommand(workspace, """{"command":"shutdown"}""")
      proc.waitFor(10, TimeUnit.SECONDS)
  }

  test("shutdown: shutdown command exits daemon and cleans up socket") {
    val proc = startDaemon()
    waitForReady(proc)
    val sockPath = socketPath(workspace)
    val response = socketQuery(sockPath, """{"command":"shutdown"}""")
    assert(response.startsWith("SDBX_OK"), s"Expected SDBX_OK response, got: $response")
    val exited = proc.waitFor(10, TimeUnit.SECONDS)
    assert(exited, "Daemon did not exit after shutdown command")
    assertEquals(proc.exitValue(), 0)
    Thread.sleep(500) // give shutdown hook time
    assert(!Files.exists(sockPath), "Socket file was not cleaned up")
  }

  test("idle-timeout: daemon exits after idle period") {
    val proc = startDaemon(idleTimeout = 3)
    waitForReady(proc)
    val exited = proc.waitFor(15, TimeUnit.SECONDS)
    assert(exited, "Daemon did not exit after idle timeout")
    assertEquals(proc.exitValue(), 0)
  }

  test("max-lifetime: daemon exits despite activity") {
    val proc = startDaemon(idleTimeout = 300, maxLifetime = 4)
    waitForReady(proc)
    val sockPath = socketPath(workspace)
    // Send heartbeats via socket to prevent idle timeout
    val heartbeatThread = new Thread:
      override def run(): Unit =
        try
          for _ <- 1 to 10 do
            Thread.sleep(1000)
            socketQuery(sockPath, """{"command":"heartbeat"}""")
        catch case _: Exception => () // expected when daemon exits
    heartbeatThread.setDaemon(true)
    heartbeatThread.start()
    // Despite activity, daemon must exit after max lifetime
    val exited = proc.waitFor(15, TimeUnit.SECONDS)
    assert(exited, "Daemon did not exit after max lifetime")
    assertEquals(proc.exitValue(), 0)
  }

  test("heartbeat-keeps-alive: daemon survives past idle timeout with activity") {
    val proc = startDaemon(idleTimeout = 2)
    waitForReady(proc)
    val sockPath = socketPath(workspace)
    // Send heartbeat at ~1s to reset idle timer
    Thread.sleep(1000)
    val hbResponse = socketQuery(sockPath, """{"command":"heartbeat"}""")
    assert(hbResponse.startsWith("SDBX_OK"))
    // At ~3s total (past original idle timeout of 2s), daemon should still be alive
    Thread.sleep(2000)
    assert(proc.isAlive, "Daemon died prematurely despite heartbeat activity")
    // Clean up
    sendSocketCommand(workspace, """{"command":"shutdown"}""")
    proc.waitFor(10, TimeUnit.SECONDS)
  }

  test("multiple-connections: sequential connections work") {
    val proc = startDaemon()
    waitForReady(proc)
    val sockPath = socketPath(workspace)
    try
      val r1 = socketQuery(sockPath, """{"command":"stats"}""")
      assert(r1.startsWith("SDBX_OK"), s"First query failed: $r1")
      val r2 = socketQuery(sockPath, """{"command":"heartbeat"}""")
      assert(r2.startsWith("SDBX_OK"), s"Second query failed: $r2")
      val r3 = socketQuery(sockPath, """{"command":"stats"}""")
      assert(r3.startsWith("SDBX_OK"), s"Third query failed: $r3")
    finally
      sendSocketCommand(workspace, """{"command":"shutdown"}""")
      proc.waitFor(10, TimeUnit.SECONDS)
  }

  test("error-recovery: malformed JSON returns error, daemon stays alive") {
    val proc = startDaemon()
    waitForReady(proc)
    val sockPath = socketPath(workspace)
    try
      val errResponse = socketQuery(sockPath, "this is not json")
      assert(errResponse.startsWith("SDBX_ERR"), s"Expected SDBX_ERR response, got: $errResponse")
      // Daemon should still accept connections
      val okResponse = socketQuery(sockPath, """{"command":"heartbeat"}""")
      assert(okResponse.startsWith("SDBX_OK"), s"Expected SDBX_OK after recovery, got: $okResponse")
    finally
      sendSocketCommand(workspace, """{"command":"shutdown"}""")
      proc.waitFor(10, TimeUnit.SECONDS)
  }

  // ── Helpers ────────────────────────────────────────────────────────────

  private val sdbxSrcDir = Path.of("scalex-semanticdb/src").toAbsolutePath.toString

  private def startDaemon(
    idleTimeout: Int = 300,
    maxLifetime: Int = 1800,
  ): Process =
    val baseArgs = List("scala-cli", "run", sdbxSrcDir, "--")
    val daemonArgs = List("daemon")
    val positionalArgs = List(idleTimeout.toString, maxLifetime.toString)
    val wsArgs = List("--workspace", workspace.toString)

    val cmd = baseArgs ++ daemonArgs ++ positionalArgs ++ wsArgs
    val pb = ProcessBuilder(cmd*)
    pb.redirectErrorStream(false)
    pb.start()

  private def waitForReady(proc: Process, timeoutSec: Int = 90): Unit =
    val reader = BufferedReader(InputStreamReader(proc.getInputStream))
    val ready = reader.readLine()
    assert(ready != null, "Daemon exited before sending ready signal")
    assert(ready.contains("sdbx daemon ready"), s"Expected ready signal, got: $ready")

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

  private def socketQuery(sockPath: Path, json: String): String =
    val addr = java.net.UnixDomainSocketAddress.of(sockPath)
    val ch = java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX)
    try
      ch.connect(addr)
      val out = java.nio.channels.Channels.newOutputStream(ch)
      out.write((json + "\n").getBytes("UTF-8"))
      out.flush()
      ch.shutdownOutput()
      val reader = BufferedReader(InputStreamReader(
        java.nio.channels.Channels.newInputStream(ch)))
      val sb = StringBuilder()
      var line = reader.readLine()
      while line != null do
        sb.append(line).append('\n')
        line = reader.readLine()
      val response = sb.toString.trim
      assert(response.nonEmpty, "Daemon closed connection without response")
      response
    finally ch.close()

  private def sendSocketCommand(workspace: Path, json: String): Unit =
    val sockPath = socketPath(workspace)
    if Files.exists(sockPath) then
      try socketQuery(sockPath, json) catch case _: Exception => ()

  private def deleteRecursive(path: Path): Unit =
    if Files.isDirectory(path) then
      Files.list(path).iterator().asScala.foreach(deleteRecursive)
    Files.deleteIfExists(path)
