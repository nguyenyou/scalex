package scalex

import com.github.javaparser.metamodel.JavaParserMetaModel
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import scala.util.Using

class NativeMetadataSuite extends FunSuite {
  test("native metadata covers every field read by JavaParser's non-empty-list validator") {
    val required = JavaParserMetaModel.getNodeMetaModels.asScala.flatMap { node =>
      node.getAllPropertyMetaModels.asScala.collect {
        case property if property.isNonEmpty && property.isNodeList =>
          (owner = property.getContainingNodeMetaModel.getType.getName, field = property.getName)
      }
    }.toSet
    val resource = getClass.getResourceAsStream("/META-INF/native-image/scalex/reflect-config.json")
    assert(resource != null, "Native metadata must be included in the application resources")
    val metadata = Using.resource(resource)(stream => ujson.read(stream.readAllBytes()))
    val registered = metadata.arr.flatMap { entry =>
      entry("fields").arr.map(field => (owner = entry("name").str, field = field("name").str))
    }.toSet
    assertEquals(registered, required)
  }
}
