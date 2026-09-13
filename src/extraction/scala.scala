package scalex.extraction

import scala.meta.Type as MetaType

import scalex.*

import scala.meta.*
import scala.collection.mutable
import java.nio.file.Path
import com.google.common.hash.BloomFilter

/** Names bound by `Pat.Var` patterns (ignores tuple/extractor patterns). */
private[scalex] def patVarNames(pats: List[Pat]): List[String] =
  pats.collect { case Pat.Var(name) => name.value }

/** Matches any type-introducing definition that carries a template body. */
private[scalex] object TypeDefn {
  def unapply(t: Tree): Option[(name: String, templ: Template)] = t match {
    case d: Defn.Class  => Some((name = d.name.value, templ = d.templ))
    case d: Defn.Trait  => Some((name = d.name.value, templ = d.templ))
    case d: Defn.Object => Some((name = d.name.value, templ = d.templ))
    case d: Defn.Enum   => Some((name = d.name.value, templ = d.templ))
    case _              => None
  }
}

private[scalex] def extractParents(templ: Template): (parents: List[String], typeParamParents: List[String]) = {
  val directParents = mutable.ListBuffer.empty[String]
  val tpParents = mutable.ListBuffer.empty[String]

  def extractTypeArgNames(tpe: MetaType): List[String] = tpe match {
    case Type.Name(name)                      => List(name)
    case Type.Select(_, Type.Name(name))      => List(name)
    case Type.Apply.After_4_6_0(_, argClause) =>
      // Skip the type constructor (Map, List, Option etc.) — only collect leaf type args
      argClause.values.flatMap(extractTypeArgNames)
    case _ => Nil
  }

  templ.inits.foreach { init =>
    init.tpe match {
      case Type.Name(name)                                    => directParents += name
      case Type.Select(_, Type.Name(name))                    => directParents += name
      case Type.Apply.After_4_6_0(Type.Name(name), argClause) =>
        directParents += name
        argClause.values.flatMap(extractTypeArgNames).foreach(tpParents += _)
      case Type.Apply.After_4_6_0(Type.Select(_, Type.Name(name)), argClause) =>
        directParents += name
        argClause.values.flatMap(extractTypeArgNames).foreach(tpParents += _)
      case _ =>
    }
  }

  val directSet = directParents.map(_.toLowerCase).toSet
  val filtered = tpParents.toList.distinct
    .filterNot(n => directSet.contains(n.toLowerCase))
    .filterNot(n => n.length == 1 && n.head.isUpper) // filter T, A, F etc.
  (directParents.toList, filtered)
}

private[scalex] def buildSignature(
    name: String,
    kind: String,
    parents: List[String],
    tparams: List[String] = Nil
): String = {
  val tps = if (tparams.nonEmpty) tparams.mkString("[", ", ", "]") else ""
  val ext = if (parents.nonEmpty) s" extends ${parents.mkString(" with ")}" else ""
  s"$kind $name$tps$ext"
}

private[scalex] def formatParamClauses(paramClauses: Seq[Term.ParamClause]): String =
  paramClauses
    .map(_.values.map(p => s"${p.name.value}: ${p.decltpe.map(_.toString()).getOrElse("?")}").mkString(", "))
    .mkString("(", ")(", ")")

private[scalex] def extractAnnotations(mods: List[Mod]): List[String] =
  mods.collect { case Mod.Annot(init) =>
    init.tpe match {
      case Type.Name(name)                 => name
      case Type.Select(_, Type.Name(name)) => name
      case _                               => init.tpe.toString()
    }
  }

