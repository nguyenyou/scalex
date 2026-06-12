import scala.meta.*
import scala.meta.parsers.Parsed
import scala.collection.mutable
import java.nio.file.{Files, Path}
import com.google.common.hash.{BloomFilter, Funnels}
import scala.jdk.CollectionConverters.*
import com.github.javaparser.{JavaParser as JP, ParserConfiguration}
import com.github.javaparser.ast.{CompilationUnit as JavaCU, Node as JavaNode, NodeList}
import com.github.javaparser.ast.body.*

// ── Source reading & parsing helpers ─────────────────────────────────────────

/** Read a source file as UTF-8, or None if unreadable. */
def readSource(path: Path): Option[String] =
  try Some(Files.readString(path)) catch { case _: java.io.IOException => None }

/** Read a source file as an array of lines, or None if unreadable. */
def readSourceLines(path: Path): Option[Array[String]] =
  try Some(Files.readAllLines(path).asScala.toArray) catch { case _: java.io.IOException => None }

private def tryParse(input: Input.VirtualFile, dialect: Dialect): Option[Source] = {
  try {
    given Dialect = dialect
    input.parse[Source] match {
      case Parsed.Success(tree) => Some(tree)
      case _: Parsed.Error => None
    }
  } catch { case _: Exception => None }
}

/** Parse Scala source, trying the Scala 3 dialect first, falling back to Scala 2.13. */
def parseSource(source: String, virtualPath: String): Option[Source] = {
  val input = Input.VirtualFile(virtualPath, source)
  tryParse(input, dialects.Scala3).orElse(tryParse(input, dialects.Scala213))
}

def parseFile(path: Path): Option[Source] =
  readSource(path).flatMap(source => parseSource(source, path.toString))

/** Names bound by `Pat.Var` patterns (ignores tuple/extractor patterns). */
private def patVarNames(pats: List[Pat]): List[String] =
  pats.collect { case Pat.Var(name) => name.value }

/** Matches any type-introducing definition that carries a template body. */
private object TypeDefn {
  def unapply(t: Tree): Option[(name: String, templ: Template)] = t match {
    case d: Defn.Class  => Some((name = d.name.value, templ = d.templ))
    case d: Defn.Trait  => Some((name = d.name.value, templ = d.templ))
    case d: Defn.Object => Some((name = d.name.value, templ = d.templ))
    case d: Defn.Enum   => Some((name = d.name.value, templ = d.templ))
    case _ => None
  }
}

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

// ── Import line extraction ───────────────────────────────────────────────────

def extractImportLines(file: Path): Option[String] = {
  (readSourceLines(file), parseFile(file)) match {
    case (Some(lines), Some(tree)) =>
      val importRanges = mutable.ListBuffer.empty[(startLine: Int, endLine: Int)]
      // Only collect top-level imports — use .stats (not .children which wraps in PkgBody)
      def collectImports(stats: List[Stat]): Unit =
        stats.foreach {
          case i: Import =>
            importRanges += ((startLine = i.pos.startLine, endLine = i.pos.endLine))
          case p: Pkg => collectImports(p.body.stats)
          case _ =>
        }
      collectImports(tree.stats)
      if importRanges.isEmpty then None
      else {
        val importText = importRanges.flatMap { (sl, el) =>
          (sl to el).filter(_ < lines.length).map(lines(_))
        }.mkString("\n")
        Some(importText)
      }
    case _ => None
  }
}

// ── Shared raw symbol extraction ─────────────────────────────────────────────

case class RawSymbol(name: String, kind: SymbolKind, line: Int, parents: List[String] = Nil, typeParamParents: List[String] = Nil, signature: String = "", annotations: List[String] = Nil)

