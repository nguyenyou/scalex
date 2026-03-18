package asciiGraph

import Utils.*

// ── VertexRenderingStrategy ─────────────────────────────────────────────────

trait VertexRenderingStrategy[-V]:
  def getPreferredSize(v: V): Dimension
  def getText(v: V, allocatedSize: Dimension): List[String]

// ── ToStringVertexRenderingStrategy ─────────────────────────────────────────

object ToStringVertexRenderingStrategy extends VertexRenderingStrategy[Any]:
  def getPreferredSize(v: Any): Dimension =
    val lines = splitLines(v.toString)
    Dimension(lines.size, if lines.isEmpty then 0 else lines.map(_.size).max)

  def getText(v: Any, allocatedSize: Dimension): List[String] =
    val unpaddedLines = splitLines(v.toString).take(allocatedSize.height).map(line => centerLine(allocatedSize, line))
    val verticalDiscrepancy = Math.max(0, allocatedSize.height - unpaddedLines.size)
    val verticalPadding = List.fill(verticalDiscrepancy / 2)("")
    verticalPadding ++ unpaddedLines ++ verticalPadding

  private def splitLines(s: String): List[String] =
    s.split("(\r)?\n").toList match
      case Nil | List("") => Nil
      case xs => xs

  private def centerLine(allocatedSize: Dimension, line: String): String =
    val discrepancy = allocatedSize.width - line.size
    val padding = " " * (discrepancy / 2)
    padding + line

// ── EdgeInfo ────────────────────────────────────────────────────────────────

case class EdgeInfo(
    startVertex: LayeringVertex,
    finishVertex: LayeringVertex,
    startPort: Point,
    finishPort: Point,
    reversed: Boolean
):
  def startColumn = startPort.column
  def finishColumn = finishPort.column
  def requiresBend = !isStraight
  def isStraight = startColumn == finishColumn
  def withFinishColumn(column: Int) = copy(finishPort = finishPort.withColumn(column))

// ── VertexInfo ──────────────────────────────────────────────────────────────

case class VertexInfo(
    boxRegion: Region,
    greaterRegion: Region,
    inEdgeToPortMap: Map[LayeringEdge, Point],
    outEdgeToPortMap: Map[LayeringEdge, Point],
    selfInPorts: List[Point],
    selfOutPorts: List[Point]
) extends Translatable[VertexInfo]:

  def contentRegion: Region =
    boxRegion.expandRight(-1).expandLeft(-1).expandDown(-1).expandUp(-1)

  def translate(down: Int = 0, right: Int = 0): VertexInfo =
    VertexInfo(
      boxRegion.translate(down, right),
      greaterRegion.translate(down, right),
      transformValues(inEdgeToPortMap)(_.translate(down, right)),
      transformValues(outEdgeToPortMap)(_.translate(down, right)),
      selfInPorts.map(_.translate(down, right)),
      selfOutPorts.map(_.translate(down, right))
    )

  def setLeft(column: Int): VertexInfo =
    translate(right = column - boxRegion.leftColumn)

// ── LayerInfo ───────────────────────────────────────────────────────────────

case class LayerInfo(vertexInfos: Map[LayeringVertex, VertexInfo])
    extends Translatable[LayerInfo]:

  def vertexInfo(v: LayeringVertex): Option[VertexInfo] = vertexInfos.get(v)
  def isEmpty = vertexInfos.isEmpty

  def maxRow = vertexInfos.values.map(_.greaterRegion.bottomRow).fold(0)(_ max _)
  def maxColumn = vertexInfos.values.map(_.greaterRegion.rightColumn).fold(0)(_ max _)

  private def getSelfEdgeBuffer(vertexInfo: VertexInfo) =
    if vertexInfo.selfInPorts.size > 0 then vertexInfo.selfInPorts.size + 1 else 0

  def topSelfEdgeBuffer: Int = vertexInfos.values.map(getSelfEdgeBuffer).fold(0)(_ max _)

  def translate(down: Int = 0, right: Int = 0) =
    copy(vertexInfos = transformValues(vertexInfos)(_.translate(down, right)))

  def realVertexInfos: List[(RealVertex, VertexInfo)] =
    vertexInfos.toList.collect { case (vertex: RealVertex, info) => (vertex, info) }

// ── EdgeBendCalculator ──────────────────────────────────────────────────────

