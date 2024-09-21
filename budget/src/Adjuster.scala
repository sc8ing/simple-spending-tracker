package budget

import budget.models.*
import budget.models.LineItem.*
import zio.*
import zio.ZIO.*
import zio.stream.*

trait Adjuster[A]:
  def adjust(a: A): Task[A]

/** Theoretically going to use this same thing over a web interface or something */
trait UserInteractor:
  def promptBool(query: String): Task[Boolean]
  def promptInput(query: String): Stream[Throwable, String]

case class CLIUserInteractor() extends UserInteractor {
  def promptBool(query: String): Task[Boolean] = for {
    input <- Console.readLine(query + " (true/false)")
    bool <- attempt(input.toBoolean).catchSome {
      case e: IllegalArgumentException =>
        Console.printLine("Try either 'true' or 'false'") *> promptBool(query)
    }
  } yield bool

  // Reads until it gets two empty lines in a row. Ignores lines starting with "--"
  def promptInput(query: String): Stream[Throwable, String] = {
    var lastReadLineEmpty = false
    def readLine: IO[Option[Throwable], String] = Console.readLine.mapError(Some(_)).flatMap {
      case s if s.isEmpty && lastReadLineEmpty =>
        fail(None)
      case s if s.isEmpty =>
        lastReadLineEmpty = true
        succeed(s)
      case s =>
        lastReadLineEmpty = false
        if (s.startsWith("--")) readLine
        else succeed(s)
    }
    ZStream.repeatZIOOption(readLine)
  }
}
object CLIUserInteractor:
  val liveLayer = ZLayer.succeed(CLIUserInteractor())

case class QuestionAnswerAdjuster(
  prompter: UserInteractor,
  defaults: DefaultSettings
) extends Adjuster[LineItem] {
  override def adjust(a: LineItem): Task[LineItem] = a match {
    case txn: Transaction => composeAdjusters(transactionAdjusters)(txn)
    case ex: Exchange => composeAdjusters(exchangeAdjusters)(ex)
  }

  def composeAdjusters[A](adjusters: List[A => Task[A]]): (A => Task[A]) =
    adjusters.fold(attempt(_: A))((a1, a2) => a => a1(a).flatMap(a2))

  val defaultCurrencyAdjuster = (cur: Currency) => cur match {
    case Currency(None, None) =>
      succeed(Currency(Some(defaults.currencyName), Some(defaults.currencySymbol)))
    case _ => succeed(cur)
  }

  val transactionAdjusters: List[Transaction => Task[Transaction]] = List(
    // Negative income adjuster
    (txn: Transaction) =>
      if (txn.category == "income" && txn.amount.magnitude >= 0)
        prompter.promptBool(
          s"Income category has positive spend amount. Convert to negative?\n\t$txn"
        ).map {
          case false =>
            txn
          case true =>
            txn.copy(amount = txn.amount.copy(magnitude = txn.amount.magnitude * (-1)))
        }
      else
        succeed(txn),
    (txn: Transaction) =>
      defaultCurrencyAdjuster(txn.amount.currency)
        .map(c => txn.copy(amount = txn.amount.copy(currency = c)))
  )

  // Don't adjust exchange currencies with defaults, force to be explicit when converting
  val exchangeAdjusters: List[Exchange => Task[Exchange]] = List.empty
}
object QuestionAnswerAdjuster:
  val liveLayer = ZLayer.fromFunction(QuestionAnswerAdjuster(_, _))
