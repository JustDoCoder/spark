/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.expressions

import java.lang.{Boolean => JavaBoolean}
import java.lang.{Byte => JavaByte}
import java.lang.{Character => JavaChar}
import java.lang.{Double => JavaDouble}
import java.lang.{Float => JavaFloat}
import java.lang.{Integer => JavaInteger}
import java.lang.{Long => JavaLong}
import java.lang.{Short => JavaShort}
import java.math.{BigDecimal => JavaBigDecimal}
import java.nio.charset.StandardCharsets
import java.sql.{Date, Timestamp}
import java.time.{Duration, Instant, LocalDate, Period}
import java.util
import java.util.Objects
import javax.xml.bind.DatatypeConverter

import scala.math.{BigDecimal, BigInt}
import scala.reflect.runtime.universe.TypeTag
import scala.util.Try

import org.json4s.JsonAST._

import org.apache.spark.sql.catalyst.{CatalystTypeConverters, InternalRow, ScalaReflection}
import org.apache.spark.sql.catalyst.expressions.codegen._
import org.apache.spark.sql.catalyst.util._
import org.apache.spark.sql.catalyst.util.DateTimeUtils.instantToMicros
import org.apache.spark.sql.catalyst.util.IntervalUtils.{durationToMicros, periodToMonths}
import org.apache.spark.sql.errors.{QueryCompilationErrors, QueryExecutionErrors}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types._
import org.apache.spark.util.Utils

object Literal {
  val TrueLiteral: Literal = Literal(true, BooleanType)

  val FalseLiteral: Literal = Literal(false, BooleanType)

  def apply(v: Any): Literal = v match {
    case i: Int => Literal(i, IntegerType)
    case l: Long => Literal(l, LongType)
    case d: Double => Literal(d, DoubleType)
    case f: Float => Literal(f, FloatType)
    case b: Byte => Literal(b, ByteType)
    case s: Short => Literal(s, ShortType)
    case s: String => Literal(UTF8String.fromString(s), StringType)
    case c: Char => Literal(UTF8String.fromString(c.toString), StringType)
    case ac: Array[Char] => Literal(UTF8String.fromString(String.valueOf(ac)), StringType)
    case b: Boolean => Literal(b, BooleanType)
    case d: BigDecimal =>
      val decimal = Decimal(d)
      Literal(decimal, DecimalType.fromDecimal(decimal))
    case d: JavaBigDecimal =>
      val decimal = Decimal(d)
      Literal(decimal, DecimalType.fromDecimal(decimal))
    case d: Decimal => Literal(d, DecimalType(Math.max(d.precision, d.scale), d.scale))
    case i: Instant => Literal(instantToMicros(i), TimestampType)
    case t: Timestamp => Literal(DateTimeUtils.fromJavaTimestamp(t), TimestampType)
    case ld: LocalDate => Literal(ld.toEpochDay.toInt, DateType)
    case d: Date => Literal(DateTimeUtils.fromJavaDate(d), DateType)
    case d: Duration => Literal(durationToMicros(d), DayTimeIntervalType)
    case p: Period => Literal(periodToMonths(p), YearMonthIntervalType)
    case a: Array[Byte] => Literal(a, BinaryType)
    case a: collection.mutable.WrappedArray[_] => apply(a.array)
    case a: Array[_] =>
      val elementType = componentTypeToDataType(a.getClass.getComponentType())
      val dataType = ArrayType(elementType)
      val convert = CatalystTypeConverters.createToCatalystConverter(dataType)
      Literal(convert(a), dataType)
    case i: CalendarInterval => Literal(i, CalendarIntervalType)
    case null => Literal(null, NullType)
    case v: Literal => v
    case _ =>
      throw QueryExecutionErrors.literalTypeUnsupportedError(v)
  }

