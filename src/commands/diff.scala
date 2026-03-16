import scala.collection.mutable
import java.nio.file.Files

def cmdDiff(args: List[String], ctx: CommandContext): CmdResult =
  args.headOption match
    case None => CmdResult.UsageError("Usage: scalex diff <git-ref> (e.g. scalex diff HEAD~1)")
    case Some(ref) =>
      val changedFiles = runGitDiff(ctx.workspace, ref)
      val added = mutable.ListBuffer.empty[DiffSymbol]
      val removed = mutable.ListBuffer.empty[DiffSymbol]
      val modified = mutable.ListBuffer.empty[(before: DiffSymbol, after: DiffSymbol)]

      changedFiles.take(ctx.limit * 5).foreach { relPath =>
        val currentPath = ctx.workspace.resolve(relPath)
        val currentSource = try Some(Files.readString(currentPath)) catch { case _: Exception => None }
        val oldSource = gitShowFile(ctx.workspace, ref, relPath)

        val currentSyms = currentSource.map(s => extractSymbolsFromSource(s, relPath)).getOrElse(Nil)
        val oldSyms = oldSource.map(s => extractSymbolsFromSource(s, relPath)).getOrElse(Nil)

        val currentByKey = currentSyms.map(s => (s.name, s.kind) -> s).toMap
        val oldByKey = oldSyms.map(s => (s.name, s.kind) -> s).toMap

        // Added: in current but not in old
        currentByKey.foreach { case (key, sym) =>
          if !oldByKey.contains(key) then added += sym
        }
        // Removed: in old but not in current
        oldByKey.foreach { case (key, sym) =>
          if !currentByKey.contains(key) then removed += sym
        }
        // Modified: in both but signature changed
        currentByKey.foreach { case (key, cSym) =>
          oldByKey.get(key).foreach { oSym =>
            if cSym.signature != oSym.signature || cSym.line != oSym.line then
              modified += ((before = oSym, after = cSym))
          }
        }
      }

      CmdResult.SymbolDiff(ref, changedFiles.size, added.toList, removed.toList, modified.toList)
