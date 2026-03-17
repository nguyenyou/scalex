import scala.collection.mutable
import java.nio.file.Path

def cmdMembers(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex members <Symbol>")
    case Some(symbol) =>
      val typeKinds = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object, SymbolKind.Enum)
      val defs = filterSymbols(ctx.idx.findDefinition(symbol).filter(s => typeKinds.contains(s.kind)), ctx)

      // Collect inherited members if --inherited is set
      def collectInherited(sym: SymbolInfo): List[(parentName: String, parentFile: Option[Path], parentPackage: String, members: List[MemberInfo])] = {
        if !ctx.inherited then return Nil
        val visited = mutable.HashSet.empty[String]
        visited += sym.name.toLowerCase
        val ownMembers = extractMembers(sym.file, sym.name).map(m => (m.name, m.kind)).toSet
        val result = mutable.ListBuffer.empty[(String, Option[Path], String, List[MemberInfo])]

        def walk(parentNames: List[String]): Unit = {
          parentNames.foreach { pName =>
            if !visited.contains(pName.toLowerCase) then {
              visited += pName.toLowerCase
              val parentDefs = ctx.idx.findDefinition(pName).filter(s => typeKinds.contains(s.kind))
              parentDefs.headOption.foreach { pd =>
                val parentMembers = extractMembers(pd.file, pd.name)
                val filtered = parentMembers.filterNot(m => ownMembers.contains((m.name, m.kind)))
                if filtered.nonEmpty then result += ((pd.name, Some(pd.file), pd.packageName, filtered))
                walk(pd.parents)
              }
            }
          }
        }

        walk(sym.parents)
        result.toList
      }

      if defs.isEmpty then
        CmdResult.NotFound(
          s"""No class/trait/object/enum "$symbol" found""",
          mkNotFoundWithSuggestions(symbol, ctx, "members"))
      else
        val sections = defs.map { s =>
          val members = extractMembers(s.file, symbol)
          val inherited = collectInherited(s)
          // Companion lookup (same pattern as explain)
          val companionKinds: Set[SymbolKind] = s.kind match
            case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Enum => Set(SymbolKind.Object)
            case SymbolKind.Object => Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Enum)
            case _ => Set.empty
          val companion: Option[(sym: SymbolInfo, members: List[MemberInfo])] =
            if companionKinds.isEmpty then None
            else
              val allDefs = ctx.idx.findDefinition(symbol).filter(d => typeKinds.contains(d.kind))
              allDefs.find(d => companionKinds.contains(d.kind) && d.packageName == s.packageName && d.file == s.file)
                .map { compSym =>
                  val compMembers = extractMembers(compSym.file, symbol)
                  (sym = compSym, members = compMembers)
                }
          MemberSectionData(
            file = s.file,
            ownerKind = s.kind,
            packageName = s.packageName,
            line = s.line,
            ownMembers = members,
            inherited = inherited,
            companion = companion
          )
        }
        CmdResult.MemberSections(symbol, sections)
