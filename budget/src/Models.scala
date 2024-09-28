package budget

import java.time.LocalDateTime
import upickle.default.*

object models {
  case class Currency(name: Option[String], symbol: Option[String])
  object Currency:
    implicit def currencyRW: ReadWriter[Currency] = macroRW[Currency]
  case class Amount(currency: Currency, magnitude: Double)
  object Amount:
    implicit def amountRW: ReadWriter[Amount] = macroRW[Amount]

  /** An individual recording in the budget text file */
  sealed trait LineItem {
    val datetime: LocalDateTime
    val notes: String
    val category: String
    val tags: List[String]
  }
  object LineItem {
    case class Transaction(
      amount: Amount,
      datetime: LocalDateTime,
      notes: String,
      category: String,
      tags: List[String]
    ) extends LineItem
    object Transaction:
      import LocalDateTimeRW.*
      implicit def transactionRW: ReadWriter[Transaction] = macroRW[Transaction]

    case class Exchange(
      givenAmount: Amount,
      receivedAmount: Amount,
      datetime: LocalDateTime,
      notes: String,
      category: String,
      tags: List[String]
    ) extends LineItem
  }
}
