package scalex.commands

import scalex.*
import scalex.extraction.*

def cmdBody(args: List[String], ctx: CommandContext): CmdResult =
  requireArg(args, "Usage: scalex body <symbol> [--in <owner>]") { symbol =>
    // Find files containing the symbol
    val defs = filterSymbols(ctx.idx.findDefinition(symbol), ctx.copy(filters = ctx.filters.copy(kindFilter = None)))
    val ownerFiles = ctx.search.inOwner match {
      case Some(owner) =>
        filterSymbols(findTypeDefs(owner, ctx), ctx.copy(filters = ctx.filters.copy(kindFilter = None)))
          .map(_.file)
          .distinct
      case None => Nil
    }
    val filesToSearch = if (defs.nonEmpty) {
      // When --in is specified, also include the owner's files — the symbol may
      // be indexed in a different file but also exist as a nested def in the owner
      (defs.map(_.file).distinct ++ ownerFiles).distinct
    } else {
      // If not found directly, search owner files or all type files
      if (ownerFiles.nonEmpty) ownerFiles
      else
        filterSymbols(
          ctx.idx.symbols.filter(s => typeKinds.contains(s.kind)),
          ctx.copy(filters = ctx.filters.copy(kindFilter = None))
        )
          .map(_.file)
          .distinct
    }
    // Collect (file, body) pairs
    // For dotted --in owners like "Outer.Inner", use simple name for body extraction
    val effectiveOwner = ctx.search.inOwner.map(simpleNameOf)
    val blocks = filesToSearch.flatMap { f =>
      extractBody(f, symbol, effectiveOwner).map(b => (file = f, body = b))
    }
    // Fallback: if no results and symbol has a dot, split into Owner.member
    val (displayName, effectiveBlocks) =
      splitOwnerMember(symbol) match {
        case Some((ownerName, memberName)) if blocks.isEmpty && ctx.search.inOwner.isEmpty =>
          val dottedOwnerFiles = filterSymbols(findTypeDefs(ownerName, ctx), ctx)
            .map(_.file)
            .distinct
          val dottedBlocks = dottedOwnerFiles.flatMap { f =>
            extractBody(f, memberName, Some(ownerName)).map(b => (file = f, body = b))
          }
          if (dottedBlocks.nonEmpty) (displayName = memberName, effectiveBlocks = dottedBlocks)
          else (displayName = symbol, effectiveBlocks = blocks)
        case _ => (displayName = symbol, effectiveBlocks = blocks)
      }

    if (effectiveBlocks.isEmpty) {
      val msg = ctx.search.inOwner match {
        case Some(owner) => s"""No body found for "$symbol" in $owner"""
        case None        => s"""No body found for "$symbol""""
      }
      val hint = mkNotFoundWithSuggestions(symbol, ctx, "body")
      // When --in is specified, replace global suggestions with owner-scoped members
      val scopedHint = ctx.search.inOwner match {
        case Some(owner) =>
          val scoped = mkOwnerScopedSuggestions(symbol, owner, ctx)
          if (scoped.nonEmpty) hint.copy(suggestions = scoped) else hint
        case None => hint
      }
      CmdResult.NotFound(msg, scopedHint)
    } else
      CmdResult.SourceBlocks(displayName, effectiveBlocks, ctx.output.contextLines, ctx.members.showImports)
  }