def extractImports(tree: Tree): (imports: List[String], aliases: Map[String, String]) = {
  val buf = mutable.ListBuffer.empty[String]
  val aliases = mutable.Map.empty[String, String]
  def visit(t: Tree): Unit = {
    t match {
      case i: Import =>
        buf += i.toString()
        i.importers.foreach { importer =>
          importer.importees.foreach {
            case r: Importee.Rename => aliases(r.name.value) = r.rename.value
            case _                  =>
          }
        }
      case _ =>
    }
    t.children.foreach(visit)
  }
  visit(tree)
  (buf.toList, aliases.toMap)
}

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
          case _      =>
        }
      collectImports(tree.stats)
      if (importRanges.isEmpty) None
      else {
        val importText = importRanges
          .flatMap { (sl, el) =>
            (sl to el).filter(_ < lines.length).map(lines(_))
          }
          .mkString("\n")
        Some(importText)
      }
    case _ => None
  }
}

// ── Shared raw symbol extraction ─────────────────────────────────────────────

case class RawSymbol(
    name: String,
    kind: SymbolKind,
    line: Int,
    parents: List[String] = Nil,
    typeParamParents: List[String] = Nil,
    signature: String = "",
    annotations: List[String] = Nil
)

def extractRawSymbols(
    tree: Tree,
    onSymbol: (RawSymbol, Tree) => Unit = (_, _) => ()
): (symbols: List[RawSymbol], packageName: String) = {
  val pkg = tree.children.collectFirst { case p: Pkg => p.ref.toString() }.getOrElse("")
  val buf = mutable.ListBuffer.empty[RawSymbol]

  def add(t: Tree, symbol: RawSymbol): Unit = {
    buf += symbol
    onSymbol(symbol, t)
  }

  // One path for every template-carrying definition (class/trait/object/enum)
  def addTypeDefn(
      t: Tree,
      name: String,
      kind: SymbolKind,
      keyword: String,
      templ: Template,
      tparams: List[String],
      mods: List[Mod]
  ): Unit = {
    val (parents, tpParents) = extractParents(templ)
    val sig = buildSignature(name, keyword, parents, tparams)
    add(t, RawSymbol(name, kind, t.pos.startLine + 1, parents, tpParents, sig, extractAnnotations(mods)))
  }

  def visit(t: Tree): Unit = t match {
    case d: Defn.Class =>
      addTypeDefn(
        d,
        d.name.value,
        SymbolKind.Class,
        "class",
        d.templ,
        d.tparamClause.values.map(_.name.value).toList,
        d.mods
      )
    case d: Defn.Trait =>
      addTypeDefn(
        d,
        d.name.value,
        SymbolKind.Trait,
        "trait",
        d.templ,
        d.tparamClause.values.map(_.name.value).toList,
        d.mods
      )
    case d: Defn.Object =>
      addTypeDefn(d, d.name.value, SymbolKind.Object, "object", d.templ, Nil, d.mods)
    case d: Pkg.Object =>
      addTypeDefn(d, d.name.value, SymbolKind.Object, "object", d.templ, Nil, Nil)
    case d: Defn.Enum =>
      addTypeDefn(
        d,
        d.name.value,
        SymbolKind.Enum,
        "enum",
        d.templ,
        d.tparamClause.values.map(_.name.value).toList,
        d.mods
      )
    case d: Defn.Given =>
      if (d.name.value.nonEmpty) {
        val annots = extractAnnotations(d.mods)
        add(
          t,
          RawSymbol(
            d.name.value,
            SymbolKind.Given,
            d.pos.startLine + 1,
            Nil,
            Nil,
            s"given ${d.name.value}",
            annots
          )
        )
      }
    case d: Defn.GivenAlias =>
      if (d.name.value.nonEmpty) {
        val sig = s"given ${d.name.value}: ${d.decltpe.toString()}"
        val annots = extractAnnotations(d.mods)
        add(t, RawSymbol(d.name.value, SymbolKind.Given, d.pos.startLine + 1, Nil, Nil, sig, annots))
      }
    case d: Defn.Type =>
      val sig = s"type ${d.name.value} = ${d.body.toString().take(60)}"
      val annots = extractAnnotations(d.mods)
      add(t, RawSymbol(d.name.value, SymbolKind.Type, d.pos.startLine + 1, Nil, Nil, sig, annots))
    case d: Defn.Def =>
      val params = formatParamClauses(d.paramClauses)
      val ret = d.decltpe.map(t => s": ${t.toString()}").getOrElse("")
      val sig = s"def ${d.name.value}$params$ret"
      val annots = extractAnnotations(d.mods)
      add(t, RawSymbol(d.name.value, SymbolKind.Def, d.pos.startLine + 1, Nil, Nil, sig, annots))
    case d: Defn.Val =>
      val annots = extractAnnotations(d.mods)
      patVarNames(d.pats).foreach { name =>
        val tpe = d.decltpe.map(t => s": ${t.toString()}").getOrElse("")
        add(t, RawSymbol(name, SymbolKind.Val, d.pos.startLine + 1, Nil, Nil, s"val $name$tpe", annots))
      }
    case d: Defn.ExtensionGroup =>
      val recv = d.paramClauses.headOption
        .flatMap(_.values.headOption)
        .map(p => s"(${p.name.value}: ${p.decltpe.map(_.toString()).getOrElse("?")})")
        .getOrElse("")
      add(t, RawSymbol("<extension>", SymbolKind.Extension, d.pos.startLine + 1, Nil, Nil, s"extension $recv"))
    case _ =>
  }

  def traverse(t: Tree): Unit = {
    visit(t)
    t match {
      case _: Defn.Def | _: Defn.Val | _: Defn.Var | _: Defn.Given | _: Defn.GivenAlias => ()
      case _                                                                            => t.children.foreach(traverse)
    }
  }

  traverse(tree)
  (buf.toList, pkg)
}

