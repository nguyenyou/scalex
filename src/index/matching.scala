package scalex.index

import scalex.*

import scala.collection.mutable

// ── Ranked name matching ─────────────────────────────────────────────────────

/** Match-quality tiers for ranked name search, best first. */
enum MatchTier {
  case Exact, Prefix, Contains, ReverseContains, CamelCase
}

private[scalex] def isSegmentStart(name: String, i: Int): Boolean =
  i == 0 || name(i).isUpper || (i > 0 && name(i - 1) == '_')

/** True if every char of `query` (lowercase) appears in `name` walking camelCase/snake_case segment starts, e.g. "usl"
  * matches "UserServiceLive".
  */
def camelCaseMatch(query: String, name: String): Boolean =
  query.length >= 2 && {
    val qLower = query.toLowerCase
    val nLower = name.toLowerCase
    var qi = 0
    var ni = 0
    while (qi < qLower.length && ni < nLower.length)
      if (qLower(qi) == nLower(ni)) {
        qi += 1
        ni += 1
      } else {
        ni += 1
        while (ni < nLower.length && !isSegmentStart(name, ni)) ni += 1
      }
    qi == qLower.length
  }

/** Classify how `name` matches a query (`lowerQuery` must be pre-lowercased). One classifier behind symbol search, file
  * search, and suggestion ranking.
  */
def nameMatchTier(lowerQuery: String, name: String): Option[MatchTier] = {
  val n = name.toLowerCase
  if (n == lowerQuery) Some(MatchTier.Exact)
  else if (n.startsWith(lowerQuery)) Some(MatchTier.Prefix)
  else if (n.contains(lowerQuery)) Some(MatchTier.Contains)
  else if (lowerQuery.endsWith(n) && n.length >= 3 && n.length > lowerQuery.length / 2) Some(MatchTier.ReverseContains)
  else if (camelCaseMatch(lowerQuery, name)) Some(MatchTier.CamelCase)
  else None
}

/** Bucket items by their name-match tier against `lowerQuery`, preserving encounter order within each tier. Items that
  * match no tier are dropped. One bucketer behind symbol search and file search.
  */
private[scalex] def bucketByTier[A](items: List[A], lowerQuery: String)(nameOf: A => String): Map[MatchTier, List[A]] =
  items
    .flatMap(a => nameMatchTier(lowerQuery, nameOf(a)).map(t => (tier = t, item = a)))
    .groupMap(_.tier)(_.item)

/** Inverted index: group items under each (already-normalized) key produced by `keys`. */
private[scalex] def buildMultiIndex[A, K](items: List[A])(keys: A => IterableOnce[K]): Map[K, List[A]] = {
  val idx = mutable.HashMap.empty[K, mutable.ListBuffer[A]]
  items.foreach { item =>
    keys(item).iterator.foreach { k =>
      idx.getOrElseUpdate(k, mutable.ListBuffer.empty) += item
    }
  }
  idx.map((k, v) => k -> v.toList).toMap
}
