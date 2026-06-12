package asciiGraph

import scala.PartialFunction.condOpt
import scala.annotation.tailrec
import scala.util.boundary, boundary.break
import Direction.*
import Utils.*

// ── DrawingElement ──────────────────────────────────────────────────────────

sealed trait DrawingElement
    extends Translatable[DrawingElement]
    with Transposable[DrawingElement]:
  def translate(down: Int = 0, right: Int = 0): DrawingElement
  def transpose: DrawingElement

case class VertexDrawingElement(region: Region, textLines: List[String])
    extends DrawingElement
    with Translatable[VertexDrawingElement]
    with Transposable[VertexDrawingElement]
    with HasRegion:
  def translate(down: Int = 0, right: Int = 0) = copy(region = region.translate(down, right))
  def transpose: VertexDrawingElement = copy(region = region.transpose)

case class EdgeDrawingElement(
    bendPoints: List[Point],
    hasArrow1: Boolean,
    hasArrow2: Boolean
) extends DrawingElement
    with Translatable[EdgeDrawingElement]
    with Transposable[EdgeDrawingElement]:

  def translate(down: Int = 0, right: Int = 0) =
    copy(bendPoints = bendPoints.map(_.translate(down, right)))

  private def direction(point1: Point, point2: Point): Direction =
    val (Point(r1, c1), Point(r2, c2)) = (point1, point2)
    if r1 == r2 then
      if c1 < c2 then Right else if c1 > c2 then Left
      else throw RuntimeException("Same point: " + point1)
    else if c1 == c2 then
      if r1 < r2 then Down else if r1 > r2 then Up
      else throw RuntimeException("Same point")
    else throw RuntimeException("Points not aligned: " + point1 + ", " + point2)

  lazy val segments: List[EdgeSegment] =
    for (point1, point2) <- adjacentPairs(bendPoints)
    yield EdgeSegment(point1, direction(point1, point2), point2)

  def transpose: EdgeDrawingElement = copy(bendPoints = bendPoints.map(_.transpose))

  def replaceSegment(oldSegment: EdgeSegment, newSegment: EdgeSegment): EdgeDrawingElement =
    val EdgeSegment(newStart, _, newFinish) = newSegment
    val oldIndex = bendPoints.indexOf(oldSegment.start)
    val newBendPoints = bendPoints.patch(oldIndex, List(newStart, newFinish), 2)
    copy(bendPoints = newBendPoints)

  def startPoint = bendPoints.head
  def finishPoint = bendPoints.last

case class EdgeSegment(start: Point, direction: Direction, finish: Point) extends HasRegion:
  def region =
    if start.column < finish.column || start.row < finish.row then Region(start, finish)
    else Region(finish, start)

// ── EdgeSegmentInfo ─────────────────────────────────────────────────────────

case class EdgeSegmentInfo(
    edgeElement: EdgeDrawingElement,
    segment1: EdgeSegment,
    segment2: EdgeSegment,
    segment3: EdgeSegment
):
  def row = segment2.start.row

  def withRow(row: Int): EdgeSegmentInfo =
    val newStart2 = segment2.start.copy(row = row)
    val newFinish2 = segment2.finish.copy(row = row)
    val newSegment1 = segment1.copy(finish = newStart2)
    val newSegment2 = segment2.copy(start = newStart2, finish = newFinish2)
    val newSegment3 = segment3.copy(start = newFinish2)
    EdgeSegmentInfo(edgeElement, newSegment1, newSegment2, newSegment3)

// ── Drawing ─────────────────────────────────────────────────────────────────

