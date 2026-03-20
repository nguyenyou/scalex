import munit.FunSuite
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

abstract class ScalexTestBase extends FunSuite:

  var workspace: Path = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    workspace = Files.createTempDirectory("scalex-test")

    // Create sample Scala files
    writeFile("src/main/scala/com/example/UserService.scala",
      """package com.example
        |
        |trait UserService {
        |  def findUser(id: String): Option[User]
        |  def createUser(name: String): User
        |}
        |
        |class UserServiceLive(db: Database) extends UserService {
        |  def findUser(id: String): Option[User] = db.query(id)
        |  def createUser(name: String): User = db.insert(name)
        |}
        |
        |object UserService {
        |  val default: UserService = UserServiceLive(Database.live)
        |}
        |""".stripMargin)

    writeFile("src/main/scala/com/example/Model.scala",
      """package com.example
        |
        |case class User(id: String, name: String)
        |
        |enum Role:
        |  case Admin, Editor, Viewer
        |
        |type UserId = String
        |
        |given userOrdering: Ordering[User] = Ordering.by(_.name)
        |""".stripMargin)

    writeFile("src/main/scala/com/example/Database.scala",
      """package com.example
        |
        |trait Database {
        |  def query(id: String): Option[User]
        |  def insert(name: String): User
        |}
        |
        |object Database {
        |  val live: Database = new Database {
        |    def query(id: String): Option[User] = None
        |    def insert(name: String): User = User(name, name)
        |  }
        |}
        |""".stripMargin)

    writeFile("src/main/scala/com/other/Helper.scala",
      """package com.other
        |
        |object Helper {
        |  def formatUser(user: com.example.User): String = user.name
        |  val version = "1.0"
        |}
        |
        |extension (s: String)
        |  def toUserId: com.example.UserId = s
        |""".stripMargin)

    writeFile("src/test/scala/com/example/UserServiceSpec.scala",
      """package com.example
        |
        |class UserServiceSpec {
        |  val service: UserService = UserService.default
        |  def testFindUser(): Unit = {
        |    val result = service.findUser("123")
        |  }
        |}
        |""".stripMargin)

    writeFile("src/main/scala/com/client/ExplicitClient.scala",
      """package com.client
        |
        |import com.example.UserService
        |
        |class ExplicitClient {
        |  val svc: UserService = ???
        |}
        |""".stripMargin)

    writeFile("src/main/scala/com/client/WildcardClient.scala",
      """package com.client
        |
        |import com.example._
        |
        |class WildcardClient {
        |  val svc: UserService = ???
        |}
        |""".stripMargin)

    writeFile("src/main/scala/com/unrelated/NoImportClient.scala",
      """package com.unrelated
        |
        |class NoImportClient {
        |  val svc: UserService = ???
        |}
        |""".stripMargin)

    writeFile("src/main/scala/com/client/AliasClient.scala",
      """package com.client
        |
        |import com.example.UserService as US
        |import com.example.{Database as DB}
        |
        |class AliasClient {
        |  val svc: US = ???
        |  val db: DB = ???
        |}
        |""".stripMargin)

    writeFile("src/main/scala/com/example/Annotated.scala",
      """package com.example
        |
        |@deprecated class OldThing
        |@deprecated("use NewService", "2.0") class OldService extends UserService {
        |  def findUser(id: String): Option[User] = None
        |  def createUser(name: String): User = User(name, name)
        |}
        |@specialized val fastVal: Int = 42
        |""".stripMargin)

    writeFile("src/main/scala/com/example/Documented.scala",
      """package com.example
        |
        |/**
        | * A service for processing payments.
        | * Handles credit cards and bank transfers.
        | */
        |trait PaymentService {
        |  /** Process a single payment */
        |  def processPayment(amount: BigDecimal): Boolean
        |  def refund(id: String): Unit
        |}
        |
        |class PaymentServiceLive extends PaymentService {
        |  def processPayment(amount: BigDecimal): Boolean = true
        |  def refund(id: String): Unit = ()
        |  val maxRetries: Int = 3
        |  var lastError: Option[String] = None
        |  type TransactionId = String
        |}
        |""".stripMargin)

    writeFile("src/test/scala/com/example/UserServiceTest.scala",
      """package com.example
        |
        |class UserServiceTest extends munit.FunSuite {
        |  test("findUser returns None for unknown id") {
        |    val svc = UserServiceLive(Database.live)
        |    assertEquals(svc.findUser("unknown"), None)
        |  }
        |
        |  test("createUser returns new user") {
        |    val svc = UserServiceLive(Database.live)
        |    val user = svc.createUser("Alice")
        |    assertEquals(user.name, "Alice")
        |  }
        |}
        |""".stripMargin)

    writeFile("src/test/scala/com/example/DynamicTest.scala",
      """package com.example
        |
        |class DynamicTest extends munit.FunSuite {
        |  test("static test works") {
        |    assert(true)
        |  }
        |
        |  for (i <- 1 to 5)
        |    test(s"dynamic test $i") {
        |      assert(i > 0)
        |    }
        |
        |  List("a", "b").foreach { x =>
        |    test("prefix " + x) {
        |      assert(x.nonEmpty)
        |    }
        |  }
        |}
        |""".stripMargin)

    writeFile("src/test/scala/com/example/DynamicOnlyTest.scala",
      """package com.example
        |
        |class DynamicOnlyTest extends munit.FunSuite {
        |  for (i <- 1 to 3)
        |    test(s"generated test $i") {
        |      assert(i > 0)
        |    }
        |}
        |""".stripMargin)

    writeFile("src/main/scala/com/example/MainApp.scala",
      """package com.example
        |
        |@main def run(): Unit = println("hello")
        |
        |object MyApp {
        |  def main(args: Array[String]): Unit = println("hello")
        |}
        |""".stripMargin)

    writeFile("src/main/scala/com/example/LegacyApp.scala",
      """package com.example
        |
        |object Legacy extends App {
        |  println("hello")
        |}
        |""".stripMargin)

    writeFile("src/main/scala/com/example/Mixins.scala",
      """package com.example
        |
        |trait Processor[T] {
        |  def process(item: T): Unit
        |  def validate(item: T): Boolean = true
        |}
        |
        |class UserProcessor extends Processor[User] {
        |  def process(item: User): Unit = ()
        |}
        |
        |class RoleProcessor extends Processor[Role] with Serializable
        |
        |class GenericProcessor[A] extends Processor[A]
        |
        |class NestedTypeArgProcessor extends Processor[Map[String, User]]
        |""".stripMargin)

    writeFile("src/main/java/com/example/EventBus.java",
      """package com.example;
        |
        |public interface EventBus {
        |    void publish(String event);
        |    void subscribe(String topic);
        |}
        |""".stripMargin)

    writeFile("src/main/java/com/example/SimpleEventBus.java",
      """package com.example;
        |
        |public class SimpleEventBus implements EventBus {
        |    public void publish(String event) {}
        |    public void subscribe(String topic) {}
        |}
        |""".stripMargin)

    writeFile("src/main/java/com/example/GenericRepository.java",
      """package com.example;
        |
        |import java.util.List;
        |import java.util.Optional;
        |
        |@SuppressWarnings("unchecked")
        |public abstract class GenericRepository<T> {
        |    private final String tableName;
        |    protected int maxResults = 100;
        |
        |    public GenericRepository(String tableName) {
        |        this.tableName = tableName;
        |    }
        |
        |    public abstract Optional<T> findById(String id);
        |
        |    public List<T> findAll() {
        |        return List.of();
        |    }
        |
        |    public void delete(String id) {}
        |
        |    public enum Status {
        |        ACTIVE,
        |        INACTIVE,
        |        DELETED
        |    }
        |}
        |""".stripMargin)

    writeFile("src/main/java/com/example/UserRepository.java",
      """package com.example;
        |
        |import java.util.Optional;
        |
        |public class UserRepository extends GenericRepository<String> implements EventBus {
        |    public UserRepository() {
        |        super("users");
        |    }
        |
        |    @Override
        |    public Optional<String> findById(String id) {
        |        return Optional.of(id);
        |    }
        |
        |    @Override
        |    public void publish(String event) {}
        |
        |    @Override
        |    public void subscribe(String topic) {}
        |
        |    public String findByName(String name) {
        |        return name;
        |    }
        |}
        |""".stripMargin)

    // Cross-package duplicate for disambiguation tests (#164)
    writeFile("src/main/scala/com/other/Registry.scala",
      """package com.other
        |
        |trait Registry {
        |  def lookup(id: Int): String
        |  def register(name: String): Unit
        |}
        |""".stripMargin)
    writeFile("src/main/scala/com/example/Registry.scala",
      """package com.example
        |
        |trait Registry {
        |  def lookup(id: Int): String
        |  def register(name: String): Unit
        |  def unregister(id: Int): Unit
        |  def list(): List[String]
        |}
        |""".stripMargin)

    // File with nested local defs inside methods (#172)
    writeFile("src/main/scala/com/example/Pipeline.scala",
      """package com.example
        |
        |class Pipeline(steps: List[String]) {
        |  def execute(): Unit = {
        |    var count = 0
        |    def runSteps(remaining: List[String]): Unit = {
        |      remaining match {
        |        case head :: tail =>
        |          count += 1
        |          runSteps(tail)
        |        case Nil => ()
        |      }
        |    }
        |    runSteps(steps)
        |  }
        |
        |  def validate(): Boolean = {
        |    def checkStep(step: String): Boolean = step.nonEmpty
        |    steps.forall(checkStep)
        |  }
        |}
        |
        |object Pipeline {
        |  def create(steps: String*): Pipeline = Pipeline(steps.toList)
        |}
        |""".stripMargin)

    // Scala file with local def nested inside a wrapper call (#172)
    writeFile("src/main/scala/com/example/Scheduler.scala",
      """package com.example
        |
        |object Scheduler {
        |  def schedule(tasks: List[String]): Unit = synchronized {
        |    def processBatch(batch: List[String]): Int = {
        |      batch.size
        |    }
        |    processBatch(tasks)
        |  }
        |}
        |""".stripMargin)

    // Java file with syntax that may trigger parser errors (#172)
    writeFile("src/main/java/com/example/BrokenRecord.java",
      """package com.example;
        |
        |// This sealed interface + record pattern can trigger JavaParser errors
        |public sealed interface BrokenRecord permits BrokenRecord.Ok, BrokenRecord.Err {
        |    record Ok(String value) implements BrokenRecord {}
        |    record Err(String message) implements BrokenRecord {}
        |}
        |""".stripMargin)

    // #197: Symbol indexed in one file, also exists as nested def in a different class
    // "create" is already indexed as Pipeline.create. This file adds a class
    // whose method has a local def also named "create" — body --in Assembler
    // must find it even though "create" is indexed elsewhere.
    writeFile("src/main/scala/com/example/Assembler.scala",
      """package com.example
        |
        |class Assembler(parts: List[String]) {
        |  def build(): String = {
        |    def create(part: String): String = {
        |      part.toUpperCase
        |    }
        |    parts.map(create).mkString(", ")
        |  }
        |}
        |""".stripMargin)

    // #228: Project type with stdlib-collision name for related-types false positive test
    writeFile("src/main/scala/com/ui/Option.scala",
      """package com.ui
        |
        |class Option(val label: String, val value: String)
        |""".stripMargin)

    // #209: Trait with short member names for suggestion ranking tests
    writeFile("src/main/scala/com/example/ShortNames.scala",
      """package com.example
        |
        |trait ShortNames {
        |  def e(): Unit
        |  def execute(): Unit
        |  def evaluate(): Unit
        |}
        |""".stripMargin)

    // #239: classes nested inside objects for Owner.Member dotted syntax tests
    writeFile("src/main/scala/com/example/Outer.scala",
      """package com.example
        |
        |object Outer {
        |  class Inner {
        |    def hello: String = "world"
        |    def greet(name: String): String = s"Hello, $name"
        |  }
        |
        |  class AnotherInner extends Inner {
        |    override def hello: String = "overridden"
        |  }
        |}
        |
        |class Outer {
        |  val inner = new Outer.Inner
        |}
        |""".stripMargin)

    // #239: cross-file implementor of nested class
    writeFile("src/main/scala/com/example/CrossFileImpl.scala",
      """package com.example
        |
        |class CrossFileImpl extends Outer.Inner {
        |  override def hello: String = "cross-file"
        |}
        |""".stripMargin)

    // Initialize git repo
    run("git", "init")
    run("git", "add", ".")
    run("git", "commit", "-m", "init")

  override def afterAll(): Unit =
    deleteRecursive(workspace)

  protected def writeFile(relativePath: String, content: String): Unit =
    val file = workspace.resolve(relativePath)
    Files.createDirectories(file.getParent)
    Files.writeString(file, content)

  protected def run(cmd: String*): Unit =
    val pb = ProcessBuilder(cmd*)
    pb.directory(workspace.toFile)
    pb.redirectErrorStream(true)
    val proc = pb.start()
    proc.getInputStream.readAllBytes() // drain
    val exit = proc.waitFor()
    assert(exit == 0, s"Command failed: ${cmd.mkString(" ")}")

  protected def captureOut(body: => Unit): String =
    val out = new java.io.ByteArrayOutputStream()
    val err = new java.io.ByteArrayOutputStream()
    Console.withOut(out) { Console.withErr(err) { body } }
    out.toString

  protected def captureOutErr(body: => Unit): (stdout: String, stderr: String) =
    val out = new java.io.ByteArrayOutputStream()
    val err = new java.io.ByteArrayOutputStream()
    Console.withOut(out) { Console.withErr(err) { body } }
    (stdout = out.toString, stderr = err.toString)

  private def deleteRecursive(path: Path): Unit =
    if Files.isDirectory(path) then
      Files.list(path).iterator().asScala.foreach(deleteRecursive)
    Files.deleteIfExists(path)
