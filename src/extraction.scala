import scala.meta.*
import scala.collection.mutable
import java.nio.file.{Files, Path}
import com.google.common.hash.{BloomFilter, Funnels}
import scala.jdk.CollectionConverters.*

// ── Symbol extraction + bloom filter ────────────────────────────────────────

def buildBloomFilterFromSource(source: String): BloomFilter[CharSequence] =
  val expected = math.max(500, source.length / 15)
  val bloom = BloomFilter.create(Funnels.unencodedCharsFunnel(), expected, 0.01)
  var i = 0
  val len = source.length
  while i < len do
    if source(i).isLetter || source(i) == '_' then
      val start = i
      while i < len && (source(i).isLetterOrDigit || source(i) == '_') do i += 1
      val word = source.substring(start, i)
      if word.length >= 2 then bloom.put(word)
    else
      i += 1
  bloom

private def extractParents(templ: Template): List[String] =
  templ.inits.flatMap { init =>
    init.tpe match
      case Type.Name(name) => Some(name)
      case Type.Select(_, Type.Name(name)) => Some(name)
      case Type.Apply.After_4_6_0(Type.Name(name), _) => Some(name)
      case Type.Apply.After_4_6_0(Type.Select(_, Type.Name(name)), _) => Some(name)
      case _ => None
  }

private def buildSignature(name: String, kind: String, parents: List[String], tparams: List[String] = Nil): String =
  val tps = if tparams.nonEmpty then tparams.mkString("[", ", ", "]") else ""
  val ext = if parents.nonEmpty then s" extends ${parents.mkString(" with ")}" else ""
  s"$kind $name$tps$ext"

private def extractAnnotations(mods: List[Mod]): List[String] =
  mods.collect { case Mod.Annot(init) =>
    init.tpe match
      case Type.Name(name) => name
      case Type.Select(_, Type.Name(name)) => name
      case _ => init.tpe.toString()
  }

private def extractImports(tree: Tree): (imports: List[String], aliases: Map[String, String]) =
  val buf = mutable.ListBuffer.empty[String]
  val aliases = mutable.Map.empty[String, String]
  def visit(t: Tree): Unit =
    t match
      case i: Import =>
        buf += i.toString()
        i.importers.foreach { importer =>
          importer.importees.foreach {
            case r: Importee.Rename => aliases(r.name.value) = r.rename.value
            case _ =>
          }
        }
      case _ =>
    t.children.foreach(visit)
  visit(tree)
  (buf.toList, aliases.toMap)

