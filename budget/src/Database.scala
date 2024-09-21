package budget

import java.io.File
import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet, SQLException, Statement, Time}
import java.time.ZoneId

import budget.models.*
import zio.*
import zio.ZIO.*

trait Database {
  def setupDbIfNeeded:             RIO[Connection, Unit]
  def insertCurrency(c: Currency): RIO[Connection, Int]
  def insertTag(c: String):        RIO[Connection, Int]
  def insertCategory(c: String):   RIO[Connection, Int]
  def insertLineItem(c: LineItem): RIO[Connection, Int]
}

case class SQLDatabase(
  connManager: SQLConnManager,
  defaultSettings: DefaultSettings
) extends Database {

  def setupDbIfNeeded: RIO[Connection, Unit] = {
    val createTables: RIO[Connection, Unit] = foreach(List(
        """
      |CREATE TABLE IF NOT EXISTS currency (
        |  currency_id INTEGER PRIMARY KEY AUTOINCREMENT,
        |  name TEXT NULL,
        |  symbol TEXT NULL
        |);
      """.stripMargin,
      """
      |CREATE TABLE IF NOT EXISTS category (
        |  category_id INTEGER PRIMARY KEY AUTOINCREMENT,
        |  name TEXT NOT NULL
        |);
      """.stripMargin,
      """
      |CREATE TABLE IF NOT EXISTS tag (
        |  tag_id INTEGER PRIMARY KEY AUTOINCREMENT,
        |  name TEXT NOT NULL
        |);
      """.stripMargin,
      """
      |CREATE TABLE IF NOT EXISTS exchange (
        |  exchange_id INTEGER PRIMARY KEY AUTOINCREMENT,
        |  given_currency_id INTEGER NOT NULL,
        |  given_magnitude INTEGER NOT NULL,
        |  received_currency_id INTEGER NOT NULL,
        |  received_magnitude INTEGER NOT NULL,
        |  category_id INTEGER NOT NULL,
        |  notes TEXT NULL,
        |  created_at DATETIME NOT NULL,
        |  FOREIGN KEY (given_currency_id) REFERENCES currency (currency_id),
        |  FOREIGN KEY (received_currency_id) REFERENCES currency (currency_id)
        |);
      """.stripMargin,
      """
      |CREATE TABLE IF NOT EXISTS exchange_tag (
        |  exchange_id INTEGER NOT NULL,
        |  tag_id INTEGER NOT NULL,
        |  FOREIGN KEY (exchange_id) REFERENCES exchange (exchange_id),
        |  FOREIGN KEY (tag_id) REFERENCES tag (tag_id)
        |);
      """.stripMargin,
      """
      |CREATE TABLE IF NOT EXISTS txn (
        |  txn_id INTEGER PRIMARY KEY AUTOINCREMENT,
        |  currency_id INTEGER NOT NULL,
        |  amount INTEGER NOT NULL,
        |  category_id INTEGER NOT NULL,
        |  notes TEXT NOT NULL,
        |  created_at DATETIME NOT NULL,
        |  FOREIGN KEY (currency_id) REFERENCES currency (currency_id),
        |  FOREIGN KEY (category_id) REFERENCES category (category_id)
        |);
      """.stripMargin,
      """
      |CREATE TABLE IF NOT EXISTS txn_tag (
        |  txn_id INTEGER NOT NULL,
        |  tag_id INTEGER NOT NULL,
        |  FOREIGN KEY (txn_id) REFERENCES txn (txn_id),
        |  FOREIGN KEY (tag_id) REFERENCES tag (tag_id)
        |);
      """.stripMargin
    ))(sql => serviceWithZIO[Connection](conn =>
      attempt(conn.createStatement().execute(sql))
    )).unit

    val tablesExist: RIO[Connection, Boolean] = 
      log("Checking if tables exist") *>
        serviceWithZIO[Connection](conn => attempt(
          conn.prepareStatement("SELECT * FROM txn").executeQuery().close()
        ).as(true))
        .catchAllCause(e =>
          logCause(
            "Assuming tables don't exist because of error reading from txn table",
            e
          ).as(false)
        )

    val addDefaultCurrency = insertCurrency(Currency(
      Some(defaultSettings.currencyName),
      Some(defaultSettings.currencySymbol)
    ))

    ifZIO(tablesExist)(
      log("DB already setup"),
      log("Setting up DB") *> createTables
    ) *> addDefaultCurrency *> log("DB setup successfully")
  }

  def insert(sql: String, setParams: PreparedStatement => Unit): RIO[Connection, Int] =
    serviceWithZIO[Connection](conn => for {
      stat <- attempt(conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS))
      _ <- attempt(setParams(stat))
      _ <- log("Running SQL: " + stat.toString.replace('\n', ' '))
      res <- attempt {
        stat.executeUpdate()
        val rs = stat.getGeneratedKeys()
        val res = rs.getInt(1)
        rs.close()
        res
      }
    } yield res)

  def runQuery(sql: String, setParams: PreparedStatement => Unit, isRead: Boolean = true): RIO[Connection & Scope, Option[ResultSet]] =
    serviceWithZIO[Connection](conn => for {
      stat <- attempt(conn.prepareStatement(sql))
      _ <- attempt(setParams(stat))
      _ <- log("Running SQL: " + stat.toString.replace('\n', ' '))
      res <- acquireRelease(
        attempt(if (isRead) Some(stat.executeQuery())
                  else        { stat.executeUpdate(); None })) {
        case None => unit
        case Some(rs) => attempt(rs.close()).logError("Couldn't close result set").ignore
      }
    } yield res)

  def getId(query: String, setParams: PreparedStatement => Unit): RIO[Connection, Option[Int]] =
    scoped(runQuery(query, setParams).flatMap(getOrFail(_)).flatMap(rs => attempt {
      val res = if (rs.next()) Option(rs.getInt(1)) else None
      if (rs.next()) throw new SQLException("Expected only one result!")
      res
    }))

  def getOrInsert(table: String, columnParams: List[(String, Any)]): RIO[Connection, Int] = for {
    columns <- succeed(columnParams.map(_._1))
    setParams = (ps: PreparedStatement) => columnParams.zipWithIndex.foreach {
      case ((_, value), index) =>
        ps.setObject(index + 1, value)
    }
    doInsert = insert(
      s"INSERT INTO $table (${columns.mkString(", ")}) VALUES (${columns.map(_ => "?").mkString(", ")})",
      setParams
    )
    maybeId <- getId(
      s"SELECT * FROM $table WHERE " + columns.mkString(" = ? AND ") + " = ?",
      setParams
    )
    id <- maybeId.fold(doInsert)(succeed(_))
  } yield id

  override def insertCurrency(c: Currency): RIO[Connection, Int] =
    // Not using getOrInsert here because we need to use OR
    scoped(runQuery(
      "SELECT currency_id, name, symbol FROM currency WHERE name = ? OR symbol = ?",
      (ps: PreparedStatement) => {
        ps.setString(1, c.name.orNull)
        ps.setString(2, c.symbol.orNull)
      }
    ).flatMap(getOrFail(_)).map { rs =>
      var idsOfCurrencies = List.empty[(Int, Option[String], Option[String])]
      while (rs.next())
        idsOfCurrencies = (
            rs.getInt("currency_id"),
            Option(rs.getString("name")),
            Option(rs.getString("symbol"))
          ) +: idsOfCurrencies
      idsOfCurrencies
    }).flatMap {
      case (id, dbName, dbSym) :: Nil =>
        when(c.name.exists(_ != dbName.orNull))(
          log(s"Updating currency $id because name used is not what was stored (${c.name} != $dbName)") *>
          scoped(runQuery(
            "UPDATE currency SET name = ? WHERE currency_id = ?",
            (ps: PreparedStatement) => {
              ps.setString(1, c.name.orNull)
              ps.setInt(2, id)
            },
            isRead = false
          ))
        ) *> when(c.symbol.exists(_ != dbSym.orNull))(
          log(s"Updating currency $id because symbol used is not what was stored (${c.symbol} != $dbSym)") *>
          scoped(runQuery(
            "UPDATE currency SET symbol = ? WHERE currency_id = ?",
            (ps: PreparedStatement) => {
              ps.setString(1, c.symbol.orNull)
              ps.setInt(2, id)
            },
            isRead = false
          ))
        ) *> succeed(id)

      case Nil =>
        log(s"Adding new currency $c") *>
        insert(
          "INSERT INTO currency (name, symbol) VALUES (?, ?)",
          (ps: PreparedStatement) => {
            ps.setString(1, c.name.orNull)
            ps.setString(2, c.symbol.orNull)
          }
        )

      case o =>
        fail(new Exception(s"Ambiguous currency $c: $o"))
    }

  override def insertTag(tagName: String): RIO[Connection, Int] =
    getOrInsert("tag", List("name" -> tagName))

  override def insertCategory(categoryName: String): RIO[Connection, Int] =
    getOrInsert("category", List("name" -> categoryName))

  override def insertLineItem(c: LineItem): RIO[Connection, Int] = c match {
    case e: LineItem.Exchange => insertExchange(e)
    case t: LineItem.Transaction => insertTransaction(t)
  }

  def insertExchange(ex: LineItem.Exchange): RIO[Connection, Int] = for {
    _ <- log(s"Inserting exchange: $ex")
    catId <- insertCategory(ex.category)
    givenCurId <- insertCurrency(ex.givenAmount.currency)
    receivedCurId <- insertCurrency(ex.receivedAmount.currency)
    tagIds <- foreach(ex.tags)(insertTag)
    exId <- getOrInsert(
      "exchange", List(
        "given_currency_id"    -> givenCurId,
        "given_magnitude"      -> ex.givenAmount.magnitude,
        "received_currency_id" -> receivedCurId,
        "received_magnitude"   -> ex.receivedAmount.magnitude,
        "category_id"          -> catId,
        "notes"                -> ex.notes,
        "created_at"           -> new Time(ex.datetime.atZone(ZoneId.systemDefault).toInstant.toEpochMilli)
      )
    )
    _ <- foreach(tagIds)(tagId =>
      getOrInsert("exchange_tag", List("exchange_id" -> exId, "tag_id" -> tagId))
    )
  } yield exId

  def insertTransaction(txn: LineItem.Transaction): RIO[Connection, Int] = for {
    catId <- insertCategory(txn.category)
    currencyId <- insertCurrency(txn.amount.currency)
    tagIds <- foreach(txn.tags)(insertTag)
    txnId <- getOrInsert(
      "txn", List(
        "currency_id" -> currencyId,
        "amount"      -> txn.amount.magnitude,
        "category_id" -> catId,
        "notes"       -> txn.notes,
        "created_at"  -> new Time(txn.datetime.atZone(ZoneId.systemDefault).toInstant.toEpochMilli)
      )
    )
    _ <- foreach(tagIds)(tagId =>
      getOrInsert("txn_tag", List("txn_id" -> txnId, "tag_id" -> tagId))
    )
  } yield txnId
}
object SQLDatabase {
  val liveLayer = ZLayer.fromFunction(SQLDatabase(_, _))
}

trait SQLConnManager {
  def withConnection[R, A](f: RIO[R & Connection, A]): RIO[R, A]
}

case class SQLiteConfig(dbPath: File)

case class SQLiteConnManager(config: SQLiteConfig) extends SQLConnManager {
  def withConnection[R, A](f: RIO[R & Connection, A]): RIO[R, A] = {
    val manageConn: RIO[Scope, Connection] = acquireReleaseExit(
        attempt(DriverManager.getConnection("jdbc:sqlite:" + config.dbPath))
          .tap(conn => attempt(conn.setAutoCommit(false)))
      ) {
        case (conn, Exit.Success(_)) =>
          log("Committing transaction") *>
          succeed { conn.commit(); conn.close() }

        case (conn, Exit.Failure(c)) =>
          logCause("Rolling back transaction", c) *>
          succeed { conn.rollback(); conn.close() }
      }
    val effectWithConnSatisfied = manageConn.flatMap(conn =>
      f.provideSomeLayer[R](ZLayer.succeed(conn))
    )
    scoped[R](effectWithConnSatisfied)
  }
}
object SQLiteConnManager {
  val liveLayer = ZLayer.fromFunction(SQLiteConnManager(_))
}
