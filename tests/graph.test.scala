class GraphSuite extends munit.FunSuite:

  // ── Render tests ──────────────────────────────────────────────────────────

  test("render simple directed graph"):
    val graph = asciiGraph.Graph(Set("A", "B"), List(("A", "B")))
    val result = asciiGraph.GraphLayout.renderGraph(graph)
    assert(result.contains("A"))
    assert(result.contains("B"))

  test("render graph with multiple edges"):
    val graph = asciiGraph.Graph(Set("A", "B", "C"), List(("A", "B"), ("B", "C")))
    val result = asciiGraph.GraphLayout.renderGraph(graph)
    assert(result.contains("A"))
    assert(result.contains("B"))
    assert(result.contains("C"))

  test("render with ASCII mode"):
    val graph = asciiGraph.Graph(Set("X", "Y"), List(("X", "Y")))
    val prefs = asciiGraph.LayoutPrefsImpl(unicode = false)
    val result = asciiGraph.GraphLayout.renderGraph(graph, layoutPrefs = prefs)
    assert(result.contains("+"))
    assert(!result.contains("┌"))

  test("render with unicode mode"):
    val graph = asciiGraph.Graph(Set("X", "Y"), List(("X", "Y")))
    val prefs = asciiGraph.LayoutPrefsImpl(unicode = true)
    val result = asciiGraph.GraphLayout.renderGraph(graph, layoutPrefs = prefs)
    assert(result.contains("┌") || result.contains("╭"))

  test("render with rounded corners"):
    val graph = asciiGraph.Graph(Set("X", "Y"), List(("X", "Y")))
    val prefs = asciiGraph.LayoutPrefsImpl(unicode = true, rounded = true)
    val result = asciiGraph.GraphLayout.renderGraph(graph, layoutPrefs = prefs)
    assert(result.contains("╭"))

  test("render with double borders"):
    val graph = asciiGraph.Graph(Set("X", "Y"), List(("X", "Y")))
    val prefs = asciiGraph.LayoutPrefsImpl(unicode = true, doubleVertices = true)
    val result = asciiGraph.GraphLayout.renderGraph(graph, layoutPrefs = prefs)
    assert(result.contains("╔"))

  test("render horizontal layout"):
    val graph = asciiGraph.Graph(Set("A", "B"), List(("A", "B")))
    val prefs = asciiGraph.LayoutPrefsImpl(vertical = false)
    val result = asciiGraph.GraphLayout.renderGraph(graph, layoutPrefs = prefs)
    assert(result.contains("A"))
    assert(result.contains(">"))

  test("render diamond graph"):
    val graph = asciiGraph.Graph(
      Set("A", "B", "C", "D"),
      List(("A", "B"), ("A", "C"), ("B", "D"), ("C", "D"))
    )
    val result = asciiGraph.GraphLayout.renderGraph(graph)
    assert(result.contains("A"))
    assert(result.contains("D"))

  test("render singleton vertex"):
    val graph = asciiGraph.Graph(Set("Alone"), List.empty[(String, String)])
    val result = asciiGraph.GraphLayout.renderGraph(graph)
    assert(result.contains("Alone"))

  // ── Parse tests ───────────────────────────────────────────────────────────

  test("parse simple diagram"):
    val input =
      """┌───┐
        |│ A │
        |└─┬─┘
        |  │
        |  v
        |┌───┐
        |│ B │
        |└───┘""".stripMargin
    val diagram = asciiGraph.Diagram(input)
    assertEquals(diagram.allBoxes.size, 2)
    assertEquals(diagram.allEdges.size, 1)

  test("parse ASCII diagram"):
    val input =
      """+---+
        || A |
        |+---+
        |  |
        |  v
        |+---+
        || B |
        |+---+""".stripMargin
    val diagram = asciiGraph.Diagram(input)
    assertEquals(diagram.allBoxes.size, 2)

  test("parse extracts box text"):
    val input =
      """┌─────────┐
        |│ Hello   │
        |└─────────┘""".stripMargin
    val diagram = asciiGraph.Diagram(input)
    assert(diagram.allBoxes.head.text.trim.contains("Hello"))

  // ── Round-trip tests ──────────────────────────────────────────────────────

  test("render then parse round-trip"):
    val graph = asciiGraph.Graph(Set("A", "B", "C"), List(("A", "B"), ("B", "C")))
    val rendered = asciiGraph.GraphLayout.renderGraph(graph)
    val diagram = asciiGraph.Diagram(rendered)
    assertEquals(diagram.allBoxes.size, 3)
    assertEquals(diagram.allEdges.size, 2)

  test("render then parse preserves vertices"):
    val graph = asciiGraph.Graph(Set("X", "Y", "Z"), List(("X", "Y"), ("Y", "Z")))
    val rendered = asciiGraph.GraphLayout.renderGraph(graph)
    val diagram = asciiGraph.Diagram(rendered)
    val boxTexts = diagram.allBoxes.map(_.text.trim).toSet
    assertEquals(boxTexts, Set("X", "Y", "Z"))

  // ── DiagramToGraphConvertor tests ─────────────────────────────────────────

  test("diagram to graph conversion"):
    val graph = asciiGraph.Graph(Set("A", "B"), List(("A", "B")))
    val rendered = asciiGraph.GraphLayout.renderGraph(graph)
    val converted = asciiGraph.Graph.fromDiagram(rendered)
    val vertexNames = converted.vertices.map(_.trim)
    assertEquals(vertexNames, Set("A", "B"))
    assertEquals(converted.edges.size, 1)

  // ── Command handler tests ─────────────────────────────────────────────────

  test("cmdGraph --render produces output"):
    val dummyIdx = WorkspaceIndex(java.nio.file.Path.of("."), needBlooms = false)
    val ctx = CommandContext(idx = dummyIdx, workspace = java.nio.file.Path.of("."))
    val result = cmdGraph(List("--render", "A->B, B->C"), ctx)
    result match
      case CmdResult.GraphOutput(text) =>
        assert(text.contains("A"))
        assert(text.contains("B"))
        assert(text.contains("C"))
      case other => fail(s"Expected GraphOutput, got: $other")

  test("cmdGraph without flags shows usage"):
    val dummyIdx = WorkspaceIndex(java.nio.file.Path.of("."), needBlooms = false)
    val ctx = CommandContext(idx = dummyIdx, workspace = java.nio.file.Path.of("."))
    val result = cmdGraph(List(), ctx)
    assert(result.isInstanceOf[CmdResult.UsageError])

  // ── Graph model tests ────────────────────────────────────────────────────

  test("Graph sources and sinks"):
    val g = asciiGraph.Graph(Set(1, 2, 3), List((1, 2), (2, 3)))
    assertEquals(g.sources, List(1))
    assertEquals(g.sinks, List(3))

  test("Graph cycle detection"):
    val acyclic = asciiGraph.Graph(Set(1, 2), List((1, 2)))
    assert(!asciiGraph.GraphUtils.hasCycle(acyclic))
    val cyclic = asciiGraph.Graph(Set(1, 2), List((1, 2), (2, 1)))
    assert(asciiGraph.GraphUtils.hasCycle(cyclic))

  test("topological sort"):
    val g = asciiGraph.Graph(Set(1, 2, 3), List((1, 2), (2, 3)))
    val sorted = asciiGraph.GraphUtils.topologicalSort(g)
    assert(sorted.isDefined)
    assertEquals(sorted.get, List(1, 2, 3))

  test("render graph with cycle"):
    val graph = asciiGraph.Graph(Set("A", "B"), List(("A", "B"), ("B", "A")))
    val result = asciiGraph.GraphLayout.renderGraph(graph)
    assert(result.contains("A"))
    assert(result.contains("B"))

  test("render graph with self-loop"):
    val graph = asciiGraph.Graph(Set("A", "B"), List(("A", "B"), ("A", "A")))
    val result = asciiGraph.GraphLayout.renderGraph(graph)
    assert(result.contains("A"))