def extractSymbols(file: Path): (symbols: List[SymbolInfo], bloom: BloomFilter[CharSequence], imports: List[String], aliases: Map[String, String], parseFailed: Boolean) =
  val source = try Files.readString(file) catch
    case _: Exception =>
      val bloom = BloomFilter.create(Funnels.unencodedCharsFunnel(), 500, 0.01)
      return (Nil, bloom, Nil, Map.empty, true)

  val bloom = buildBloomFilterFromSource(source)

  val input = Input.VirtualFile(file.toString, source)
  val tree = try
    given scala.meta.Dialect = scala.meta.dialects.Scala3
    input.parse[Source].get
  catch
    case _: Exception =>
      try
        given scala.meta.Dialect = scala.meta.dialects.Scala213
        input.parse[Source].get
      catch
        case _: Exception => return (Nil, bloom, Nil, Map.empty, true)

  val pkg = tree.children.collectFirst { case p: Pkg => p.ref.toString() }.getOrElse("")
  val (imports, aliases) = extractImports(tree)
  val buf = mutable.ListBuffer.empty[SymbolInfo]

  def visit(t: Tree): Unit = t match
    case d: Defn.Class =>
      val parents = extractParents(d.templ)
      val tparams = d.tparamClause.values.map(_.name.value)
      val sig = buildSignature(d.name.value, "class", parents, tparams)
      val annots = extractAnnotations(d.mods)
      buf += SymbolInfo(d.name.value, SymbolKind.Class, file, d.pos.startLine + 1, pkg, parents, sig, annots)
    case d: Defn.Trait =>
      val parents = extractParents(d.templ)
      val tparams = d.tparamClause.values.map(_.name.value)
      val sig = buildSignature(d.name.value, "trait", parents, tparams)
      val annots = extractAnnotations(d.mods)
      buf += SymbolInfo(d.name.value, SymbolKind.Trait, file, d.pos.startLine + 1, pkg, parents, sig, annots)
    case d: Defn.Object =>
      val parents = extractParents(d.templ)
      val sig = buildSignature(d.name.value, "object", parents)
      val annots = extractAnnotations(d.mods)
      buf += SymbolInfo(d.name.value, SymbolKind.Object, file, d.pos.startLine + 1, pkg, parents, sig, annots)
    case d: Pkg.Object =>
      val parents = extractParents(d.templ)
      val sig = buildSignature(d.name.value, "object", parents)
      buf += SymbolInfo(d.name.value, SymbolKind.Object, file, d.pos.startLine + 1, pkg, parents, sig)
    case d: Defn.Enum =>
      val parents = extractParents(d.templ)
      val tparams = d.tparamClause.values.map(_.name.value)
      val sig = buildSignature(d.name.value, "enum", parents, tparams)
      val annots = extractAnnotations(d.mods)
      buf += SymbolInfo(d.name.value, SymbolKind.Enum, file, d.pos.startLine + 1, pkg, parents, sig, annots)
    case d: Defn.Given =>
      if d.name.value.nonEmpty then
        val annots = extractAnnotations(d.mods)
        buf += SymbolInfo(d.name.value, SymbolKind.Given, file, d.pos.startLine + 1, pkg, Nil, s"given ${d.name.value}", annots)
    case d: Defn.GivenAlias =>
      if d.name.value.nonEmpty then
        val sig = s"given ${d.name.value}: ${d.decltpe.toString()}"
        val annots = extractAnnotations(d.mods)
        buf += SymbolInfo(d.name.value, SymbolKind.Given, file, d.pos.startLine + 1, pkg, Nil, sig, annots)
    case d: Defn.Type =>
      val sig = s"type ${d.name.value} = ${d.body.toString().take(60)}"
      val annots = extractAnnotations(d.mods)
      buf += SymbolInfo(d.name.value, SymbolKind.Type, file, d.pos.startLine + 1, pkg, Nil, sig, annots)
    case d: Defn.Def =>
      val params = d.paramClauses.map(_.values.map(p => s"${p.name.value}: ${p.decltpe.map(_.toString()).getOrElse("?")}").mkString(", ")).mkString("(", ")(", ")")
      val ret = d.decltpe.map(t => s": ${t.toString()}").getOrElse("")
      val sig = s"def ${d.name.value}$params$ret"
      val annots = extractAnnotations(d.mods)
      buf += SymbolInfo(d.name.value, SymbolKind.Def, file, d.pos.startLine + 1, pkg, Nil, sig, annots)
    case d: Defn.Val =>
      val annots = extractAnnotations(d.mods)
      d.pats.foreach {
        case Pat.Var(name) =>
          val tpe = d.decltpe.map(t => s": ${t.toString()}").getOrElse("")
          buf += SymbolInfo(name.value, SymbolKind.Val, file, d.pos.startLine + 1, pkg, Nil, s"val ${name.value}$tpe", annots)
        case _ =>
      }
    case d: Defn.ExtensionGroup =>
      val recv = d.paramClauses.headOption.flatMap(_.values.headOption).map(p =>
        s"(${p.name.value}: ${p.decltpe.map(_.toString()).getOrElse("?")})"
      ).getOrElse("")
      buf += SymbolInfo("<extension>", SymbolKind.Extension, file, d.pos.startLine + 1, pkg, Nil, s"extension $recv")
    case _ =>

  def traverse(t: Tree): Unit =
    visit(t)
    t.children.foreach(traverse)

  traverse(tree)
  (buf.toList, bloom, imports, aliases, false)

// ── Source parsing helper ────────────────────────────────────────────────────

def parseFile(path: Path): Option[Source] =
  val source = try Files.readString(path) catch
    case _: Exception => return None
  val input = Input.VirtualFile(path.toString, source)
  try
    given scala.meta.Dialect = scala.meta.dialects.Scala3
    Some(input.parse[Source].get)
  catch
    case _: Exception =>
      try
        given scala.meta.Dialect = scala.meta.dialects.Scala213
        Some(input.parse[Source].get)
      catch
        case _: Exception => None

// ── Member extraction ───────────────────────────────────────────────────────

