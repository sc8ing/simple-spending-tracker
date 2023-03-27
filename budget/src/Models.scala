package budget

import java.time.LocalDateTime

object models {
  case class Currency(name: Option[String], symbol: Option[String])
  case class Amount(currency: Currency, magnitude: Double)

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
