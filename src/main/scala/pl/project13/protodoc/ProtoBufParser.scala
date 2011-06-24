package pl.project13.protodoc

import exceptions.{UnknownTypeException, ProtoDocParsingException}
import model._
import scala.util.parsing.combinator._
import org.fusesource.scalate.ssp.ElseFragment

/**
 * @author Konrad Malawski
 */
object ProtoBufParser extends RegexParsers with ParserConversions {

  /**
   * Defines if log messages should be printed or not
   */
  var verbose = false

  def ID = """[a-zA-Z_]([a-zA-Z0-9_]*|_[a-zA-Z0-9]*)*""".r

  def NUM = """[1-9][0-9]*""".r

  def CHAR = """[a-zA-Z0-9]""".r

  // lists of "known types", to allow parsing of enum fields etc
  var knownEnums: List[ProtoEnumType] = List()
  var knownMessages: List[ProtoMessage] = List()

  /**
   * For now, just ignore whitespaces
   */
  protected override val whiteSpace = """(\s|//.*|(?m)/\*(\*(?!/)|[^*])*\*/)+""".r

  def pack: Parser[String] = "package" ~ repsep(ID, ".") ~ ";" ^^ {
    case p ~ packName ~ end =>
      val joinedName = packName.reduceLeft(_ + "." + _)
      log("detected package name: " + joinedName)

      joinedName
  }

  def message: Parser[_ <: ProtoMessage] = opt(pack) ~ "message" ~ ID ~ "{" ~ rep(enumTypeDef | instanceField | message) ~ "}" ^^ {
    case maybePack ~ m ~ id ~ p1 ~ allFields ~ p2 =>

      val pack = maybePack.getOrElse("")

      val postProcessedFields = addOuterMessageInfo(id, pack, allFields)

      log("Parsed message in '%s' named '%s'".format(pack, id))
      log(" fields: " + list2messageFieldList(postProcessedFields))
      log(" enums: " + list2enumTypeList(postProcessedFields))
      log(" inner messages: " + list2messageList(postProcessedFields))

      new ProtoMessage(messageName = id,
                       packageName = pack,
                       fields = allFields /*will be implicitly filtered*/ ,
                       enums = allFields /*will be implicitly filtered*/ ,
                       innerMessages = allFields /*will be implicitly filtered*/)
  }

  def modifier: Parser[ProtoModifier] = ("optional" | "required" | "repeated") ^^ {
    s =>
      log("Found " + s + " field")
      ProtoModifier.str2modifier(s)
  }

  // todo make these two fields use the same list
  val primitiveTypes = List("int32", "int64", "uint32", "uint64", "sint32", "sint64", "fixed32", "fixed64",
                            "sfixed32", "sfixed64", "double", "float", "bool","string", "bytes")

  def protoType: Parser[String] = ("int32"
                                 | "int64"
                                 | "uint32"
                                 | "uint64"
                                 | "sint32"
                                 | "sint64"
                                 | "fixed32"
                                 | "fixed64"
                                 | "sfixed32"
                                 | "sfixed64"
                                 | "double"
                                 | "float"
                                 | "bool"
                                 | "string"
                                 | "bytes"
                                 )

  def enumType: Parser[String] = ("""\w+""".r) ^^ {
    s =>
      log("Trying to parse '" + s + "' as 'known' enum...")
      val knownEnumNames = knownEnums.map(_.typeName)
      log("Known enums are: " + knownEnumNames)

      if (knownEnumNames.contains(s)) {
        s
      } else {
        throw new UnknownTypeException("Unable to link '" + s + "' to any known enum Type.")
      }
  }

  def anyField = enumTypeDef | instanceField //| enumField

  // enums --------------------------------------------------------------------
  def enumTypeDef: Parser[ProtoEnumType] = "enum" ~ ID ~ "{" ~ rep(enumValue) ~ "}" ^^ {
    case e ~ id ~ p1 ~ vals ~ p2 =>
      log("detected enum type '" + id + "'...")
      log("              values: " + vals)

      val definedEnum = ProtoEnumType(id, "", vals)
      knownEnums ::= definedEnum
      definedEnum
  }

  def enumValue: Parser[ProtoEnumValue] = ID ~ "=" ~ NUM ~ ";" ^^ {
    case id ~ eq ~ num ~ end =>
      ProtoEnumValue(id, num)
  }

