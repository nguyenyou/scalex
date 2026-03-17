import scala.meta.*
import scala.collection.mutable
import java.nio.file.{Files, Path}
import com.google.common.hash.{BloomFilter, Funnels}
import scala.jdk.CollectionConverters.*
import com.github.javaparser.{JavaParser as JP, ParserConfiguration}
import com.github.javaparser.ast.{CompilationUnit as JavaCU}
import com.github.javaparser.ast.body.*
import com.github.javaparser.ast.Modifier.Keyword as JKeyword

// ── File type routing ────────────────────────────────────────────────────────

def isJavaFile(path: Path): Boolean = path.toString.endsWith(".java")

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

private def extractParents(templ: Template): (parents: List[String], typeParamParents: List[String]) =
  val directParents = mutable.ListBuffer.empty[String]
  val tpParents = mutable.ListBuffer.empty[String]

  def extractTypeArgNames(tpe: scala.meta.Type): List[String] = tpe match
    case Type.Name(name) => List(name)
    case Type.Select(_, Type.Name(name)) => List(name)
    case Type.Apply.After_4_6_0(_, argClause) =>
      // Skip the type constructor (Map, List, Option etc.) — only collect leaf type args
      argClause.values.flatMap(extractTypeArgNames)
    case _ => Nil

  templ.inits.foreach { init =>
    init.tpe match
      case Type.Name(name) => directParents += name
      case Type.Select(_, Type.Name(name)) => directParents += name
      case Type.Apply.After_4_6_0(Type.Name(name), argClause) =>
        directParents += name
        argClause.values.flatMap(extractTypeArgNames).foreach(tpParents += _)
      case Type.Apply.After_4_6_0(Type.Select(_, Type.Name(name)), argClause) =>
        directParents += name
        argClause.values.flatMap(extractTypeArgNames).foreach(tpParents += _)
      case _ =>
  }

  val directSet = directParents.map(_.toLowerCase).toSet
  val filtered = tpParents.toList.distinct
    .filterNot(n => directSet.contains(n.toLowerCase))
    .filterNot(n => n.length == 1 && n.head.isUpper) // filter T, A, F etc.
  (directParents.toList, filtered)

private def buildSignature(name: String, kind: String, parents: List[String], tparams: List[String] = Nil): String =
  val tps = if tparams.nonEmpty then tparams.mkString("[", ", ", "]") else ""
  val ext = if parents.nonEmpty then s" extends ${parents.mkString(" with ")}" else ""
  s"$kind $name$tps$ext"

private def formatParamClauses(paramClauses: Seq[Term.ParamClause]): String =
  paramClauses.map(_.values.map(p =>
    s"${p.name.value}: ${p.decltpe.map(_.toString()).getOrElse("?")}"
  ).mkString(", ")).mkString("(", ")(", ")")

private def extractAnnotations(mods: List[Mod]): List[String] =
  mods.collect { case Mod.Annot(init) =>
    init.tpe match
      case Type.Name(name) => name
      case Type.Select(_, Type.Name(name)) => name
      case _ => init.tpe.toString()
  }

def extractImports(tree: Tree): (imports: List[String], aliases: Map[String, String]) =
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

// ── Shared raw symbol extraction ─────────────────────────────────────────────

case class RawSymbol(name: String, kind: SymbolKind, line: Int, parents: List[String] = Nil, typeParamParents: List[String] = Nil, signature: String = "", annotations: List[String] = Nil)