case class Drawing(elements: List[DrawingElement]) extends Transposable[Drawing]:
  lazy val dimension: Dimension =
    val largestPoint = elements.map(maxPoint).foldLeft(Point(-1, -1))(_ `maxRowCol` _)
    Dimension.fromPoint(largestPoint)

  private def maxPoint(element: DrawingElement): Point =
    element match
      case element: VertexDrawingElement => element.region.bottomRight
      case element: EdgeDrawingElement => element.bendPoints.reduce(_ `maxRowCol` _)

  def replaceElement(element: DrawingElement, replacement: DrawingElement) =
    copy(elements = replacement :: elements.filterNot(_ == element))

  def vertexElementAt(point: Point): Option[VertexDrawingElement] =
    elements.collectFirst { case vde: VertexDrawingElement if vde.region.contains(point) => vde }

  def vertexElements: List[VertexDrawingElement] =
    elements.collect { case vde: VertexDrawingElement => vde }

  def edgeElements: List[EdgeDrawingElement] =
    elements.collect { case ede: EdgeDrawingElement => ede }

  def transpose: Drawing = Drawing(elements.map(_.transpose))

  override def toString = Renderer.render(this, LayoutPrefs())

// ── Grid ────────────────────────────────────────────────────────────────────

class Grid(dimension: Dimension):
  private val backgroundChar = ' '
  private val chars: Array[Array[Char]] = Array.fill(dimension.height, dimension.width)(backgroundChar)

  def apply(point: Point): Char =
    try chars(point.row)(point.column)
    catch
      case _: ArrayIndexOutOfBoundsException =>
        throw ArrayIndexOutOfBoundsException(point.toString + " is not in " + dimension)

  def update(point: Point, char: Char) = chars(point.row)(point.column) = char

  def update(point: Point, s: String): Unit =
    var p = point
    for c <- s do
      this(p) = c
      p = p.right

  private def region = Region(Point(0, 0), dimension)
  def contains(point: Point) = region.contains(point)
  override def toString = chars.map(new String(_)).mkString("\n")

// ── EdgeTracker ─────────────────────────────────────────────────────────────

class EdgeTracker(drawing: Drawing):
  private val horizontalQuadTree: QuadTree[Region] = new QuadTree(drawing.dimension)
  private val verticalQuadTree: QuadTree[Region] = new QuadTree(drawing.dimension)
  private val vertexRegions: List[Region] = drawing.vertexElements.map(_.region)
  private val arrowRegions: List[Region] = drawing.edgeElements.map { edgeElement =>
    if edgeElement.hasArrow1 then edgeElement.startPoint.region
    else edgeElement.finishPoint.region
  }

  arrowRegions.foreach(horizontalQuadTree.add)
  vertexRegions.foreach(horizontalQuadTree.add)
  vertexRegions.foreach(verticalQuadTree.add)

  for
    edge <- drawing.edgeElements
    segment <- edge.segments
  do
    if segment.direction.isHorizontal then horizontalQuadTree.add(segment.region)
    else verticalQuadTree.add(segment.region)

  def addEdgeSegments(segmentInfo: EdgeSegmentInfo): Unit =
    verticalQuadTree.add(segmentInfo.segment1.region)
    horizontalQuadTree.add(segmentInfo.segment2.region)
    verticalQuadTree.add(segmentInfo.segment3.region)

  def removeEdgeSegments(segmentInfo: EdgeSegmentInfo): Unit =
    verticalQuadTree.remove(segmentInfo.segment1.region)
    horizontalQuadTree.remove(segmentInfo.segment2.region)
    verticalQuadTree.remove(segmentInfo.segment3.region)

  def addHorizontalSegment(edgeSegment: EdgeSegment): Unit = horizontalQuadTree.add(edgeSegment.region)
  def addVerticalSegment(edgeSegment: EdgeSegment): Unit = verticalQuadTree.add(edgeSegment.region)
  def removeHorizontalSegment(edgeSegment: EdgeSegment): Unit = horizontalQuadTree.remove(edgeSegment.region)
  def removeVerticalSegment(edgeSegment: EdgeSegment): Unit = verticalQuadTree.remove(edgeSegment.region)

  def collidesHorizontal(edgeSegment: EdgeSegment): Boolean = horizontalQuadTree.collides(edgeSegment.region)
  def collidesVertical(edgeSegment: EdgeSegment): Boolean = verticalQuadTree.collides(edgeSegment.region)

  def collidesWith(segmentInfo: EdgeSegmentInfo): Boolean =
    verticalQuadTree.collides(segmentInfo.segment1.region) ||
      horizontalQuadTree.collides(segmentInfo.segment2.region) ||
      verticalQuadTree.collides(segmentInfo.segment3.region) ||
      verticalQuadTree.collides(segmentInfo.segment2.region) &&
      horizontalQuadTree.collides(segmentInfo.segment3.region)

