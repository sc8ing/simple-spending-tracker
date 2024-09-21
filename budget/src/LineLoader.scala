package budget

import budget.models.*
import zio.*
import zio.ZIO.*

trait LineLoader:
  def loadFromString(rawTxnLogs: String): Task[Unit]

case class SqliteLineLoader(
  cm: SQLConnManager,
  db: Database,
  parser: Parser[String],
  adjuster: Adjuster[LineItem]
) extends LineLoader {
  override def loadFromString(rawTxnLogs: String): Task[Unit] = for {
    _                 <- Console.printLine("Running")
    maybeParsed        = parser.parseLineItemBlocks(rawTxnLogs)
    lineItems         <- fromEither(maybeParsed).mapError(new Throwable(_))
    adjustedLineItems <- foreach(lineItems)(adjuster.adjust).map(_.reverse)
    _                 <- cm.withConnection(foreachDiscard(adjustedLineItems)(db.insertLineItem))
    _                 <- Console.printLine(s"Loaded ${adjustedLineItems.size} line items")
  } yield ()
}

object SqliteLineLoader:
  val layer = ZLayer.fromFunction(SqliteLineLoader(_, _, _, _))