private[scalex] def extractScalaSymbols(file: Path): (
    symbols: List[SymbolInfo],
    bloom: Option[BloomFilter[CharSequence]],
    imports: List[String],
    aliases: Map[String, String],
    parseFailed: Boolean
) = {
  readSource(file) match {
    case None         => (Nil, None, Nil, Map.empty, true)
    case Some(source) =>
      val bloom = buildBloomFilterFromSource(source)
      parseSource(source, file.toString) match {
        case None       => (Nil, Some(bloom), Nil, Map.empty, true)
        case Some(tree) =>
          val (imports, aliases) = extractImports(tree)
          val (rawSymbols, pkg) = extractRawSymbols(tree)
          val symbols = rawSymbols.map(r =>
            SymbolInfo(r.name, r.kind, file, r.line, pkg, r.parents, r.typeParamParents, r.signature, r.annotations)
          )
          (symbols, Some(bloom), imports, aliases, false)
      }
  }
}

// ── Member extraction ───────────────────────────────────────────────────────

/** Where a member came from. Lets callers include or exclude constructor params and abstract declarations without
  * re-implementing the traversal.
  */
enum MemberOrigin {
  case Definition, AbstractDecl, CtorParam
}

case class ExtractedMember(member: MemberInfo, startLine: Int, endLine: Int, origin: MemberOrigin)

/** Single traversal behind extractMembers and extractMembersWithSpans: finds the type named `symbolName` and extracts
  * its constructor params and template members.
  */