  /**
   * Returns the Spark SQL DataType for a given class object. Since this type needs to be resolved
   * in runtime, we use match-case idioms for class objects here. However, there are similar
   * functions in other files (e.g., HiveInspectors), so these functions need to merged into one.
   */
  private[this] def componentTypeToDataType(clz: Class[_]): DataType = clz match {
    // primitive types
    case JavaShort.TYPE => ShortType
    case JavaInteger.TYPE => IntegerType
    case JavaLong.TYPE => LongType
    case JavaDouble.TYPE => DoubleType
    case JavaByte.TYPE => ByteType
    case JavaFloat.TYPE => FloatType
    case JavaBoolean.TYPE => BooleanType
    case JavaChar.TYPE => StringType

    // java classes
    case _ if clz == classOf[LocalDate] => DateType
    case _ if clz == classOf[Date] => DateType
    case _ if clz == classOf[Instant] => TimestampType
    case _ if clz == classOf[Timestamp] => TimestampType
    case _ if clz == classOf[Duration] => DayTimeIntervalType
    case _ if clz == classOf[Period] => YearMonthIntervalType
    case _ if clz == classOf[JavaBigDecimal] => DecimalType.SYSTEM_DEFAULT
    case _ if clz == classOf[Array[Byte]] => BinaryType
    case _ if clz == classOf[Array[Char]] => StringType
    case _ if clz == classOf[JavaShort] => ShortType
    case _ if clz == classOf[JavaInteger] => IntegerType
    case _ if clz == classOf[JavaLong] => LongType
    case _ if clz == classOf[JavaDouble] => DoubleType
    case _ if clz == classOf[JavaByte] => ByteType
    case _ if clz == classOf[JavaFloat] => FloatType
    case _ if clz == classOf[JavaBoolean] => BooleanType

    // other scala classes
    case _ if clz == classOf[String] => StringType
    case _ if clz == classOf[BigInt] => DecimalType.SYSTEM_DEFAULT
    case _ if clz == classOf[BigDecimal] => DecimalType.SYSTEM_DEFAULT
    case _ if clz == classOf[CalendarInterval] => CalendarIntervalType

    case _ if clz.isArray => ArrayType(componentTypeToDataType(clz.getComponentType))

    case _ => throw QueryCompilationErrors.arrayComponentTypeUnsupportedError(clz)
  }

  /**
   * Constructs a [[Literal]] of [[ObjectType]], for example when you need to pass an object
   * into code generation.
   */
  def fromObject(obj: Any, objType: DataType): Literal = new Literal(obj, objType)
  def fromObject(obj: Any): Literal = new Literal(obj, ObjectType(obj.getClass))

  def create(v: Any, dataType: DataType): Literal = {
    Literal(CatalystTypeConverters.convertToCatalyst(v), dataType)
  }

  def create[T : TypeTag](v: T): Literal = Try {
    val ScalaReflection.Schema(dataType, _) = ScalaReflection.schemaFor[T]
    val convert = CatalystTypeConverters.createToCatalystConverter(dataType)
    Literal(convert(v), dataType)
  }.getOrElse {
    Literal(v)
  }

  /**
   * Create a literal with default value for given DataType
   */
  def default(dataType: DataType): Literal = dataType match {
    case NullType => create(null, NullType)
    case BooleanType => Literal(false)
    case ByteType => Literal(0.toByte)
    case ShortType => Literal(0.toShort)
    case IntegerType => Literal(0)
    case LongType => Literal(0L)
    case FloatType => Literal(0.0f)
    case DoubleType => Literal(0.0)
    case dt: DecimalType => Literal(Decimal(0, dt.precision, dt.scale))
    case DateType => create(0, DateType)
    case TimestampType => create(0L, TimestampType)
    case DayTimeIntervalType => create(0L, DayTimeIntervalType)
    case YearMonthIntervalType => create(0, YearMonthIntervalType)
    case StringType => Literal("")
    case BinaryType => Literal("".getBytes(StandardCharsets.UTF_8))
    case CalendarIntervalType => Literal(new CalendarInterval(0, 0, 0))
    case arr: ArrayType => create(Array(), arr)
    case map: MapType => create(Map(), map)
    case struct: StructType =>
      create(InternalRow.fromSeq(struct.fields.map(f => default(f.dataType).value)), struct)
    case udt: UserDefinedType[_] => Literal(default(udt.sqlType).value, udt)
    case other =>
      throw QueryExecutionErrors.noDefaultForDataTypeError(dataType)
  }

