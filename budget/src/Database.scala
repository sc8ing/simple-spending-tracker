package budget

import java.io.File
import java.sql.{PreparedStatement, Connection, DriverManager, ResultSet, SQLException, Statement, Time}
import java.time.ZoneId
import zio._
import zio.macros.accessible

import models._

@accessible
trait Database {
  def setupDbIfNeeded: Task[Unit]
  def insertCurrency(c: Currency): Task[Int]
  def insertTag(c: String): Task[Int]
  def insertCategory(c: String): Task[Int]
  def insertLineItem(c: LineItem): Task[Int]
}

case class SQLDatabase(connManager: SQLConnManager) extends Database {

  def setupDbIfNeeded: Task[Unit] = connManager.withConnection { conn =>
    val createTables: Task[Unit] = ZIO.foreach(List(
        """
      |CREATE TABLE IF NOT EXISTS currency (
        |  currency_id INTEGER PRIMARY KEY AUTOINCREMENT,
        |  name TEXT NOT NULL,
        |  symbol TEXT NOT NULL
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
    ))(sql => ZIO.attempt(conn.createStatement().execute(sql))).unit

    val tablesExist: UIO[Boolean] = 
      ZIO.log("Checking if tables exist") *>
        connManager.withConnection(conn => ZIO.attempt(
          conn.prepareStatement("SELECT * FROM txn").executeQuery()
        ).as(true))
        .catchAllCause(e =>
          ZIO.logCause(
            "Assuming tables don't exist because of error reading from txn table",
            e
          ).as(false)
        )

    ZIO.ifZIO(tablesExist)(
      ZIO.log("DB already setup"),
      ZIO.log("Setting up DB") *> createTables *> ZIO.log("DB setup successfully")
    )
  }

  def insert(sql: String, setParams: PreparedStatement => Unit): Task[Int] =
    connManager.withConnection(conn =>
      ZIO.attempt {
        val stat = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
        setParams(stat)
        stat.executeUpdate()
        stat.getGeneratedKeys().getInt(1)
      }
    )

  def getId(query: String, setParams: PreparedStatement => Unit): Task[Option[Int]] =
    connManager.withConnection(conn =>
      ZIO.attempt {
        val stat = conn.prepareStatement(query)
        setParams(stat)
        stat.executeQuery()
        val rs = stat.getResultSet()
        if (rs.next()) Option(rs.getInt(1)) else None
      }
    )

  def getOrInsert(table: String, columnParams: List[(String, Any)]): Task[Int] = for {
    columns <- ZIO.succeed(columnParams.map(_._1))
    setParams = (ps: PreparedStatement) => columnParams.zipWithIndex.foreach {
      case ((_, value), index) => ps.setObject(index, value)
    }
    doInsert = insert(
      s"INSERT INTO $table (${columns.mkString(", ")}) VALUES (${columns.map(_ => "?").mkString(", ")})",
      setParams
    )
    maybeId <- getId(
      s"SELECT * FROM $table WHERE " + columns.mkString(" = ? AND "),
      setParams
    )
    id <- maybeId.fold(doInsert)(ZIO.succeed(_))
  } yield id

  override def insertCurrency(c: Currency): Task[Int] =
    getOrInsert("currency", List("name" -> c.name, "symbol" -> c.symbol))

  override def insertTag(tagName: String): Task[Int] =
    getOrInsert("tag", List("name" -> tagName))

  override def insertCategory(categoryName: String): Task[Int] =
    getOrInsert("category", List("name" -> categoryName))

  override def insertLineItem(c: LineItem): Task[Int] = c match {
    case e: LineItem.Exchange => insertExchange(e)
    case t: LineItem.Transaction => insertTransaction(t)
  }

  def insertExchange(ex: LineItem.Exchange): Task[Int] = for {
    catId <- insertCategory(ex.category)
    givenCurId <- insertCurrency(ex.givenAmount.currency)
    receivedCurId <- insertCurrency(ex.receivedAmount.currency)
    tagIds <- ZIO.foreach(ex.tags)(insertTag)
    exId <- getOrInsert(
      "exchange", List(
        "given_currency_id" -> givenCurId,
        "given_magnitude" -> ex.givenAmount.magnitude,
        "received_currency_id" -> receivedCurId,
        "received_magnitude" -> ex.receivedAmount.magnitude,
        "created_at" -> new Time(ex.datetime.atZone(ZoneId.systemDefault).toInstant.toEpochMilli)
      )
    )
    _ <- ZIO.foreach(tagIds)(tagId =>
      getOrInsert("exchange_tag", List("exchange_id" -> exId, "tag_id" -> tagId))
    )
  } yield exId

  def insertTransaction(txn: LineItem.Transaction): Task[Int] = for {
    catId <- insertCategory(txn.category)
    currencyId <- insertCurrency(txn.amount.currency)
    tagIds <- ZIO.foreach(txn.tags)(insertTag)
    txnId <- getOrInsert(
      "txn", List(
        "currency_id" -> currencyId,
        "amount" -> txn.amount.magnitude,
        "category_id" -> catId,
        "notes" -> txn.notes,
        "created_at" -> new Time(txn.datetime.atZone(ZoneId.systemDefault).toInstant.toEpochMilli)
      )
    )
    _ <- ZIO.foreach(tagIds)(tagId =>
      getOrInsert("txn_tag", List("txn_id" -> txnId, "tag_id" -> tagId))
    )
  } yield txnId
}
object SQLDatabase {
  val liveLayer = ZLayer.fromFunction(SQLDatabase(_))
}

trait SQLConnManager {
  def withConnection[A](f: Connection => Task[A]): Task[A]
}

case class SQLiteConfig(dbPath: File)

case class SQLiteConnManager(config: SQLiteConfig) extends SQLConnManager {
  def withConnection[A](f: Connection => Task[A]): Task[A] = {
    val manageConn = ZIO.acquireReleaseExit(
        ZIO.attempt(DriverManager.getConnection("jdbc:sqlite:" + config.dbPath))
          .tap(conn => ZIO.attempt(conn.setAutoCommit(false)))
      ) {
        case (conn, Exit.Success(_)) =>
          ZIO.succeed { conn.commit(); conn.close() }
        case (conn, Exit.Failure(c)) =>
          ZIO.logCause(c) *>
            ZIO.succeed { conn.rollback(); conn.close() }
      }
    ZIO.scoped(manageConn.flatMap(f))
  }
}
object SQLiteConnManager {
  val liveLayer = ZLayer.fromFunction(SQLiteConnManager(_))
}