  // comments -----------------------------------------------------------------
  def protoDocComment: Parser[ProtoDocComment] = "/**" ~ rep(CHAR) ~ "*/" ^^ {
    case s ~ text ~ end =>
      val wholeComment = text.reduceLeft(_ + _)

      log("detected comment: '" + wholeComment + "'")
      ProtoDocComment(wholeComment)
  }

  // fields -------------------------------------------------------------------
  def instanceField = opt(protoDocComment) ~ modifier ~ (protoType | enumType /* | msgType*/) ~ ID ~ "=" ~ integerValue ~ opt(defaultValue) ~ ";" ^^ {
    case doc ~ mod ~ pType ~ id ~ eq ~ tag ~ defaultVal ~ end =>
      log("parsing field '" + id + "'...")

      if(primitiveTypes.contains(pType)) {
        ProtoMessageField.toTypedField(pType, id, tag, mod, defaultVal)
      } else if(knownEnums.map(_.typeName).contains(pType)){
        // it's an enum
        val itsEnumType = knownEnums.find(p => p.typeName == pType).get
        ProtoMessageField.toEnumField(id, itsEnumType, tag, mod, defaultVal)
      }
//      else if(knownMessages.map(_.messageName).contains(pType)){ // todo must be impreved, fullname also is ok here
//        ProtoMessageField.to // todo implement messages, the same way as enum fields
//      }
      else {
        throw new UnknownTypeException("Unable to create Field instance for field: '" + id + "', type: " + pType)
      }
  }

  def defaultValue: Parser[Any] = "[" ~ "default" ~ "=" ~ (ID | NUM | stringValue) ~ "]" ^^ {
    case b ~ d ~ eq ~ value ~ end =>
      log("Default values: " + value)
      value
  }

  // field values -------------------------------------------------------------
  def integerValue: Parser[Int] = ("[1-9][0-9]*".r) ^^ {
    s =>
      s.toInt
  }

  def stringValue: Parser[String] = "\"" ~ """\w+""".r ~ "\"" ^^ {
    case o ~ stringValue ~ end =>
      stringValue
  }

  def booleanValue: Parser[Boolean] = ("true" | "false") ^^ {
    s =>
      s.toBoolean
  }

  /* -------------------- helper methods ----------------------------------- */

  /**
   * This method adds package information for messages and enums contained withing another message
   * so that the access path to them is reflected by their package, for example:
   * <pre>
   *   package pl.project13;
   *
   *   message Outer {
   *     message Inner {}
   *   }
   * </pre>
   * Should result in <em>Inner</em> having the package "pl.project13.Outer".
   */
  def addOuterMessageInfo(msgName: String, msgPack: String, innerFields: List[_]): List[Any] = {
    var processedFields = List[Any]()

    for(field <- innerFields) field match {
      case ProtoMessage(_, _, _, _, _) =>
        val f = field.asInstanceOf[ProtoMessage]
        log("Adding package info to: " + f.fullName)
        val packageWithOuterClass: String = msgPack + "." + msgName + f.packageName
        processedFields ::= ProtoMessage(messageName = f.messageName,
                                         packageName = packageWithOuterClass,
                                         fields = f.fields,
                                         enums = addOuterMessageInfo(f.messageName,
                                                                     packageWithOuterClass,
                                                                     f.enums),
                                         innerMessages = addOuterMessageInfo(f.messageName,
                                                                             packageWithOuterClass,
                                                                             f.innerMessages))
      case ProtoEnumType(_, _, _) =>
        val e = field.asInstanceOf[ProtoEnumType]
        val packageWithOuterClass: String = msgPack + "." + msgName
        processedFields ::= ProtoEnumType(typeName = e.typeName,
                                          packageName = packageWithOuterClass,
                                          values = e.values)
      case f =>
        log("Ignored field while fixing packages: " + f)
    }

    processedFields
  }

  /* ----------------- API methods ----------------------------------------- */

  def parse(s: String): ProtoMessage = parseAll(message, s) match {
    case Success(res, _) => res
    //    case Failure(msg, _)  => throw new RuntimeException(msg)
    //    case Error(msg, _) => throw new RuntimeException(msg)
    case x: Failure => throw new ProtoDocParsingException(x.toString())
    case x: Error => throw new RuntimeException(x.toString())
  }

  def log(msg: String) {
    if(verbose) {
      Console.println(msg)
    }
  }
}