  private[expressions] def validateLiteralValue(value: Any, dataType: DataType): Unit = {
    def doValidate(v: Any, dataType: DataType): Boolean = dataType match {
      case _ if v == null => true
      case BooleanType => v.isInstanceOf[Boolean]
      case ByteType => v.isInstanceOf[Byte]
      case ShortType => v.isInstanceOf[Short]
      case IntegerType | DateType | YearMonthIntervalType => v.isInstanceOf[Int]
      case LongType | TimestampType | DayTimeIntervalType => v.isInstanceOf[Long]
      case FloatType => v.isInstanceOf[Float]
      case DoubleType => v.isInstanceOf[Double]
      case _: DecimalType => v.isInstanceOf[Decimal]
      case CalendarIntervalType => v.isInstanceOf[CalendarInterval]
      case BinaryType => v.isInstanceOf[Array[Byte]]
      case StringType => v.isInstanceOf[UTF8String]
      case st: StructType =>
        v.isInstanceOf[InternalRow] && {
          val row = v.asInstanceOf[InternalRow]
          st.fields.map(_.dataType).zipWithIndex.forall {
            case (dt, i) => doValidate(row.get(i, dt), dt)
          }
        }
      case at: ArrayType =>
        v.isInstanceOf[ArrayData] && {
          val ar = v.asInstanceOf[ArrayData]
          ar.numElements() == 0 || doValidate(ar.get(0, at.elementType), at.elementType)
        }
      case mt: MapType =>
        v.isInstanceOf[MapData] && {
          val map = v.asInstanceOf[MapData]
          doValidate(map.keyArray(), ArrayType(mt.keyType)) &&
            doValidate(map.valueArray(), ArrayType(mt.valueType))
        }
      case ObjectType(cls) => cls.isInstance(v)
      case udt: UserDefinedType[_] => doValidate(v, udt.sqlType)
      case _ => false
    }
    require(doValidate(value, dataType),
      s"Literal must have a corresponding value to ${dataType.catalogString}, " +
      s"but class ${Utils.getSimpleName(value.getClass)} found.")
  }
}

/**
 * An extractor that matches non-null literal values
 */
object NonNullLiteral {
  def unapply(literal: Literal): Option[(Any, DataType)] = {
    Option(literal.value).map(_ => (literal.value, literal.dataType))
  }
}

/**
 * Extractor for retrieving Float literals.
 */
object FloatLiteral {
  def unapply(a: Any): Option[Float] = a match {
    case Literal(a: Float, FloatType) => Some(a)
    case _ => None
  }
}

/**
 * Extractor for retrieving Double literals.
 */
object DoubleLiteral {
  def unapply(a: Any): Option[Double] = a match {
    case Literal(a: Double, DoubleType) => Some(a)
    case _ => None
  }
}

/**
 * Extractor for retrieving Int literals.
 */
object IntegerLiteral {
  def unapply(a: Any): Option[Int] = a match {
    case Literal(a: Int, IntegerType) => Some(a)
    case _ => None
  }
}

/**
 * Extractor for retrieving String literals.
 */
object StringLiteral {
  def unapply(a: Any): Option[String] = a match {
    case Literal(s: UTF8String, StringType) => Some(s.toString)
    case _ => None
  }
}

/**
 * Extractor for and other utility methods for decimal literals.
 */
object DecimalLiteral {
  def apply(v: Long): Literal = Literal(Decimal(v))

  def apply(v: Double): Literal = Literal(Decimal(v))

  def unapply(e: Expression): Option[Decimal] = e match {
    case Literal(v, _: DecimalType) => Some(v.asInstanceOf[Decimal])
    case _ => None
  }

  def largerThanLargestLong(v: Decimal): Boolean = v > Decimal(Long.MaxValue)

  def smallerThanSmallestLong(v: Decimal): Boolean = v < Decimal(Long.MinValue)
}

/**
 * In order to do type checking, use Literal.create() instead of constructor
 */
