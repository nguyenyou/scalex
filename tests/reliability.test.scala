package scalex

import scalex.index.*

import java.nio.file.Files
import java.nio.charset.StandardCharsets.UTF_8
import clibase.OutputBudget

class ReliabilitySuite extends ScalexTestBase {
  test("test repositories configure a local identity and disable commit signing") {
    val expectedSettings = Map(
      "user.name" -> "Scalex tests",
      "user.email" -> "scalex-tests@example.com",
      "commit.gpgsign" -> "false"
    )
    expectedSettings.foreach { (key, expected) =>
      val process = ProcessBuilder("git", "config", "--local", "--get", key).directory(workspace.toFile).start()
      val value = String(process.getInputStream.readAllBytes(), UTF_8).trim
      assertEquals(process.waitFor(), 0, s"Missing local Git setting: $key")
      assertEquals(value, expected)
    }
  }

  test("JSON budgets emit a valid truncation response") {
    val idx = WorkspaceIndex.load(workspace)
    val out = captureOut {
      runCommand(
        "packages",
        Nil,
        CommandContext(idx = idx, workspace = workspace, output = OutputOptions(jsonOutput = true, maxOutput = 20))
      )
    }
    assertEquals(ujson.read(out)("truncated").bool, true)
  }

  test("symbol summaries obey the JSON budget") {
    val idx = WorkspaceIndex.load(workspace)
    val out = captureOut {
      runCommand(
        "symbols",
        List("src/main/scala/com/example/UserService.scala"),
        CommandContext(
          idx = idx,
          workspace = workspace,
          output = OutputOptions(jsonOutput = true, maxOutput = 20),
          overview = OverviewOptions(summaryMode = true)
        )
      )
    }
    assertEquals(ujson.read(out)("truncated").bool, true)
  }

  test("usage errors are structured in JSON mode") {
    val idx = WorkspaceIndex.load(workspace)
    val out = captureOut {
      runCommand(
        "def",
        Nil,
        CommandContext(idx = idx, workspace = workspace, output = OutputOptions(jsonOutput = true))
      )
    }
    assert(ujson.read(out)("error").str.contains("Usage:"))
  }

  test("invalid Git references are errors rather than empty diffs") {
    val idx = WorkspaceIndex.load(workspace)
    val out = captureOut {
      runCommand(
        "diff",
        List("no-such-review-ref"),
        CommandContext(idx = idx, workspace = workspace, output = OutputOptions(jsonOutput = true))
      )
    }
    assert(ujson.read(out).obj.contains("error"))
  }

  test("index construction produces a ready-to-query snapshot") {
    val idx = WorkspaceIndex.load(workspace)
    assert(idx.findDefinition("UserService").nonEmpty)
  }

  test("a newly loaded snapshot sees staged changes and the old snapshot stays stable") {
    val before = WorkspaceIndex.load(workspace)
    val oldSymbols = before.symbols
    val path = "SnapshotProbe.scala"
    try {
      writeFile(path, "object SnapshotProbe {}")
      run("git", "add", path)
      val after = WorkspaceIndex.load(workspace)
      assert(after.findDefinition("SnapshotProbe").nonEmpty)
      assertEquals(before.symbols, oldSymbols)
      assert(before.findDefinition("SnapshotProbe").isEmpty)
    } finally {
      Files.deleteIfExists(workspace.resolve(path))
      run("git", "add", "-u")
    }
  }

  test("output budgets count Unicode characters rather than UTF-8 bytes") {
    val out = captureOut { OutputBudget.run(4, "truncated") { print("ééé") } }
    assertEquals(out, "ééé")
  }

  test("output exactly at the budget is preserved") {
    val out = captureOut { OutputBudget.run(3, "truncated") { print("abc") } }
    assertEquals(out, "abc")
  }
}
