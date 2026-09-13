package asciiGraph

// ── GraphLayout ─────────────────────────────────────────────────────────────

object GraphLayout {
  def renderGraph[V](graph: Graph[V], layoutPrefs: LayoutPrefs = LayoutPrefs()): String = {
    val cycleRemovalResult = CycleRemover.removeCycles(graph)
    val layering = LayeringCalculator[V].assignLayers(cycleRemovalResult)
    val reorderedLayering = LayerOrderingCalculator.reorder(layering)
    val layouter = Layouter(layoutPrefs.vertical)
    var drawing = layouter.layout(reorderedLayering)
    if (layoutPrefs.removeKinks) drawing = KinkRemover.removeKinks(drawing)
    if (layoutPrefs.elevateEdges) drawing = EdgeElevator.elevateEdges(drawing)
    if (layoutPrefs.compactify) drawing = RedundantRowRemover.removeRedundantRows(drawing)
    if (!layoutPrefs.vertical) drawing = drawing.transpose
    Renderer.render(drawing, layoutPrefs)
  }
}
