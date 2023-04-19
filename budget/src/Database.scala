package budget

import java.io.File
import java.sql.{PreparedStatement, Connection, DriverManager, ResultSet, SQLException, Statement, Time}
import java.time.ZoneId
import zio._
import zio.macros.accessible

import models._

@accessible
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
    val createTables: RIO[Connection, Unit] = ZIO.foreach(List(
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
    ))(sql => ZIO.serviceWithZIO[Connection](conn =>
      ZIO.attempt(conn.createStatement().execute(sql))
    )).unit

    val tablesExist: RIO[Connection, Boolean] = 
      ZIO.log("Checking if tables exist") *>
        ZIO.serviceWithZIO[Connection](conn => ZIO.attempt(
          conn.prepareStatement("SELECT * FROM txn").executeQuery().close()
        ).as(true))
        .catchAllCause(e =>
          ZIO.logCause(
            "Assuming tables don't exist because of error reading from txn table",
            e
          ).as(false)
        )

    val addDefaultCurrency = insertCurrency(Currency(
      Some(defaultSettings.currencyName),
      Some(defaultSettings.currencySymbol)
    ))

    ZIO.ifZIO(tablesExist)(
      ZIO.log("DB already setup"),
      ZIO.log("Setting up DB") *> createTables
    ) *> addDefaultCurrency *> ZIO.log("DB setup successfully")
  }

  def insert(sql: String, setParams: PreparedStatement => Unit): RIO[Connection, Int] =
    ZIO.serviceWithZIO[Connection](conn => for {
      stat <- ZIO.attempt(conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS))
      _ <- ZIO.attempt(setParams(stat))
      _ <- ZIO.log("Running SQL: " + stat.toString.replace('\n', ' '))
      res <- ZIO.attempt {
        stat.executeUpdate()
        val rs = stat.getGeneratedKeys()
        val res = rs.getInt(1)
        rs.close()
        res
      }
    } yield res)

  def runQuery(query: String, setParams: PreparedStatement => Unit): RIO[Connection with Scope, ResultSet] =
    ZIO.serviceWithZIO[Connection](conn => for {
      stat <- ZIO.attempt(conn.prepareStatement(query))
      _ <- ZIO.attempt(setParams(stat))
      _ <- ZIO.log("Running SQL: " + stat.toString.replace('\n', ' '))
      res <- ZIO.acquireRelease(
        ZIO.attempt(stat.executeQuery()))(
        rs => ZIO.attempt(rs.close()).logError("Couldn't close result set").ignore
      )
    } yield res)

  def getId(query: String, setParams: PreparedStatement => Unit): RIO[Connection, Option[Int]] =
    ZIO.scoped(runQuery(query, setParams).flatMap(rs => ZIO.attempt {
      val res = if (rs.next()) Option(rs.getInt(1)) else None
      if (rs.next()) throw new SQLException("Expected only one result!")
      res
    }))

  def getOrInsert(table: String, columnParams: List[(String, Any)]): RIO[Connection, Int] = for {
    columns <- ZIO.succeed(columnParams.map(_._1))
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
    id <- maybeId.fold(doInsert)(ZIO.succeed(_))
  } yield id

  override def insertCurrency(c: Currency): RIO[Connection, Int] =
    // Not using getOrInsert here because we need to use OR
    ZIO.scoped(runQuery(
      "SELECT currency_id, name, symbol FROM currency WHERE name = ? OR symbol = ?",
      (ps: PreparedStatement) => {
        ps.setString(1, c.name.orNull)
        ps.setString(2, c.symbol.orNull)
      }
    ).map { rs =>
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
        ZIO.when(c.name.exists(_ != dbName.orNull))(
          ZIO.log(s"Updating currency $id because name used is not what was stored (${c.name} != $dbName)") *>
          ZIO.scoped(runQuery(
            "UPDATE currency SET name = ? WHERE currency_id = ?",
            (ps: PreparedStatement) => {
              ps.setString(1, c.name.orNull)
              ps.setInt(2, id)
            }
          ))
        ) *> ZIO.when(c.symbol.exists(_ != dbSym.orNull))(
          ZIO.log(s"Updating currency $id because symbol used is not what was stored (${c.symbol} != $dbSym)") *>
          ZIO.scoped(runQuery(
            "UPDATE currency SET symbol = ? WHERE currency_id = ?",
            (ps: PreparedStatement) => {
              ps.setString(1, c.symbol.orNull)
              ps.setInt(2, id)
            }
          ))
        ) *> ZIO.succeed(id)

      case Nil =>
        ZIO.log(s"Adding new currency $c") *>
        insert(
          "INSERT INTO currency (name, symbol) VALUES (?, ?)",
          (ps: PreparedStatement) => {
            ps.setString(1, c.name.orNull)
            ps.setString(2, c.symbol.orNull)
          }
        )

      case o =>
        ZIO.fail(new Exception(s"Ambiguous currency $c: $o"))
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
    _ <- ZIO.log(s"Inserting exchange: $ex")
    catId <- insertCategory(ex.category)
    givenCurId <- insertCurrency(ex.givenAmount.currency)
    receivedCurId <- insertCurrency(ex.receivedAmount.currency)
    tagIds <- ZIO.foreach(ex.tags)(insertTag)
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
    _ <- ZIO.foreach(tagIds)(tagId =>
      getOrInsert("exchange_tag", List("exchange_id" -> exId, "tag_id" -> tagId))
    )
  } yield exId

  def insertTransaction(txn: LineItem.Transaction): RIO[Connection, Int] = for {
    catId <- insertCategory(txn.category)
    currencyId <- insertCurrency(txn.amount.currency)
    tagIds <- ZIO.foreach(txn.tags)(insertTag)
    txnId <- getOrInsert(
      "txn", List(
        "currency_id" -> currencyId,
        "amount"      -> txn.amount.magnitude,
        "category_id" -> catId,
        "notes"       -> txn.notes,
        "created_at"  -> new Time(txn.datetime.atZone(ZoneId.systemDefault).toInstant.toEpochMilli)
      )
    )
    _ <- ZIO.foreach(tagIds)(tagId =>
      getOrInsert("txn_tag", List("txn_id" -> txnId, "tag_id" -> tagId))
    )
  } yield txnId
}
object SQLDatabase {
  val liveLayer = ZLayer.fromFunction(SQLDatabase(_, _))
}

@accessible
trait SQLConnManager {
  def withConnection[R, A](f: RIO[R with Connection, A]): RIO[R, A]
}

case class SQLiteConfig(dbPath: File)

case class SQLiteConnManager(config: SQLiteConfig) extends SQLConnManager {
  def withConnection[R, A](f: RIO[R with Connection, A]): RIO[R, A] = {
    val manageConn: RIO[Scope, Connection] = ZIO.acquireReleaseExit(
        ZIO.attempt(DriverManager.getConnection("jdbc:sqlite:" + config.dbPath))
          .tap(conn => ZIO.attempt(conn.setAutoCommit(false)))
      ) {
        case (conn, Exit.Success(_)) =>
          ZIO.log("Committing transaction") *>
          ZIO.succeed { conn.commit(); conn.close() }

        case (conn, Exit.Failure(c)) =>
          ZIO.logCause("Rolling back transaction", c) *>
          ZIO.succeed { conn.rollback(); conn.close() }
      }
    val effectWithConnSatisfied = manageConn.flatMap(conn =>
      f.provideSomeLayer[R](ZLayer.succeed(conn))
    )
    ZIO.scoped[R](effectWithConnSatisfied)
  }
}
object SQLiteConnManager {
  val liveLayer = ZLayer.fromFunction(SQLiteConnManager(_))
}
