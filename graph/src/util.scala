package asciiGraph

import scala.annotation.tailrec

// ── Utils ───────────────────────────────────────────────────────────────────

object Utils:
  def transformValues[K, V, V2](map: Map[K, V])(f: V => V2): Map[K, V2] =
    map.map { case (k, v) => (k, f(v)) }

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

  def removeFirst[T](xs: List[T], x: T): List[T] =
    xs match
      case Nil => Nil
      case h :: t =>
        if h == x then t
        else h :: removeFirst(t, x)

  def makeMap[T, U](s: Iterable[T], f: T => U): Map[T, U] =
    s.iterator.map(t => t -> f(t)).toMap

  def signum(x: Int) =
    x match
      case _ if x < 0 => -1
      case 0 => 0
      case _ if x > 0 => +1

  def conditionallyMap[T](xs: List[T])(fn: PartialFunction[T, T]): List[T] =
    xs.map(t => if fn.isDefinedAt(t) then fn(t) else t)

  def addToMultimap[K, V](m: Map[K, List[V]], k: K, v: V): Map[K, List[V]] =
    m + (k -> (v :: m.getOrElse(k, Nil)))

// ── QuadTree ────────────────────────────────────────────────────────────────

class QuadTree[T <: HasRegion](dimension: Dimension):
  private val maxCapacity = 1
  private val allRegion = Region(Point(0, 0), Point(dimension.height - 1, dimension.width - 1))
  private var rootNode: Node = LeafNode(allRegion, items = Set())

  private sealed abstract class Node:
    def region: Region
    def items: Set[T]
    def contains(t: T) = region.contains(t.region)
    def immediateItemsIntersecting(region: Region) = items.filter(i => i.region.intersects(region))
    def immediateItemIntersects(region: Region): Boolean = items.exists(i => i.region.intersects(region))
    def childNodes: List[Node]
    def addItem(t: T): Node
    def removeItem(t: T): Node

  private case class QuadNode(
      region: Region, items: Set[T],
      topLeft: Node, topRight: Node, bottomLeft: Node, bottomRight: Node
  ) extends Node:
    override def childNodes: List[Node] = List(topLeft, topRight, bottomLeft, bottomRight)
    def addItem(t: T) = copy(items = items + t)
    def removeItem(t: T) = copy(items = items - t)

  private object QuadNode:
    private val topLeftL = Lens.lens[QuadNode, Node](_.topLeft, (n1, n2) => n1.copy(topLeft = n2))
    private val topRightL = Lens.lens[QuadNode, Node](_.topRight, (n1, n2) => n1.copy(topRight = n2))
    private val bottomLeftL = Lens.lens[QuadNode, Node](_.bottomLeft, (n1, n2) => n1.copy(bottomLeft = n2))
    private val bottomRightL = Lens.lens[QuadNode, Node](_.bottomRight, (n1, n2) => n1.copy(bottomRight = n2))

    object Lenses:
      val topLeft = topLeftL
      val topRight = topRightL
      val bottomLeft = bottomLeftL
      val bottomRight = bottomRightL
      val childNodeLenses = List(topLeft, topRight, bottomLeft, bottomRight)

  private case class LeafNode(region: Region, items: Set[T]) extends Node:
    override def childNodes: List[Node] = Nil
    def addItem(t: T): LeafNode = copy(items = items + t)
    def removeItem(t: T): LeafNode = copy(items = items - t)

  def add(t: T): Unit =
    val tRegion = t.region
    def addRec(n: Node): Node =
      require(n.region.contains(tRegion))
      n match
        case qn: QuadNode =>
          QuadNode.Lenses.childNodeLenses.find(lens => lens(qn).region.contains(tRegion)) match
            case Some(childLens) => childLens.update(qn, addRec)
            case None => qn.addItem(t)
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
        case qn: QuadNode =>
          QuadNode.Lenses.childNodeLenses.find(lens => lens(qn).region.contains(tRegion)) match
            case Some(childLens) => childLens.update(qn, removeRec)
            case None => n.removeItem(t)
        case _: LeafNode =>
          n.removeItem(t)
    rootNode = removeRec(rootNode)

  private def quadrate(leaf: LeafNode): QuadNode =
    val (tl, tr, bl, br) = quadrateRegion(leaf.region)
    def makeLeaf(quadrant: Region) = LeafNode(quadrant, leaf.items.filter(i => quadrant.contains(i.region)))
    val topLeftNode = makeLeaf(tl)
    val topRightNode = makeLeaf(tr)
    val bottomLeftNode = makeLeaf(bl)
    val bottomRightNode = makeLeaf(br)
    val newItems = leaf.items.filterNot(i =>
      topLeftNode.contains(i) || topRightNode.contains(i) ||
      bottomLeftNode.contains(i) || bottomRightNode.contains(i)
    )
    QuadNode(leaf.region, newItems, topLeftNode, topRightNode, bottomLeftNode, bottomRightNode)

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

  def collisions(t: T): Set[T] = collectCollisions(t.region, rootNode)

  private def collectCollisions(region: Region, node: Node): Set[T] =
    if region.intersects(node.region) then
      node.immediateItemsIntersecting(region) ++
        node.childNodes.flatMap(collectCollisions(region, _))
    else Set()

// ── Lens ────────────────────────────────────────────────────────────────────

object Lens:
  def lens[T, X](getter: T => X, setter: (T, X) => T): Lens[T, X] =
    new Lens[T, X]:
      def get(t: T) = getter(t)
      def set(t: T, x: X) = setter(t, x)

trait Lens[T, X]:
  def apply(t: T): X = get(t)
  def get(t: T): X
  def set(t: T, x: X): T
  def update(t: T, f: X => X): T = set(t, f(get(t)))
