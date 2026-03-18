package asciiGraph

// ── RendererPrefs ───────────────────────────────────────────────────────────

trait RendererPrefs:
  def unicode: Boolean
  def doubleVertices: Boolean
  def rounded: Boolean
  def explicitAsciiBends: Boolean

// ── LayoutPrefs ─────────────────────────────────────────────────────────────

trait LayoutPrefs extends RendererPrefs:
  def removeKinks: Boolean
  def compactify: Boolean
  def elevateEdges: Boolean
  def vertical: Boolean

// ── LayoutPrefsImpl ─────────────────────────────────────────────────────────

case class LayoutPrefsImpl(
    removeKinks: Boolean = true,
    compactify: Boolean = true,
    elevateEdges: Boolean = true,
    vertical: Boolean = true,
    unicode: Boolean = true,
    doubleVertices: Boolean = false,
    rounded: Boolean = false,
    explicitAsciiBends: Boolean = false
) extends LayoutPrefs
