package asciiGraph

import scala.PartialFunction.cond
import Utils.*

// ── Graph ───────────────────────────────────────────────────────────────────

object Graph:
  def fromDiagram(s: String): Graph[String] = fromDiagram(Diagram(s))
  def fromDiagram(diagram: Diagram): Graph[String] = DiagramToGraphConvertor.toGraph(diagram)

case class Graph[V](vertices: Set[V], edges: List[(V, V)]):

  val outMap: Map[V, List[V]] = edges
    .groupBy(_._1)
    .map { case (k, vs) => (k, vs.map(_._2)) }

  val inMap: Map[V, List[V]] = edges
    .groupBy(_._2)
    .map { case (k, vs) => (k, vs.map(_._1)) }

  require(outMap.keys.forall(vertices.contains))
  require(inMap.keys.forall(vertices.contains))

  def isEmpty = vertices.isEmpty
  def inEdges(v: V): List[(V, V)] = edges.filter(_._2 == v)
  def outEdges(v: V): List[(V, V)] = edges.filter(_._1 == v)
  def inVertices(v: V): List[V] = inMap.getOrElse(v, Nil)
  def outVertices(v: V): List[V] = outMap.getOrElse(v, Nil)
  def outDegree(v: V): Int = outVertices(v).size
  def inDegree(v: V): Int = inVertices(v).size
  def sources: List[V] = vertices.toList.filter(inDegree(_) == 0)
  def sinks: List[V] = vertices.toList.filter(outDegree(_) == 0)

  def removeEdge(edge: (V, V)): Graph[V] =
    copy(edges = removeFirst(edges, edge))

  def removeVertex(v: V): Graph[V] =
    Graph(vertices.filterNot(_ == v), edges.filterNot { case (v1, v2) => v1 == v || v2 == v })

  def map[U](f: V => U): Graph[U] =
    Graph(vertices.map(f), edges.map { case (v1, v2) => (f(v1), f(v2)) })

  override lazy val hashCode = vertices.## + edges.##

  override def equals(obj: Any): Boolean =
    cond(obj) { case other: Graph[V @unchecked] =>
      multisetCompare(vertices.toList, other.vertices.toList) &&
        multisetCompare(edges, other.edges)
    }

  private def singletonVertices =
    vertices.filter(v => inDegree(v) == 0 && outDegree(v) == 0)

  private def asVertexList: String =
    def render(v: V) = v.toString.replaceAll("\n", "\\\\n")
    singletonVertices.toList.map(render).sorted.mkString("\n") + "\n" +
      edges.toList
        .sortBy(e => (render(e._1), render(e._2)))
        .map(e => render(e._1) + "," + render(e._2))
        .mkString("\n")

  override def toString =
    try
      val layoutPrefs = LayoutPrefsImpl(unicode = true, explicitAsciiBends = false)
      "\n" + GraphLayout.renderGraph(this, layoutPrefs = layoutPrefs) + "\n" + asVertexList
    catch
      case _: Throwable => asVertexList

// ── GraphUtils ──────────────────────────────────────────────────────────────

object GraphUtils:
  def topologicalSort[V](g: Graph[V]): Option[List[V]] =
    var sort: List[V] = Nil
    var sources: List[V] = g.sources
    var deletedEdges: Set[(V, V)] = Set()
    while sources.nonEmpty do
      val n = sources.head
      sources = sources.tail
      sort ::= n
      for
        m <- g.outVertices(n)
        if !deletedEdges.contains((m, n))
      do
        deletedEdges += ((n, m))
        if g.inEdges(m).filterNot(deletedEdges).isEmpty &&
           !sources.contains(m)
        then sources ::= m
    if deletedEdges == g.edges.toSet then Some(sort.reverse)
    else None

  def hasCycle(g: Graph[?]): Boolean = topologicalSort(g).isEmpty

// ── DiagramToGraphConvertor ─────────────────────────────────────────────────

object DiagramToGraphConvertor:
  def toGraph(diagram: Diagram): Graph[String] =
    val boxToVertexMap: Map[DiagramBox, String] = makeMap(diagram.childBoxes, _.text)
    val vertices = boxToVertexMap.values.toSet
    val edges =
      for
        edge <- diagram.allEdges
        vertex1 <- boxToVertexMap.get(edge.box1)
        vertex2 <- boxToVertexMap.get(edge.box2)
      yield
        if edge.hasArrow2 then vertex1 -> vertex2
        else vertex2 -> vertex1
    Graph(vertices, edges)
