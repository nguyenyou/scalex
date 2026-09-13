package scalex.extraction

import scalex.*

import java.nio.file.Path
import com.google.common.hash.BloomFilter

// ── Symbol extraction + bloom filter ────────────────────────────────────────

def extractSymbols(file: Path): (
    symbols: List[SymbolInfo],
    bloom: Option[BloomFilter[CharSequence]],
    imports: List[String],
    aliases: Map[String, String],
    parseFailed: Boolean
) =
  if (isJavaFile(file)) extractJavaSymbols(file)
  else extractScalaSymbols(file)

def extractMembers(file: Path, symbolName: String, filterKind: Option[SymbolKind] = None): List[MemberInfo] =
  if (isJavaFile(file)) extractJavaMembers(file, symbolName)
  else extractScalaMemberTree(file, symbolName, filterKind).map(_.member)

/** Members with their body line spans, for span-scoped grep. Excludes constructor params and abstract val/type
  * declarations (no body to grep). Java not supported.
  */
def extractMembersWithSpans(
    file: Path,
    symbolName: String,
    filterKind: Option[SymbolKind] = None
): List[(member: MemberInfo, startLine: Int, endLine: Int)] =
  if (isJavaFile(file)) Nil
  else
    extractScalaMemberTree(file, symbolName, filterKind).collect {
      case em if em.origin == MemberOrigin.Definition || em.member.kind == SymbolKind.Def =>
        (member = em.member, startLine = em.startLine, endLine = em.endLine)
    }

// ── Body extraction ─────────────────────────────────────────────────────────

def extractBody(file: Path, symbolName: String, ownerName: Option[String]): List[BodyInfo] =
  if (isJavaFile(file)) extractJavaBody(file, symbolName, ownerName)
  else extractScalaBody(file, symbolName, ownerName)
