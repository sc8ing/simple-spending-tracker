package budget

import zio._
import zio.stream._
import zio.macros.accessible

import models._
import models.LineItem._

@accessible
trait Adjuster[A] {
  def adjust(a: A): Task[A]
}

/** Theoretically going to use this same thing over a web interface or something */
@accessible
trait UserInteractor {
  def promptBool(query: String): Task[Boolean]
  def promptInput(query: String): Stream[Throwable, String]
}
case class CLIUserInteractor() extends UserInteractor {
  def promptBool(query: String): Task[Boolean] = for {
    input <- Console.readLine(query)
    bool <- ZIO.attempt(input.toBoolean).catchSome {
      case e: IllegalArgumentException =>
        Console.printLine("Try either 'true' or 'false'") *> promptBool(query)
    }
  } yield bool

  def promptInput(query: String): Stream[Throwable, String] =
    ZStream.fromZIOOption(Console.readLine.mapError(Option(_)).flatMap {
      case "EOF" => ZIO.fail(None)
      case s => ZIO.succeed(s)
    })
}
object CLIUserInteractor {
  val liveLayer = ZLayer.succeed(CLIUserInteractor())
}

case class QuestionAnswerAdjuster(prompter: UserInteractor) extends Adjuster[LineItem] {
  override def adjust(a: LineItem): Task[LineItem] = a match {
    case txn: Transaction => composeAdjusters(transactionAdjusters)(txn)
    case ex: Exchange => composeAdjusters(exchangeAdjusters)(ex)
  }

  def composeAdjusters[A](adjusters: List[A => Task[A]]): (A => Task[A]) =
    adjusters.fold(ZIO.attempt(_: A))((a1, a2) => a => a1(a).flatMap(a2))

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
        ZIO.succeed(txn)

  )
  val exchangeAdjusters: List[Exchange => Task[Exchange]] = List.empty
}
object QuestionAnswerAdjuster {
  val liveLayer = ZLayer.fromFunction(QuestionAnswerAdjuster(_))
}
