package scalex.index

import java.io.ByteArrayInputStream

import scalex.*

import scala.collection.mutable
import scala.util.Using
import java.nio.file.{Files, Path}
import java.io.{BufferedInputStream, BufferedOutputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}
import com.google.common.hash.{BloomFilter, Funnels}

// ── Binary persistence ──────────────────────────────────────────────────────

object IndexPersistence {
  private val MAGIC = 0x53584458
  // Invalidate cached Java parse failures from native images missing validator metadata.
  private val VERSION: Byte = 9

  def indexPath(workspace: Path): Path = workspace.resolve(".scalex").resolve("index.bin")

  def save(workspace: Path, files: List[IndexedFile]): Unit = {
    val dir = workspace.resolve(".scalex")
    if (!Files.exists(dir)) Files.createDirectories(dir)

    val stringTable = mutable.LinkedHashMap.empty[String, Int]
    def intern(s: String): Int =
      stringTable.getOrElseUpdate(s, stringTable.size)

    files.foreach { f =>
      intern(f.relativePath)
      intern(f.oid)
      f.symbols.foreach { s =>
        intern(s.name)
        intern(s.packageName)
        intern(s.signature)
        s.parents.foreach(intern)
        s.typeParamParents.foreach(intern)
        s.annotations.foreach(intern)
      }
      f.imports.foreach(intern)
      f.aliases.foreach { (k, v) => intern(k); intern(v) }
    }

    val out = DataOutputStream(BufferedOutputStream(Files.newOutputStream(indexPath(workspace)), 1 << 16))
    try {
      out.writeInt(MAGIC)
      out.writeByte(VERSION)

      val strings = stringTable.keys.toArray
      out.writeInt(strings.length)
      strings.foreach(out.writeUTF)

      out.writeInt(files.size)
      files.foreach { f =>
        out.writeInt(intern(f.relativePath))
        out.writeInt(intern(f.oid))

        out.writeShort(f.symbols.size)
        f.symbols.foreach { s =>
          out.writeInt(intern(s.name))
          out.writeByte(s.kind.id)
          out.writeInt(s.line)
          out.writeInt(intern(s.packageName))
          out.writeInt(intern(s.signature))
          out.writeShort(s.parents.size)
          s.parents.foreach(p => out.writeInt(intern(p)))
          out.writeShort(s.typeParamParents.size)
          s.typeParamParents.foreach(p => out.writeInt(intern(p)))
          out.writeShort(s.annotations.size)
          s.annotations.foreach(a => out.writeInt(intern(a)))
        }

        // Imports
        out.writeShort(f.imports.size)
        f.imports.foreach(i => out.writeInt(intern(i)))

        // Aliases
        out.writeShort(f.aliases.size)
        f.aliases.foreach { (k, v) =>
          out.writeInt(intern(k))
          out.writeInt(intern(v))
        }

        // Bloom filter
        f.identifierBloom match {
          case Some(bloom) =>
            val bloomBytes = ByteArrayOutputStream()
            bloom.writeTo(bloomBytes)
            val ba = bloomBytes.toByteArray
            out.writeInt(ba.length)
            out.write(ba)
          case None =>
            out.writeInt(0)
        }

        // Parse failed flag
        out.writeBoolean(f.parseFailed)
      }
    } finally out.close()
  }

  def load(workspace: Path, loadBlooms: Boolean = true): Option[Map[String, IndexedFile]] = {
    val p = indexPath(workspace)
    if (!Files.exists(p)) None
    else
      try
        Using.resource(DataInputStream(BufferedInputStream(Files.newInputStream(p), 1 << 16))) { in =>
          if (in.readInt() != MAGIC) None
          else if (in.readByte() != VERSION) None
          else Some(loadBody(workspace, in, loadBlooms))
        }
      catch {
        case e: Exception =>
          System.err.println(s"scalex: index load failed (${e.getClass.getSimpleName}: ${e.getMessage}) — rebuilding")
          None
      }
  }

  /** Read the index body after the magic/version header has been validated. */
  private def loadBody(workspace: Path, in: DataInputStream, loadBlooms: Boolean): Map[String, IndexedFile] = {
    val strCount = in.readInt()
    val strings = Array.fill(strCount)(in.readUTF())

    val fileCount = in.readInt()
    val result = mutable.HashMap.empty[String, IndexedFile]

    var fi = 0
    while (fi < fileCount) {
      val relPath = strings(in.readInt())
      val oid = strings(in.readInt())

      val symCount = in.readShort()
      val syms = List.newBuilder[SymbolInfo]
      var si = 0
      while (si < symCount) {
        val name = strings(in.readInt())
        val kind = SymbolKind.fromId(in.readByte())
        val line = in.readInt()
        val pkg = strings(in.readInt())
        val sig = strings(in.readInt())
        val parentCount = in.readShort()
        val parents = (0 until parentCount).map(_ => strings(in.readInt())).toList
        val tpParentCount = in.readShort()
        val tpParents = (0 until tpParentCount).map(_ => strings(in.readInt())).toList
        val annotCount = in.readShort()
        val annots = (0 until annotCount).map(_ => strings(in.readInt())).toList
        syms += SymbolInfo(name, kind, workspace.resolve(relPath), line, pkg, parents, tpParents, sig, annots)
        si += 1
      }

      // Imports
      val importCount = in.readShort()
      val imports = (0 until importCount).map(_ => strings(in.readInt())).toList

      // Aliases
      val aliasCount = in.readShort()
      val aliases = (0 until aliasCount).map { _ =>
        val k = strings(in.readInt())
        val v = strings(in.readInt())
        k -> v
      }.toMap

      // Bloom filter
      val bloomLen = in.readInt()
      val bloom: Option[BloomFilter[CharSequence]] =
        if (bloomLen == 0) None
        else if (loadBlooms) {
          val bloomBytes = Array.ofDim[Byte](bloomLen)
          in.readFully(bloomBytes)
          Some(
            BloomFilter.readFrom(
              ByteArrayInputStream(bloomBytes),
              Funnels.unencodedCharsFunnel()
            )
          )
        } else {
          in.skipBytes(bloomLen)
          None
        }

      // Parse failed flag
      val parseFailed = in.readBoolean()

      result(relPath) = IndexedFile(relPath, oid, syms.result(), bloom, imports, aliases, parseFailed)
      fi += 1
    }

    result.toMap
  }
}
