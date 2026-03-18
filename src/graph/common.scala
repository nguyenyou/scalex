package asciiGraph

import scala.PartialFunction.cond

// ── Point ───────────────────────────────────────────────────────────────────

object Point:
  private def sameColumn(p1: Point, p2: Point, p3: Point) =
    p1.column == p2.column && p2.column == p3.column
  private def sameRow(p1: Point, p2: Point, p3: Point) =
    p1.row == p2.row && p2.row == p3.row
  private def colinear(p1: Point, p2: Point, p3: Point) =
    sameColumn(p1, p2, p3) || sameRow(p1, p2, p3)

  def removeRedundantPoints(points: List[Point]): List[Point] =
    points match
      case Nil | List(_) | List(_, _) => points
      case p1 :: p2 :: p3 :: remainder if colinear(p1, p2, p3) =>
        removeRedundantPoints(p1 :: p3 :: remainder)
      case p :: ps => p :: removeRedundantPoints(ps)

case class Point(row: Int, column: Int)
    extends Translatable[Point]
    with Transposable[Point]:

  def maxRowCol(that: Point): Point =
    Point(math.max(this.row, that.row), math.max(this.column, that.column))

  type Self = Point

  def translate(down: Int = 0, right: Int = 0): Point =
    Point(row + down, column + right)

  def transpose: Point = Point(column, row)

  def neighbours: List[Point] = List(up, right, down, left)

  def withRow(newRow: Int) = copy(row = newRow)

  def withColumn(newColumn: Int) = copy(column = newColumn)

  def region: Region = Region(this, this)

// ── Region ──────────────────────────────────────────────────────────────────

object Region:
  def apply(topLeft: Point, dimension: Dimension): Region =
    val bottomRight = Point(
      topLeft.row + dimension.height - 1,
      topLeft.column + dimension.width - 1
    )
    Region(topLeft, bottomRight)

case class Region(topLeft: Point, bottomRight: Point)
    extends Translatable[Region]
    with Transposable[Region]
    with HasRegion:
  require(width >= 0 && height >= 0)

  def region = this

  def bottomLeft = Point(bottomRight.row, topLeft.column)
  def topRight = Point(topLeft.row, bottomRight.column)
  def topRow = topLeft.row
  def bottomRow = bottomRight.row
  def leftColumn = topLeft.column
  def rightColumn = bottomRight.column

  def expandRight(n: Int) = copy(bottomRight = bottomRight.right(n))
  def expandDown(n: Int) = copy(bottomRight = bottomRight.down(n))
  def expandUp(n: Int) = copy(topLeft = topLeft.up(n))
  def expandLeft(n: Int) = copy(topLeft = topLeft.left(n))

  def contains(point: Point): Boolean =
    point.row >= topRow && point.column >= leftColumn &&
    point.row <= bottomRow && point.column <= rightColumn

  def contains(region: Region): Boolean =
    contains(region.topLeft) && contains(region.bottomRight)

  def intersects(that: Region): Boolean = !isDisjoint(that)

  def isDisjoint(that: Region): Boolean =
    this.rightColumn < that.leftColumn || that.rightColumn < this.leftColumn ||
      this.bottomRow < that.topRow || that.bottomRow < this.topRow

  def width = rightColumn - leftColumn + 1
  def height = bottomRow - topRow + 1
  def dimension = Dimension(height, width)
  def area = width * height

  def points: List[Point] =
    for
      row <- topRow.to(bottomRow).toList
      column <- leftColumn.to(rightColumn)
    yield Point(row, column)

  def translate(down: Int = 0, right: Int = 0): Region =
    Region(topLeft.translate(down, right), bottomRight.translate(down, right))

  def transpose = Region(topLeft.transpose, bottomRight.transpose)

// ── Dimension ───────────────────────────────────────────────────────────────

object Dimension:
  def fromPoint(largestPoint: Point): Dimension =
    Dimension(width = largestPoint.column + 1, height = largestPoint.row + 1)

case class Dimension(height: Int, width: Int) extends Transposable[Dimension]:
  def transpose = Dimension(width, height)

// ── Direction ───────────────────────────────────────────────────────────────

sealed trait Direction:
  import Direction.*

  val turnLeft: Direction
  val turnRight: Direction
  val opposite: Direction

  def isVertical = this == Up || this == Down
  def isHorizontal = !isVertical

object Direction:
  case object Up extends Direction:
    val turnLeft = Left
    val turnRight = Right
    val opposite: Direction = Down

  case object Down extends Direction:
    val turnLeft = Right
    val turnRight = Left
    val opposite: Direction = Up

  case object Left extends Direction:
    val turnLeft = Down
    val turnRight = Up
    val opposite: Direction = Right

  case object Right extends Direction:
    val turnLeft = Up
    val turnRight = Down
    val opposite: Direction = Left

// ── Translatable ────────────────────────────────────────────────────────────

trait Translatable[+Self]:
  def translate(down: Int = 0, right: Int = 0): Self

  def up: Self = up(1)
  def up(n: Int): Self = translate(down = -n)
  def down: Self = down(1)
  def down(n: Int): Self = translate(down = n)
  def left: Self = left(1)
  def left(n: Int): Self = translate(right = -n)
  def right: Self = right(1)
  def right(n: Int): Self = translate(right = n)

  def go(direction: Direction) =
    direction match
      case Direction.Up    => up
      case Direction.Down  => down
      case Direction.Left  => left
      case Direction.Right => right

// ── Transposable ────────────────────────────────────────────────────────────

trait Transposable[+T]:
  def transpose: T

// ── HasRegion ───────────────────────────────────────────────────────────────

trait HasRegion:
  def region: Region

// ── Characters ──────────────────────────────────────────────────────────────

object Characters:
  def isAheadArrow(c: Char, direction: Direction): Boolean =
    cond((c, direction)):
      case ('^', Direction.Up) => true
      case ('v' | 'V', Direction.Down) => true
      case ('<', Direction.Left) => true
      case ('>', Direction.Right) => true

  def isLeftArrow(c: Char, direction: Direction) =
    isAheadArrow(c, direction.turnLeft)

  def isRightArrow(c: Char, direction: Direction) =
    isAheadArrow(c, direction.turnRight)
