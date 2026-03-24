// ── callers command ─────────────────────────────────────────────────────────

def cmdCallers(args: List[String], ctx: SemCommandContext): SemCmdResult =
  args match
    case Nil => SemCmdResult.UsageError("Usage: callers <symbol> [--depth N]")
    case query :: _ =>
      val symbols = ctx.index.resolveSymbol(query)
      if symbols.isEmpty then
        return SemCmdResult.NotFound(s"No symbol found matching '$query'")

      val resolved = filterByKind(symbols, ctx.kindFilter)
      val afterKind = if resolved.nonEmpty then resolved else symbols
      // Apply --in scope filter
      val candidates = ctx.inScope match
        case Some(scope) =>
          val scopeLower = scope.toLowerCase
          val scopeFqnLower = scope.replace(".", "/").toLowerCase
          val scoped = afterKind.filter { s =>
            s.owner.toLowerCase.contains(scopeLower) ||
            s.fqn.toLowerCase.contains(scopeFqnLower) ||
            s.sourceUri.toLowerCase.contains(scopeLower)
          }
          if scoped.nonEmpty then scoped
          else
            System.err.println(s"Warning: --in '$scope' matched no candidates, falling back to unscoped resolution")
            afterKind
        case None => afterKind
      val maxDepth = ctx.depth.getOrElse(1)

      if maxDepth <= 1 then
        // Flat mode (default): show direct callers as a list
        val fqns = candidates.map(_.fqn).toSet
        val callerSymbols = fqns.toList.flatMap(fqn => findCallersTraitAware(fqn, ctx.index))
          .distinctBy(_.fqn)
          .filterNot(s => fqns.contains(s.fqn))
          .filterNot(s => ctx.smart && isInfraNoise(s))
          .filterNot(s => (ctx.noAccessors || ctx.smart) && isAccessor(s))
          .filterNot(s => ctx.smart && isMonadicCombinator(s))
          .filterNot(s => ctx.excludeTest && isTestSource(s.sourceUri))
        val filtered = filterByExcludePkg(filterByExclude(callerSymbols, ctx.excludePatterns), ctx.excludePkgPatterns)
        val limited = filtered.take(ctx.limit)
        val name = candidates.head.displayName

        if ctx.groupByFile then
          val grouped = limited.groupBy(_.sourceUri).toList.sortBy(-_._2.size)
          val lines = grouped.flatMap { (file, syms) =>
            val header = s"  $file (${syms.size})"
            header :: syms.map { s =>
              val props = s.propertyNames
              val propsStr = if props.isEmpty then "" else s" [${props.mkString(", ")}]"
              val memberOf = formatMemberOf(s)
              s"    ${s.kind.toString.toLowerCase} ${s.displayName}$propsStr$memberOf"
            }
          }
          val truncation = if filtered.size > limited.size then
            List(s"... and ${filtered.size - limited.size} more (use --limit 0 for all)")
          else Nil
          SemCmdResult.Tree(
            s"${filtered.size} callers of '$name' (grouped by file)",
            lines ++ truncation,
          )
        else
          SemCmdResult.SymbolList(
            s"${filtered.size} callers of '$name'",
            limited,
            filtered.size,
          )
      else
        // Transitive tree mode: recursive caller traversal (single root)
        val sym = resolveOne(query, ctx.index, ctx.kindFilter, ctx.inScope) match
          case None => return SemCmdResult.NotFound(s"No symbol found matching '$query'")
          case Some(s) => s
        val lines = scala.collection.mutable.ListBuffer.empty[String]
        val visited = scala.collection.mutable.Set.empty[String]
        val rootModule = if ctx.smart then Some(modulePrefix(sym.sourceUri)) else None

        def walk(fqn: String, indent: Int): Unit = {
          if indent > maxDepth || visited.contains(fqn) then return
          visited += fqn

          val callers = filterByExcludePkg(
            findCallersTraitAware(fqn, ctx.index)
              .filterNot(s => isTrivial(s.fqn))
              .filterNot(s => (ctx.noAccessors || ctx.smart) && isAccessor(s))
              .filterNot(s => ctx.smart && isInfraNoise(s))
              .filterNot(s => ctx.smart && isMonadicCombinator(s))
              .filterNot(s => ctx.excludeTest && isTestSource(s.sourceUri))
              .filterNot(s => ctx.excludePatterns.exists(p => s.fqn.contains(p) || s.sourceUri.contains(p))),
            ctx.excludePkgPatterns,
          )

          callers.foreach { caller =>
            if !visited.contains(caller.fqn) then
              val prefix = "  " * indent
              val loc = ctx.index.definitionRanges.get(caller.fqn) match
                case Some((file, range)) => s" ($file:${range.startLine + 1})"
                case None => ""
              lines += s"$prefix${caller.kind.toString.toLowerCase} ${caller.displayName}$loc"
              val sameModule = rootModule.forall(rm => caller.sourceUri.startsWith(rm))
              if sameModule then walk(caller.fqn, indent + 1)
              else visited += caller.fqn // mark cross-module leaves to prevent duplicates
          }
        }

        val rootLoc = ctx.index.definitionRanges.get(sym.fqn) match
          case Some((file, range)) => s" ($file:${range.startLine + 1})"
          case None => ""
        lines.prepend(s"${sym.kind.toString.toLowerCase} ${sym.displayName}$rootLoc")
        walk(sym.fqn, 1)

        SemCmdResult.FlowTree(s"Caller tree of '${sym.displayName}' (depth=$maxDepth)", lines.toList)