def extractRawSymbols(tree: Tree): (symbols: List[RawSymbol], packageName: String) =
  val pkg = tree.children.collectFirst { case p: Pkg => p.ref.toString() }.getOrElse("")
  val buf = mutable.ListBuffer.empty[RawSymbol]

  def visit(t: Tree): Unit = t match
    case d: Defn.Class =>
      val (parents, tpParents) = extractParents(d.templ)
      val tparams = d.tparamClause.values.map(_.name.value)
      val sig = buildSignature(d.name.value, "class", parents, tparams)
      val annots = extractAnnotations(d.mods)
      buf += RawSymbol(d.name.value, SymbolKind.Class, d.pos.startLine + 1, parents, tpParents, sig, annots)
    case d: Defn.Trait =>
      val (parents, tpParents) = extractParents(d.templ)
      val tparams = d.tparamClause.values.map(_.name.value)
      val sig = buildSignature(d.name.value, "trait", parents, tparams)
      val annots = extractAnnotations(d.mods)
      buf += RawSymbol(d.name.value, SymbolKind.Trait, d.pos.startLine + 1, parents, tpParents, sig, annots)
    case d: Defn.Object =>
      val (parents, tpParents) = extractParents(d.templ)
      val sig = buildSignature(d.name.value, "object", parents)
      val annots = extractAnnotations(d.mods)
      buf += RawSymbol(d.name.value, SymbolKind.Object, d.pos.startLine + 1, parents, tpParents, sig, annots)
    case d: Pkg.Object =>
      val (parents, tpParents) = extractParents(d.templ)
      val sig = buildSignature(d.name.value, "object", parents)
      buf += RawSymbol(d.name.value, SymbolKind.Object, d.pos.startLine + 1, parents, tpParents, sig)
    case d: Defn.Enum =>
      val (parents, tpParents) = extractParents(d.templ)
      val tparams = d.tparamClause.values.map(_.name.value)
      val sig = buildSignature(d.name.value, "enum", parents, tparams)
      val annots = extractAnnotations(d.mods)
      buf += RawSymbol(d.name.value, SymbolKind.Enum, d.pos.startLine + 1, parents, tpParents, sig, annots)
    case d: Defn.Given =>
      if d.name.value.nonEmpty then
        val annots = extractAnnotations(d.mods)
        buf += RawSymbol(d.name.value, SymbolKind.Given, d.pos.startLine + 1, Nil, Nil, s"given ${d.name.value}", annots)
    case d: Defn.GivenAlias =>
      if d.name.value.nonEmpty then
        val sig = s"given ${d.name.value}: ${d.decltpe.toString()}"
        val annots = extractAnnotations(d.mods)
        buf += RawSymbol(d.name.value, SymbolKind.Given, d.pos.startLine + 1, Nil, Nil, sig, annots)
    case d: Defn.Type =>
      val sig = s"type ${d.name.value} = ${d.body.toString().take(60)}"
      val annots = extractAnnotations(d.mods)
      buf += RawSymbol(d.name.value, SymbolKind.Type, d.pos.startLine + 1, Nil, Nil, sig, annots)
    case d: Defn.Def =>
      val params = formatParamClauses(d.paramClauses)
      val ret = d.decltpe.map(t => s": ${t.toString()}").getOrElse("")
      val sig = s"def ${d.name.value}$params$ret"
      val annots = extractAnnotations(d.mods)
      buf += RawSymbol(d.name.value, SymbolKind.Def, d.pos.startLine + 1, Nil, Nil, sig, annots)
    case d: Defn.Val =>
      val annots = extractAnnotations(d.mods)
      d.pats.foreach {
        case Pat.Var(name) =>
          val tpe = d.decltpe.map(t => s": ${t.toString()}").getOrElse("")
          buf += RawSymbol(name.value, SymbolKind.Val, d.pos.startLine + 1, Nil, Nil, s"val ${name.value}$tpe", annots)
        case _ =>
      }
    case d: Defn.ExtensionGroup =>
      val recv = d.paramClauses.headOption.flatMap(_.values.headOption).map(p =>
        s"(${p.name.value}: ${p.decltpe.map(_.toString()).getOrElse("?")})"
      ).getOrElse("")
      buf += RawSymbol("<extension>", SymbolKind.Extension, d.pos.startLine + 1, Nil, Nil, s"extension $recv")
    case _ =>

  def traverse(t: Tree): Unit =
    visit(t)
    t match
      case _: Defn.Def | _: Defn.Val | _: Defn.Var | _: Defn.Given | _: Defn.GivenAlias => ()
      case _ => t.children.foreach(traverse)

  traverse(tree)
  (buf.toList, pkg)

