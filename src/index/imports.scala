package scalex.index

import scalex.*

import scala.collection.mutable

// ── Import line parsing ──────────────────────────────────────────────────────

/** Parse an import line into its package, imported names, and wildcard flag. Handles brace imports ("import pkg.{A, B
  * as C, _}") and simple imports.
  */
def parseImportTarget(imp: String): Option[(pkg: String, names: List[String], isWildcard: Boolean)] = {
  val trimmed = imp.trim.stripPrefix("import ")
  if (trimmed.isEmpty) None
  else {

    // Handle brace-enclosed imports: import pkg.{A, B, C as D, _}
    val braceStart = trimmed.indexOf('{')
    if (braceStart >= 0) {
      val pkg = trimmed.substring(0, braceStart).stripSuffix(".")
      val braceEnd = trimmed.indexOf('}', braceStart)
      val inner = if (braceEnd >= 0) trimmed.substring(braceStart + 1, braceEnd) else trimmed.substring(braceStart + 1)
      val parts = inner.split(',').map(_.trim).filter(_.nonEmpty)
      var isWildcard = false
      val names = mutable.ListBuffer.empty[String]
      parts.foreach { part =>
        if (part == "_" || part == "*") isWildcard = true
        else {
          // Handle "Foo as Bar" or "Foo => Bar" — we want the original name (Foo)
          val asIdx = part.indexOf(" as ")
          val arrowIdx = part.indexOf(" => ")
          val name =
            if (asIdx >= 0) part.substring(0, asIdx).trim
            else if (arrowIdx >= 0) part.substring(0, arrowIdx).trim
            else part.trim
          if (name.nonEmpty && name != "_" && name != "*") names += name
        }
      }
      Some((pkg, names.toList, isWildcard))
    } else {
      // Simple import: import pkg.Name or import pkg._ or import pkg.*
      val lastDot = trimmed.lastIndexOf('.')
      if (lastDot < 0) None
      else {
        val pkg = trimmed.substring(0, lastDot)
        val name = trimmed.substring(lastDot + 1)
        if (name == "_" || name == "*") Some((pkg, Nil, true))
        else Some((pkg, List(name), false))
      }
    }
  }
}

/** Package of a wildcard import line ("import pkg._" / "import pkg.*"), or None. */
def wildcardImportPkg(imp: String): Option[String] = {
  val trimmed = imp.trim.stripPrefix("import ")
  if (trimmed.endsWith("._") || trimmed.endsWith(".*")) Some(trimmed.dropRight(2)) else None
}
