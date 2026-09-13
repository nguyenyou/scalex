package scalex.commands

import scalex.*

import asciiGraph.{Diagram, Graph, GraphLayout, LayoutPrefs}
import scala.io.Source
import scala.util.control.NonFatal

// ── Graph command handler ───────────────────────────────────────────────────

def cmdGraph(args: List[String], ctx: CommandContext): CmdResult = {
  args match {
    case "--render" :: rest =>
      val parsed = parseGraphFlags(rest)
      val edgeListStr = parsed.remaining.mkString(" ")
      if (edgeListStr.isEmpty) {
        CmdResult.UsageError("Usage: scalex graph --render \"V1->V2, V2->V3\"")
      } else { renderGraphCmd(edgeListStr, parsed.flags) }
    case "--parse" :: _ =>
      val input = Source.stdin.getLines().mkString("\n")
      if (input.trim.isEmpty) { CmdResult.UsageError("No input provided on stdin for --parse") }
      else { parseGraphCmd(input) }
    case _ =>
      CmdResult.UsageError(
        """Usage: scalex graph --render "V1->V2, V2->V3" [--unicode|--no-unicode] [--vertical|--horizontal] [--rounded] [--double]
          |       scalex graph --parse [--json] < diagram.txt""".stripMargin
      )
  }
}

private case class GraphCmdFlags(
    unicode: Boolean = true,
    vertical: Boolean = true,
    rounded: Boolean = false,
    double: Boolean = false
)
private case class ParsedGraphEdges(vertices: Set[String], edges: List[(from: String, to: String)])

private[scalex] def parseGraphFlags(args: List[String]): (flags: GraphCmdFlags, remaining: List[String]) = {
  var flags = GraphCmdFlags()
  val remaining = List.newBuilder[String]
  args.foreach {
    case "--unicode"    => flags = flags.copy(unicode = true)
    case "--no-unicode" => flags = flags.copy(unicode = false)
    case "--vertical"   => flags = flags.copy(vertical = true)
    case "--horizontal" => flags = flags.copy(vertical = false)
    case "--rounded"    => flags = flags.copy(rounded = true)
    case "--double"     => flags = flags.copy(double = true)
    case "--json"       => () // Output selection belongs to CommandContext.
    case other          => remaining += other
  }
  (flags = flags, remaining = remaining.result())
}

private[scalex] def renderGraphCmd(edgeListStr: String, flags: GraphCmdFlags): CmdResult = {
  try {
    val parsed = parseGraphEdgeList(edgeListStr)
    val graph = Graph(parsed.vertices, parsed.edges.map(_.toTuple))
    val prefs = LayoutPrefs(
      unicode = flags.unicode,
      vertical = flags.vertical,
      rounded = flags.rounded,
      doubleVertices = flags.double
    )
    CmdResult.GraphOutput(GraphLayout.renderGraph(graph, layoutPrefs = prefs))
  } catch {
    case NonFatal(e) => CmdResult.UsageError(s"Error rendering graph: ${e.getMessage}")
  }
}

private[scalex] def parseGraphCmd(input: String): CmdResult = {
  try {
    val diagram = Diagram(input)
    CmdResult.ParsedDiagram(
      diagram.allBoxes.map(_.text.trim).toList,
      diagram.allEdges
        .map(e => DiagramLink(e.box1.text.trim, e.box2.text.trim, e.hasArrow1, e.hasArrow2, e.label))
        .toList
    )
  } catch {
    case NonFatal(e) => CmdResult.UsageError(s"Error parsing diagram: ${e.getMessage}")
  }
}

private[scalex] def parseGraphEdgeList(s: String): ParsedGraphEdges = {
  var vertices = Set.empty[String]
  val edges = List.newBuilder[(from: String, to: String)]
  for (part <- s.split(",").map(_.trim).filter(_.nonEmpty)) {
    if (part.contains("->")) {
      val Array(from, to) = part.split("->", 2).map(_.trim)
      vertices += from
      vertices += to
      edges += ((from = from, to = to))
    } else { vertices += part }
  }
  ParsedGraphEdges(vertices, edges.result())
}
