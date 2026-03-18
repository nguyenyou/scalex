// ── Graph command handler ───────────────────────────────────────────────────

def cmdGraph(args: List[String], ctx: CommandContext): CmdResult =
  args match
    case "--render" :: rest =>
      val parsed = parseGraphFlags(rest)
      val edgeListStr = parsed.remaining.mkString(" ")
      if edgeListStr.isEmpty then
        CmdResult.UsageError("Usage: scalex graph --render \"V1->V2, V2->V3\"")
      else
        renderGraphCmd(edgeListStr, parsed.flags)
    case "--parse" :: rest =>
      val parsed = parseGraphFlags(rest)
      val input = scala.io.Source.stdin.getLines().mkString("\n")
      if input.trim.isEmpty then CmdResult.UsageError("No input provided on stdin for --parse")
      else parseGraphCmd(input, parsed.flags)
    case _ =>
      CmdResult.UsageError(
        """Usage: scalex graph --render "V1->V2, V2->V3" [--unicode|--no-unicode] [--vertical|--horizontal] [--rounded] [--double]
          |       scalex graph --parse [--json] < diagram.txt""".stripMargin)

private case class GraphCmdFlags(
  unicode: Boolean = true,
  vertical: Boolean = true,
  rounded: Boolean = false,
  double: Boolean = false,
  json: Boolean = false,
)

private case class ParsedGraphEdges(vertices: Set[String], edges: List[(String, String)])

private def parseGraphFlags(args: List[String]): (flags: GraphCmdFlags, remaining: List[String]) =
  var flags = GraphCmdFlags()
  val remaining = scala.collection.mutable.ListBuffer[String]()
  var i = 0
  while i < args.size do
    args(i) match
      case "--unicode" => flags = flags.copy(unicode = true)
      case "--no-unicode" => flags = flags.copy(unicode = false)
      case "--vertical" => flags = flags.copy(vertical = true)
      case "--horizontal" => flags = flags.copy(vertical = false)
      case "--rounded" => flags = flags.copy(rounded = true)
      case "--double" => flags = flags.copy(double = true)
      case "--json" => flags = flags.copy(json = true)
      case other => remaining += other
    i += 1
  (flags = flags, remaining = remaining.toList)

private def renderGraphCmd(edgeListStr: String, flags: GraphCmdFlags): CmdResult =
  try
    val parsed = parseGraphEdgeList(edgeListStr)
    val graph = asciiGraph.Graph(parsed.vertices, parsed.edges)
    val prefs = asciiGraph.LayoutPrefsImpl(
      unicode = flags.unicode,
      vertical = flags.vertical,
      rounded = flags.rounded,
      doubleVertices = flags.double,
    )
    val rendered = asciiGraph.GraphLayout.renderGraph(graph, layoutPrefs = prefs)
    if flags.json then CmdResult.GraphOutput(s"""{"rendered":"${jsonEscape(rendered)}"}""")
    else CmdResult.GraphOutput(rendered)
  catch
    case e: Exception =>
      CmdResult.UsageError(s"Error rendering graph: ${e.getMessage}")

private def parseGraphCmd(input: String, flags: GraphCmdFlags): CmdResult =
  try
    val diagram = asciiGraph.Diagram(input)
    if flags.json then
      val boxesJson = diagram.allBoxes.map(b => s"""{"text":"${jsonEscape(b.text.trim)}"}""").mkString("[", ",", "]")
      val edgesJson = diagram.allEdges.map { e =>
        val from = jsonEscape(e.box1.text.trim)
        val to = jsonEscape(e.box2.text.trim)
        val directed = e.hasArrow1 || e.hasArrow2
        val labelStr = e.label.map(l => s""","label":"${jsonEscape(l)}"""").getOrElse("")
        s"""{"from":"$from","to":"$to","directed":$directed$labelStr}"""
      }.mkString("[", ",", "]")
      CmdResult.GraphOutput(s"""{"boxes":$boxesJson,"edges":$edgesJson}""")
    else
      val sb = new StringBuilder
      sb.append("Boxes: ")
      sb.append(diagram.allBoxes.map(_.text.trim).mkString(", "))
      sb.append("\nEdges:")
      for edge <- diagram.allEdges do
        val arrow =
          if edge.hasArrow1 && edge.hasArrow2 then " <-> "
          else if edge.hasArrow2 then " -> "
          else if edge.hasArrow1 then " <- "
          else " -- "
        val labelStr = edge.label.map(l => s" [$l]").getOrElse("")
        sb.append(s"\n  ${edge.box1.text.trim}$arrow${edge.box2.text.trim}$labelStr")
      CmdResult.GraphOutput(sb.toString)
  catch
    case e: asciiGraph.DiagramParserException =>
      CmdResult.UsageError(s"Error parsing diagram: ${e.getMessage}")
    case e: Exception =>
      CmdResult.UsageError(s"Error parsing diagram: ${e.getMessage}")

private def parseGraphEdgeList(s: String): ParsedGraphEdges =
  var vertices = Set[String]()
  var edges = List[(String, String)]()
  for part <- s.split(",").map(_.trim).filter(_.nonEmpty) do
    if part.contains("->") then
      val Array(from, to) = part.split("->", 2).map(_.trim)
      vertices += from
      vertices += to
      edges = edges :+ (from, to)
    else
      vertices += part.trim
  ParsedGraphEdges(vertices, edges)
