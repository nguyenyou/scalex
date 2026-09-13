package scalex.extraction

import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations

import scalex.*

import scala.collection.mutable
import java.nio.file.Path
import com.google.common.hash.BloomFilter
import com.github.javaparser.{JavaParser as JP, ParserConfiguration}
import com.github.javaparser.ast.{CompilationUnit as JavaCU, Node as JavaNode, NodeList}
import com.github.javaparser.ast.body.*

// ── Java parsing helper ──────────────────────────────────────────────────────

private[scalex] def parseJavaSource(source: String, path: Path): Option[JavaCU] =
  try {
    val config = ParserConfiguration()
    config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
    val parser = JP(config)
    val result = parser.parse(source)
    if (result.isSuccessful) Some(result.getResult.get())
    else None
  } catch {
    case _: Exception | _: Error => None
  }

private[scalex] def parseJavaFile(path: Path): Option[JavaCU] =
  readSource(path).flatMap(source => parseJavaSource(source, path))

/** 1-based start line of a JavaParser node (0 when unknown). */
private[scalex] def javaLine(node: JavaNode): Int =
  node.getBegin.map(_.line).orElse(0)

/** "name: Type, name: Type" rendering of a Java parameter list. */
private[scalex] def javaParams(params: NodeList[Parameter]): String = {
  val buf = mutable.ListBuffer.empty[String]
  params.forEach(p => buf += s"${p.getNameAsString}: ${p.getType.asString()}")
  buf.mkString(", ")
}

/** Scala-style `def` signature for a Java method — the one shape used by both file-symbol extraction and member
  * extraction.
  */
private[scalex] def javaMethodSig(m: MethodDeclaration): String =
  s"def ${m.getNameAsString}(${javaParams(m.getParameters)}): ${m.getType.asString()}"

/** Kind + Scala-style signature for one declarator of a Java field. */
private[scalex] def javaFieldInfo(f: FieldDeclaration, v: VariableDeclarator): (kind: SymbolKind, sig: String) = {
  val prefix = if (f.isFinal) "val" else "var"
  val kind = if (f.isFinal) SymbolKind.Val else SymbolKind.Var
  (kind = kind, sig = s"$prefix ${v.getNameAsString}: ${f.getCommonType.asString()}")
}

private[scalex] def javaParentsFromType(td: TypeDeclaration[?]): List[String] = {
  val buf = mutable.ListBuffer.empty[String]
  td match {
    case cd: ClassOrInterfaceDeclaration =>
      cd.getExtendedTypes.forEach(t => buf += t.getNameAsString)
      cd.getImplementedTypes.forEach(t => buf += t.getNameAsString)
    case ed: EnumDeclaration =>
      ed.getImplementedTypes.forEach(t => buf += t.getNameAsString)
    case rd: RecordDeclaration =>
      rd.getImplementedTypes.forEach(t => buf += t.getNameAsString)
    case _ =>
  }
  buf.toList
}

private[scalex] def javaAnnotations(decl: NodeWithAnnotations[?]): List[String] = {
  val buf = mutable.ListBuffer.empty[String]
  decl.getAnnotations.forEach(a => buf += a.getNameAsString)
  buf.toList
}

private[scalex] def javaTypeParams(td: TypeDeclaration[?]): List[String] = {
  val buf = mutable.ListBuffer.empty[String]
  td match {
    case cd: ClassOrInterfaceDeclaration => cd.getTypeParameters.forEach(tp => buf += tp.getNameAsString)
    case rd: RecordDeclaration           => rd.getTypeParameters.forEach(tp => buf += tp.getNameAsString)
    case _                               => ()
  }
  buf.toList
}

private[scalex] def javaKindAndSig(td: TypeDeclaration[?]): (kind: SymbolKind, sig: String) = {
  val name = td.getNameAsString
  val parents = javaParentsFromType(td)
  val tparams = javaTypeParams(td)
  val tps = if (tparams.nonEmpty) tparams.mkString("[", ", ", "]") else ""
  val ext = if (parents.nonEmpty) s" extends ${parents.mkString(", ")}" else ""
  td match {
    case _: EnumDeclaration =>
      (SymbolKind.Enum, s"enum $name$ext")
    case cd: ClassOrInterfaceDeclaration if cd.isInterface =>
      (SymbolKind.Trait, s"interface $name$tps$ext")
    case rd: RecordDeclaration =>
      (SymbolKind.Class, s"record $name(${javaParams(rd.getParameters)})$ext")
    case _ =>
      (SymbolKind.Class, s"class $name$tps$ext")
  }
}