class EdgeBendCalculator(edgeInfos: List[EdgeInfo], edgeZoneTopRow: Int, selfEdgeBuffer: Int):
  private val edgeRows: Map[EdgeInfo, Int] = orderEdgeBends(edgeInfos)
  require(edgeInfos.forall(edge => edge.isStraight || edgeRows.contains(edge)))

  private def bendRow(rowIndex: Int) = edgeZoneTopRow + rowIndex * 1 + 1

  val edgeZoneBottomRow =
    (if edgeInfos.isEmpty then -1
     else if edgeRows.isEmpty then edgeZoneTopRow + 2
     else bendRow(edgeRows.values.max) + 2) + selfEdgeBuffer

  def bendRow(edgeInfo: EdgeInfo): Int = bendRow(edgeRows(edgeInfo))

  private def orderEdgeBends(edgeInfos: List[EdgeInfo]): Map[EdgeInfo, Int] =
    def edgeRank(edgeInfo: EdgeInfo): Int =
      val startColumn = edgeInfo.startColumn
      val finishColumn = edgeInfo.finishColumn
      signum(startColumn - finishColumn) * finishColumn
    val orderedEdges = edgeInfos.filter(_.requiresBend).sortBy(edgeRank)
    val edgeToRowMap: Map[EdgeInfo, Int] = orderedEdges.zipWithIndex.toMap
    reorderEdgesWithSameStartAndEndColumns(edgeToRowMap)

  private def reorderEdgesWithSameStartAndEndColumns(edgeToRowMap: Map[EdgeInfo, Int]): Map[EdgeInfo, Int] =
    var updatedEdgeToRowMap = edgeToRowMap
    var continue = true
    var swappedEdges = Set[(EdgeInfo, EdgeInfo)]()
    while continue do
      continue = false
      for
        edgeInfo1 @ EdgeInfo(_, _, start1, finish1, _) <- edgeToRowMap.keys
        edgeInfo2 @ EdgeInfo(_, _, start2, finish2, _) <- edgeToRowMap.keys
        if edgeInfo1 != edgeInfo2
        if start1.column == finish2.column
        if start2.column != finish1.column
        row1 = updatedEdgeToRowMap(edgeInfo1)
        row2 = updatedEdgeToRowMap(edgeInfo2)
        if row1 > row2
        if !swappedEdges.contains((edgeInfo1, edgeInfo2))
      do
        updatedEdgeToRowMap += edgeInfo1 -> row2
        updatedEdgeToRowMap += edgeInfo2 -> row1
        swappedEdges += ((edgeInfo1, edgeInfo2))
        continue = true
    updatedEdgeToRowMap

// ── PortNudger ──────────────────────────────────────────────────────────────