def extractMembers(file: Path, symbolName: String): List[MemberInfo] =
  parseFile(file) match
    case None => Nil
    case Some(tree) =>
      val buf = mutable.ListBuffer.empty[MemberInfo]

      def extractFromTemplate(templ: Template): Unit =
        templ.stats.foreach {
          case d: Defn.Def =>
            val params = d.paramClauses.map(_.values.map(p => s"${p.name.value}: ${p.decltpe.map(_.toString()).getOrElse("?")}").mkString(", ")).mkString("(", ")(", ")")
            val ret = d.decltpe.map(t => s": ${t.toString()}").getOrElse("")
            val annots = extractAnnotations(d.mods)
            buf += MemberInfo(d.name.value, SymbolKind.Def, d.pos.startLine + 1, s"def ${d.name.value}$params$ret", annots)
          case d: Defn.Val =>
            val annots = extractAnnotations(d.mods)
            d.pats.foreach {
              case Pat.Var(name) =>
                val tpe = d.decltpe.map(t => s": ${t.toString()}").getOrElse("")
                buf += MemberInfo(name.value, SymbolKind.Val, d.pos.startLine + 1, s"val ${name.value}$tpe", annots)
              case _ =>
            }
          case d: Defn.Var =>
            val annots = extractAnnotations(d.mods)
            d.pats.foreach {
              case Pat.Var(name) =>
                val tpe = d.decltpe.map(t => s": ${t.toString()}").getOrElse("")
                buf += MemberInfo(name.value, SymbolKind.Var, d.pos.startLine + 1, s"var ${name.value}$tpe", annots)
              case _ =>
            }
          case d: Defn.Type =>
            val annots = extractAnnotations(d.mods)
            buf += MemberInfo(d.name.value, SymbolKind.Type, d.pos.startLine + 1, s"type ${d.name.value} = ${d.body.toString().take(60)}", annots)
          case d: Decl.Def =>
            val params = d.paramClauses.map(_.values.map(p => s"${p.name.value}: ${p.decltpe.map(_.toString()).getOrElse("?")}").mkString(", ")).mkString("(", ")(", ")")
            val ret = s": ${d.decltpe.toString()}"
            val annots = extractAnnotations(d.mods)
            buf += MemberInfo(d.name.value, SymbolKind.Def, d.pos.startLine + 1, s"def ${d.name.value}$params$ret", annots)
          case d: Decl.Val =>
            val annots = extractAnnotations(d.mods)
            d.pats.foreach {
              case p: Pat.Var =>
                val tpe = s": ${d.decltpe.toString()}"
                buf += MemberInfo(p.name.value, SymbolKind.Val, d.pos.startLine + 1, s"val ${p.name.value}$tpe", annots)
              case _ =>
            }
          case d: Decl.Type =>
            val annots = extractAnnotations(d.mods)
            buf += MemberInfo(d.name.value, SymbolKind.Type, d.pos.startLine + 1, s"type ${d.name.value}", annots)
          case d: Defn.Class =>
            val annots = extractAnnotations(d.mods)
            buf += MemberInfo(d.name.value, SymbolKind.Class, d.pos.startLine + 1, s"class ${d.name.value}", annots)
          case d: Defn.Trait =>
            val annots = extractAnnotations(d.mods)
            buf += MemberInfo(d.name.value, SymbolKind.Trait, d.pos.startLine + 1, s"trait ${d.name.value}", annots)
          case d: Defn.Object =>
            val annots = extractAnnotations(d.mods)
            buf += MemberInfo(d.name.value, SymbolKind.Object, d.pos.startLine + 1, s"object ${d.name.value}", annots)
          case d: Defn.Enum =>
            val annots = extractAnnotations(d.mods)
            buf += MemberInfo(d.name.value, SymbolKind.Enum, d.pos.startLine + 1, s"enum ${d.name.value}", annots)
          case _ =>
        }

      def findAndExtract(t: Tree): Unit = t match
        case d: Defn.Class if d.name.value == symbolName => extractFromTemplate(d.templ)
        case d: Defn.Trait if d.name.value == symbolName => extractFromTemplate(d.templ)
        case d: Defn.Object if d.name.value == symbolName => extractFromTemplate(d.templ)
        case d: Defn.Enum if d.name.value == symbolName => extractFromTemplate(d.templ)
        case _ => t.children.foreach(findAndExtract)

      findAndExtract(tree)
      buf.toList

// ── Scaladoc extraction ─────────────────────────────────────────────────────

def extractScaladoc(file: Path, targetLine: Int): Option[String] =
  val lines = try Files.readAllLines(file).asScala.toArray catch
    case _: Exception => return None
  // targetLine is 1-indexed, array is 0-indexed
  var i = targetLine - 2 // line before the symbol
  // skip blank lines between doc and symbol
  while i >= 0 && lines(i).trim.isEmpty do i -= 1
  if i < 0 then return None
  // Check if this line ends a scaladoc
  val endLine = i
  if lines(endLine).trim == "*/" || lines(endLine).trim.endsWith("*/") then
    // Multi-line or single-line: find the opening /**
    while i >= 0 && !lines(i).trim.startsWith("/**") do i -= 1
    if i >= 0 then Some((i to endLine).map(lines(_)).mkString("\n"))
    else None
  else if lines(endLine).trim.startsWith("/**") && lines(endLine).trim.endsWith("*/") then
    // Single-line /** brief */
    Some(lines(endLine))
  else None