// ── Symbol extraction + bloom filter ────────────────────────────────────────

def extractSymbols(file: Path): (symbols: List[SymbolInfo], bloom: Option[BloomFilter[CharSequence]], imports: List[String], aliases: Map[String, String], parseFailed: Boolean) =
  if isJavaFile(file) then extractJavaSymbols(file)
  else extractScalaSymbols(file)

private def extractScalaSymbols(file: Path): (symbols: List[SymbolInfo], bloom: Option[BloomFilter[CharSequence]], imports: List[String], aliases: Map[String, String], parseFailed: Boolean) =
  val source = try Files.readString(file) catch
    case _: java.io.IOException =>
      return (Nil, None, Nil, Map.empty, true)

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
        case _: Exception =>
          return (Nil, Some(bloom), Nil, Map.empty, true)

  val (imports, aliases) = extractImports(tree)
  val (rawSymbols, pkg) = extractRawSymbols(tree)
  val symbols = rawSymbols.map(r => SymbolInfo(r.name, r.kind, file, r.line, pkg, r.parents, r.typeParamParents, r.signature, r.annotations))
  (symbols, Some(bloom), imports, aliases, false)

// ── Source parsing helper ────────────────────────────────────────────────────

def parseFile(path: Path): Option[Source] =
  val source = try Files.readString(path) catch
    case _: java.io.IOException => return None
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
        case _: Exception =>
          None

// ── Member extraction ───────────────────────────────────────────────────────

def extractMembers(file: Path, symbolName: String): List[MemberInfo] =
  if isJavaFile(file) then extractJavaMembers(file, symbolName)
  else extractScalaMembers(file, symbolName)

