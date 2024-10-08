package budget

import java.time.format.DateTimeFormatter
import java.time.{LocalDate, LocalDateTime}
import scala.collection.JavaConverters.*
import scala.util.Try

import budget.models.*
import zio.ZLayer

trait Parser[Err] {
  def parseLineItemBlocks(s: String): Either[Err, List[LineItem]]
}

case class SimpleInefficientParser() extends Parser[String] {

  override def parseLineItemBlocks(s: String): Either[String, List[LineItem]] =
    s.trim.split("\n\n").toList.foldLeft[Either[String, List[LineItem]]](Right(List.empty)) {
      case (Right(lineItems), block) =>
        parseLineItemBlock(block).map(_ ++ lineItems)
      case (err@Left(_), _) =>
        err
    }

  def parseLineItemBlock(s: String): Either[String, List[LineItem]] = s.lines.toList.asScala.toList match {
    case date :: lineItems =>
      parseDate(date).flatMap(lineDate =>
        lineItems.foldLeft[Either[String, List[LineItem]]](Right(List.empty)) {
          case (Right(lineItems), line) =>
            parseLineItem(lineDate, line).map(_ :: lineItems)
          case (err@Left(_), _) =>
            err
        }
      )
    case _ =>
      Left(s"Bad block: $s")
  }

  def parseLineItem(dateTime: LocalDateTime, s: String): Either[String, LineItem] =
      parseExchangeLine(dateTime, s)
        .orElse(parseTransactionLine(dateTime, s))

  def parseExchangeLine(dateTime: LocalDateTime, s: String): Either[String, LineItem.Exchange] =
    s.split("->").toList.map(_.trim) match {
      case amountGiven :: amountReceivedWithMeta :: Nil =>
        amountReceivedWithMeta.split(",").toList match {
          case amountReceived :: maybeMeta =>
            val meta = maybeMeta match {
              case catAndTags :: notes =>
                parseCategoryAndAnyTags(catAndTags.trim).map { case (cat, tags) =>
                  (cat, tags, notes.mkString(","))
                }
              case _ =>
                Right(("exchange", List.empty, ""))
            }

            for {
              amtGiven <- parseAmountWithOptionalCurrency(amountGiven)
              amtRec <- parseAmountWithOptionalCurrency(amountReceived)
              met <- meta
              (cat, tags, nots) = met
            } yield LineItem.Exchange(amtGiven, amtRec, dateTime, nots, cat, tags)

          case _ =>
            Left(s"Bad line: $s")
        }
      case _ =>
        Left(s"Bad line: $s")
    }

  def parseTransactionLine(dateTime: LocalDateTime, s: String): Either[String, LineItem.Transaction] =
    s.split(",").toList.map(_.trim) match {
      case amount :: catAndTags :: notes =>
        for {
          amount <- parseAmountWithOptionalCurrency(amount)
          catAndTags <- parseCategoryAndAnyTags(catAndTags)
          (cat, tags) = catAndTags
        } yield LineItem.Transaction(amount, dateTime, notes.mkString(","), cat, tags)
      case _ =>
        Left(s"Bad line: $s")
    }

  def parseAmountWithOptionalCurrency(s: String): Either[String, Amount] = {
    val isIncome = s.trim.startsWith("+")
    val magnitudeString = s.trim
        .drop(if (isIncome) 1 else 0) // drop leading '+'
        .takeWhile(c => c.isDigit || c == '.')

    def currency(magLength: Int) = s.drop(magLength).trim.split(" ").toList match {
      case name :: symbol :: Nil if name.length <= symbol.length =>
        Left(s"Bad amount: $s (symbol must be shorter than name, order is name then symbol, or just symbol without a name)")
      case name :: symbol :: Nil =>
        Right(Currency(name = Some(name), symbol = Some(symbol)))
      case symbol :: Nil =>
        Right(Currency(name = None, symbol = Some(symbol).filterNot(_.isEmpty)))
      case _ =>
        Left(s"Bad amount: $s")
    }

    for {
      mag <- Try(magnitudeString.toDouble).toEither.left.map(t => 
        s"Couldn't convert '$magnitudeString' to double in '$s': $t"
      )
      cur <- currency(magnitudeString.length + (if (isIncome) 1 else 0))
    } yield Amount(cur, mag * (if (isIncome) -1 else 1)) // treat income as a negative expense
  }

  def parseCategoryAndAnyTags(s: String): Either[String, (String, List[String])] =
    s.split(" ").toList match {
      case cat :: tags => Right((cat, tags))
      case _ => Left(s"Bad category and tags: $s")
    }

  def parseDate(s: String): Either[String, LocalDateTime] = {
    val format = DateTimeFormatter.ofPattern("M/d/yy")
    // Maybe I'll add support for recording specific times later
    Try(LocalDate.parse(s, format).atTime(0, 0))
      .toEither.left.map(_.toString)
  }
}
object SimpleInefficientParser {
  val liveLayer = ZLayer.succeed(SimpleInefficientParser())
}
