package asciiGraph

import scala.PartialFunction.cond
import scala.annotation.tailrec
import Direction.*
import Characters.*
import BoxDrawingCharacters.*

// ── DiagramParserException ──────────────────────────────────────────────────

class DiagramParserException(message: String) extends RuntimeException(message)

// ── DiagramParser ───────────────────────────────────────────────────────────

class DiagramParser(s: String):
  // ── UnicodeEdgeParser ───────────────────────────────────────────────────

  @tailrec
  protected final def followUnicodeEdge(points: List[Point], direction: Direction): Option[EdgeImpl] =
    val currentPoint = points.head
    if !inDiagram(currentPoint) then None
    else if isBoxEdge(currentPoint) then Some(new EdgeImpl(points.reverse))
    else
      val c = charAt(currentPoint)
      if isStraightAheadUnicode(c, direction) || isCrossingUnicode(c) || isAheadArrowUnicode(c, direction) then
        followUnicodeEdge(currentPoint.go(direction) :: points, direction)
      else if isLeftTurnUnicode(c, direction) || isLeftArrow(c, direction) then
        followUnicodeEdge(currentPoint.go(direction.turnLeft) :: points, direction.turnLeft)
      else if isRightTurnUnicode(c, direction) || isRightArrow(c, direction) then
        followUnicodeEdge(currentPoint.go(direction.turnRight) :: points, direction.turnRight)
      else None

  protected def isEdgeStart(c: Char, direction: Direction): Boolean =
    cond((c, direction)):
      case ('\u2564' | '\u252c', Down) => true   // '╤' | '┬'
      case ('\u256a' | '\u253c', Up | Down) => true   // '╪' | '┼'
      case ('\u2567' | '\u2534', Up) => true   // '╧' | '┴'
      case ('\u255f' | '\u251c', Right) => true   // '╟' | '├'
      case ('\u256b' | '\u253c', Right | Left) => true   // '╫' | '┼'
      case ('\u2562' | '\u2524', Left) => true   // '╢' | '┤'

  private def isStraightAheadUnicode(c: Char, direction: Direction): Boolean =
    cond((c, direction)):
      case ('\u2500', Right | Left) => true   // '─'
      case ('\u2502', Up | Down) => true   // '│'

  private def isAheadArrowUnicode(c: Char, direction: Direction): Boolean =
    cond((c, direction)):
      case ('^', Up) => true
      case ('v' | 'V', Down) => true
      case ('<', Left) => true
      case ('>', Right) => true

  private def isRightTurnUnicode(c: Char, direction: Direction): Boolean =
    cond((c, direction)):
      case ('\u256e' | '\u2510', Right) => true   // '╮' | '┐'
      case ('\u256f' | '\u2518', Down) => true   // '╯' | '┘'
      case ('\u256d' | '\u250c', Up) => true   // '╭' | '┌'
      case ('\u2570' | '\u2514', Left) => true   // '╰' | '└'

  private def isLeftTurnUnicode(c: Char, direction: Direction): Boolean =
    isRightTurnUnicode(c, direction.turnRight)

  private def isCrossingUnicode(c: Char): Boolean = c == '\u253c' // '┼'

  // ── AsciiEdgeParser ─────────────────────────────────────────────────────

  @tailrec
  protected final def followAsciiEdge(points: List[Point], direction: Direction): Option[EdgeImpl] =
    val currentPoint = points.head
    if !inDiagram(currentPoint) then None
    else if isBoxEdge(currentPoint) then
      if points.size <= 2 then None
      else Some(new EdgeImpl(points.reverse))
    else
      val c = charAt(currentPoint)
      val ahead = currentPoint.go(direction)
      val left = currentPoint.go(direction.turnLeft)
      val right = currentPoint.go(direction.turnRight)
      val aheadIsContinuation = isContinuation(ahead, direction)
      val rightIsContinuation = isContinuation(right, direction.turnRight)
      val leftIsContinuation = isContinuation(left, direction.turnLeft)

      if isCrossingAscii(c) || isAheadArrow(c, direction) then
        followAsciiEdge(currentPoint.go(direction) :: points, direction)
      else if isLeftTurnAscii(c, direction) && points.size > 2 then
        followAsciiEdge(left :: points, direction.turnLeft)
      else if isRightTurnAscii(c, direction) && points.size > 2 then
        followAsciiEdge(right :: points, direction.turnRight)
      else if isStraightAheadAscii(c, direction) then
        if aheadIsContinuation then followAsciiEdge(ahead :: points, direction)
        else if leftIsContinuation && !rightIsContinuation && !isTurn(left) then
          followAsciiEdge(left :: points, direction.turnLeft)
        else if !leftIsContinuation && rightIsContinuation && !isTurn(right) then
          followAsciiEdge(right :: points, direction.turnRight)
        else followAsciiEdge(ahead :: points, direction)
      else if isOrthogonalAscii(c, direction) then
        if leftIsContinuation && !rightIsContinuation then
          followAsciiEdge(left :: points, direction.turnLeft)
        else if !leftIsContinuation && rightIsContinuation then
          followAsciiEdge(right :: points, direction.turnRight)
        else followAsciiEdge(ahead :: points, direction)
      else if isLeftArrow(c, direction) then
        followAsciiEdge(left :: points, direction.turnLeft)
      else if isRightArrow(c, direction) then
        followAsciiEdge(right :: points, direction.turnRight)
      else None

  private def isContinuation(point: Point, direction: Direction): Boolean =
    isBoxEdge(point) || charAtOpt(point).exists { c =>
      isStraightAheadAscii(c, direction) || isCrossingAscii(c) ||
      isAheadArrow(c, direction) || isLeftTurnAscii(c, direction) ||
      isRightTurnAscii(c, direction)
    }

  private def isStraightAheadAscii(c: Char, direction: Direction): Boolean =
    cond((c, direction)):
      case ('-', Right | Left) => true
      case ('|', Up | Down) => true

  private def isOrthogonalAscii(c: Char, direction: Direction): Boolean =
    isStraightAheadAscii(c, direction.turnRight)

  private def isCrossingAscii(c: Char): Boolean = c == '+'

  private def isTurn(p: Point): Boolean = charAtOpt(p).exists(isTurnChar)
  private def isTurnChar(c: Char): Boolean = c == '\\' || c == '/'

  private def isRightTurnAscii(c: Char, direction: Direction): Boolean =
    cond((c, direction)):
      case ('\\', Left | Right) => true
      case ('/', Up | Down) => true

  private def isLeftTurnAscii(c: Char, direction: Direction): Boolean =
    isRightTurnAscii(c, direction.turnRight)

  // ── BoxParser ───────────────────────────────────────────────────────────

  protected def findAllBoxes: List[BoxImpl] =
    for
      topLeft <- possibleTopLefts
      bottomRight <- completeBox(topLeft)
    yield new BoxImpl(topLeft, bottomRight)

  private def possibleTopLefts: List[Point] =
    for
      row <- 0.until(numberOfRows - 1).toList
      column <- 0.until(numberOfColumns - 1)
      point = Point(row, column)
      cornerChar = charAt(point)
      if isTopLeftCorner(cornerChar)
      if isHorizontalBoxEdge(charAt(point.go(Right))) || isBoxDrawingCharacter(cornerChar)
      if isVerticalBoxEdge(charAt(point.go(Down))) || isBoxDrawingCharacter(cornerChar)
    yield point

  @tailrec
  private def scanBoxEdge(p: Point, dir: Direction, isCorner: Char => Boolean, isEdge: Char => Boolean): Option[Point] =
    if inDiagram(p) then
      val c = charAt(p)
      if isCorner(c) then Some(p)
      else if isEdge(c) then scanBoxEdge(p.go(dir), dir, isCorner, isEdge)
      else None
    else None

  private def completeBox(topLeft: Point): Option[Point] =
    for
      topRight <- scanBoxEdge(topLeft.right, Right, isTopRightCorner, isHorizontalBoxEdge)
      bottomRight <- scanBoxEdge(topRight.down, Down, isBottomRightCorner, isVerticalBoxEdge)
      bottomLeft <- scanBoxEdge(topLeft.down, Down, isBottomLeftCorner, isVerticalBoxEdge)
      bottomRight2 <- scanBoxEdge(bottomLeft.right, Right, isBottomRightCorner, isHorizontalBoxEdge)
      if bottomRight == bottomRight2
    yield bottomRight

  private def isTopRightCorner(c: Char): Boolean =
    cond(c) { case '\u2557' | '\u256e' | '\u2510' | '+' => true } // '╗' | '╮' | '┐' | '+'
  private def isBottomRightCorner(c: Char): Boolean =
    cond(c) { case '\u255d' | '\u256f' | '\u2518' | '+' => true } // '╝' | '╯' | '┘' | '+'
  private def isTopLeftCorner(c: Char): Boolean =
    cond(c) { case '\u2554' | '\u256d' | '\u250c' | '+' => true } // '╔' | '╭' | '┌' | '+'
  private def isBottomLeftCorner(c: Char): Boolean =
    cond(c) { case '\u255a' | '\u2570' | '\u2514' | '+' => true } // '╚' | '╰' | '└' | '+'

  private def isHorizontalBoxEdge(c: Char): Boolean =
    cond(c) { case '\u2550' | '\u2500' | '-' | '\u2564' | '\u252c' | '\u2567' | '\u2534' | '\u256a' | '\u253c' => true }
    // '═' | '─' | '-' | '╤' | '┬' | '╧' | '┴' | '╪' | '┼'
  private def isVerticalBoxEdge(c: Char): Boolean =
    cond(c) { case '\u2551' | '\u2502' | '|' | '\u2562' | '\u2524' | '\u255f' | '\u251c' | '\u256b' | '\u253c' => true }
    // '║' | '│' | '|' | '╢' | '┤' | '╟' | '├' | '╫' | '┼'

  // ── LabelParser ─────────────────────────────────────────────────────────

  protected def getLabel(edge: EdgeImpl): Option[Label] =
    val labels =
      for
        point <- edge.points
        startPoint <- point.neighbours
        c <- charAtOpt(startPoint)
        if c == '[' || c == ']'
        label <- completeLabel(startPoint, edge.parent)
      yield label
    if labels.distinct.size > 1 then
      throw DiagramParserException(
        "Multiple labels for edge " + edge + ", " + labels.distinct.map(_.text).mkString(",")
      )
    else labels.headOption

  private def completeLabel(startPoint: Point, parent: ContainerImpl): Option[Label] =
    val childBoxPoints = parent.childBoxes.flatMap(_.region.points).toSet
    val occupiedPoints = childBoxPoints ++ allEdgePoints
    val (finalChar, direction) = charAt(startPoint) match
      case '[' => (']', Right)
      case ']' => ('[', Left)
    def search(point: Point): Option[Label] =
      charAtOpt(point).flatMap {
        case `finalChar` =>
          val List(p1, p2) = List(startPoint, point).sortBy(_.column)
          Some(Label(p1, p2))
        case _ if occupiedPoints.contains(point) => None
        case _ => search(point.go(direction))
      }
    search(startPoint.go(direction))

  // ── DiagramImplementation ───────────────────────────────────────────────

  protected class DiagramImpl(numberOfRows: Int, numberOfColumns: Int) extends ContainerImpl with Diagram:
    var allBoxes: List[BoxImpl] = Nil
    var allEdges: List[EdgeImpl] = Nil
    def boxAt(point: Point): Option[BoxImpl] = allBoxes.find(_.boundaryPoints.contains(point))
    def region: Region = diagramRegion
    def contentsRegion = region

  protected case class Label(start: Point, end: Point):
    require(start.row == end.row)
    val row = start.row
    def points: List[Point] =
      for column <- start.column.to(end.column).toList yield Point(row, column)
    val text: String =
      val sb = new StringBuilder
      for column <- (start.column + 1).to(end.column - 1) do sb.append(charAt(Point(row, column)))
      sb.toString

  protected abstract class ContainerImpl extends RegionToString:
    self: DiagramContainer =>
    var text: String = ""
    var childBoxes: List[BoxImpl] = Nil
    def contentsRegion: Region

  protected class BoxImpl(val topLeft: Point, val bottomRight: Point) extends ContainerImpl with DiagramBox:
    var edges: List[EdgeImpl] = Nil
    var parent: Option[DiagramContainer & ContainerImpl] = None
    def region: Region = Region(topLeft, bottomRight)
    def contentsRegion: Region = Region(topLeft.right.down, bottomRight.up.left)
    val leftBoundary: List[Point] =
      for row <- topLeft.row.to(bottomRight.row).toList yield Point(row, topLeft.column)
    val rightBoundary: List[Point] =
      for row <- topLeft.row.to(bottomRight.row).toList yield Point(row, bottomRight.column)
    val topBoundary: List[Point] =
      for column <- topLeft.column.to(bottomRight.column).toList yield Point(topLeft.row, column)
    val bottomBoundary: List[Point] =
      for column <- topLeft.column.to(bottomRight.column).toList yield Point(bottomRight.row, column)
    val boundaryPoints: Set[Point] =
      leftBoundary.toSet ++ rightBoundary.toSet ++ topBoundary.toSet ++ bottomBoundary.toSet

  protected class EdgeImpl(val points: List[Point]) extends DiagramEdge:
    val box1: BoxImpl = diagram.boxAt(points.head).get
    val box2: BoxImpl = diagram.boxAt(points.last).get
    var label_ : Option[Label] = None
    lazy val label = label_.map(_.text)
    lazy val parent: DiagramContainer & ContainerImpl =
      if box1.parent == Some(box2) then box2
      else box2.parent.get
    lazy val hasArrow1 = isArrow(charAt(points.drop(1).head))
    lazy val hasArrow2 = isArrow(charAt(points.dropRight(1).last))
    lazy val edgeAndLabelPoints: List[Point] = points ++ label_.map(_.points).getOrElse(Nil)
    override def toString = diagramRegionToString(regionOf(edgeAndLabelPoints), edgeAndLabelPoints.contains)
    def regionOf(points: List[Point]): Region =
      Region(Point(points.map(_.row).min, points.map(_.column).min), Point(points.map(_.row).max, points.map(_.column).max))

  protected trait RegionToString:
    def region: Region
    override def toString = diagramRegionToString(region)

  private def diagramRegionToString(region: Region, includePoint: Point => Boolean = _ => true) =
    val sb = new StringBuilder("\n")
    for row <- region.topLeft.row.to(region.bottomRight.row) do
      for
        column <- region.topLeft.column.to(region.bottomRight.column)
        point = Point(row, column)
        c = if includePoint(point) then charAt(point) else ' '
      do sb.append(c)
      sb.append("\n")
    sb.toString

  private def isArrow(c: Char) = cond(c) { case '^' | '<' | '>' | 'V' | 'v' => true }

  // ── Parser initialization ───────────────────────────────────────────────

  private val rawRows: List[String] = if s.isEmpty then Nil else s.split("(\r)?\n").toList
  protected val numberOfColumns = if rawRows.isEmpty then 0 else rawRows.map(_.length).max
  private val rows = rawRows.map(_.padTo(numberOfColumns, ' ')).toArray
  protected val numberOfRows = rows.length
  protected val diagramRegion = Region(Point(0, 0), Point(numberOfRows - 1, numberOfColumns - 1))
  protected val diagram = new DiagramImpl(numberOfRows, numberOfColumns)

  diagram.allBoxes = findAllBoxes

  private val boxContains: Map[BoxImpl, BoxImpl] =
    (for
      outerBox <- diagram.allBoxes
      innerBox <- diagram.allBoxes
      if outerBox != innerBox
      if outerBox.region.contains(innerBox.region)
    yield outerBox -> innerBox).toMap

  for (box, containingBoxMap) <- boxContains.groupBy(_._2) do
    val containingBoxes = box :: containingBoxMap.keys.toList
    val orderedBoxes = containingBoxes.sortBy(_.region.area)
    for (childBox, parentBox) <- orderedBoxes.zip(orderedBoxes.drop(1)) do
      childBox.parent = Some(parentBox)
      parentBox.childBoxes ::= childBox

  for
    box <- diagram.allBoxes
    if box.parent.isEmpty
  do
    diagram.childBoxes ::= box
    box.parent = Some(diagram)

  private val edges = diagram.allBoxes.flatMap { box =>
    box.rightBoundary.flatMap(followEdge(Right, _)) ++
    box.leftBoundary.flatMap(followEdge(Left, _)) ++
    box.topBoundary.flatMap(followEdge(Up, _)) ++
    box.bottomBoundary.flatMap(followEdge(Down, _))
  }

  diagram.allEdges = edges.groupBy(_.points.toSet).values.toList.map(_.head)

  for edge <- diagram.allEdges do
    edge.box1.edges ::= edge
    if edge.box1 != edge.box2 then edge.box2.edges ::= edge

  protected lazy val allEdgePoints: Set[Point] = diagram.allEdges.flatMap(_.points).toSet

  for edge <- diagram.allEdges do edge.label_ = getLabel(edge)

  for box <- diagram.allBoxes do box.text = collectText(box)
  diagram.text = collectText(diagram)

  protected def inDiagram(p: Point): Boolean = diagramRegion.contains(p)
  protected def charAt(point: Point): Char = rows(point.row)(point.column)
  protected def charAtOpt(point: Point): Option[Char] =
    if inDiagram(point) then Some(charAt(point)) else None

  def getDiagram: Diagram = diagram

  protected def isBoxEdge(point: Point) =
    inDiagram(point) && diagram.allBoxes.exists(_.boundaryPoints.contains(point))

  private def followEdge(direction: Direction, startPoint: Point): Option[EdgeImpl] =
    val initialPoints = startPoint.go(direction) :: startPoint :: Nil
    if isEdgeStart(charAt(startPoint), direction) then followUnicodeEdge(initialPoints, direction)
    else if !isBoxDrawingCharacter(charAt(startPoint)) then followAsciiEdge(initialPoints, direction)
    else None

  private def collectText(container: ContainerImpl): String =
    val childBoxPoints = container.childBoxes.flatMap(_.region.points).toSet
    val sb = new StringBuilder
    val region = container.contentsRegion
    for row <- region.topLeft.row.to(region.bottomRight.row) do
      for
        column <- region.topLeft.column.to(region.bottomRight.column)
        point = Point(row, column)
        if !childBoxPoints.contains(point)
        if !allEdgePoints.contains(point)
        if !(diagram.allEdges.flatMap(_.label_.toList.flatMap(_.points)).toSet.contains(point))
      do sb.append(charAt(point))
      sb.append("\n")
    if sb.nonEmpty then sb.deleteCharAt(sb.length - 1)
    sb.toString