// ── Test extraction ─────────────────────────────────────────────────────

private val testFnNames = Set("test", "it", "describe")

private def extractTestName(t: Tree): Option[(name: String, line: Int)] =
  t match
    case app: Term.Apply =>
      app.fun match
        case innerApp: Term.Apply =>
          innerApp.fun match
            case fn: Term.Name if testFnNames.contains(fn.value) =>
              innerApp.argClause.values.collectFirst { case lit: Lit.String => (lit.value, app.pos.startLine + 1) }
            case _ => None
        case _ => None
    case infix: Term.ApplyInfix =>
      if infix.op.value == "in" || infix.op.value == ">>" then
        infix.lhs match
          case lit: Lit.String => Some((lit.value, infix.pos.startLine + 1))
          case _ => None
      else None
    case _ => None

def extractTests(file: Path): List[TestSuiteInfo] = {
  parseFile(file) match
    case None => Nil
    case Some(tree) =>
      val suites = mutable.ListBuffer.empty[TestSuiteInfo]

      def collectTests(stats: List[Tree], suiteName: String): List[TestCaseInfo] = {
        val tests = mutable.ListBuffer.empty[TestCaseInfo]
        def visit(t: Tree): Unit = {
          extractTestName(t) match
            case Some((name, line)) =>
              tests += TestCaseInfo(name, line, suiteName, file)
            case None =>
          t.children.foreach(visit)
        }
        stats.foreach(visit)
        tests.toList
      }

      def findSuites(t: Tree): Unit = {
        t match
          case d: Defn.Class =>
            val tests = collectTests(d.templ.stats, d.name.value)
            if tests.nonEmpty then
              suites += TestSuiteInfo(d.name.value, file, d.pos.startLine + 1, tests)
          case d: Defn.Object =>
            val tests = collectTests(d.templ.stats, d.name.value)
            if tests.nonEmpty then
              suites += TestSuiteInfo(d.name.value, file, d.pos.startLine + 1, tests)
          case _ =>
        t.children.foreach(findSuites)
      }

      findSuites(tree)
      suites.toList
}

// ── Body extraction ─────────────────────────────────────────────────────────