private[scalex] def extractScalaMemberTree(
    file: Path,
    symbolName: String,
    filterKind: Option[SymbolKind]
): List[ExtractedMember] =
  parseFile(file) match {
    case None       => Nil
    case Some(tree) =>
      val buf = mutable.ListBuffer.empty[ExtractedMember]

      def add(t: Tree, m: MemberInfo, origin: MemberOrigin = MemberOrigin.Definition): Unit =
        buf += ExtractedMember(m, t.pos.startLine + 1, t.pos.endLine + 1, origin)

      def declaredType(decltpe: Option[MetaType]): String =
        decltpe.map(t => s": ${t.toString()}").getOrElse("")

      def extractFromTemplate(templ: Template): Unit =
        templ.body.stats.foreach {
          case d: Defn.Def =>
            val sig = s"def ${d.name.value}${formatParamClauses(d.paramClauses)}${declaredType(d.decltpe)}"
            add(d, MemberInfo(d.name.value, SymbolKind.Def, d.pos.startLine + 1, sig, extractAnnotations(d.mods)))
          case d: Defn.Val =>
            val annots = extractAnnotations(d.mods)
            patVarNames(d.pats).foreach { name =>
              add(
                d,
                MemberInfo(name, SymbolKind.Val, d.pos.startLine + 1, s"val $name${declaredType(d.decltpe)}", annots)
              )
            }
          case d: Defn.Var =>
            val annots = extractAnnotations(d.mods)
            patVarNames(d.pats).foreach { name =>
              add(
                d,
                MemberInfo(name, SymbolKind.Var, d.pos.startLine + 1, s"var $name${declaredType(d.decltpe)}", annots)
              )
            }
          case d: Defn.Type =>
            add(
              d,
              MemberInfo(
                d.name.value,
                SymbolKind.Type,
                d.pos.startLine + 1,
                s"type ${d.name.value} = ${d.body.toString().take(60)}",
                extractAnnotations(d.mods)
              )
            )
          case d: Decl.Def =>
            val sig = s"def ${d.name.value}${formatParamClauses(d.paramClauses)}: ${d.decltpe.toString()}"
            add(
              d,
              MemberInfo(d.name.value, SymbolKind.Def, d.pos.startLine + 1, sig, extractAnnotations(d.mods)),
              MemberOrigin.AbstractDecl
            )
          case d: Decl.Val =>
            val annots = extractAnnotations(d.mods)
            patVarNames(d.pats).foreach { name =>
              add(
                d,
                MemberInfo(name, SymbolKind.Val, d.pos.startLine + 1, s"val $name: ${d.decltpe.toString()}", annots),
                MemberOrigin.AbstractDecl
              )
            }
          case d: Decl.Type =>
            add(
              d,
              MemberInfo(
                d.name.value,
                SymbolKind.Type,
                d.pos.startLine + 1,
                s"type ${d.name.value}",
                extractAnnotations(d.mods)
              ),
              MemberOrigin.AbstractDecl
            )
          case d: Defn.Class =>
            add(
              d,
              MemberInfo(
                d.name.value,
                SymbolKind.Class,
                d.pos.startLine + 1,
                s"class ${d.name.value}",
                extractAnnotations(d.mods)
              )
            )
          case d: Defn.Trait =>
            add(
              d,
              MemberInfo(
                d.name.value,
                SymbolKind.Trait,
                d.pos.startLine + 1,
                s"trait ${d.name.value}",
                extractAnnotations(d.mods)
              )
            )
          case d: Defn.Object =>
            add(
              d,
              MemberInfo(
                d.name.value,
                SymbolKind.Object,
                d.pos.startLine + 1,
                s"object ${d.name.value}",
                extractAnnotations(d.mods)
              )
            )
          case d: Defn.Enum =>
            add(
              d,
              MemberInfo(
                d.name.value,
                SymbolKind.Enum,
                d.pos.startLine + 1,
                s"enum ${d.name.value}",
                extractAnnotations(d.mods)
              )
            )
          case _ =>
        }

      // Case class params are public vals; regular class params only if marked val/var
      def addCtorParams(d: Defn.Class): Unit = {
        val isCaseClass = d.mods.exists(_.isInstanceOf[Mod.Case])
        d.ctor.paramClauses.foreach { clause =>
          clause.values.foreach { p =>
            val isVal = isCaseClass || p.mods.exists(m => m.isInstanceOf[Mod.ValParam] || m.isInstanceOf[Mod.VarParam])
            if (isVal) {
              val tpe = p.decltpe.map(t => s": ${t.toString}").getOrElse("")
              val kind = if (p.mods.exists(_.isInstanceOf[Mod.VarParam])) SymbolKind.Var else SymbolKind.Val
              add(
                p,
                MemberInfo(p.name.value, kind, p.pos.startLine + 1, s"val ${p.name.value}$tpe"),
                MemberOrigin.CtorParam
              )
            }
          }
        }
      }

      def kindMatches(k: SymbolKind): Boolean = filterKind.forall(_ == k)

      def findAndExtract(t: Tree): Unit = t match {
        case d: Defn.Class if d.name.value == symbolName && kindMatches(SymbolKind.Class) =>
          addCtorParams(d)
          extractFromTemplate(d.templ)
        case d: Defn.Trait if d.name.value == symbolName && kindMatches(SymbolKind.Trait) =>
          extractFromTemplate(d.templ)
        case d: Defn.Object if d.name.value == symbolName && kindMatches(SymbolKind.Object) =>
          extractFromTemplate(d.templ)
        case d: Defn.Enum if d.name.value == symbolName && kindMatches(SymbolKind.Enum) => extractFromTemplate(d.templ)
        case _ => t.children.foreach(findAndExtract)
      }

      findAndExtract(tree)
      buf.toList
  }

