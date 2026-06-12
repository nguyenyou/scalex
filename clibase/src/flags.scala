package clibase

// ── Declarative flag registry ───────────────────────────────────────────────
//
// A flag is declared exactly once — names, value shape, default, and help text —
// and the parser, the typed value bag, and the generated options help all derive
// from that single declaration.

/** One CLI flag. Instances are singletons declared by the application and used
  * as typed keys into [[Flags]]. */
sealed trait Flag[A] {
  /** All accepted spellings, in help-display order (e.g. `-w, --workspace`). */
  def names: List[String]

  /** Value placeholder shown in help (e.g. `N`, `PATH`); empty for booleans. */
  def label: String

  /** One-line help description. */
  def help: String

  /** Value when the flag is absent. */
  def default: A
}

object Flag {
  /** Presence flag: `--verbose`. Absent → false. */
  final class BooleanFlag private[Flag] (val names: List[String], val help: String) extends Flag[Boolean] {
    val label: String = ""
    val default: Boolean = false
  }

  /** Int-valued flag: `--limit N`. Always consumes the next token; a missing or
    * non-integer value falls back to `default`. */
  final class IntFlag private[Flag] (val names: List[String], val label: String, val help: String, val default: Int) extends Flag[Int]

  /** Int flag whose value is optional: bare `--expand` means `presentValue`,
    * `--expand 2` means 2. Only consumes the next token when it parses as an
    * integer, so a following flag is never swallowed. */
  final class OptionalIntFlag private[Flag] (val names: List[String], val label: String, val help: String, val default: Int, val presentValue: Int) extends Flag[Int]

  /** Int flag with no meaningful default: `--top N`. Absent or invalid → None. */
  final class IntOptionFlag private[Flag] (val names: List[String], val label: String, val help: String) extends Flag[Option[Int]] {
    val default: Option[Int] = None
  }

  /** String-valued flag: `--kind K`. Always consumes the next token; `transform`
    * is applied to the raw value (e.g. strip a leading `/`). */
  final class StringFlag private[Flag] (val names: List[String], val label: String, val help: String, val transform: String => String) extends Flag[Option[String]] {
    val default: Option[String] = None
  }

  /** Repeatable string flag: `-e PAT -e PAT`. Values accumulate in order; a
    * value starting with `-` is consumed but dropped, so a following flag is
    * never treated as a pattern. */
  final class RepeatedFlag private[Flag] (val names: List[String], val label: String, val help: String) extends Flag[List[String]] {
    val default: List[String] = Nil
  }

  def boolean(names: String*)(help: String): BooleanFlag =
    new BooleanFlag(names.toList, help)

  def int(names: String*)(label: String, default: Int, help: String): IntFlag =
    new IntFlag(names.toList, label, help, default)

  def optionalInt(names: String*)(label: String, default: Int, presentValue: Int, help: String): OptionalIntFlag =
    new OptionalIntFlag(names.toList, label, help, default, presentValue)

  def intOption(names: String*)(label: String, help: String): IntOptionFlag =
    new IntOptionFlag(names.toList, label, help)

  def string(names: String*)(label: String, help: String, transform: String => String = identity): StringFlag =
    new StringFlag(names.toList, label, help, transform)

  def repeated(names: String*)(label: String, help: String): RepeatedFlag =
    new RepeatedFlag(names.toList, label, help)
}

/** An application's full flag set, in help-display order. */
final class FlagRegistry(val flags: List[Flag[?]]) {
  /** Lookup of every accepted spelling to its flag declaration. */
  val byName: Map[String, Flag[?]] =
    flags.iterator.flatMap(f => f.names.map(_ -> f)).toMap

  /** Generated `Options:` section body — one line per flag, declaration order. */
  def optionsHelp: String = {
    flags.map { f =>
      val names = f.names.mkString(", ")
      val display = if f.label.isEmpty then names else s"$names ${f.label}"
      f"  $display%-21s ${f.help}"
    }.mkString("\n")
  }
}

/** Typed flag values from one parse, plus the positional args in order. */
final class Flags private (values: Map[Flag[?], Any], val positional: List[String]) {
  def apply[A](flag: Flag[A]): A = values.getOrElse(flag, flag.default).asInstanceOf[A]

  def updated[A](flag: Flag[A], value: A): Flags = new Flags(values.updated(flag, value), positional)
}

object Flags {
  /** Single left-to-right pass over the arg list. Each token is classified exactly
    * once as a flag, a flag's value, or a positional arg — so a positional that
    * happens to equal a flag's value elsewhere in the list is never misclassified.
    *
    * Lenient by design: unknown `--long` flags are ignored (a caller passing a
    * wrong flag still gets useful output); unknown short tokens stay positional.
    * When a flag appears more than once, the last occurrence wins. */
  def parse(args: List[String], registry: FlagRegistry): Flags = {
    var values = Map.empty[Flag[?], Any]
    val positional = List.newBuilder[String]
    val argv = args.toVector
    var i = 0
    while i < argv.length do {
      val token = argv(i)
      registry.byName.get(token) match {
        case Some(f: Flag.BooleanFlag) =>
          values = values.updated(f, true)
        case Some(f: Flag.IntFlag) =>
          i += 1
          values = values.updated(f, argv.lift(i).flatMap(_.toIntOption).getOrElse(f.default))
        case Some(f: Flag.OptionalIntFlag) =>
          argv.lift(i + 1).flatMap(_.toIntOption) match {
            case Some(v) =>
              i += 1
              values = values.updated(f, v)
            case None =>
              values = values.updated(f, f.presentValue)
          }
        case Some(f: Flag.IntOptionFlag) =>
          i += 1
          values = values.updated(f, argv.lift(i).flatMap(_.toIntOption))
        case Some(f: Flag.StringFlag) =>
          i += 1
          values = values.updated(f, argv.lift(i).map(f.transform))
        case Some(f: Flag.RepeatedFlag) =>
          i += 1
          argv.lift(i).filterNot(_.startsWith("-")).foreach { v =>
            val prev = values.getOrElse(f, Nil).asInstanceOf[List[String]]
            values = values.updated(f, prev :+ v)
          }
        case None if token.startsWith("--") =>
          () // lenient: unknown long flags are ignored
        case None =>
          positional += token
      }
      i += 1
    }
    new Flags(values, positional.result())
  }
}
