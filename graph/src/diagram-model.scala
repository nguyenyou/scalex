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

// ── DiagramEdge ─────────────────────────────────────────────────────────────

trait DiagramEdge:
  val box1: DiagramBox
  val box2: DiagramBox
  def hasArrow1: Boolean
  def hasArrow2: Boolean
  def label: Option[String]

// ── Diagram ─────────────────────────────────────────────────────────────────

object Diagram:
  @throws(classOf[DiagramParserException])
  def apply(s: String): Diagram = new DiagramParser(s).getDiagram

trait Diagram extends DiagramContainer:
  def allBoxes: List[DiagramBox]
  def allEdges: List[DiagramEdge]
  def parent: Option[DiagramContainer] = None
  def boxAt(point: Point): Option[DiagramBox]
