import munit.FunSuite
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Base class for scalex-semanticdb tests.
  *
  * Creates a temp workspace with Scala source files, compiles them with
  * -Xsemanticdb to produce .semanticdb data, and builds a SemIndex.
  *
  * Subclasses get `workspace`, `semanticdbDir`, and `index` ready to query.
  */
abstract class SemTestBase extends FunSuite:

  var workspace: Path = scala.compiletime.uninitialized
  var semanticdbDir: Path = scala.compiletime.uninitialized
  var index: SemIndex = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    workspace = Files.createTempDirectory("sdbx-test")
    val srcDir = workspace.resolve("src")
    Files.createDirectories(srcDir)

    // Write test source files
    writeSources(srcDir)

    // Compile with SemanticDB enabled
    semanticdbDir = workspace.resolve("out")
    Files.createDirectories(semanticdbDir)
    compileWithSemanticdb(srcDir, semanticdbDir)

    // Build the index
    index = SemIndex(workspace)
    index.rebuild()

  override def afterAll(): Unit =
    deleteRecursive(workspace)

  /** Override to provide custom source files. Default writes a rich test fixture. */
  protected def writeSources(srcDir: Path): Unit =
    writeDefaultFixtures(srcDir)

  // ── Helpers ────────────────────────────────────────────────────────────

  protected def writeFile(dir: Path, relativePath: String, content: String): Unit =
    val file = dir.resolve(relativePath)
    Files.createDirectories(file.getParent)
    Files.writeString(file, content)

  protected def captureOut(body: => Unit): String =
    val out = java.io.ByteArrayOutputStream()
    Console.withOut(out) { Console.withErr(java.io.ByteArrayOutputStream()) { body } }
    out.toString

  protected def captureErr(body: => Unit): String =
    val err = java.io.ByteArrayOutputStream()
    Console.withErr(err) { body }
    err.toString

  protected def makeCtx(
    limit: Int = 100,
    verbose: Boolean = false,
    kindFilter: Option[String] = None,
    roleFilter: Option[String] = None,
    depth: Option[Int] = None,
    noAccessors: Boolean = false,
    excludePatterns: List[String] = Nil,
    smart: Boolean = false,
    inScope: Option[String] = None,
    excludeTest: Boolean = false,
    excludePkgPatterns: List[String] = Nil,
    sourceOnly: Boolean = false,
  ): SemCommandContext =
    SemCommandContext(
      index = index,
      workspace = workspace,
      limit = limit,
      verbose = verbose,
      kindFilter = kindFilter,
      roleFilter = roleFilter,
      depth = depth,
      noAccessors = noAccessors,
      excludePatterns = excludePatterns,
      smart = smart,
      inScope = inScope,
      excludeTest = excludeTest,
      excludePkgPatterns = excludePkgPatterns,
      sourceOnly = sourceOnly,
    )

  private def deleteRecursive(path: Path): Unit =
    if Files.isDirectory(path) then
      Files.list(path).iterator().asScala.foreach(deleteRecursive)
    Files.deleteIfExists(path)

  // ── Compilation ────────────────────────────────────────────────────────

  private def compileWithSemanticdb(srcDir: Path, outDir: Path): Unit =
    val cmd = List(
      "scala-cli", "compile",
      "--scala", "3.8.2",
      "--scalac-option", "-Xsemanticdb",
      "--scalac-option", "-semanticdb-target",
      "--scalac-option", outDir.toString,
      srcDir.toString,
    )

    val pb = ProcessBuilder(cmd*)
    pb.directory(workspace.toFile)
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val output = String(proc.getInputStream.readAllBytes())
    val exit = proc.waitFor()
    assert(exit == 0, s"Compilation failed:\n$output\nCommand: ${cmd.mkString(" ")}")

  // ── Default test fixtures ──────────────────────────────────────────────

  private def writeDefaultFixtures(srcDir: Path): Unit =
    // Core domain types
    writeFile(srcDir, "example/Animal.scala",
      """package example
        |
        |trait Animal:
        |  def name: String
        |  def sound: String
        |  def greet(): String = s"I'm $name and I say $sound"
        |""".stripMargin)

    writeFile(srcDir, "example/Dog.scala",
      """package example
        |
        |class Dog(val name: String) extends Animal:
        |  def sound: String = "Woof"
        |  def fetch(item: String): String = s"$name fetches $item"
        |  def tricks(): List[String] = List("sit", "shake", "roll over")
        |""".stripMargin)

    writeFile(srcDir, "example/Cat.scala",
      """package example
        |
        |class Cat(val name: String) extends Animal:
        |  def sound: String = "Meow"
        |  def purr(): Unit = ()
        |""".stripMargin)

    // Service layer
    writeFile(srcDir, "example/AnimalService.scala",
      """package example
        |
        |trait AnimalService:
        |  def register(animal: Animal): Unit
        |  def findByName(name: String): Option[Animal]
        |  def listAll(): List[Animal]
        |
        |class AnimalServiceImpl extends AnimalService:
        |  private val animals = scala.collection.mutable.ListBuffer.empty[Animal]
        |
        |  def register(animal: Animal): Unit =
        |    animals += animal
        |
        |  def findByName(name: String): Option[Animal] =
        |    animals.find(_.name == name)
        |
        |  def listAll(): List[Animal] =
        |    animals.toList
        |""".stripMargin)

    // Entry point with cross-cutting calls
    writeFile(srcDir, "example/Main.scala",
      """package example
        |
        |object Main:
        |  def main(args: Array[String]): Unit =
        |    val service = AnimalServiceImpl()
        |    val dog = Dog("Rex")
        |    val cat = Cat("Whiskers")
        |    service.register(dog)
        |    service.register(cat)
        |    val all = service.listAll()
        |    all.foreach(a => println(a.greet()))
        |    println(dog.fetch("ball"))
        |""".stripMargin)

    // Sealed hierarchy for subtype tests
    writeFile(srcDir, "example/Shape.scala",
      """package example
        |
        |sealed trait Shape:
        |  def area: Double
        |
        |case class Circle(radius: Double) extends Shape:
        |  def area: Double = math.Pi * radius * radius
        |
        |case class Rectangle(width: Double, height: Double) extends Shape:
        |  def area: Double = width * height
        |
        |case class Triangle(base: Double, height: Double) extends Shape:
        |  def area: Double = 0.5 * base * height
        |""".stripMargin)

    // Case class with user-overridden toString/equals for synthetic filtering tests
    writeFile(srcDir, "example/Event.scala",
      """package example
        |
        |case class Event(id: String, payload: String, timestamp: Long):
        |  override def toString: String = s"Event($id)"
        |  override def equals(that: Any): Boolean = that match
        |    case e: Event => e.id == id
        |    case _ => false
        |  override def hashCode: Int = id.hashCode
        |""".stripMargin)

    // Generated source file (simulates protobuf/codegen output)
    writeFile(srcDir, "out/generated/Proto.scala",
      """package generated
        |
        |class ProtoMessage:
        |  def toByteArray: Array[Byte] = Array.empty
        |  def parseFrom(bytes: Array[Byte]): ProtoMessage = ProtoMessage()
        |""".stripMargin)

    // Type hierarchy for supertypes tests
    writeFile(srcDir, "example/Persistence.scala",
      """package example
        |
        |trait Repository[T]:
        |  def find(id: String): Option[T]
        |  def save(entity: T): Unit
        |
        |trait CrudRepository[T] extends Repository[T]:
        |  def delete(id: String): Unit
        |  def update(entity: T): Unit
        |
        |class AnimalRepository extends CrudRepository[Animal]:
        |  def find(id: String): Option[Animal] = None
        |  def save(entity: Animal): Unit = ()
        |  def delete(id: String): Unit = ()
        |  def update(entity: Animal): Unit = ()
        |""".stripMargin)

    // Enum for property tests
    writeFile(srcDir, "example/Color.scala",
      """package example
        |
        |enum Color:
        |  case Red, Green, Blue
        |
        |given colorOrdering: Ordering[Color] = Ordering.by(_.ordinal)
        |""".stripMargin)

    // Annotated symbols for annotation tests
    writeFile(srcDir, "example/Deprecated.scala",
      """package example
        |
        |@deprecated("use NewService", "2.0")
        |class OldService:
        |  def oldMethod(): Unit = ()
        |
        |@deprecated("removed", "3.0")
        |trait LegacyApi:
        |  def legacyCall(): Unit
        |""".stripMargin)

    // Second package for package/summary tests
    writeFile(srcDir, "util/Helpers.scala",
      """package util
        |
        |object StringHelpers:
        |  def capitalize(s: String): String = s.capitalize
        |
        |object MathHelpers:
        |  def clamp(v: Int, lo: Int, hi: Int): Int = math.max(lo, math.min(hi, v))
        |""".stripMargin)

    // Test file for --exclude-test testing
    writeFile(srcDir, "example/test/DogTest.scala",
      """package example.test
        |
        |class DogTest:
        |  def testBark(): Unit =
        |    val d = example.Dog("Rex")
        |    println(d.sound)
        |    println(d.fetch("ball"))
        |""".stripMargin)

    // Companion + extension for related tests
    writeFile(srcDir, "example/Extensions.scala",
      """package example
        |
        |extension (animal: Animal)
        |  def describe: String = s"${animal.name} (${animal.getClass.getSimpleName})"
        |
        |object AnimalOps:
        |  def loudGreet(animal: Animal): String =
        |    animal.greet().toUpperCase
        |""".stripMargin)