object PortNudger:
  def nudge(layering: Layering, layerInfos: Map[Layer, LayerInfo]): Map[Layer, LayerInfo] =
    var updatedLayerInfos = layerInfos
    for (previousLayerOpt, currentLayer) <- withPrevious(layering.layers) do
      val previousLayerInfoOpt = previousLayerOpt.map(updatedLayerInfos)
      val currentLayerInfo = layerInfos(currentLayer)
      val updatedLayerInfo = nudgeLayer(previousLayerInfoOpt, currentLayerInfo)
      updatedLayerInfos += currentLayer -> updatedLayerInfo
    updatedLayerInfos

  private def getOutEdgeColumns(previousLayerInfoOpt: Option[LayerInfo]): List[Int] =
    for
      previousLayerInfo <- previousLayerInfoOpt.toList
      vertexInfo <- previousLayerInfo.vertexInfos.values
      outPort <- vertexInfo.outEdgeToPortMap.values
    yield outPort.column

  private def nudgeLayer(previousLayerInfoOpt: Option[LayerInfo], currentLayerInfo: LayerInfo): LayerInfo =
    val previousEdgeColumns: Set[Int] = getOutEdgeColumns(previousLayerInfoOpt).toSet
    val newVertexInfos = currentLayerInfo.vertexInfos.map { case (vertex, vertexInfo) =>
      vertex -> nudgeVertexInfo(vertex, vertexInfo, previousLayerInfoOpt, previousEdgeColumns)
    }
    currentLayerInfo.copy(vertexInfos = newVertexInfos)

  private def nudgeVertexInfo(
      vertex: LayeringVertex,
      vertexInfo: VertexInfo,
      previousLayerInfoOpt: Option[LayerInfo],
      previousEdgeColumns: Set[Int]
  ): VertexInfo =
    def shouldNudge(edge: LayeringEdge, port: Point): Boolean =
      previousEdgeColumns.contains(port.column) && !isStraight(edge, vertexInfo, previousLayerInfoOpt)

    var nudgedColumns: Set[Int] = Set()

    val newInEdgeToPortMap = vertexInfo.inEdgeToPortMap.map {
      case (edge, port) if shouldNudge(edge, port) =>
        nudgedColumns += port.column
        edge -> port.right
      case pair => pair
    }

    val newOutEdgeToPortMap = vertex match
      case _: DummyVertex =>
        vertexInfo.outEdgeToPortMap.map {
          case (edge, port) if nudgedColumns.contains(port.column) => edge -> port.right
          case pair => pair
        }
      case _: RealVertex => vertexInfo.outEdgeToPortMap

    vertexInfo.copy(inEdgeToPortMap = newInEdgeToPortMap, outEdgeToPortMap = newOutEdgeToPortMap)

  private def isStraight(edge: LayeringEdge, vertexInfo: VertexInfo, previousLayerInfoOpt: Option[LayerInfo]) =
    val column1: Option[Int] =
      for
        previousLayerInfo <- previousLayerInfoOpt
        previousVertexInfo <- previousLayerInfo.vertexInfo(edge.startVertex)
        outPort = previousVertexInfo.outEdgeToPortMap(edge)
      yield outPort.column
    val column2: Option[Int] = Some(vertexInfo.inEdgeToPortMap(edge).column)
    column1 == column2

// ── Layouter ────────────────────────────────────────────────────────────────

object Layouter:
  private val MINIMUM_VERTEX_HEIGHT = 3

