package asciiGraph

import scala.collection.mutable.Buffer
import scala.collection.mutable.ListBuffer

// ── Layering ────────────────────────────────────────────────────────────────

case class Layering(layers: List[Layer], edges: List[LayeringEdge]):
  def edgesInto(layer: Layer): List[LayeringEdge] =
    edges.filter(e => layer.contains(e.finishVertex))

case class Layer(vertices: List[LayeringVertex]):
  def contains(v: LayeringVertex) = vertices.contains(v)
  def positionOf(v: LayeringVertex) = vertices.indexOf(v)

sealed abstract class LayeringVertex

class DummyVertex() extends LayeringVertex:
  override def toString = "DummyVertex"

class RealVertex(val contents: Any, val selfEdges: Int) extends LayeringVertex:
  override def toString = "RealVertex(" + contents.toString + ", selfEdges = " + selfEdges + ")"

class LayeringEdge(val startVertex: LayeringVertex, val finishVertex: LayeringVertex, val reversed: Boolean):
  override def toString = "LayeringEdge(" + startVertex + ", " + finishVertex + "," + reversed + ")"

object LayeringEdge:
  def unapply(e: LayeringEdge) = Some((e.startVertex, e.finishVertex))

// ── LayeringCalculator ──────────────────────────────────────────────────────

class LayeringCalculator[V]:
  def assignLayers(cycleRemovalResult: CycleRemovalResult[V]): (Layering, Map[V, RealVertex]) =
    val graph = cycleRemovalResult.dag
    val distancesToSink = LongestDistancesToSinkCalculator.longestDistancesToSink(cycleRemovalResult.dag)
    val maxLayerNum = if distancesToSink.isEmpty then -1 else distancesToSink.values.max
    def layerNum(v: V): Int = maxLayerNum - distancesToSink(v)

    val layeringBuilder = new LayeringBuilder(maxLayerNum + 1)
    val realVertices: Map[V, RealVertex] = makeRealVertices(cycleRemovalResult)
    for v <- graph.vertices do
      layeringBuilder.addVertex(layerNum(v), realVertices(v))
    addEdges(cycleRemovalResult, layerNum, layeringBuilder, realVertices)
    (layeringBuilder.build, realVertices)

  private class LayeringBuilder(numberOfLayers: Int):
    val layers: Buffer[Buffer[LayeringVertex]] = ListBuffer.fill(numberOfLayers)(ListBuffer[LayeringVertex]())
    var edges: List[LayeringEdge] = Nil

    def addVertex(layerNum: Int, v: LayeringVertex): Unit = layers(layerNum) += v
    def addEdge(edge: LayeringEdge): Unit = edges ::= edge
    def build = Layering(layers.toList.map(layer => Layer(layer.toList)), edges)

  private def addEdges(
      cycleRemovalResult: CycleRemovalResult[V],
      layerNum: V => Int,
      layeringBuilder: LayeringBuilder,
      realVertices: Map[V, RealVertex]
  ): Unit =
    var revEdges = Utils.mkMultiset(cycleRemovalResult.reversedEdges)
    for graphEdge @ (from, to) <- cycleRemovalResult.dag.edges do
      val fromLayer = layerNum(from)
      val toLayer = layerNum(to)
      val dummies = (fromLayer + 1).to(toLayer - 1).map { layerNum =>
        val dummy = new DummyVertex
        layeringBuilder.addVertex(layerNum, dummy)
        dummy
      }
      val vertexChain = realVertices(from) +: dummies :+ realVertices(to)
      val reversed = revEdges.get(graphEdge) match
        case Some(count) =>
          if count == 1 then revEdges -= graphEdge
          else revEdges += graphEdge -> (count - 1)
          true
        case None => false
      for (v1, v2) <- vertexChain.zip(vertexChain.tail) do
        layeringBuilder.addEdge(new LayeringEdge(v1, v2, reversed))

  private def makeRealVertices(cycleRemovalResult: CycleRemovalResult[V]): Map[V, RealVertex] =
    cycleRemovalResult.dag.vertices.map { v =>
      val selfEdges = cycleRemovalResult.countSelfEdges(v)
      v -> new RealVertex(v, selfEdges)
    }.toMap

// ── LayerOrderingCalculator ─────────────────────────────────────────────────

object LayerOrderingCalculator:
  def reorder(layering: Layering): Layering =
    var previousLayerOpt: Option[Layer] = None
    val newLayers = layering.layers.map { currentLayer =>
      val updatedLayer = previousLayerOpt match
        case Some(previousLayer) => reorder(previousLayer, currentLayer, layering.edges)
        case None => currentLayer
      previousLayerOpt = Some(updatedLayer)
      updatedLayer
    }
    layering.copy(layers = newLayers)

  private def reorder(layer1: Layer, layer2: Layer, edges: List[LayeringEdge]): Layer =
    def barycenter(vertex: LayeringVertex): Double =
      val inVertices = edges.collect { case LayeringEdge(v1, `vertex`) => v1 }
      average(inVertices)(v => layer1.positionOf(v).toDouble)
    layer2.copy(vertices = layer2.vertices.sortBy(barycenter))

  private def average[T](items: Iterable[T])(f: T => Double): Double =
    items.map(f).sum / items.size

// ── LongestDistancesToSinkCalculator ────────────────────────────────────────

object LongestDistancesToSinkCalculator:
  def longestDistancesToSink[V](graph: Graph[V]): Map[V, Int] =
    var finalisedVertices: Set[V] = graph.sinks.toSet
    var distances: Map[V, Int] = graph.vertices.map(_ -> 0).toMap
    var boundary = finalisedVertices
    while boundary.nonEmpty do
      var newBoundary = Set[V]()
      for
        v2 <- boundary
        v1 <- graph.inVertices(v2)
      do
        val newDistance = math.max(distances(v1), distances(v2) + 1)
        distances += v1 -> newDistance
        if graph.outVertices(v1).forall(finalisedVertices) then
          finalisedVertices += v1
          newBoundary += v1
      boundary = newBoundary
    distances

// ── CrossingCalculator ──────────────────────────────────────────────────────

class CrossingCalculator(layer1: Layer, layer2: Layer, edges: List[LayeringEdge]):
  def crossingNumber(u: LayeringVertex, v: LayeringVertex): Int =
    if u == v then 0
    else
      var count = 0
      for
        LayeringEdge(w, u2) <- edges
        if u2 == u
        LayeringEdge(z, v2) <- edges
        if v2 == v
        if layer1.positionOf(z) < layer1.positionOf(w)
      do count += 1
      count

  def numberOfCrossings: Int =
    (for
      u <- layer2.vertices
      v <- layer2.vertices
      if layer2.positionOf(u) < layer2.positionOf(v)
    yield crossingNumber(u, v)).sum