private def extractScalaMembers(file: Path, symbolName: String): List[MemberInfo] =
  parseFile(file) match
    case None => Nil
    case Some(tree) =>
      val buf = mutable.ListBuffer.empty[MemberInfo]

      def extractFromTemplate(templ: Template): Unit =
        templ.stats.foreach {
          case d: Defn.Def =>
            val params = formatParamClauses(d.paramClauses)
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
            val params = formatParamClauses(d.paramClauses)
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
        case d: Defn.Class if d.name.value == symbolName =>
          // Constructor params: case class params are public vals; regular class params only if marked val/var
          val isCaseClass = d.mods.exists(_.isInstanceOf[Mod.Case])
          d.ctor.paramClauses.foreach { clause =>
            clause.values.foreach { p =>
              val isVal = isCaseClass || p.mods.exists(m => m.isInstanceOf[Mod.ValParam] || m.isInstanceOf[Mod.VarParam])
              if isVal then
                val tpe = p.decltpe.map(t => s": ${t.toString}").getOrElse("")
                val kind = if p.mods.exists(_.isInstanceOf[Mod.VarParam]) then SymbolKind.Var else SymbolKind.Val
                buf += MemberInfo(p.name.value, kind, p.pos.startLine + 1, s"val ${p.name.value}$tpe")
            }
          }
          extractFromTemplate(d.templ)
        case d: Defn.Trait if d.name.value == symbolName => extractFromTemplate(d.templ)
        case d: Defn.Object if d.name.value == symbolName => extractFromTemplate(d.templ)
        case d: Defn.Enum if d.name.value == symbolName => extractFromTemplate(d.templ)
        case _ => t.children.foreach(findAndExtract)

      findAndExtract(tree)
      buf.toList

// ── Doc extraction (Scaladoc / Javadoc) ─────────────────────────────────────

def extractDoc(file: Path, targetLine: Int): Option[String] =
  if isJavaFile(file) then return None
  val lines = try Files.readAllLines(file).asScala.toArray catch
    case _: java.io.IOException => return None
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
  if isJavaFile(file) then return Nil
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

def extractBody(file: Path, symbolName: String, ownerName: Option[String]): List[BodyInfo] =
  if isJavaFile(file) then extractJavaBody(file, symbolName, ownerName)
  else extractScalaBody(file, symbolName, ownerName)

private def extractScalaBody(file: Path, symbolName: String, ownerName: Option[String]): List[BodyInfo] = {
  val lines = try Files.readAllLines(file).asScala.toArray catch
    case _: java.io.IOException => return Nil
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
  if isJavaFile(file) then return Nil
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

// ── Java parsing helper ──────────────────────────────────────────────────────

private def parseJavaFile(path: Path): Option[JavaCU] =
  val source = try Files.readString(path) catch
    case _: java.io.IOException => return None
  try
    val config = new ParserConfiguration()
    config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
    val parser = new JP(config)
    val result = parser.parse(source)
    if result.isSuccessful then Some(result.getResult.get())
    else
      System.err.println(s"scalex: java parse failed: $path")
      None
  catch
    case _: Exception =>
      System.err.println(s"scalex: java parse failed: $path")
      None

private def javaTypeToString(tpe: com.github.javaparser.ast.`type`.Type): String =
  tpe.asString()

private def javaParentsFromType(td: TypeDeclaration[?]): List[String] =
  val buf = mutable.ListBuffer.empty[String]
  td match
    case cd: ClassOrInterfaceDeclaration =>
      cd.getExtendedTypes.forEach(t => buf += t.getNameAsString)
      cd.getImplementedTypes.forEach(t => buf += t.getNameAsString)
    case ed: EnumDeclaration =>
      ed.getImplementedTypes.forEach(t => buf += t.getNameAsString)
    case rd: RecordDeclaration =>
      rd.getImplementedTypes.forEach(t => buf += t.getNameAsString)
    case _ =>
  buf.toList

private def javaAnnotations(decl: com.github.javaparser.ast.nodeTypes.NodeWithAnnotations[?]): List[String] =
  val buf = mutable.ListBuffer.empty[String]
  decl.getAnnotations.forEach(a => buf += a.getNameAsString)
  buf.toList

private def javaTypeParams(td: TypeDeclaration[?]): List[String] =
  td match
    case cd: ClassOrInterfaceDeclaration =>
      val buf = mutable.ListBuffer.empty[String]
      cd.getTypeParameters.forEach(tp => buf += tp.getNameAsString)
      buf.toList
    case rd: RecordDeclaration =>
      val buf = mutable.ListBuffer.empty[String]
      rd.getTypeParameters.forEach(tp => buf += tp.getNameAsString)
      buf.toList
    case _ => Nil

private def javaKindAndSig(td: TypeDeclaration[?]): (SymbolKind, String) =
  val name = td.getNameAsString
  val parents = javaParentsFromType(td)
  val tparams = javaTypeParams(td)
  val tps = if tparams.nonEmpty then tparams.mkString("[", ", ", "]") else ""
  val ext = if parents.nonEmpty then s" extends ${parents.mkString(", ")}" else ""
  td match
    case _: EnumDeclaration =>
      (SymbolKind.Enum, s"enum $name$ext")
    case cd: ClassOrInterfaceDeclaration if cd.isInterface =>
      (SymbolKind.Trait, s"interface $name$tps$ext")
    case rd: RecordDeclaration =>
      val params = mutable.ListBuffer.empty[String]
      rd.getParameters.forEach(p => params += s"${p.getNameAsString}: ${javaTypeToString(p.getType)}")
      (SymbolKind.Class, s"record $name(${params.mkString(", ")})$ext")
    case _ =>
      (SymbolKind.Class, s"class $name$tps$ext")

// ── Java symbol extraction (JavaParser-based) ──────────────────────────────

private def extractJavaSymbols(file: Path): (symbols: List[SymbolInfo], bloom: Option[BloomFilter[CharSequence]], imports: List[String], aliases: Map[String, String], parseFailed: Boolean) =
  val source = try Files.readString(file) catch
    case _: java.io.IOException =>
      return (Nil, None, Nil, Map.empty, true)

  val bloom = buildBloomFilterFromSource(source)

  parseJavaFile(file) match
    case None =>
      return (Nil, Some(bloom), Nil, Map.empty, true)
    case Some(cu) =>
      val buf = mutable.ListBuffer.empty[SymbolInfo]
      val importBuf = mutable.ListBuffer.empty[String]

      val pkg = if cu.getPackageDeclaration.isPresent then cu.getPackageDeclaration.get().getNameAsString else ""

      cu.getImports.forEach { imp =>
        importBuf += s"import ${imp.getNameAsString}${if imp.isAsterisk then ".*" else ""}"
      }

      def visitType(td: TypeDeclaration[?], outerName: String): Unit =
        val name = td.getNameAsString
        val parents = javaParentsFromType(td)
        val annots = javaAnnotations(td)
        val (kind, sig) = javaKindAndSig(td)
        val line = td.getBegin.map(_.line).orElse(0)
        buf += SymbolInfo(name, kind, file, line, pkg, parents, Nil, sig, annots)

        // Extract methods
        td.getMethods.forEach { m =>
          val mName = m.getNameAsString
          val params = mutable.ListBuffer.empty[String]
          m.getParameters.forEach(p => params += s"${p.getNameAsString}: ${javaTypeToString(p.getType)}")
          val ret = javaTypeToString(m.getType)
          val mSig = s"def $mName(${params.mkString(", ")}): $ret"
          val mAnnots = javaAnnotations(m)
          val mLine = m.getBegin.map(_.line).orElse(0)
          buf += SymbolInfo(mName, SymbolKind.Def, file, mLine, pkg, Nil, Nil, mSig, mAnnots)
        }

        // Extract fields
        td.getFields.forEach { f =>
          f.getVariables.forEach { v =>
            val vName = v.getNameAsString
            val vType = javaTypeToString(f.getCommonType)
            val isFinal = f.isFinal
            val fKind = if isFinal then SymbolKind.Val else SymbolKind.Var
            val prefix = if isFinal then "val" else "var"
            val fSig = s"$prefix $vName: $vType"
            val fAnnots = javaAnnotations(f)
            val fLine = f.getBegin.map(_.line).orElse(0)
            buf += SymbolInfo(vName, fKind, file, fLine, pkg, Nil, Nil, fSig, fAnnots)
          }
        }

        // Recurse into nested types
        td.getMembers.forEach {
          case nested: TypeDeclaration[?] => visitType(nested, name)
          case _ =>
        }

      cu.getTypes.forEach(td => visitType(td, ""))

      (buf.toList, Some(bloom), importBuf.toList, Map.empty, false)

// ── Java member extraction ──────────────────────────────────────────────────

private def extractJavaMembers(file: Path, symbolName: String): List[MemberInfo] =
  parseJavaFile(file) match
    case None => Nil
    case Some(cu) =>
      val buf = mutable.ListBuffer.empty[MemberInfo]

      def findType(td: TypeDeclaration[?]): Unit =
        if td.getNameAsString == symbolName then
          // Methods
          td.getMethods.forEach { m =>
            val params = mutable.ListBuffer.empty[String]
            m.getParameters.forEach(p => params += s"${p.getNameAsString}: ${javaTypeToString(p.getType)}")
            val ret = javaTypeToString(m.getType)
            val sig = s"def ${m.getNameAsString}(${params.mkString(", ")}): $ret"
            val annots = javaAnnotations(m)
            val isOverride = annots.contains("Override")
            val line = m.getBegin.map(_.line).orElse(0)
            buf += MemberInfo(m.getNameAsString, SymbolKind.Def, line, sig, annots, isOverride)
          }

          // Fields
          td.getFields.forEach { f =>
            f.getVariables.forEach { v =>
              val vName = v.getNameAsString
              val vType = javaTypeToString(f.getCommonType)
              val isFinal = f.isFinal
              val fKind = if isFinal then SymbolKind.Val else SymbolKind.Var
              val prefix = if isFinal then "val" else "var"
              val sig = s"$prefix $vName: $vType"
              val annots = javaAnnotations(f)
              val line = f.getBegin.map(_.line).orElse(0)
              buf += MemberInfo(vName, fKind, line, sig, annots)
            }
          }

          // Constructors
          td.getConstructors.forEach { c =>
            val params = mutable.ListBuffer.empty[String]
            c.getParameters.forEach(p => params += s"${p.getNameAsString}: ${javaTypeToString(p.getType)}")
            val sig = s"def <init>(${params.mkString(", ")})"
            val annots = javaAnnotations(c)
            val line = c.getBegin.map(_.line).orElse(0)
            buf += MemberInfo("<init>", SymbolKind.Def, line, sig, annots)
          }

          // Nested types
          td.getMembers.forEach {
            case nested: TypeDeclaration[?] =>
              val (kind, sig) = javaKindAndSig(nested)
              val annots = javaAnnotations(nested)
              val line = nested.getBegin.map(_.line).orElse(0)
              buf += MemberInfo(nested.getNameAsString, kind, line, sig, annots)
            case _ =>
          }

          // Enum constants
          td match
            case ed: EnumDeclaration =>
              ed.getEntries.forEach { entry =>
                val line = entry.getBegin.map(_.line).orElse(0)
                buf += MemberInfo(entry.getNameAsString, SymbolKind.Val, line, s"val ${entry.getNameAsString}")
              }
            case _ =>
        else
          // Recurse into nested types
          td.getMembers.forEach {
            case nested: TypeDeclaration[?] => findType(nested)
            case _ =>
          }

      cu.getTypes.forEach(td => findType(td))
      buf.toList

// ── Java body extraction ────────────────────────────────────────────────────

private def extractJavaBody(file: Path, symbolName: String, ownerName: Option[String]): List[BodyInfo] =
  val sourceLines = try Files.readAllLines(file).asScala.toArray catch
    case _: java.io.IOException => return Nil
  parseJavaFile(file) match
    case None => Nil
    case Some(cu) =>
      val buf = mutable.ListBuffer.empty[BodyInfo]

      def extractFromType(td: TypeDeclaration[?]): Unit =
        val typeName = td.getNameAsString

        // Check if the type itself matches
        if symbolName == typeName && (ownerName.isEmpty || ownerName.contains("")) then
          val sl = td.getBegin.map(_.line).orElse(1)
          val el = td.getEnd.map(_.line).orElse(sl)
          val body = ((sl - 1) until el).filter(_ < sourceLines.length).map(sourceLines(_)).mkString("\n")
          buf += BodyInfo("", typeName, body, sl, el)

        // Methods
        td.getMethods.forEach { m =>
          if m.getNameAsString == symbolName && (ownerName.isEmpty || ownerName.contains(typeName)) then
            val sl = m.getBegin.map(_.line).orElse(1)
            val el = m.getEnd.map(_.line).orElse(sl)
            val body = ((sl - 1) until el).filter(_ < sourceLines.length).map(sourceLines(_)).mkString("\n")
            buf += BodyInfo(typeName, m.getNameAsString, body, sl, el)
        }

        // Fields
        td.getFields.forEach { f =>
          f.getVariables.forEach { v =>
            if v.getNameAsString == symbolName && (ownerName.isEmpty || ownerName.contains(typeName)) then
              val sl = f.getBegin.map(_.line).orElse(1)
              val el = f.getEnd.map(_.line).orElse(sl)
              val body = ((sl - 1) until el).filter(_ < sourceLines.length).map(sourceLines(_)).mkString("\n")
              buf += BodyInfo(typeName, v.getNameAsString, body, sl, el)
          }
        }

        // Nested types — recurse
        td.getMembers.forEach {
          case nested: TypeDeclaration[?] =>
            if nested.getNameAsString == symbolName && (ownerName.isEmpty || ownerName.contains(typeName)) then
              val sl = nested.getBegin.map(_.line).orElse(1)
              val el = nested.getEnd.map(_.line).orElse(sl)
              val body = ((sl - 1) until el).filter(_ < sourceLines.length).map(sourceLines(_)).mkString("\n")
              buf += BodyInfo(typeName, nested.getNameAsString, body, sl, el)
            extractFromType(nested)
          case _ =>
        }

      cu.getTypes.forEach(td => extractFromType(td))
      buf.toList