def extractBody(file: Path, symbolName: String, ownerName: Option[String]): List[BodyInfo] = {
  val lines = try Files.readAllLines(file).asScala.toArray catch
    case _: Exception => return Nil
  parseFile(file) match
    case None => Nil
    case Some(tree) =>
      val buf = mutable.ListBuffer.empty[BodyInfo]

      def extractFromTree(t: Tree, currentOwner: String): Unit = {
        t match
          case d: Defn.Def if d.name.value == symbolName =>
            if ownerName.isEmpty || ownerName.contains(currentOwner) then
              val sl = d.pos.startLine
              val el = d.pos.endLine
              val body = (sl to el).map(lines(_)).mkString("\n")
              buf += BodyInfo(currentOwner, d.name.value, body, sl + 1, el + 1)
          case d: Defn.Val =>
            d.pats.foreach {
              case Pat.Var(name) if name.value == symbolName =>
                if ownerName.isEmpty || ownerName.contains(currentOwner) then
                  val sl = d.pos.startLine
                  val el = d.pos.endLine
                  val body = (sl to el).map(lines(_)).mkString("\n")
                  buf += BodyInfo(currentOwner, name.value, body, sl + 1, el + 1)
              case _ =>
            }
          case d: Defn.Var =>
            d.pats.foreach {
              case Pat.Var(name) if name.value == symbolName =>
                if ownerName.isEmpty || ownerName.contains(currentOwner) then
                  val sl = d.pos.startLine
                  val el = d.pos.endLine
                  val body = (sl to el).map(lines(_)).mkString("\n")
                  buf += BodyInfo(currentOwner, name.value, body, sl + 1, el + 1)
              case _ =>
            }
          case d: Defn.Type if d.name.value == symbolName =>
            if ownerName.isEmpty || ownerName.contains(currentOwner) then
              val sl = d.pos.startLine
              val el = d.pos.endLine
              val body = (sl to el).map(lines(_)).mkString("\n")
              buf += BodyInfo(currentOwner, d.name.value, body, sl + 1, el + 1)
          case d: Defn.Class =>
            if d.name.value == symbolName && (ownerName.isEmpty || ownerName.contains(currentOwner)) then
              val sl = d.pos.startLine
              val el = d.pos.endLine
              val body = (sl to el).map(lines(_)).mkString("\n")
              buf += BodyInfo(currentOwner, d.name.value, body, sl + 1, el + 1)
            d.templ.stats.foreach(s => extractFromTree(s, d.name.value))
          case d: Defn.Trait =>
            if d.name.value == symbolName && (ownerName.isEmpty || ownerName.contains(currentOwner)) then
              val sl = d.pos.startLine
              val el = d.pos.endLine
              val body = (sl to el).map(lines(_)).mkString("\n")
              buf += BodyInfo(currentOwner, d.name.value, body, sl + 1, el + 1)
            d.templ.stats.foreach(s => extractFromTree(s, d.name.value))
          case d: Defn.Object =>
            if d.name.value == symbolName && (ownerName.isEmpty || ownerName.contains(currentOwner)) then
              val sl = d.pos.startLine
              val el = d.pos.endLine
              val body = (sl to el).map(lines(_)).mkString("\n")
              buf += BodyInfo(currentOwner, d.name.value, body, sl + 1, el + 1)
            d.templ.stats.foreach(s => extractFromTree(s, d.name.value))
          case d: Defn.Enum =>
            if d.name.value == symbolName && (ownerName.isEmpty || ownerName.contains(currentOwner)) then
              val sl = d.pos.startLine
              val el = d.pos.endLine
              val body = (sl to el).map(lines(_)).mkString("\n")
              buf += BodyInfo(currentOwner, d.name.value, body, sl + 1, el + 1)
            d.templ.stats.foreach(s => extractFromTree(s, d.name.value))
          case app: Term.Apply =>
            extractTestName(app) match
              case Some((name, _)) if name == symbolName =>
                if ownerName.isEmpty || ownerName.contains(currentOwner) then
                  val sl = app.pos.startLine
                  val el = app.pos.endLine
                  val body = (sl to el).map(lines(_)).mkString("\n")
                  buf += BodyInfo(currentOwner, name, body, sl + 1, el + 1)
              case _ =>
            app.children.foreach(c => extractFromTree(c, currentOwner))
          case infix: Term.ApplyInfix =>
            extractTestName(infix) match
              case Some((name, _)) if name == symbolName =>
                if ownerName.isEmpty || ownerName.contains(currentOwner) then
                  val sl = infix.pos.startLine
                  val el = infix.pos.endLine
                  val body = (sl to el).map(lines(_)).mkString("\n")
                  buf += BodyInfo(currentOwner, name, body, sl + 1, el + 1)
              case _ =>
            infix.children.foreach(c => extractFromTree(c, currentOwner))
          case p: Pkg =>
            p.stats.foreach(s => extractFromTree(s, currentOwner))
          case _ =>
      }

      tree.children.foreach(c => extractFromTree(c, ""))
      buf.toList
}

// ── Scope extraction (context) ──────────────────────────────────────────────

def extractScopes(file: Path, targetLine: Int): List[ScopeInfo] = {
  parseFile(file) match
    case None => Nil
    case Some(tree) =>
      val buf = mutable.ListBuffer.empty[ScopeInfo]

      def visit(t: Tree): Unit = {
        val startLine = t.pos.startLine + 1
        val endLine = t.pos.endLine + 1
        if targetLine >= startLine && targetLine <= endLine then {
          t match
            case d: Pkg =>
              buf += ScopeInfo(d.ref.toString(), "package", startLine)
            case d: Defn.Class =>
              buf += ScopeInfo(d.name.value, "class", startLine)
            case d: Defn.Trait =>
              buf += ScopeInfo(d.name.value, "trait", startLine)
            case d: Defn.Object =>
              buf += ScopeInfo(d.name.value, "object", startLine)
            case d: Defn.Enum =>
              buf += ScopeInfo(d.name.value, "enum", startLine)
            case d: Defn.Def =>
              buf += ScopeInfo(d.name.value, "def", startLine)
            case d: Defn.Val =>
              d.pats.foreach {
                case Pat.Var(name) => buf += ScopeInfo(name.value, "val", startLine)
                case _ =>
              }
            case d: Defn.ExtensionGroup =>
              buf += ScopeInfo("<extension>", "extension", startLine)
            case _ =>
          t.children.foreach(visit)
        }
      }

      visit(tree)
      buf.toList
}
