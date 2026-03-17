def cmdEntrypoints(args: List[String], ctx: CommandContext): CmdResult =
  import EntrypointCategory.*
  val seen = scala.collection.mutable.HashSet.empty[(name: String, file: String, line: Int)]
  val results = scala.collection.mutable.ListBuffer.empty[EntrypointInfo]

  def addIfNew(sym: SymbolInfo, cat: EntrypointCategory, enclosing: Option[String] = None): Unit = {
    val key = (name = sym.name, file = sym.file.toString, line = sym.line)
    if !seen.contains(key) then {
      seen += key
      results += EntrypointInfo(sym, cat, enclosing)
    }
  }

  // 1. @main annotated
  filterSymbols(ctx.idx.findAnnotated("main"), ctx).foreach(s => addIfNew(s, MainAnnotation))

  // 2. extends App
  filterSymbols(ctx.idx.findImplementations("App"), ctx).foreach(s => addIfNew(s, ExtendsApp))

  // 3. def main(...) inside objects
  val objectSymbols = filterSymbols(ctx.idx.symbols.filter(_.kind == SymbolKind.Object), ctx)
  objectSymbols.foreach { obj =>
    val members = extractMembers(obj.file, obj.name)
    if members.exists(m => m.name == "main" && m.kind == SymbolKind.Def) then
      addIfNew(obj, MainMethod, Some(obj.name))
  }

  // 4. Test suites
  val testParents = Set("FunSuite", "AnyFunSuite", "FlatSpec", "AnyFlatSpec", "WordSpec", "AnyWordSpec",
    "FreeSpec", "AnyFreeSpec", "PropSpec", "FeatureSpec", "Suite", "Specification", "FunSpec")
  testParents.foreach { parent =>
    filterSymbols(ctx.idx.findImplementations(parent), ctx).foreach(s => addIfNew(s, TestSuite))
  }

  CmdResult.Entrypoints(results.toList, results.size)