private[scalex] def extractScalaBody(file: Path, symbolName: String, ownerName: Option[String]): List[BodyInfo] = {
  (readSourceLines(file), parseFile(file)) match {
    case (Some(lines), Some(tree)) =>
      val buf = mutable.ListBuffer.empty[BodyInfo]

      // Slice the node's source span if it defines `symbolName` under an accepted owner
      def addBody(t: Tree, owner: String, name: String, isAbstract: Boolean = false): Unit = {
        if (name == symbolName && (ownerName.isEmpty || ownerName.contains(owner))) {
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
          case d: Decl.Def           => addBody(d, currentOwner, d.name.value, isAbstract = true)
          case d: Decl.Val           => patVarNames(d.pats).foreach(n => addBody(d, currentOwner, n, isAbstract = true))
          case d: Decl.Var           => patVarNames(d.pats).foreach(n => addBody(d, currentOwner, n, isAbstract = true))
          case d: Decl.Type          => addBody(d, currentOwner, d.name.value, isAbstract = true)
          case d: Defn.Val           => patVarNames(d.pats).foreach(n => addBody(d, currentOwner, n))
          case d: Defn.Var           => patVarNames(d.pats).foreach(n => addBody(d, currentOwner, n))
          case d: Defn.Type          => addBody(d, currentOwner, d.name.value)
          case TypeDefn(name, templ) =>
            addBody(t, currentOwner, name)
            templ.body.stats.foreach(s => extractFromTree(s, name))
          case app: Term.Apply =>
            classifyTestCall(app) match {
              case TestCallMatch.Literal(name, _) => addBody(app, currentOwner, name)
              case _                              =>
            }
            app.children.foreach(c => extractFromTree(c, currentOwner))
          case infix: Term.ApplyInfix =>
            classifyTestCall(infix) match {
              case TestCallMatch.Literal(name, _) => addBody(infix, currentOwner, name)
              case _                              =>
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
  if (isJavaFile(file)) Nil
  else
    parseFile(file) match {
      case None       => Nil
      case Some(tree) =>
        val buf = mutable.ListBuffer.empty[ScopeInfo]

        def visit(t: Tree): Unit = {
          val startLine = t.pos.startLine + 1
          val endLine = t.pos.endLine + 1
          if (targetLine >= startLine && targetLine <= endLine) {
            t match {
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
                  case _             =>
                }
              case d: Defn.ExtensionGroup =>
                buf += ScopeInfo("<extension>", "extension", startLine)
              case _ =>
            }
            t.children.foreach(visit)
          }
        }

        visit(tree)
        buf.toList
    }
}
