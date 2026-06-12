package asciiGraph

// ── GraphLayout ─────────────────────────────────────────────────────────────

object GraphLayout:
  def renderGraph[V](graph: Graph[V], layoutPrefs: LayoutPrefs = LayoutPrefs()): String =
    val cycleRemovalResult = CycleRemover.removeCycles(graph)
    val layering = new LayeringCalculator[V].assignLayers(cycleRemovalResult)
    val reorderedLayering = LayerOrderingCalculator.reorder(layering)
    val layouter = new Layouter(layoutPrefs.vertical)
    var drawing = layouter.layout(reorderedLayering)
    if layoutPrefs.removeKinks then drawing = KinkRemover.removeKinks(drawing)
    if layoutPrefs.elevateEdges then drawing = EdgeElevator.elevateEdges(drawing)
    if layoutPrefs.compactify then drawing = RedundantRowRemover.removeRedundantRows(drawing)
    if !layoutPrefs.vertical then drawing = drawing.transpose
    Renderer.render(drawing, layoutPrefs)
