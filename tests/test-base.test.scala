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

    writeFile("src/main/scala/com/example/Mixins.scala",
      """package com.example
        |
        |trait Processor[T]
        |
        |class UserProcessor extends Processor[User]
        |
        |class RoleProcessor extends Processor[Role] with Serializable
        |
        |class GenericProcessor[A] extends Processor[A]
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

  private def deleteRecursive(path: Path): Unit =
    if Files.isDirectory(path) then
      Files.list(path).iterator().asScala.foreach(deleteRecursive)
    Files.deleteIfExists(path)