// ── KinkRemover ─────────────────────────────────────────────────────────────

object KinkRemover:
  def removeKinks(drawing: Drawing): Drawing =
    val edgeTracker = new EdgeTracker(drawing)
    var currentDrawing = drawing
    var continue = true
    while continue do
      removeKink(currentDrawing, edgeTracker) match
        case None => continue = false
        case Some((oldEdge, updatedEdge)) =>
          currentDrawing = currentDrawing.replaceElement(oldEdge, updatedEdge)
    currentDrawing

  private def removeKink(
      drawing: Drawing, edgeTracker: EdgeTracker
  ): Option[(EdgeDrawingElement, EdgeDrawingElement)] =
    drawing.edgeElements.view.flatMap { edgeElement =>
      removeKinkInEdge(edgeElement, drawing, edgeTracker).map(edgeElement -> _)
    }.headOption

  private def removeKinkInEdge(
      edge: EdgeDrawingElement, drawing: Drawing, edgeTracker: EdgeTracker
  ): Option[EdgeDrawingElement] = boundary {
    val segments: List[EdgeSegment] = edge.segments
    adjacentPairsWithPreviousAndNext(segments).foreach {
      case (
            segment1Opt,
            segment2 @ EdgeSegment(start, Down, middle),
            segment3 @ EdgeSegment(_, Left | Right, end),
            segment4Opt
          ) =>
        val alternativeMiddle = Point(start.row, end.column)
        segment1Opt.foreach(edgeTracker.removeHorizontalSegment)
        edgeTracker.removeVerticalSegment(segment2)
        edgeTracker.removeHorizontalSegment(segment3)
        segment4Opt.foreach(edgeTracker.removeVerticalSegment)
        val newSegment1Opt = segment1Opt.map(s1 => EdgeSegment(s1.start, s1.direction, alternativeMiddle))
        val newSegment4Opt = segment4Opt.map(s4 => EdgeSegment(alternativeMiddle, s4.direction, s4.finish))
        val collision = newSegment1Opt.exists(edgeTracker.collidesHorizontal) ||
          newSegment4Opt.exists(edgeTracker.collidesVertical)
        if !collision && checkVertexConnection(drawing, start, alternativeMiddle, Direction.Up) then
          segment1Opt.foreach(edgeTracker.addHorizontalSegment)
          segment4Opt.foreach(edgeTracker.addVerticalSegment)
          break(Some(applyKinkRemoval(edge, start, alternativeMiddle)))
        else
          segment1Opt.foreach(edgeTracker.addHorizontalSegment)
          edgeTracker.addVerticalSegment(segment2)
          edgeTracker.addHorizontalSegment(segment3)
          segment4Opt.foreach(edgeTracker.addVerticalSegment)

      case (
            segment1Opt,
            segment2 @ EdgeSegment(start, Left | Right, middle),
            segment3 @ EdgeSegment(_, Down, end),
            segment4Opt
          ) =>
        val alternativeMiddle = Point(end.row, start.column)
        segment1Opt.foreach(edgeTracker.removeVerticalSegment)
        edgeTracker.removeHorizontalSegment(segment2)
        edgeTracker.removeVerticalSegment(segment3)
        segment4Opt.foreach(edgeTracker.removeHorizontalSegment)
        val newSegment1Opt = segment1Opt.map(s1 => EdgeSegment(s1.start, s1.direction, alternativeMiddle))
        val newSegment4Opt = segment4Opt.map(s4 => EdgeSegment(alternativeMiddle, s4.direction, s4.finish))
        val collision = newSegment1Opt.exists(edgeTracker.collidesVertical) ||
          newSegment4Opt.exists(edgeTracker.collidesHorizontal)
        if !collision && checkVertexConnection(drawing, end, alternativeMiddle, Direction.Down) then
          segment1Opt.foreach(edgeTracker.addVerticalSegment)
          segment4Opt.foreach(edgeTracker.addHorizontalSegment)
          break(Some(applyKinkRemoval(edge, start, alternativeMiddle)))
        else
          segment1Opt.foreach(edgeTracker.addVerticalSegment)
          edgeTracker.addHorizontalSegment(segment2)
          edgeTracker.addVerticalSegment(segment3)
          segment4Opt.foreach(edgeTracker.addHorizontalSegment)

      case _ => ()
    }
    None
  }

  private def checkVertexConnection(
      drawing: Drawing, end: Point, alternativeMiddle: Point, direction: Direction
  ): Boolean =
    drawing.vertexElementAt(end.go(direction)).forall { vertex =>
      val connectedToSameVertex = drawing.vertexElementAt(alternativeMiddle.go(direction)) == Some(vertex)
      val extremeLeftOfVertex = alternativeMiddle.column == vertex.region.leftColumn
      val extremeRightOfVertex = alternativeMiddle.column == vertex.region.rightColumn
      connectedToSameVertex && !extremeLeftOfVertex && !extremeRightOfVertex
    }

  private def applyKinkRemoval(edge: EdgeDrawingElement, start: Point, alternativeMiddle: Point): EdgeDrawingElement =
    val oldBendPoints = edge.bendPoints
    val oldIndex = oldBendPoints.indexOf(start)
    val newBendPoints = Point.removeRedundantPoints(
      oldBendPoints.patch(oldIndex, List(alternativeMiddle), 3).distinct
    )
    edge.copy(bendPoints = newBendPoints)