// ── Java symbol extraction (JavaParser-based) ──────────────────────────────

private[scalex] def extractJavaSymbols(file: Path): (
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
      parseJavaSource(source, file) match {
        case None     => (Nil, Some(bloom), Nil, Map.empty, true)
        case Some(cu) =>
          val (symbols, imports) = javaSymbolsFromCu(cu, file)
          (symbols, Some(bloom), imports, Map.empty, false)
      }
  }
}

private[scalex] def javaSymbolsFromCu(cu: JavaCU, file: Path): (symbols: List[SymbolInfo], imports: List[String]) = {
  val buf = mutable.ListBuffer.empty[SymbolInfo]
  val importBuf = mutable.ListBuffer.empty[String]

  val pkg = if (cu.getPackageDeclaration.isPresent) cu.getPackageDeclaration.get().getNameAsString else ""

  cu.getImports.forEach { imp =>
    importBuf += s"import ${imp.getNameAsString}${if (imp.isAsterisk) ".*" else ""}"
  }

  def visitType(td: TypeDeclaration[?]): Unit = {
    val name = td.getNameAsString
    val parents = javaParentsFromType(td)
    val annots = javaAnnotations(td)
    val (kind, sig) = javaKindAndSig(td)
    buf += SymbolInfo(name, kind, file, javaLine(td), pkg, parents, Nil, sig, annots)

    // Extract methods
    td.getMethods.forEach { m =>
      buf += SymbolInfo(
        m.getNameAsString,
        SymbolKind.Def,
        file,
        javaLine(m),
        pkg,
        Nil,
        Nil,
        javaMethodSig(m),
        javaAnnotations(m)
      )
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
      case _                          =>
    }
  }

  cu.getTypes.forEach(td => visitType(td))
  (symbols = buf.toList, imports = importBuf.toList)
}

// ── Java member extraction ──────────────────────────────────────────────────

private[scalex] def extractJavaMembers(file: Path, symbolName: String): List[MemberInfo] =
  parseJavaFile(file) match {
    case None     => Nil
    case Some(cu) =>
      val buf = mutable.ListBuffer.empty[MemberInfo]

      def findType(td: TypeDeclaration[?]): Unit =
        if (td.getNameAsString == symbolName) {
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
          td match {
            case ed: EnumDeclaration =>
              ed.getEntries.forEach { entry =>
                buf += MemberInfo(
                  entry.getNameAsString,
                  SymbolKind.Val,
                  javaLine(entry),
                  s"val ${entry.getNameAsString}"
                )
              }
            case _ =>
          }
        } else
          // Recurse into nested types
          td.getMembers.forEach {
            case nested: TypeDeclaration[?] => findType(nested)
            case _                          =>
          }

      cu.getTypes.forEach(td => findType(td))
      buf.toList
  }

// ── Java body extraction ────────────────────────────────────────────────────

private[scalex] def extractJavaBody(file: Path, symbolName: String, ownerName: Option[String]): List[BodyInfo] =
  (readSourceLines(file), parseJavaFile(file)) match {
    case (None, _) | (_, None)         => Nil
    case (Some(sourceLines), Some(cu)) =>
      val buf = mutable.ListBuffer.empty[BodyInfo]

      // Slice the node's source span if it defines `symbolName` under an accepted owner
      def addBody(node: JavaNode, owner: String, name: String): Unit =
        if (name == symbolName && (ownerName.isEmpty || ownerName.contains(owner))) {
          val sl = node.getBegin.map(_.line).orElse(1)
          val el = node.getEnd.map(_.line).orElse(sl)
          val body = ((sl - 1) until el).filter(_ < sourceLines.length).map(sourceLines(_)).mkString("\n")
          buf += BodyInfo(owner, name, body, sl, el)
        }

      def extractFromType(td: TypeDeclaration[?], currentOwner: String): Unit = {
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
      }

      cu.getTypes.forEach(td => extractFromType(td, ""))
      buf.toList
  }
