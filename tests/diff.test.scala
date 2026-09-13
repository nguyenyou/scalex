package scalex

import scalex.index.WorkspaceIndex

class DiffSuite extends ScalexTestBase {
  private val path = "diff-fixture/Thing.scala"
  private val source = """package demo
                         |
                         |class Thing {
                         |  def apply(a: Int): Int = {
                         |    val x = a * 2
                         |    x + 1
                         |  }
                         |  def helper(s: String): String = s.trim
                         |}
                         |""".stripMargin

  private def baseline(): Unit = {
    writeFile(path, source)
    run("git", "add", ".")
    run("git", "commit", "--allow-empty", "-m", "diff baseline")
  }

  private def diff(filters: FiltersOptions = FiltersOptions(), limit: Int = 20): ujson.Value = {
    val output = captureOut {
      val status = runCommand(
        "diff",
        List("HEAD"),
        CommandContext(
          idx = WorkspaceIndex.empty(workspace),
          workspace = workspace,
          filters = filters,
          output = OutputOptions(jsonOutput = true, limit = limit)
        )
      )
      assertEquals(status, 0)
    }
    ujson.read(output)
  }

  test("#366: moving unchanged declarations does not mark them modified") {
    baseline()
    writeFile(path, "// leading comment\n" + source)
    val result = diff()
    assertEquals(result("filesChanged").num.toInt, 1)
    assertEquals(result("modified").arr.size, 0)
    assertEquals(result("added").arr.size, 0)
    assertEquals(result("removed").arr.size, 0)
  }

  test("#366: a same-line-count body edit is reported without staging") {
    baseline()
    writeFile(path, source.replace("a * 2", "a * 99"))
    val names = diff()("modified").arr.map(_("new")("name").str).toSet
    assert(names.contains("apply"), s"Missing edited method: $names")
    assert(!names.contains("helper"), s"Unchanged sibling reported: $names")
  }

  test("#366: path filtering excludes changes outside the selected subtree") {
    baseline()
    writeFile(path, source.replace("def helper", "def renamedHelper"))
    val result = diff(FiltersOptions(pathFilter = Some("unrelated/")))
    assertEquals(result("added").arr.size, 0)
    assertEquals(result("removed").arr.size, 0)
    assertEquals(result("modified").arr.size, 0)
  }

  test("#366: default display limit does not discard the 101st changed file") {
    baseline()
    (0 to 100).foreach { i =>
      writeFile(f"many/F$i%03d.scala", s"package demo\nclass F$i { def value: Int = 1 }\n")
    }
    run("git", "add", ".")
    run("git", "commit", "-m", "many files baseline")
    (0 to 100).foreach { i =>
      val text = s"package demo\nclass F$i { def value: Int = 1 }\n"
      writeFile(f"many/F$i%03d.scala", if (i == 100) text.replace("value", "renamed") else "// comment\n" + text)
    }
    val result = diff()
    assertEquals(result("filesChanged").num.toInt, 101)
    assert(result("added").arr.exists(_("name").str == "renamed"), "Last file was never examined")
    assert(result("removed").arr.exists(_("name").str == "value"), "Last file was never examined")
  }

  test("#366: include and exclude path filters apply before counting changed files") {
    baseline()
    writeFile(path, source.replace("helper", "renamedHelper"))
    writeFile("outside/Other.scala", "package demo\nclass Other {}\n")
    run("git", "add", ".")
    val included = diff(FiltersOptions(pathFilter = Some("diff-fixture/")))
    assertEquals(included("filesChanged").num.toInt, 1)
    assertEquals(included("added").arr.map(_("name").str).toList, List("renamedHelper"))
    val excluded = diff(FiltersOptions(excludePath = Some("diff-fixture/")))
    assertEquals(excluded("filesChanged").num.toInt, 1)
    assertEquals(excluded("added").arr.map(_("name").str).toList, List("Other"))
  }

  test("#366: source fingerprints preserve whitespace inside literals") {
    baseline()
    val before = "package demo\nobject Text { val text = \"a b\" }\n"
    writeFile(path, before)
    run("git", "add", ".")
    run("git", "commit", "-m", "literal baseline")
    writeFile(path, before.replace("a b", "a  b"))
    val names = diff()("modified").arr.map(_("new")("name").str).toSet
    assertEquals(names, Set("Text", "text"))
  }

  test("#366: display limit does not change files examined or hide late additions") {
    baseline()
    writeFile(path, source.replace("helper", "renamedHelper"))
    val limited = diff(limit = 1)
    val unlimited = diff(limit = Int.MaxValue)
    assertEquals(limited("filesChanged"), unlimited("filesChanged"))
    assertEquals(limited("added"), unlimited("added"))
    assertEquals(limited("removed"), unlimited("removed"))
    assertEquals(limited("modified").arr.size, 1)
    assertEquals(unlimited("modified").arr.size, 1)
  }

  test("#366: body edits survive overloaded and same-named methods in one file") {
    baseline()
    val before = """package demo
                   |class First {
                   |  def value(i: Int): Int = 1
                   |  def value(s: String): Int = 2
                   |}
                   |class Second { def value(i: Int): Int = 3 }
                   |""".stripMargin
    writeFile(path, before)
    run("git", "add", ".")
    run("git", "commit", "-m", "overloaded baseline")
    writeFile(path, before.replace("Int = 1", "Int = 99"))
    val methods = diff()("modified").arr.filter(_("new")("kind").str == "def")
    assertEquals(methods.size, 1)
    assertEquals(methods.head("new")("line").num.toInt, 3)
  }

  test("#366: exchanging method bodies between owners modifies both methods") {
    baseline()
    val before = "package demo\nclass First { def value: Int = 1 }\nclass Second { def value: Int = 2 }\n"
    writeFile(path, before)
    run("git", "add", ".")
    run("git", "commit", "-m", "owner baseline")
    writeFile(path, before.replace("Int = 1", "Int = TEMP").replace("Int = 2", "Int = 1").replace("TEMP", "2"))
    val methods = diff()("modified").arr.filter(_("new")("kind").str == "def")
    assertEquals(methods.size, 2)
  }
}