// ── EdgeElevator ────────────────────────────────────────────────────────────

object EdgeElevator:
  def elevateEdges(drawing: Drawing): Drawing =
    val edgeTracker = new EdgeTracker(drawing)
    var currentDrawing = drawing
    val segmentInfos =
      for
        edgeElement <- drawing.edgeElements
        (segment1, segment2, segment3) <- adjacentTriples(edgeElement.segments)
        if segment2.direction.isHorizontal
      yield EdgeSegmentInfo(edgeElement, segment1, segment2, segment3)

    var segmentUpdates: Map[EdgeDrawingElement, List[(EdgeSegment, EdgeSegment)]] = Map()
    for
      segmentInfo <- segmentInfos.sortBy(_.row)
      updatedEdgeSegment <- elevate(segmentInfo, edgeTracker)
    do
      val edge = segmentInfo.edgeElement
      val update = segmentInfo.segment2 -> updatedEdgeSegment
      segmentUpdates += edge -> (update :: segmentUpdates.getOrElse(edge, Nil))

    for (edge, updates) <- segmentUpdates do
      currentDrawing = currentDrawing.replaceElement(edge, updateEdge(edge, updates))
    currentDrawing

  @tailrec
  private def updateEdge(edge: EdgeDrawingElement, updates: List[(EdgeSegment, EdgeSegment)]): EdgeDrawingElement =
    updates match
      case Nil => edge
      case (oldSegment, newSegment) :: rest => updateEdge(edge.replaceSegment(oldSegment, newSegment), rest)

  private def elevate(segmentInfo: EdgeSegmentInfo, edgeTracker: EdgeTracker): Option[EdgeSegment] =
    val firstRow = segmentInfo.segment1.start.row + 1
    val lastRow = segmentInfo.segment2.start.row - 1
    firstRow.to(lastRow).view.flatMap(row => elevateRow(row, segmentInfo, edgeTracker)).headOption

  private def elevateRow(row: Int, segmentInfo: EdgeSegmentInfo, edgeTracker: EdgeTracker): Option[EdgeSegment] =
    edgeTracker.removeEdgeSegments(segmentInfo)
    val newSegmentInfo = segmentInfo.withRow(row)
    if edgeTracker.collidesWith(newSegmentInfo) then
      edgeTracker.addEdgeSegments(segmentInfo)
      None
    else
      edgeTracker.addEdgeSegments(newSegmentInfo)
      Some(newSegmentInfo.segment2)

// ── RedundantRowRemover ─────────────────────────────────────────────────────