def extractRawSymbols(tree: Tree): (symbols: List[RawSymbol], packageName: String) =
  val pkg = tree.children.collectFirst { case p: Pkg => p.ref.toString() }.getOrElse("")
  val buf = mutable.ListBuffer.empty[RawSymbol]

  // One path for every template-carrying definition (class/trait/object/enum)
  def addTypeDefn(t: Tree, name: String, kind: SymbolKind, keyword: String, templ: Template,
                  tparams: List[String], mods: List[Mod]): Unit =
    val (parents, tpParents) = extractParents(templ)
    val sig = buildSignature(name, keyword, parents, tparams)
    buf += RawSymbol(name, kind, t.pos.startLine + 1, parents, tpParents, sig, extractAnnotations(mods))

  def visit(t: Tree): Unit = t match
    case d: Defn.Class =>
      addTypeDefn(d, d.name.value, SymbolKind.Class, "class", d.templ, d.tparamClause.values.map(_.name.value).toList, d.mods)
    case d: Defn.Trait =>
      addTypeDefn(d, d.name.value, SymbolKind.Trait, "trait", d.templ, d.tparamClause.values.map(_.name.value).toList, d.mods)
    case d: Defn.Object =>
      addTypeDefn(d, d.name.value, SymbolKind.Object, "object", d.templ, Nil, d.mods)
    case d: Pkg.Object =>
      addTypeDefn(d, d.name.value, SymbolKind.Object, "object", d.templ, Nil, Nil)
    case d: Defn.Enum =>
      addTypeDefn(d, d.name.value, SymbolKind.Enum, "enum", d.templ, d.tparamClause.values.map(_.name.value).toList, d.mods)
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
      patVarNames(d.pats).foreach { name =>
        val tpe = d.decltpe.map(t => s": ${t.toString()}").getOrElse("")
        buf += RawSymbol(name, SymbolKind.Val, d.pos.startLine + 1, Nil, Nil, s"val $name$tpe", annots)
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

private def extractScalaSymbols(file: Path): (symbols: List[SymbolInfo], bloom: Option[BloomFilter[CharSequence]], imports: List[String], aliases: Map[String, String], parseFailed: Boolean) = {
  readSource(file) match {
    case None => (Nil, None, Nil, Map.empty, true)
    case Some(source) =>
      val bloom = buildBloomFilterFromSource(source)
      parseSource(source, file.toString) match {
        case None => (Nil, Some(bloom), Nil, Map.empty, true)
        case Some(tree) =>
          val (imports, aliases) = extractImports(tree)
          val (rawSymbols, pkg) = extractRawSymbols(tree)
          val symbols = rawSymbols.map(r => SymbolInfo(r.name, r.kind, file, r.line, pkg, r.parents, r.typeParamParents, r.signature, r.annotations))
          (symbols, Some(bloom), imports, aliases, false)
      }
  }
}

// ── Member extraction ───────────────────────────────────────────────────────

/** Where a member came from. Lets callers include or exclude constructor
  * params and abstract declarations without re-implementing the traversal. */
enum MemberOrigin {
  case Definition, AbstractDecl, CtorParam
}

case class ExtractedMember(member: MemberInfo, startLine: Int, endLine: Int, origin: MemberOrigin)

def extractMembers(file: Path, symbolName: String, filterKind: Option[SymbolKind] = None): List[MemberInfo] =
  if isJavaFile(file) then extractJavaMembers(file, symbolName)
  else extractScalaMemberTree(file, symbolName, filterKind).map(_.member)

/** Members with their body line spans, for span-scoped grep. Excludes constructor
  * params and abstract val/type declarations (no body to grep). Java not supported. */
def extractMembersWithSpans(file: Path, symbolName: String, filterKind: Option[SymbolKind] = None): List[(member: MemberInfo, startLine: Int, endLine: Int)] =
  if isJavaFile(file) then Nil
  else extractScalaMemberTree(file, symbolName, filterKind).collect {
    case em if em.origin == MemberOrigin.Definition || em.member.kind == SymbolKind.Def =>
      (member = em.member, startLine = em.startLine, endLine = em.endLine)
  }

/** Single traversal behind extractMembers and extractMembersWithSpans: finds the
  * type named `symbolName` and extracts its constructor params and template members. */
private def extractScalaMemberTree(file: Path, symbolName: String, filterKind: Option[SymbolKind]): List[ExtractedMember] =
  parseFile(file) match {
    case None => Nil
    case Some(tree) =>
      val buf = mutable.ListBuffer.empty[ExtractedMember]

      def add(t: Tree, m: MemberInfo, origin: MemberOrigin = MemberOrigin.Definition): Unit =
        buf += ExtractedMember(m, t.pos.startLine + 1, t.pos.endLine + 1, origin)

      def declaredType(decltpe: Option[scala.meta.Type]): String =
        decltpe.map(t => s": ${t.toString()}").getOrElse("")

      def extractFromTemplate(templ: Template): Unit =
        templ.body.stats.foreach {
          case d: Defn.Def =>
            val sig = s"def ${d.name.value}${formatParamClauses(d.paramClauses)}${declaredType(d.decltpe)}"
            add(d, MemberInfo(d.name.value, SymbolKind.Def, d.pos.startLine + 1, sig, extractAnnotations(d.mods)))
          case d: Defn.Val =>
            val annots = extractAnnotations(d.mods)
            patVarNames(d.pats).foreach { name =>
              add(d, MemberInfo(name, SymbolKind.Val, d.pos.startLine + 1, s"val $name${declaredType(d.decltpe)}", annots))
            }
          case d: Defn.Var =>
            val annots = extractAnnotations(d.mods)
            patVarNames(d.pats).foreach { name =>
              add(d, MemberInfo(name, SymbolKind.Var, d.pos.startLine + 1, s"var $name${declaredType(d.decltpe)}", annots))
            }
          case d: Defn.Type =>
            add(d, MemberInfo(d.name.value, SymbolKind.Type, d.pos.startLine + 1, s"type ${d.name.value} = ${d.body.toString().take(60)}", extractAnnotations(d.mods)))
          case d: Decl.Def =>
            val sig = s"def ${d.name.value}${formatParamClauses(d.paramClauses)}: ${d.decltpe.toString()}"
            add(d, MemberInfo(d.name.value, SymbolKind.Def, d.pos.startLine + 1, sig, extractAnnotations(d.mods)), MemberOrigin.AbstractDecl)
          case d: Decl.Val =>
            val annots = extractAnnotations(d.mods)
            patVarNames(d.pats).foreach { name =>
              add(d, MemberInfo(name, SymbolKind.Val, d.pos.startLine + 1, s"val $name: ${d.decltpe.toString()}", annots), MemberOrigin.AbstractDecl)
            }
          case d: Decl.Type =>
            add(d, MemberInfo(d.name.value, SymbolKind.Type, d.pos.startLine + 1, s"type ${d.name.value}", extractAnnotations(d.mods)), MemberOrigin.AbstractDecl)
          case d: Defn.Class =>
            add(d, MemberInfo(d.name.value, SymbolKind.Class, d.pos.startLine + 1, s"class ${d.name.value}", extractAnnotations(d.mods)))
          case d: Defn.Trait =>
            add(d, MemberInfo(d.name.value, SymbolKind.Trait, d.pos.startLine + 1, s"trait ${d.name.value}", extractAnnotations(d.mods)))
          case d: Defn.Object =>
            add(d, MemberInfo(d.name.value, SymbolKind.Object, d.pos.startLine + 1, s"object ${d.name.value}", extractAnnotations(d.mods)))
          case d: Defn.Enum =>
            add(d, MemberInfo(d.name.value, SymbolKind.Enum, d.pos.startLine + 1, s"enum ${d.name.value}", extractAnnotations(d.mods)))
          case _ =>
        }

      // Case class params are public vals; regular class params only if marked val/var
      def addCtorParams(d: Defn.Class): Unit = {
        val isCaseClass = d.mods.exists(_.isInstanceOf[Mod.Case])
        d.ctor.paramClauses.foreach { clause =>
          clause.values.foreach { p =>
            val isVal = isCaseClass || p.mods.exists(m => m.isInstanceOf[Mod.ValParam] || m.isInstanceOf[Mod.VarParam])
            if isVal then {
              val tpe = p.decltpe.map(t => s": ${t.toString}").getOrElse("")
              val kind = if p.mods.exists(_.isInstanceOf[Mod.VarParam]) then SymbolKind.Var else SymbolKind.Val
              add(p, MemberInfo(p.name.value, kind, p.pos.startLine + 1, s"val ${p.name.value}$tpe"), MemberOrigin.CtorParam)
            }
          }
        }
      }

      def kindMatches(k: SymbolKind): Boolean = filterKind.forall(_ == k)

      def findAndExtract(t: Tree): Unit = t match {
        case d: Defn.Class if d.name.value == symbolName && kindMatches(SymbolKind.Class) =>
          addCtorParams(d)
          extractFromTemplate(d.templ)
        case d: Defn.Trait if d.name.value == symbolName && kindMatches(SymbolKind.Trait) => extractFromTemplate(d.templ)
        case d: Defn.Object if d.name.value == symbolName && kindMatches(SymbolKind.Object) => extractFromTemplate(d.templ)
        case d: Defn.Enum if d.name.value == symbolName && kindMatches(SymbolKind.Enum) => extractFromTemplate(d.templ)
        case _ => t.children.foreach(findAndExtract)
      }

      findAndExtract(tree)
      buf.toList
  }

// ── Doc extraction (Scaladoc / Javadoc) ─────────────────────────────────────

def extractDoc(file: Path, targetLine: Int): Option[String] = {
  val lines = if isJavaFile(file) then None else readSourceLines(file)
  lines.flatMap { lines =>
    // targetLine is 1-indexed, array is 0-indexed
    var i = targetLine - 2 // line before the symbol
    // skip blank lines between doc and symbol
    while i >= 0 && lines(i).trim.isEmpty do i -= 1
    if i < 0 || !lines(i).trim.endsWith("*/") then None
    else {
      // The line ends a scaladoc — walk up to the opening /** (which may be the same line)
      val endLine = i
      while i >= 0 && !lines(i).trim.startsWith("/**") do i -= 1
      if i >= 0 then Some((i to endLine).map(lines(_)).mkString("\n"))
      else None
    }
  }
}

// ── Test extraction ─────────────────────────────────────────────────────

private val testFnNames = Set("test", "it", "describe")

private enum TestCallMatch:
  case Literal(name: String, line: Int)
  case Dynamic(line: Int)
  case NotATest

private def classifyTestCall(t: Tree): TestCallMatch =
  t match
    case app: Term.Apply =>
      app.fun match
        // test("name")(body) — double apply
        case innerApp: Term.Apply =>
          innerApp.fun match
            case fn: Term.Name if testFnNames.contains(fn.value) =>
              innerApp.argClause.values.collectFirst { case lit: Lit.String => lit } match
                case Some(lit) => TestCallMatch.Literal(lit.value, app.pos.startLine + 1)
                case None      => TestCallMatch.Dynamic(app.pos.startLine + 1)
            case _ => TestCallMatch.NotATest
        // test("name") { body } — single apply with name + block in same arglist
        case fn: Term.Name if testFnNames.contains(fn.value) =>
          app.argClause.values.collectFirst { case lit: Lit.String => lit } match
            case Some(lit) => TestCallMatch.Literal(lit.value, app.pos.startLine + 1)
            case None      => TestCallMatch.Dynamic(app.pos.startLine + 1)
        case _ => TestCallMatch.NotATest
    case infix: Term.ApplyInfix =>
      if infix.op.value == "in" || infix.op.value == ">>" then
        infix.lhs match
          case lit: Lit.String => TestCallMatch.Literal(lit.value, infix.pos.startLine + 1)
          case _               => TestCallMatch.Dynamic(infix.pos.startLine + 1)
      else TestCallMatch.NotATest
    case _ => TestCallMatch.NotATest

def extractTests(file: Path): List[TestSuiteInfo] = {
  if isJavaFile(file) then Nil
  else parseFile(file) match
    case None => Nil
    case Some(tree) =>
      val suites = mutable.ListBuffer.empty[TestSuiteInfo]

      def collectTests(stats: List[Tree]): (tests: List[TestCaseInfo], dynamicSites: Int) = {
        val tests = mutable.ListBuffer.empty[TestCaseInfo]
        var dynamicCount = 0
        def visit(t: Tree): Unit = {
          classifyTestCall(t) match
            case TestCallMatch.Literal(name, line) =>
              tests += TestCaseInfo(name, line)
              // Don't recurse into matched test node — avoids double-counting
              // when inner Term.Apply also matches (e.g. test("name")(body))
            case TestCallMatch.Dynamic(_) =>
              dynamicCount += 1
              // Don't recurse — the dynamic call is one test site
            case TestCallMatch.NotATest =>
              t.children.foreach(visit)
        }
        stats.foreach(visit)
        (tests = tests.toList, dynamicSites = dynamicCount)
      }

      def findSuites(t: Tree): Unit = {
        t match
          case d: Defn.Class =>
            val (tests, dynSites) = collectTests(d.templ.body.stats)
            if tests.nonEmpty || dynSites > 0 then
              suites += TestSuiteInfo(d.name.value, file, d.pos.startLine + 1, tests, dynSites)
          case d: Defn.Object =>
            val (tests, dynSites) = collectTests(d.templ.body.stats)
            if tests.nonEmpty || dynSites > 0 then
              suites += TestSuiteInfo(d.name.value, file, d.pos.startLine + 1, tests, dynSites)
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
  (readSourceLines(file), parseFile(file)) match {
    case (Some(lines), Some(tree)) =>
      val buf = mutable.ListBuffer.empty[BodyInfo]

      // Slice the node's source span if it defines `symbolName` under an accepted owner
      def addBody(t: Tree, owner: String, name: String, isAbstract: Boolean = false): Unit = {
        if name == symbolName && (ownerName.isEmpty || ownerName.contains(owner)) then {
          val sl = t.pos.startLine
          val el = t.pos.endLine
          val body = (sl to el).map(lines(_)).mkString("\n")
          buf += BodyInfo(owner, name, body, sl + 1, el + 1, isAbstract)
        }
      }

      def extractFromTree(t: Tree, currentOwner: String): Unit = {
        t match {
          case d: Defn.Def =>
            addBody(d, currentOwner, d.name.value)
            // Recurse into the def body to find nested local defs
            d.body.children.foreach(c => extractFromTree(c, currentOwner))
          case d: Decl.Def => addBody(d, currentOwner, d.name.value, isAbstract = true)
          case d: Decl.Val => patVarNames(d.pats).foreach(n => addBody(d, currentOwner, n, isAbstract = true))
          case d: Decl.Var => patVarNames(d.pats).foreach(n => addBody(d, currentOwner, n, isAbstract = true))
          case d: Decl.Type => addBody(d, currentOwner, d.name.value, isAbstract = true)
          case d: Defn.Val => patVarNames(d.pats).foreach(n => addBody(d, currentOwner, n))
          case d: Defn.Var => patVarNames(d.pats).foreach(n => addBody(d, currentOwner, n))
          case d: Defn.Type => addBody(d, currentOwner, d.name.value)
          case TypeDefn(name, templ) =>
            addBody(t, currentOwner, name)
            templ.body.stats.foreach(s => extractFromTree(s, name))
          case app: Term.Apply =>
            classifyTestCall(app) match {
              case TestCallMatch.Literal(name, _) => addBody(app, currentOwner, name)
              case _ =>
            }
            app.children.foreach(c => extractFromTree(c, currentOwner))
          case infix: Term.ApplyInfix =>
            classifyTestCall(infix) match {
              case TestCallMatch.Literal(name, _) => addBody(infix, currentOwner, name)
              case _ =>
            }
            infix.children.foreach(c => extractFromTree(c, currentOwner))
          case p: Pkg =>
            p.body.stats.foreach(s => extractFromTree(s, currentOwner))
          case other =>
            // Recurse into all other nodes (Term.Block, Term.If, etc.)
            // to find nested local defs, vals, and vars
            other.children.foreach(c => extractFromTree(c, currentOwner))
        }
      }

      tree.children.foreach(c => extractFromTree(c, ""))
      buf.toList
    case _ => Nil
  }
}

// ── Scope extraction (context) ──────────────────────────────────────────────

def extractScopes(file: Path, targetLine: Int): List[ScopeInfo] = {
  if isJavaFile(file) then Nil
  else parseFile(file) match
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

private def parseJavaSource(source: String, path: Path): Option[JavaCU] =
  try
    val config = new ParserConfiguration()
    config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
    val parser = new JP(config)
    val result = parser.parse(source)
    if result.isSuccessful then Some(result.getResult.get())
    else None
  catch
    case _: Exception | _: Error => None

private def parseJavaFile(path: Path): Option[JavaCU] =
  readSource(path).flatMap(source => parseJavaSource(source, path))

/** 1-based start line of a JavaParser node (0 when unknown). */
private def javaLine(node: JavaNode): Int =
  node.getBegin.map(_.line).orElse(0)

/** "name: Type, name: Type" rendering of a Java parameter list. */
private def javaParams(params: NodeList[Parameter]): String =
  val buf = mutable.ListBuffer.empty[String]
  params.forEach(p => buf += s"${p.getNameAsString}: ${p.getType.asString()}")
  buf.mkString(", ")

/** Scala-style `def` signature for a Java method — the one shape used by both
  * file-symbol extraction and member extraction. */
private def javaMethodSig(m: MethodDeclaration): String =
  s"def ${m.getNameAsString}(${javaParams(m.getParameters)}): ${m.getType.asString()}"

/** Kind + Scala-style signature for one declarator of a Java field. */
private def javaFieldInfo(f: FieldDeclaration, v: VariableDeclarator): (kind: SymbolKind, sig: String) =
  val prefix = if f.isFinal then "val" else "var"
  val kind = if f.isFinal then SymbolKind.Val else SymbolKind.Var
  (kind = kind, sig = s"$prefix ${v.getNameAsString}: ${f.getCommonType.asString()}")

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
  val buf = mutable.ListBuffer.empty[String]
  td match
    case cd: ClassOrInterfaceDeclaration => cd.getTypeParameters.forEach(tp => buf += tp.getNameAsString)
    case rd: RecordDeclaration => rd.getTypeParameters.forEach(tp => buf += tp.getNameAsString)
    case _ => ()
  buf.toList

private def javaKindAndSig(td: TypeDeclaration[?]): (kind: SymbolKind, sig: String) =
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
      (SymbolKind.Class, s"record $name(${javaParams(rd.getParameters)})$ext")
    case _ =>
      (SymbolKind.Class, s"class $name$tps$ext")

// ── Java symbol extraction (JavaParser-based) ──────────────────────────────

private def extractJavaSymbols(file: Path): (symbols: List[SymbolInfo], bloom: Option[BloomFilter[CharSequence]], imports: List[String], aliases: Map[String, String], parseFailed: Boolean) = {
  readSource(file) match {
    case None => (Nil, None, Nil, Map.empty, true)
    case Some(source) =>
      val bloom = buildBloomFilterFromSource(source)
      parseJavaSource(source, file) match {
        case None => (Nil, Some(bloom), Nil, Map.empty, true)
        case Some(cu) =>
          val (symbols, imports) = javaSymbolsFromCu(cu, file)
          (symbols, Some(bloom), imports, Map.empty, false)
      }
  }
}

private def javaSymbolsFromCu(cu: JavaCU, file: Path): (symbols: List[SymbolInfo], imports: List[String]) = {
  val buf = mutable.ListBuffer.empty[SymbolInfo]
  val importBuf = mutable.ListBuffer.empty[String]

  val pkg = if cu.getPackageDeclaration.isPresent then cu.getPackageDeclaration.get().getNameAsString else ""

  cu.getImports.forEach { imp =>
    importBuf += s"import ${imp.getNameAsString}${if imp.isAsterisk then ".*" else ""}"
  }

  def visitType(td: TypeDeclaration[?]): Unit = {
    val name = td.getNameAsString
    val parents = javaParentsFromType(td)
    val annots = javaAnnotations(td)
    val (kind, sig) = javaKindAndSig(td)
    buf += SymbolInfo(name, kind, file, javaLine(td), pkg, parents, Nil, sig, annots)

    // Extract methods
    td.getMethods.forEach { m =>
      buf += SymbolInfo(m.getNameAsString, SymbolKind.Def, file, javaLine(m), pkg, Nil, Nil, javaMethodSig(m), javaAnnotations(m))
    }

    // Extract fields
    td.getFields.forEach { f =>
      f.getVariables.forEach { v =>
        val (fKind, fSig) = javaFieldInfo(f, v)
        buf += SymbolInfo(v.getNameAsString, fKind, file, javaLine(f), pkg, Nil, Nil, fSig, javaAnnotations(f))
      }
    }

    // Recurse into nested types
    td.getMembers.forEach {
      case nested: TypeDeclaration[?] => visitType(nested)
      case _ =>
    }
  }

  cu.getTypes.forEach(td => visitType(td))
  (symbols = buf.toList, imports = importBuf.toList)
}

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
            val annots = javaAnnotations(m)
            val isOverride = annots.contains("Override")
            buf += MemberInfo(m.getNameAsString, SymbolKind.Def, javaLine(m), javaMethodSig(m), annots, isOverride)
          }

          // Fields
          td.getFields.forEach { f =>
            f.getVariables.forEach { v =>
              val (fKind, sig) = javaFieldInfo(f, v)
              buf += MemberInfo(v.getNameAsString, fKind, javaLine(f), sig, javaAnnotations(f))
            }
          }

          // Constructors
          td.getConstructors.forEach { c =>
            val sig = s"def <init>(${javaParams(c.getParameters)})"
            buf += MemberInfo("<init>", SymbolKind.Def, javaLine(c), sig, javaAnnotations(c))
          }

          // Nested types
          td.getMembers.forEach {
            case nested: TypeDeclaration[?] =>
              val (kind, sig) = javaKindAndSig(nested)
              buf += MemberInfo(nested.getNameAsString, kind, javaLine(nested), sig, javaAnnotations(nested))
            case _ =>
          }

          // Enum constants
          td match
            case ed: EnumDeclaration =>
              ed.getEntries.forEach { entry =>
                buf += MemberInfo(entry.getNameAsString, SymbolKind.Val, javaLine(entry), s"val ${entry.getNameAsString}")
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
  (readSourceLines(file), parseJavaFile(file)) match
    case (None, _) | (_, None) => Nil
    case (Some(sourceLines), Some(cu)) =>
      val buf = mutable.ListBuffer.empty[BodyInfo]

      // Slice the node's source span if it defines `symbolName` under an accepted owner
      def addBody(node: JavaNode, owner: String, name: String): Unit =
        if name == symbolName && (ownerName.isEmpty || ownerName.contains(owner)) then
          val sl = node.getBegin.map(_.line).orElse(1)
          val el = node.getEnd.map(_.line).orElse(sl)
          val body = ((sl - 1) until el).filter(_ < sourceLines.length).map(sourceLines(_)).mkString("\n")
          buf += BodyInfo(owner, name, body, sl, el)

      def extractFromType(td: TypeDeclaration[?], currentOwner: String): Unit =
        val typeName = td.getNameAsString

        addBody(td, currentOwner, typeName)
        td.getMethods.forEach(m => addBody(m, typeName, m.getNameAsString))
        td.getFields.forEach { f =>
          f.getVariables.forEach(v => addBody(f, typeName, v.getNameAsString))
        }

        // Nested types — recurse
        td.getMembers.forEach {
          case nested: TypeDeclaration[?] =>
            extractFromType(nested, typeName)
          case _ =>
        }

      cu.getTypes.forEach(td => extractFromType(td, ""))
      buf.toList
