// ── api command ─────────────────────────────────────────────────────────────

/** Access bitmask values from semanticdb.proto */
private val PRIVATE_ACCESS       = 0x1  // PrivateAccess tag
private val PRIVATE_THIS_ACCESS  = 0x2
private val PRIVATE_WITHIN       = 0x3

def cmdApi(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: api <package>")
    case query :: _ =>
      val lower = query.toLowerCase
      val matchingPkgs = ctx.index.symbolsByPackage.keys
        .filter(_.toLowerCase.contains(lower))
        .toList

      val symbols = matchingPkgs
        .flatMap(pkg => ctx.index.symbolsByPackage.getOrElse(pkg, Nil))
        .filter { s =>
          // Include non-private, non-local, non-parameter symbols
          s.kind != SemKind.Parameter && s.kind != SemKind.TypeParam &&
          s.kind != SemKind.Local && s.kind != SemKind.Constructor &&
          !s.fqn.startsWith("local")
        }

      val filtered = filterByKind(symbols, ctx.kindFilter)
      val sorted = filtered.sortBy(s => (kindOrder(s.kind), s.displayName))
      val limited = sorted.take(ctx.limit)
      SemCmdResult.SymbolList(s"${filtered.size} API symbols in packages matching '$query'", limited, filtered.size)