object RedundantRowRemover:
  def removeRedundantRows(drawing: Drawing): Drawing =
    iterate(drawing, removeRedundantRow)

  private def removeRedundantRow(drawing: Drawing): Option[Drawing] =
    0.until(drawing.dimension.height)
      .find(row => canRemove(drawing, row))
      .map(row => removeRows(drawing, row, row))

  private def canRemove(drawing: Drawing, row: Int): Boolean =
    drawing.elements.forall {
      case ede: EdgeDrawingElement => canRemove(ede, row)
      case vde: VertexDrawingElement => row < vde.region.topRow || row > vde.region.bottomRow
    }

  private def canRemove(ede: EdgeDrawingElement, row: Int): Boolean =
    val firstBendPoint = ede.bendPoints.head
    val secondBendPoint = ede.bendPoints(1)
    val wouldLeaveStubbyUpArrow =
      row == firstBendPoint.row + 1 && ede.hasArrow1 &&
        secondBendPoint.row == row + 1 && ede.bendPoints.size > 2

    val lastBendPoint = ede.bendPoints.last
    val secondLastBendPoint = ede.bendPoints(ede.bendPoints.size - 2)
    val wouldLeaveStubbyDownArrow =
      row == lastBendPoint.row - 1 && ede.hasArrow2 &&
        secondLastBendPoint.row == row - 1 && ede.bendPoints.size > 2

    !wouldLeaveStubbyDownArrow && !wouldLeaveStubbyUpArrow &&
    ede.bendPoints.forall(_.row != row)

  private def removeRows(drawing: Drawing, fromRow: Int, toRow: Int): Drawing =
    val upShift = toRow - fromRow + 1
    val newElements = drawing.elements.map {
      case ede: EdgeDrawingElement =>
        ede.copy(bendPoints = ede.bendPoints.map(p => if p.row >= fromRow then p.up(upShift) else p))
      case vde: VertexDrawingElement =>
        if vde.region.topRow < fromRow then vde
        else vde.up(upShift)
    }
    drawing.copy(elements = newElements)

// ── BoxDrawingCharacters ────────────────────────────────────────────────────

object BoxDrawingCharacters:
  def isBoxDrawingCharacter(c: Char): Boolean = c >= 0x2500 && c <= 0x257f

// ── Renderer ────────────────────────────────────────────────────────────────

object Renderer:
  def render(drawing: Drawing, prefs: LayoutPrefs) =
    new Renderer(prefs).render(drawing)

