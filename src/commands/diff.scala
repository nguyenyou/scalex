package scalex.commands

import scalex.*
import scalex.index.*

import scala.collection.mutable
import java.nio.file.Files

def cmdDiff(args: List[String], ctx: CommandContext): CmdResult =
  requireArg(args, "Usage: scalex diff <git-ref> (e.g. scalex diff HEAD~1)") { ref =>
    val keep = pathPredicate(ctx.filters.noTests, ctx.filters.pathFilter, ctx.filters.excludePath, ctx.workspace)
    val changedFiles = runGitDiff(ctx.workspace, ref).filter(path => keep(ctx.workspace.resolve(path)))
    val added = mutable.ListBuffer.empty[DiffSymbol]
    val removed = mutable.ListBuffer.empty[DiffSymbol]
    val modified = mutable.ListBuffer.empty[(before: DiffSymbol, after: DiffSymbol)]

    changedFiles.foreach { relPath =>
      val currentPath = ctx.workspace.resolve(relPath)
      val currentSource = try Some(Files.readString(currentPath))
      catch { case _: Exception => None }
      val oldSource = gitShowFile(ctx.workspace, ref, relPath)

      val currentSyms = currentSource.map(s => extractSymbolsFromSource(s, relPath)).getOrElse(Nil)
      val oldSyms = oldSource.map(s => extractSymbolsFromSource(s, relPath)).getOrElse(Nil)

      def key(s: DiffSymbol): (owner: List[String], name: String, kind: SymbolKind) =
        (owner = s.owner, name = s.name, kind = s.kind)

      val currentByKey = currentSyms.groupBy(key)
      val oldByKey = oldSyms.groupBy(key).view.mapValues(syms => mutable.ListBuffer.from(syms)).toMap

      currentByKey.foreach { (identity, current) =>
        val remaining = oldByKey.getOrElse(identity, mutable.ListBuffer.empty[DiffSymbol])
        val changed = mutable.ListBuffer.empty[DiffSymbol]
        // Match unchanged overloads first so insertion/reordering does not steal their partners.
        current.foreach { after =>
          val exact = remaining.indexWhere(_.contentHash == after.contentHash)
          if (exact >= 0) {
            val before = remaining.remove(exact)
            if (before.packageName != after.packageName) modified += ((before = before, after = after))
          } else changed += after
        }
        changed.foreach { after =>
          if (remaining.isEmpty) added += after
          else {
            val sameSignature = remaining.indexWhere(_.signature == after.signature)
            val before = remaining.remove(if (sameSignature >= 0) sameSignature else 0)
            modified += ((before = before, after = after))
          }
        }
      }
      oldByKey.values.foreach(removed ++= _)
    }

    CmdResult.SymbolDiff(
      ref,
      changedFiles.size,
      added.toList.sortBy(s => (file = s.file, line = s.line, name = s.name)),
      removed.toList.sortBy(s => (file = s.file, line = s.line, name = s.name)),
      modified.toList.sortBy(s => (file = s.after.file, line = s.after.line, name = s.after.name))
    )
  }
