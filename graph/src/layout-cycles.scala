package asciiGraph

import scala.annotation.tailrec
import scala.collection.immutable.SortedMap

// ── CycleRemovalResult ─────────────────────────────────────────────────────

case class CycleRemovalResult[V](
    dag: Graph[V],
    reversedEdges: List[(V, V)],
    selfEdges: List[(V, V)]
):
  def countSelfEdges(v: V): Int = selfEdges.count(_._1 == v)

// ── CycleRemover ────────────────────────────────────────────────────────────

object CycleRemover:
  def removeCycles[V](graph: Graph[V]): CycleRemovalResult[V] =
    val cycleRemover = new CycleRemover[V]
    val (graphWithoutLoops, selfEdges) = cycleRemover.removeSelfLoops(graph)
    val (graphWithoutCycles, reversedEdges) = cycleRemover.removeCycles(graphWithoutLoops)
    CycleRemovalResult(graphWithoutCycles, reversedEdges, selfEdges)

class CycleRemover[V]:
  private class Removal(graph: Graph[V]):
    private val graphInfo = new CycleRemovalInfo(graph)
    private var left: List[V] = Nil
    private var right: List[V] = Nil

    @tailrec
    final def run(): Removal =
      addSinksToRight()
      addSourcesToLeft()
      graphInfo.getLargestDegreeDiffVertex match
        case Some(v) =>
          graphInfo.removeVertex(v)
          left ::= v
          run()
        case None => this

    private def addSinksToRight() =
      while graphInfo.getSinks.nonEmpty do
        for v <- graphInfo.getSinks do
          graphInfo.removeVertex(v)
          right ::= v

    private def addSourcesToLeft() =
      while graphInfo.getSources.nonEmpty do
        for v <- graphInfo.getSources do
          graphInfo.removeVertex(v)
          left ::= v

    def getSequence = left.reverse ++ right

  private def findVertexSequence(graph: Graph[V]): List[V] =
    new Removal(graph).run().getSequence

  private def isSelfLoop[X](edge: (X, X)) = edge._1 == edge._2

  def removeSelfLoops(graph: Graph[V]): (Graph[V], List[(V, V)]) =
    val (selfEdges, newEdges) = graph.edges.partition(isSelfLoop)
    (graph.copy(edges = newEdges), selfEdges)

  def removeCycles(graph: Graph[V]): (Graph[V], List[(V, V)]) =
    reflowGraph(graph, findVertexSequence(graph))

  def reflowGraph(graph: Graph[V], vertexSequence: List[V]): (Graph[V], List[(V, V)]) =
    val vertexIndexMap: Map[V, Int] = vertexSequence.zipWithIndex.toMap
    var newEdges: List[(V, V)] = Nil
    var reversedEdges: List[(V, V)] = Nil
    for
      (source, target) <- graph.edges
      sourceIndex = vertexIndexMap(source)
      targetIndex = vertexIndexMap(target)
    do
      if targetIndex < sourceIndex then
        reversedEdges ::= (target, source)
        newEdges ::= (target, source)
      else
        newEdges ::= (source, target)
    (graph.copy(edges = newEdges), reversedEdges)

// ── CycleRemovalInfo ────────────────────────────────────────────────────────

class CycleRemovalInfo[V](graph: Graph[V]):
  private var sources: Set[V] = graph.sources.toSet
  private var sinks: Set[V] = graph.sinks.toSet
  private var verticesToDegreeDiff: Map[V, Int] = Map()
  private var degreeDiffToVertices: SortedMap[Int, List[V]] = SortedMap()
  private var deletedVertices: Set[V] = Set()

  for v <- graph.vertices do
    addVertexToDegreeDiffMaps(v, graph.outDegree(v) - graph.inDegree(v))

  def getSources: Set[V] = sources
  def getSinks: Set[V] = sinks

  def getLargestDegreeDiffVertex: Option[V] =
    degreeDiffToVertices.lastOption.flatMap(_._2.headOption)

  def removeVertex(v: V): Unit =
    deletedVertices += v
    if sinks.contains(v) then sinks -= v
    if sources.contains(v) then sources -= v
    removeVertexFromDegreeDiffMaps(v)

    for outVertex <- getOutVertices(v) do
      adjustDegreeDiff(outVertex, +1)
      if getInVertices(outVertex).isEmpty then sources += outVertex
    for inVertex <- getInVertices(v) do
      adjustDegreeDiff(inVertex, -1)
      if getOutVertices(inVertex).isEmpty then sinks += inVertex

  private def getInVertices(v: V): List[V] = graph.inVertices(v).filterNot(deletedVertices)
  private def getOutVertices(v: V): List[V] = graph.outVertices(v).filterNot(deletedVertices)

  private def adjustDegreeDiff(v: V, delta: Int) =
    val previousDegreeDiff = removeVertexFromDegreeDiffMaps(v)
    addVertexToDegreeDiffMaps(v, previousDegreeDiff + delta)

  private def addVertexToDegreeDiffMaps(v: V, degreeDiff: Int) =
    degreeDiffToVertices += degreeDiff -> (v :: degreeDiffToVertices.getOrElse(degreeDiff, Nil))
    verticesToDegreeDiff += v -> degreeDiff

  private def removeVertexFromDegreeDiffMaps(v: V): Int =
    val degreeDiff = verticesToDegreeDiff(v)
    val vertices = degreeDiffToVertices(degreeDiff)
    val updatedVertices = vertices.filterNot(_ == v)
    if updatedVertices.isEmpty then degreeDiffToVertices -= degreeDiff
    else degreeDiffToVertices += degreeDiff -> updatedVertices
    verticesToDegreeDiff -= v
    degreeDiff