class Renderer(prefs: LayoutPrefs):
  import prefs.*

  def render(drawing: Drawing): String =
    val grid = new Grid(drawing.dimension)
    drawing.vertexElements.foreach(vde => renderVertex(grid, vde))
    drawing.edgeElements.foreach(ede => renderEdge(grid, ede, drawing))
    grid.toString

  @tailrec
  private def drawLine(grid: Grid, point1: Point, direction: Direction, point2: Point): Unit =
    val lineChar = direction match
      case Up | Down => verticalLineChar
      case Right | Left => horizontalLineChar
    grid(point1) =
      if grid(point1) == backgroundChar then lineChar
      else intersectionChar
    if point1 != point2 then drawLine(grid, point1.go(direction), direction, point2)

  private def renderEdge(grid: Grid, element: EdgeDrawingElement, drawing: Drawing): Unit =
    for (previousSegmentOpt, segment @ EdgeSegment(point1, direction, point2)) <- withPrevious(element.segments) do
      val startPoint = point1
      val endPoint =
        if point2 == element.bendPoints.last then point2
        else point2.go(direction.opposite)
      try drawLine(grid, startPoint, direction, endPoint)
      catch
        case e: Throwable =>
          throw RuntimeException("Problem drawing segment " + segment + " in edge " + element, e)

      condOpt((previousSegmentOpt.map(_.direction), direction)) {
        case (Some(Up), Right) | (Some(Left), Down) => grid(point1) = bendChars(0)
        case (Some(Up), Left) | (Some(Right), Down) => grid(point1) = bendChars(1)
        case (Some(Down), Right) | (Some(Left), Up) => grid(point1) = bendChars(2)
        case (Some(Down), Left) | (Some(Right), Up) => grid(point1) = bendChars(3)
      }

    def drawBoxIntersection(intersectionPoint: Point, direction: Direction) =
      if unicode && drawing.vertexElementAt(intersectionPoint).isDefined && grid.contains(intersectionPoint) then
        grid(intersectionPoint) = direction match
          case Up => joinChars(0)
          case Down => joinChars(1)
          case Right => joinChars(2)
          case Left => joinChars(3)

    for EdgeSegment(point, direction, _) <- element.segments.headOption do
      if element.hasArrow1 then grid(point) = arrow(direction.opposite)
      else drawBoxIntersection(point.go(direction.opposite), direction.opposite)

    for EdgeSegment(_, direction, point) <- element.segments.lastOption do
      if element.hasArrow2 then grid(point) = arrow(direction)
      else drawBoxIntersection(point.go(direction), direction)

  private def renderVertex(grid: Grid, element: VertexDrawingElement): Unit =
    val region = element.region
    grid(region.topLeft) = cornerChars(0)
    grid(region.topRight) = cornerChars(1)
    grid(region.bottomLeft) = cornerChars(2)
    grid(region.bottomRight) = cornerChars(3)

    for column <- (region.leftColumn + 1).to(region.rightColumn - 1) do
      grid(Point(region.topRow, column)) = boxHorizontalChar
      grid(Point(region.bottomRow, column)) = boxHorizontalChar
    for row <- (region.topRow + 1).to(region.bottomRow - 1) do
      grid(Point(row, region.leftColumn)) = boxVerticalChar
      grid(Point(row, region.rightColumn)) = boxVerticalChar

    for (line, index) <- element.textLines.zipWithIndex do
      grid(region.topLeft.right.down(index + 1)) = line

    // Where an edge line meets a box side, replace the border with a junction
    if unicode then
      for row <- (region.topRow + 1).to(region.bottomRow - 1) do
        val left = Point(row, region.leftColumn)
        if grid(left.right) == '\u2500' then grid(left) = joinChars(3)
        val right = Point(row, region.rightColumn)
        if grid(right.left) == '\u2500' then grid(right) = joinChars(2)

  private def verticalLineChar = if unicode then '\u2502' else '|' // '│'
  private def horizontalLineChar = if unicode then '\u2500' else '-' // '─'
  private def intersectionChar = if unicode then '\u253c' else '-' // '┼'

  // Indexed glyph sets; call sites map junction kinds to indexes 0-3
  private val bendChars: String = // up\u2192right/left\u2192down, up\u2192left/right\u2192down, down\u2192right/left\u2192up, down\u2192left/right\u2192up
    if unicode then (if rounded then "\u256d\u256e\u2570\u256f" else "\u250c\u2510\u2514\u2518") // "╭╮╰╯" or "┌┐└┘"
    else if explicitAsciiBends then "/\\\\/"
    else "----"

  private val cornerChars: String = // top-left, top-right, bottom-left, bottom-right
    if !unicode then "++++"
    else if doubleVertices then "\u2554\u2557\u255a\u255d" // "╔╗╚╝"
    else if rounded then "\u256d\u256e\u2570\u256f" // "╭╮╰╯"
    else "\u250c\u2510\u2514\u2518" // "┌┐└┘"

  private val joinChars: String = // edge enters box: from above, below, right, left
    if doubleVertices then "\u2564\u2567\u2562\u255f" else "\u252c\u2534\u2524\u251c" // "╤╧╢╟" or "┬┴┤├"

  private def boxHorizontalChar =
    if unicode then (if doubleVertices then '\u2550' else '\u2500') // '═' or '─'
    else '-'
  private def boxVerticalChar =
    if unicode then (if doubleVertices then '\u2551' else '\u2502') // '║' or '│'
    else '|'

  private def backgroundChar = ' '

  private def arrow(direction: Direction): Char =
    direction match
      case Up => '^'
      case Down => 'v'
      case Right => '>'
      case Left => '<'