class Layouter(
    vertexRenderingStrategy: VertexRenderingStrategy[?],
    vertical: Boolean = true
):
  import Layouter.*

  case class LayoutState(
      previousLayerInfo: LayerInfo,
      incompleteEdges: Map[DummyVertex, List[Point]],
      drawingElements: List[DrawingElement]
  ):
    def mergeLayerResult(result: LayerLayoutResult): LayoutState =
      val LayerLayoutResult(elements, updatedLayerInfo, updatedIncompletedEdges) = result
      LayoutState(updatedLayerInfo, updatedIncompletedEdges, drawingElements ++ elements)

  def layout(layering: Layering): Drawing =
    val layerInfos: Map[Layer, LayerInfo] = calculateLayerInfos(layering)
    var layoutState = LayoutState(LayerInfo(Map()), Map(), Nil)
    for layer <- layering.layers do
      val layerResult = layoutLayer(
        layoutState.previousLayerInfo, layerInfos(layer),
        layering.edges, layoutState.incompleteEdges
      )
      layoutState = layoutState.mergeLayerResult(layerResult)
    Drawing(layoutState.drawingElements)

  private def calculateLayerInfos(layering: Layering): Map[Layer, LayerInfo] =
    var layerInfos: Map[Layer, LayerInfo] = Map()
    for (previousLayerOpt, currentLayer, nextLayerOpt) <- withPreviousAndNext(layering.layers) do
      val layerInfo = calculateLayerInfo(currentLayer, layering.edges, previousLayerOpt, nextLayerOpt)
      layerInfos += currentLayer -> layerInfo
    PortNudger.nudge(layering, spaceVertices(layerInfos))

  private def calculateLayerInfo(
      layer: Layer,
      edges: List[LayeringEdge],
      previousLayerOpt: Option[Layer],
      nextLayerOpt: Option[Layer]
  ): LayerInfo =
    val inEdges = previousLayerOpt.map { previousLayer =>
      edges.sortBy { case LayeringEdge(v1, _) => previousLayer.vertices.indexOf(v1) }
    }.getOrElse(Nil)
    val outEdges = nextLayerOpt.map { nextLayer =>
      edges.sortBy { case LayeringEdge(_, v2) => nextLayer.vertices.indexOf(v2) }
    }.getOrElse(Nil)
    def getInEdges(vertex: LayeringVertex) = inEdges.collect { case e @ LayeringEdge(v1, `vertex`) => e }
    def getOutEdges(vertex: LayeringVertex) = outEdges.collect { case e @ LayeringEdge(`vertex`, v2) => e }
    def getDimension(vertex: LayeringVertex): Dimension =
      vertex match
        case v: RealVertex => calculateVertexDimension(v, getInEdges(vertex).size, getOutEdges(vertex).size)
        case _: DummyVertex => Dimension(height = 1, width = 1)
    val dimensions: Map[LayeringVertex, Dimension] = makeMap(layer.vertices, getDimension)
    val regions: Map[LayeringVertex, (Region, Region)] = calculateVertexRegions(layer, dimensions)
    def buildVertexInfo(v: LayeringVertex) =
      val (boxRegion, greaterRegion) = regions(v)
      makeVertexInfo(v, boxRegion, greaterRegion, getInEdges(v), getOutEdges(v))
    LayerInfo(makeMap(layer.vertices, buildVertexInfo))

  private def makeVertexInfo(
      vertex: LayeringVertex, boxRegion: Region, greaterRegion: Region,
      inEdges: List[LayeringEdge], outEdges: List[LayeringEdge]
  ): VertexInfo =
    vertex match
      case realVertex: RealVertex =>
        makeRealVertexInfo(realVertex, boxRegion, greaterRegion, inEdges, outEdges)
      case dummyVertex: DummyVertex =>
        makeDummyVertexInfo(dummyVertex, boxRegion, greaterRegion, inEdges, outEdges)

  private def makeRealVertexInfo(
      vertex: RealVertex, boxRegion: Region, greaterRegion: Region,
      inEdges: List[LayeringEdge], outEdges: List[LayeringEdge]
  ): VertexInfo =
    val inDegree = inEdges.size + vertex.selfEdges
    val inPorts: List[Point] = portOffsets(inDegree, boxRegion.width).map(boxRegion.topLeft.right)
    val inEdgeToPortMap = inEdges.zip(inPorts).toMap
    val selfInPorts = inPorts.drop(inEdges.size)
    val outDegree = outEdges.size + vertex.selfEdges
    val outPorts = portOffsets(outDegree, boxRegion.width).map(boxRegion.bottomLeft.right)
    val outEdgeToPortMap = outEdges.zip(outPorts).toMap
    val selfOutPorts = outPorts.drop(outEdges.size)
    VertexInfo(boxRegion, greaterRegion, inEdgeToPortMap, outEdgeToPortMap, selfInPorts, selfOutPorts)

  private def makeDummyVertexInfo(
      vertex: DummyVertex, boxRegion: Region, greaterRegion: Region,
      inEdges: List[LayeringEdge], outEdges: List[LayeringEdge]
  ): VertexInfo =
    val inVertex = inEdges.head
    val outVertex = outEdges.head
    val port = boxRegion.topLeft
    val inEdgeToPortMap = Map(inVertex -> port)
    val outEdgeToPortMap = Map(outVertex -> port)
    VertexInfo(boxRegion, greaterRegion, inEdgeToPortMap, outEdgeToPortMap, Nil, Nil)

  private def portOffsets(portCount: Int, vertexWidth: Int): List[Int] =
    val factor = vertexWidth / (portCount + 1)
    val centraliser = (vertexWidth - factor * (portCount + 1)) / 2
    0.until(portCount).toList.map(i => (i + 1) * factor + centraliser)

  private def calculateVertexDimension(v: RealVertex, inDegree: Int, outDegree: Int) =
    val selfEdges = v.selfEdges
    def requiredWidth(degree: Int) =
      if vertical then (degree + selfEdges) * 2 + 1 + 2
      else (degree + selfEdges) * 2 + 1 + 2
    val requiredInputWidth = requiredWidth(inDegree)
    val requiredOutputWidth = requiredWidth(outDegree)
    val Dimension(preferredHeight, preferredWidth) = getPreferredSize(vertexRenderingStrategy, v)
    val width = math.max(math.max(requiredInputWidth, requiredOutputWidth), preferredWidth + 2)
    val height = math.max(MINIMUM_VERTEX_HEIGHT, preferredHeight + 2)
    Dimension(height = height, width = width)

  private def calculateVertexRegions(
      layer: Layer, dimensions: Map[LayeringVertex, Dimension]
  ): Map[LayeringVertex, (Region, Region)] =
    var regions: Map[LayeringVertex, (Region, Region)] = Map()
    var nextVertexTopLeft = Point(0, 0)
    for vertex <- layer.vertices do
      val boxRegion = Region(nextVertexTopLeft, dimensions(vertex))
      val selfEdgesSpacing = vertex match
        case realVertex: RealVertex if realVertex.selfEdges > 0 => realVertex.selfEdges * 2
        case _ => 0
      val greaterRegion = boxRegion.expandRight(selfEdgesSpacing).expandUp(selfEdgesSpacing).expandDown(selfEdgesSpacing)
      regions += vertex -> (boxRegion, greaterRegion)
      nextVertexTopLeft = boxRegion.topRight.right(selfEdgesSpacing + 2)
    regions

  private def calculateDiagramWidth(layerInfos: Map[Layer, LayerInfo]) =
    def vertexWidth(vertexInfo: VertexInfo) = vertexInfo.greaterRegion.width
    def layerWidth(layerInfo: LayerInfo) =
      val vertexInfos = layerInfo.vertexInfos.values
      val spacing = vertexInfos.size
      vertexInfos.map(vertexWidth).sum + spacing - 1
    layerInfos.values.map(layerWidth).fold(0)(_ max _)

  private def spaceVertices(layerInfos: Map[Layer, LayerInfo]): Map[Layer, LayerInfo] =
    val diagramWidth = calculateDiagramWidth(layerInfos)
    layerInfos.map { case (layer, info) => layer -> spaceVertices(layer, info, diagramWidth) }

  private def spaceVertices(layer: Layer, layerVertexInfos: LayerInfo, diagramWidth: Int): LayerInfo =
    val excessSpace = diagramWidth - layerVertexInfos.maxColumn
    val horizontalSpacing = math.max(excessSpace / (layerVertexInfos.vertexInfos.size + 1), 1)
    val layerHeight = layerVertexInfos.vertexInfos.values.map(_.boxRegion.height).max
    var leftColumn = horizontalSpacing
    val newVertexInfos =
      for
        v <- layer.vertices
        vertexInfo <- layerVertexInfos.vertexInfo(v)
      yield
        val oldLeftColumn = leftColumn
        leftColumn += vertexInfo.greaterRegion.width
        leftColumn += horizontalSpacing
        val verticalCenteringOffset = (layerHeight - vertexInfo.boxRegion.height) / 2
        v -> vertexInfo.setLeft(oldLeftColumn).down(verticalCenteringOffset)
    LayerInfo(newVertexInfos.toMap)

  case class LayerLayoutResult(
      drawingElements: List[DrawingElement],
      layerInfo: LayerInfo,
      updatedIncompletedEdges: Map[DummyVertex, List[Point]]
  )

  private def layoutLayer(
      previousLayerInfo: LayerInfo,
      currentLayerInfo: LayerInfo,
      edges: List[LayeringEdge],
      incompleteEdges: Map[DummyVertex, List[Point]]
  ): LayerLayoutResult =
    val edgeInfos: List[EdgeInfo] = makeEdgeInfos(edges, previousLayerInfo, currentLayerInfo)
    val edgeZoneTopRow = if previousLayerInfo.isEmpty then -1 else previousLayerInfo.maxRow + 1
    val edgeBendCalculator = new EdgeBendCalculator(edgeInfos, edgeZoneTopRow, currentLayerInfo.topSelfEdgeBuffer)
    val edgeInfoToPoints: Map[EdgeInfo, List[Point]] = makeMap(
      edgeInfos, edgeInfo => getEdgePoints(edgeInfo, edgeBendCalculator, incompleteEdges)
    )
    val updatedIncompleteEdges: Map[DummyVertex, List[Point]] =
      for case (EdgeInfo(_, finishVertex: DummyVertex, _, _, _), points) <- edgeInfoToPoints
      yield finishVertex -> points
    val updatedLayerInfo = currentLayerInfo.down(edgeBendCalculator.edgeZoneBottomRow + 1)
    val vertexElements = makeVertexElements(updatedLayerInfo)
    val edgeElements = makeEdgeElements(edgeInfoToPoints)
    val selfEdgeElements = makeSelfEdgeElements(updatedLayerInfo)
    LayerLayoutResult(vertexElements ++ edgeElements ++ selfEdgeElements, updatedLayerInfo, updatedIncompleteEdges)

  private def makeEdgeInfos(
      edges: List[LayeringEdge], previousLayerInfo: LayerInfo, currentLayerInfo: LayerInfo
  ): List[EdgeInfo] =
    for
      edge @ LayeringEdge(v1, v2) <- edges
      previousVertexInfo <- previousLayerInfo.vertexInfo(v1)
      currentVertexInfo <- currentLayerInfo.vertexInfo(v2)
      start = previousVertexInfo.outEdgeToPortMap(edge).down
      finish = currentVertexInfo.inEdgeToPortMap(edge).up
    yield EdgeInfo(v1, v2, start, finish, edge.reversed)

  private def getEdgePoints(
      edgeInfo: EdgeInfo,
      edgeBendCalculator: EdgeBendCalculator,
      incompleteEdges: Map[DummyVertex, List[Point]]
  ): List[Point] =
    val EdgeInfo(startVertex, _, start, finish, _) = edgeInfo
    val trueFinish = finish.translate(down = edgeBendCalculator.edgeZoneBottomRow + 1)
    val priorPoints: List[Point] = startVertex match
      case dv: DummyVertex => incompleteEdges(dv)
      case _: RealVertex => List(start)
    val lastPriorPoint = priorPoints.last
    val edgePoints =
      if lastPriorPoint.column == trueFinish.column then priorPoints :+ trueFinish
      else
        require(edgeInfo.requiresBend, edgeInfo.toString + ", " + priorPoints)
        val bendRow = edgeBendCalculator.bendRow(edgeInfo)
        priorPoints ++ List(lastPriorPoint.withRow(bendRow), trueFinish.withRow(bendRow), trueFinish)
    Point.removeRedundantPoints(edgePoints)

  private def makeEdgeElements(edgeInfoToPoints: Map[EdgeInfo, List[Point]]): List[EdgeDrawingElement] =
    for
      case (EdgeInfo(_, finishVertex: RealVertex, _, _, reversed), points) <- edgeInfoToPoints.toList
    yield EdgeDrawingElement(points, reversed, !reversed)

  private def makeSelfEdgeElements(layerInfo: LayerInfo): List[EdgeDrawingElement] =
    layerInfo.vertexInfos.collect { case (realVertex: RealVertex, vertexInfo) =>
      vertexInfo.selfOutPorts.zip(vertexInfo.selfInPorts).reverse.zipWithIndex.map {
        case ((out, in), i) => makeSelfEdgeElement(vertexInfo, out, in, i)
      }
    }.toList.flatten

  private def makeSelfEdgeElement(
      vertexInfo: VertexInfo, outPort: Point, inPort: Point, selfEdgeIndex: Int
  ): EdgeDrawingElement =
    val boxRightEdge = vertexInfo.boxRegion.rightColumn
    val p1 = outPort.down(1)
    val p2 = p1.down(selfEdgeIndex + 1)
    val p3 = p2.right(boxRightEdge - p2.column + selfEdgeIndex * 2 + 2)
    val p4 = p3.up(vertexInfo.boxRegion.height + 2 * (selfEdgeIndex + 1) + 1)
    val p5 = p4.left(p4.column - inPort.column)
    val p6 = inPort.up(1)
    EdgeDrawingElement(List(p1, p2, p3, p4, p5, p6), hasArrow1 = false, hasArrow2 = true)

  private def makeVertexElements(layerInfo: LayerInfo): List[VertexDrawingElement] =
    layerInfo.realVertexInfos.map { case (realVertex, info) =>
      val text = getText(vertexRenderingStrategy, realVertex, info.contentRegion.dimension)
      VertexDrawingElement(info.boxRegion, text)
    }

  private def getPreferredSize[V](vertexRenderingStrategy: VertexRenderingStrategy[V], realVertex: RealVertex): Dimension =
    val preferredSize = vertexRenderingStrategy.getPreferredSize(realVertex.contents.asInstanceOf[V])
    if vertical then preferredSize else preferredSize.transpose

  private def getText[V](
      vertexRenderingStrategy: VertexRenderingStrategy[V],
      realVertex: RealVertex,
      preferredSize: Dimension
  ) =
    val actualPreferredSize = if vertical then preferredSize else preferredSize.transpose
    vertexRenderingStrategy.getText(realVertex.contents.asInstanceOf[V], actualPreferredSize)
