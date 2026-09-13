package scalex.extraction

import scalex.*

import scala.meta.*
import scala.collection.mutable
import java.nio.file.Path

// ── Test extraction ─────────────────────────────────────────────────────

private[scalex] val testFnNames = Set("test", "it", "describe")

private[scalex] enum TestCallMatch {
  case Literal(name: String, line: Int)
  case Dynamic(line: Int)
  case NotATest
}

private[scalex] def classifyTestCall(t: Tree): TestCallMatch =
  t match {
    case app: Term.Apply =>
      app.fun match {
        // test("name")(body) — double apply
        case innerApp: Term.Apply =>
          innerApp.fun match {
            case fn: Term.Name if testFnNames.contains(fn.value) =>
              innerApp.argClause.values.collectFirst { case lit: Lit.String => lit } match {
                case Some(lit) => TestCallMatch.Literal(lit.value, app.pos.startLine + 1)
                case None      => TestCallMatch.Dynamic(app.pos.startLine + 1)
              }
            case _ => TestCallMatch.NotATest
          }
        // test("name") { body } — single apply with name + block in same arglist
        case fn: Term.Name if testFnNames.contains(fn.value) =>
          app.argClause.values.collectFirst { case lit: Lit.String => lit } match {
            case Some(lit) => TestCallMatch.Literal(lit.value, app.pos.startLine + 1)
            case None      => TestCallMatch.Dynamic(app.pos.startLine + 1)
          }
        case _ => TestCallMatch.NotATest
      }
    case infix: Term.ApplyInfix =>
      if (infix.op.value == "in" || infix.op.value == ">>")
        infix.lhs match {
          case lit: Lit.String => TestCallMatch.Literal(lit.value, infix.pos.startLine + 1)
          case _               => TestCallMatch.Dynamic(infix.pos.startLine + 1)
        }
      else TestCallMatch.NotATest
    case _ => TestCallMatch.NotATest
  }

def extractTests(file: Path): List[TestSuiteInfo] = {
  if (isJavaFile(file)) Nil
  else
    parseFile(file) match {
      case None       => Nil
      case Some(tree) =>
        val suites = mutable.ListBuffer.empty[TestSuiteInfo]

        def collectTests(stats: List[Tree]): (tests: List[TestCaseInfo], dynamicSites: Int) = {
          val tests = mutable.ListBuffer.empty[TestCaseInfo]
          var dynamicCount = 0
          def visit(t: Tree): Unit = {
            classifyTestCall(t) match {
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
          }
          stats.foreach(visit)
          (tests = tests.toList, dynamicSites = dynamicCount)
        }

        def findSuites(t: Tree): Unit = {
          t match {
            case d: Defn.Class =>
              val (tests, dynSites) = collectTests(d.templ.body.stats)
              if (tests.nonEmpty || dynSites > 0)
                suites += TestSuiteInfo(d.name.value, file, d.pos.startLine + 1, tests, dynSites)
            case d: Defn.Object =>
              val (tests, dynSites) = collectTests(d.templ.body.stats)
              if (tests.nonEmpty || dynSites > 0)
                suites += TestSuiteInfo(d.name.value, file, d.pos.startLine + 1, tests, dynSites)
            case _ =>
          }
          t.children.foreach(findSuites)
        }

        findSuites(tree)
        suites.toList
    }
}