case class Literal (value: Any, dataType: DataType) extends LeafExpression {

  Literal.validateLiteralValue(value, dataType)

  override def foldable: Boolean = true
  override def nullable: Boolean = value == null

  private def timeZoneId = DateTimeUtils.getZoneId(SQLConf.get.sessionLocalTimeZone)

  override def toString: String = value match {
    case null => "null"
    case binary: Array[Byte] => s"0x" + DatatypeConverter.printHexBinary(binary)
    case d: ArrayBasedMapData => s"map(${d.toString})"
    case other =>
      dataType match {
        case DateType =>
          DateFormatter(timeZoneId).format(value.asInstanceOf[Int])
        case TimestampType =>
          TimestampFormatter.getFractionFormatter(timeZoneId).format(value.asInstanceOf[Long])
        case _ =>
          other.toString
      }
  }

  override def hashCode(): Int = {
    val valueHashCode = value match {
      case null => 0
      case binary: Array[Byte] => util.Arrays.hashCode(binary)
      case other => other.hashCode()
    }
    31 * Objects.hashCode(dataType) + valueHashCode
  }

  override def equals(other: Any): Boolean = other match {
    case o: Literal if !dataType.equals(o.dataType) => false
    case o: Literal =>
      (value, o.value) match {
        case (null, null) => true
        case (a: Array[Byte], b: Array[Byte]) => util.Arrays.equals(a, b)
        case (a: ArrayBasedMapData, b: ArrayBasedMapData) =>
          a.keyArray == b.keyArray && a.valueArray == b.valueArray
        case (a: Double, b: Double) if a.isNaN && b.isNaN => true
        case (a: Float, b: Float) if a.isNaN && b.isNaN => true
        case (a, b) => a != null && a == b
      }
    case _ => false
  }

  override protected def jsonFields: List[JField] = {
    // Turns all kinds of literal values to string in json field, as the type info is hard to
    // retain in json format, e.g. {"a": 123} can be an int, or double, or decimal, etc.
    val jsonValue = (value, dataType) match {
      case (null, _) => JNull
      case (i: Int, DateType) => JString(toString)
      case (l: Long, TimestampType) => JString(toString)
      case (other, _) => JString(other.toString)
    }
    ("value" -> jsonValue) :: ("dataType" -> dataType.jsonValue) :: Nil
  }

  override def eval(input: InternalRow): Any = value

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val javaType = CodeGenerator.javaType(dataType)
    if (value == null) {
      ExprCode.forNullValue(dataType)
    } else {
      def toExprCode(code: String): ExprCode = {
        ExprCode.forNonNullValue(JavaCode.literal(code, dataType))
      }
      dataType match {
        case BooleanType | IntegerType | DateType | YearMonthIntervalType =>
          toExprCode(value.toString)
        case FloatType =>
          value.asInstanceOf[Float] match {
            case v if v.isNaN =>
              toExprCode("Float.NaN")
            case Float.PositiveInfinity =>
              toExprCode("Float.POSITIVE_INFINITY")
            case Float.NegativeInfinity =>
              toExprCode("Float.NEGATIVE_INFINITY")
            case _ =>
              toExprCode(s"${value}F")
          }
        case DoubleType =>
          value.asInstanceOf[Double] match {
            case v if v.isNaN =>
              toExprCode("Double.NaN")
            case Double.PositiveInfinity =>
              toExprCode("Double.POSITIVE_INFINITY")
            case Double.NegativeInfinity =>
              toExprCode("Double.NEGATIVE_INFINITY")
            case _ =>
              toExprCode(s"${value}D")
          }
        case ByteType | ShortType =>
          ExprCode.forNonNullValue(JavaCode.expression(s"($javaType)$value", dataType))
        case TimestampType | LongType | DayTimeIntervalType =>
          toExprCode(s"${value}L")
        case _ =>
          val constRef = ctx.addReferenceObj("literal", value, javaType)
          ExprCode.forNonNullValue(JavaCode.global(constRef, dataType))
      }
    }
  }

  override def sql: String = (value, dataType) match {
    case (_, NullType | _: ArrayType | _: MapType | _: StructType) if value == null => "NULL"
    case _ if value == null => s"CAST(NULL AS ${dataType.sql})"
    case (v: UTF8String, StringType) =>
      // Escapes all backslashes and single quotes.
      "'" + v.toString.replace("\\", "\\\\").replace("'", "\\'") + "'"
    case (v: Byte, ByteType) => v + "Y"
    case (v: Short, ShortType) => v + "S"
    case (v: Long, LongType) => v + "L"
    // Float type doesn't have a suffix
    case (v: Float, FloatType) =>
      val castedValue = v match {
        case _ if v.isNaN => "'NaN'"
        case Float.PositiveInfinity => "'Infinity'"
        case Float.NegativeInfinity => "'-Infinity'"
        case _ => s"'$v'"
      }
      s"CAST($castedValue AS ${FloatType.sql})"
    case (v: Double, DoubleType) =>
      v match {
        case _ if v.isNaN => s"CAST('NaN' AS ${DoubleType.sql})"
        case Double.PositiveInfinity => s"CAST('Infinity' AS ${DoubleType.sql})"
        case Double.NegativeInfinity => s"CAST('-Infinity' AS ${DoubleType.sql})"
        case _ => v + "D"
      }
    case (v: Decimal, t: DecimalType) => v + "BD"
    case (v: Int, DateType) =>
      s"DATE '$toString'"
    case (v: Long, TimestampType) =>
      s"TIMESTAMP '$toString'"
    case (i: CalendarInterval, CalendarIntervalType) =>
      s"INTERVAL '${i.toString}'"
    case (v: Array[Byte], BinaryType) => s"X'${DatatypeConverter.printHexBinary(v)}'"
    case _ => value.toString
  }
}