// ── findCallers helpers ───────────────────────────────────────────────────

/** Find callers of a symbol, including callers of overridden trait/abstract methods.
  * When an impl method overrides a trait method, call sites often reference the trait's
  * FQN, not the impl's. This unions callers of both to avoid missing results. */
def findCallersTraitAware(fqn: String, index: SemIndex): List[SemSymbol] =
  val directCallers = findCallers(fqn, index)
  val traitFqns = index.symbolByFqn.get(fqn).toList.flatMap(_.overriddenSymbols)
  if traitFqns.isEmpty then directCallers
  else
    val traitCallers = traitFqns.flatMap(findCallers(_, index))
    (directCallers ++ traitCallers).distinctBy(_.fqn)

/** Find all methods/fields that call the given symbol (direct callers only). */
def findCallers(fqn: String, index: SemIndex): List[SemSymbol] =
  val refs = index.occurrencesBySymbol.getOrElse(fqn, Nil)
    .filter(_.role == OccRole.Reference)

  val callerFqns = scala.collection.mutable.LinkedHashSet.empty[String]
  refs.foreach { ref =>
    findEnclosingSymbol(ref.file, ref.range.startLine, index) match
      case Some(enclosing) if enclosing != fqn =>
        callerFqns += enclosing
      case _ => ()
  }
  callerFqns.toList.flatMap(index.symbolByFqn.get)

/** Find the method/field whose body contains the given line, using next-sibling heuristic. */
private def findEnclosingSymbol(file: String, line: Int, index: SemIndex): Option[String] =
  // Get all definition occurrences in this file
  val fileOccs = index.occurrencesByFile.getOrElse(file, Nil)
    .filter(_.role == OccRole.Definition)

  // Find symbols that are methods/constructors whose definition is before this line
  // Use the simple heuristic: the closest definition before this line is the enclosing one
  val candidates = fileOccs
    .filter(o => o.range.startLine <= line)
    .flatMap { o =>
      index.symbolByFqn.get(o.symbol).map(s => (sym = s, occ = o))
    }
    .filter { (sym, _) =>
      sym.kind == SemKind.Method || sym.kind == SemKind.Constructor ||
      sym.kind == SemKind.Field
    }
    .sortBy(-_.occ.range.startLine) // closest definition first

  candidates.headOption.flatMap { (sym, occ) =>
    // Find the next sibling definition (same owner) to approximate body end
    val owner = sym.owner
    val siblingDefs = fileOccs
      .filter(o => o.role == OccRole.Definition && o.range.startLine > occ.range.startLine)
      .sortBy(_.range.startLine)
      .filter { o =>
        !o.symbol.startsWith("local") &&
        index.symbolByFqn.get(o.symbol).exists(s => s.owner == owner)
      }
    val bodyEndLine = siblingDefs.headOption.map(_.range.startLine).getOrElse(Int.MaxValue)
    if line < bodyEndLine then Some(sym.fqn) else None
  }
