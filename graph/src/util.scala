package asciiGraph

import scala.annotation.tailrec

// ── Utils ───────────────────────────────────────────────────────────────────

object Utils:
  def withPrevious[T](iterable: Iterable[T]): List[(Option[T], T)] =
    withPreviousAndNext(iterable).map { case (a, b, _) => (a, b) }

  def withPreviousAndNext[T](iterable: Iterable[T]): List[(Option[T], T, Option[T])] =
    if iterable.isEmpty then Nil
    else
      val previous = None :: iterable.init.map(Some[T]).toList
      val next = iterable.tail.map(Some[T]).toList ::: List(None)
      previous.zip(iterable).zip(next).map { case ((a, b), c) => (a, b, c) }

  def adjacentPairs[T](xs: List[T]): List[(T, T)] = xs.zip(xs.drop(1))

  def adjacentTriples[T](xs: List[T]): List[(T, T, T)] =
    xs.zip(xs.drop(1)).zip(xs.drop(2)).map { case ((x, y), z) => (x, y, z) }

  def adjacentPairsWithPreviousAndNext[T](xs: List[T]): List[(Option[T], T, T, Option[T])] =
    (None :: xs.init.map(Some(_)))
      .zip(xs)
      .zip(xs.drop(1))
      .zip(xs.drop(2).map(Some(_)) :+ None)
      .map { case (((x, y), z), u) => (x, y, z, u) }

  @tailrec
  def iterate[T](t: T, f: T => Option[T]): T =
    f(t) match
      case Some(t2) => iterate(t2, f)
      case None => t

  def multisetCompare[T](set1: List[T], set2: List[T]): Boolean =
    mkMultiset(set1) == mkMultiset(set2)

  def mkMultiset[T](set1: List[T]): Map[T, Int] =
    set1.groupBy(identity).map { case (k, v) => k -> v.size }

  def makeMap[T, U](s: Iterable[T], f: T => U): Map[T, U] =
    s.iterator.map(t => t -> f(t)).toMap

// ── QuadTree ────────────────────────────────────────────────────────────────

class QuadTree[T <: HasRegion](dimension: Dimension):
  private val maxCapacity = 1
  private val allRegion = Region(Point(0, 0), Point(dimension.height - 1, dimension.width - 1))
  private var rootNode: Node = LeafNode(allRegion, items = Set())

  private sealed abstract class Node:
    def region: Region
    def items: Set[T]
    def contains(t: T) = region.contains(t.region)
    def immediateItemIntersects(region: Region): Boolean = items.exists(i => i.region.intersects(region))
    def childNodes: List[Node]
    def addItem(t: T): Node
    def removeItem(t: T): Node

  private case class QuadNode(region: Region, items: Set[T], childNodes: List[Node]) extends Node:
    def addItem(t: T) = copy(items = items + t)
    def removeItem(t: T) = copy(items = items - t)

  private case class LeafNode(region: Region, items: Set[T]) extends Node:
    override def childNodes: List[Node] = Nil
    def addItem(t: T): LeafNode = copy(items = items + t)
    def removeItem(t: T): LeafNode = copy(items = items - t)

  /** Update the child whose region contains `tRegion`, or apply `orElse` to the node itself. */
  private def updateContainingChild(qn: QuadNode, tRegion: Region, rec: Node => Node, orElse: => Node): Node =
    qn.childNodes.indexWhere(_.region.contains(tRegion)) match
      case -1 => orElse
      case i => qn.copy(childNodes = qn.childNodes.updated(i, rec(qn.childNodes(i))))

  def add(t: T): Unit =
    val tRegion = t.region
    def addRec(n: Node): Node =
      require(n.region.contains(tRegion))
      n match
        case qn: QuadNode => updateContainingChild(qn, tRegion, addRec, qn.addItem(t))
        case leaf: LeafNode =>
          val newLeaf = leaf.addItem(t)
          if newLeaf.items.size <= maxCapacity && newLeaf.region.width > 1 && newLeaf.region.height > 1
          then newLeaf
          else quadrate(newLeaf)
    rootNode = addRec(rootNode)

  def remove(t: T): Unit =
    val tRegion = t.region
    def removeRec(n: Node): Node =
      require(n.region.contains(tRegion))
      n match
        case qn: QuadNode => updateContainingChild(qn, tRegion, removeRec, qn.removeItem(t))
        case _: LeafNode => n.removeItem(t)
    rootNode = removeRec(rootNode)

  private def quadrate(leaf: LeafNode): QuadNode =
    val (tl, tr, bl, br) = quadrateRegion(leaf.region)
    def makeLeaf(quadrant: Region) = LeafNode(quadrant, leaf.items.filter(i => quadrant.contains(i.region)))
    val childNodes = List(makeLeaf(tl), makeLeaf(tr), makeLeaf(bl), makeLeaf(br))
    val newItems = leaf.items.filterNot(i => childNodes.exists(_.contains(i)))
    QuadNode(leaf.region, newItems, childNodes)

  private def quadrateRegion(region: Region): (Region, Region, Region, Region) =
    val middleTop = region.topLeft.right(region.width / 2)
    val middleLeft = region.topLeft.down(region.height / 2)
    val middleRight = region.topRight.down(region.height / 2)
    val middleBottom = region.bottomLeft.right(region.width / 2)
    val middle = middleTop.down(region.height / 2)
    val topLeft = Region(region.topLeft, middle.up.left)
    val bottomRight = Region(middle, region.bottomRight)
    val bottomLeft = Region(middleLeft, middleBottom.left)
    val topRight = Region(middleTop, middleRight.up)
    (topLeft, topRight, bottomLeft, bottomRight)

  def collides(region: Region): Boolean = collides(region, rootNode)

  private def collides(region: Region, node: Node): Boolean =
    region.intersects(node.region) &&
      (node.immediateItemIntersects(region) ||
        node.childNodes.exists(collides(region, _)))
