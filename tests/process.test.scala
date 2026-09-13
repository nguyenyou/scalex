package scalex

import java.nio.file.{Files, Path}

class ProcessSuite extends ScalexTestBase {
  private def cli(args: String*): (status: Int, stdout: String, stderr: String) = {
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString
    val process =
      ProcessBuilder((List(java, "-cp", System.getProperty("java.class.path"), "scalex.main") ++ args)*).start()
    process.getOutputStream.close()
    val stdout = String(process.getInputStream.readAllBytes())
    val stderr = String(process.getErrorStream.readAllBytes())
    (status = process.waitFor(), stdout = stdout, stderr = stderr)
  }

  test("usage errors return status 2 and use stderr in text mode") {
    val result = cli("def", "-w", workspace.toString)
    assertEquals(result.status, 2)
    assertEquals(result.stdout, "")
    assert(result.stderr.contains("Usage:"))
  }

  test("usage errors return status 2 and valid JSON") {
    val result = cli("def", "--json", "-w", workspace.toString)
    assertEquals(result.status, 2)
    assert(ujson.read(result.stdout)("error").str.contains("Usage:"))
  }

  test("an invalid diff reference returns status 1 and diagnostics") {
    val result = cli("diff", "no-such-review-ref", "--json", "-w", workspace.toString)
    assertEquals(result.status, 1)
    assert(ujson.read(result.stdout)("error").str.contains("git diff failed"))
  }

  test("an unknown command is rejected before indexing") {
    val directory = Files.createDirectory(workspace.resolve("not-a-repository"))
    val result = cli("no-such-command", "--json", "-w", directory.toString)
    assertEquals(result.status, 2)
    assert(!Files.exists(directory.resolve(".scalex")))
    assert(ujson.read(result.stdout)("error").str.contains("Unknown command"))
  }

  test("budgeted JSON is a complete truncation response") {
    val result = cli("packages", "--json", "--max-output", "20", "-w", workspace.toString)
    assertEquals(result.status, 0)
    assertEquals(ujson.read(result.stdout)("truncated").bool, true)
  }

  test("graph rendering does not require a repository") {
    val directory = Files.createDirectory(workspace.resolve("graph-workspace"))
    val result = cli("graph", "--render", "A->B", "--json", "-w", directory.toString)
    assertEquals(result.status, 0)
    assert(ujson.read(result.stdout)("rendered").str.contains("A"))
    assert(!Files.exists(directory.resolve(".scalex")))
  }

  test("timings cover command execution, rendering, and the complete request") {
    val result = cli("def", "UserService", "--json", "--timings", "-w", workspace.toString)
    assertEquals(result.status, 0)
    assert(ujson.read(result.stdout).arr.nonEmpty)
    assert(result.stderr.contains("command"))
    assert(result.stderr.contains("render"))
    assert(result.stderr.contains("request-total"))
  }
}
