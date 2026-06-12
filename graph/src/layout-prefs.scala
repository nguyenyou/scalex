package asciiGraph

// ── LayoutPrefs ─────────────────────────────────────────────────────────────

case class LayoutPrefs(
    removeKinks: Boolean = true,
    compactify: Boolean = true,
    elevateEdges: Boolean = true,
    vertical: Boolean = true,
    unicode: Boolean = true,
    doubleVertices: Boolean = false,
    rounded: Boolean = false,
    explicitAsciiBends: Boolean = false
)
