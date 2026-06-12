package asciiGraph

// ── DiagramContainer ────────────────────────────────────────────────────────

trait DiagramContainer:
  def text: String
  def region: Region
  def childBoxes: List[DiagramBox]
  def parent: Option[DiagramContainer]

// ── DiagramBox ──────────────────────────────────────────────────────────────

trait DiagramBox extends DiagramContainer:
  def edges: List[DiagramEdge]

  def connections(edgeType: EdgeType = EdgeType.All): List[(DiagramEdge, DiagramBox)] =
    for
      edge <- edges
      if edgeType.includeEdge(edge, this)
      otherBox = edge.otherBox(this)
    yield edge -> otherBox

// ── DiagramEdge ─────────────────────────────────────────────────────────────

trait DiagramEdge:
  def points: List[Point]
  def parent: DiagramContainer
  val box1: DiagramBox
  val box2: DiagramBox
  def hasArrow1: Boolean
  def hasArrow2: Boolean
  def label: Option[String]

  def otherBox(box: DiagramBox): DiagramBox =
    if box == box1 then box2
    else if box == box2 then box1
    else throw IllegalArgumentException("Box not part of edge: " + box)

  def hasArrow(box: DiagramBox): Boolean =
    if box == box1 then hasArrow1
    else if box == box2 then hasArrow2
    else throw IllegalArgumentException("Box not part of edge: " + box)

  def otherHasArrow(box: DiagramBox): Boolean = hasArrow(otherBox(box))

// ── EdgeType ────────────────────────────────────────────────────────────────

sealed trait EdgeType:
  def includeEdge(edge: DiagramEdge, thisBox: DiagramBox): Boolean

object EdgeType:
  case object Out extends EdgeType:
    def includeEdge(edge: DiagramEdge, fromBox: DiagramBox): Boolean = edge.otherHasArrow(fromBox)

  case object In extends EdgeType:
    def includeEdge(edge: DiagramEdge, fromBox: DiagramBox): Boolean = edge.hasArrow(fromBox)

  case object Undirected extends EdgeType:
    def includeEdge(edge: DiagramEdge, fromBox: DiagramBox): Boolean =
      !edge.hasArrow1 && !edge.hasArrow2

  case object All extends EdgeType:
    def includeEdge(edge: DiagramEdge, fromBox: DiagramBox): Boolean = true

// ── Diagram ─────────────────────────────────────────────────────────────────

object Diagram:
  @throws(classOf[DiagramParserException])
  def apply(s: String): Diagram = new DiagramParser(s).getDiagram

trait Diagram extends DiagramContainer:
  def allBoxes: List[DiagramBox]
  def allEdges: List[DiagramEdge]
  def parent: Option[DiagramContainer] = None
  def boxAt(point: Point): Option[DiagramBox]
