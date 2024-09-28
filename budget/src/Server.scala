package budget

import java.time.format.DateTimeFormatter
import java.time.{LocalDate, LocalDateTime}

import scalatags.Text.all.*
import upickle.default.*
import zio.*
import zio.ZIO.*
import budget.models.Amount

case class User(username: String)
opaque type Token = String
object Token:
  def apply(str: String): Token = str
extension (t: Token)
  def toString: String = t

trait Auther:
  def validateToken(token: String): Option[User]
  def login(username: String, password: String): Option[Token]

case class SqliteAuther() extends Auther:
  def validateToken(token: String): Option[User] =
    if token.startsWith("valid") then Some(User("jacob")) else None
  def login(username: String, password: String): Option[Token] =
    if username == "jacob" && password == "bennett" then Some(Token("valid")) else None

object SqliteAuther:
  val liveLayer = ZLayer.succeed(SqliteAuther())

case class LoginRoutes(auther: Auther)(using cc: castor.Context, log: cask.Logger) extends cask.Routes:
  import LoginRoutes.SessionTokenCookieName

  @cask.get("/login")
  def login(ctx: cask.Request) = LoginRoutes.getLoggedInUser(ctx)(using auther) match
    case Some(user) =>
      log.debug(s"Already logged in as $user, redirecting to /")
      cask.Response(html(), headers = Seq("Location" -> "/"), statusCode = 302)
    case None =>
      cask.Response(body(
        form(action := "/login", method := "post")(
          input(`type` := "text", name := "username", placeholder := "Username"),
          input(`type` := "password", name := "password", placeholder := "Password"),
          input(`type` := "submit")
        )
      ))

  @cask.postForm("/login")
  def login(username: String, password: String) =
    log.debug(s"Logging in $username $password")
    auther.login(username, password) match
      case None =>
        cask.Redirect("/login")
      case Some(token) =>
        cask.Response(
          "Logged in",
          cookies = Seq(cask.Cookie(SessionTokenCookieName, token.toString)),
          headers = Seq("Location" -> "/"),
          statusCode = 302
        )

  @cask.get("/logout")
  def logout() =
    cask.Response(
      p(
        "Logged out",
        br(),
        a(href := "/login")("Log in again")
      ),
      cookies = Seq(cask.Cookie(SessionTokenCookieName, "", expires = java.time.Instant.EPOCH))
    )

  initialize()

object LoginRoutes:
  val SessionTokenCookieName = "loginSessionToken"

  def getLoggedInUser(ctx: cask.Request)(using auther: Auther): Option[User] =
    ctx.cookies.get(SessionTokenCookieName).map(_.value) flatMap
    auther.validateToken

  class requireLogin(implicit auther: Auther) extends cask.RawDecorator:
    def wrapFunction(ctx: cask.Request, delegate: Delegate) =
      getLoggedInUser(ctx).map(user => delegate(Map("user" -> user)))
        .getOrElse(cask.router.Result.Success(cask.Redirect("/login")))

case class ServerRoutes(
  auther: Auther,
  runtime: Runtime[Any],
  db: Database,
  cm: SQLConnManager
)(using cc: castor.Context, log: cask.Logger) extends cask.Routes:
  import LoginRoutes.requireLogin
  given a: Auther = auther
  def runZio[A](f: Task[A]): A =
    Unsafe.unsafe { us =>
      given u: Unsafe = us
      runtime.unsafe.run(f).getOrThrow()
    }

  @cask.staticResources("/static")
  def staticResources() = "static"

  def page(content: Frag*) = html(
    head(
      link(rel := "stylesheet", href := "/static/style.css"),
      script(src := "/static/scripts/htmx.js")
    ),
    body(content)
  )

  def recentTxns(n: Int) = runZio(for
      txns <- cm.withConnection(db.getRecentTxns(n))
    yield table(
      txns.map { txn =>
        import models.Currency
        val amt = txn.amount match
          case Amount(Currency(_, Some(sym)), magnitude) => sym + magnitude
          case Amount(Currency(Some(name), None), magnitude) => magnitude + " " + name
          case Amount(Currency(None, None), magnitude) => magnitude + " (unknown currency)"
        tr(
          td(txn.datetime.toLocalDate.toString),
          td(amt),
          td(txn.category),
          td(txn.tags),
          td(txn.notes)
        )
      }
    )
  )

  @requireLogin
  @cask.get("/")
  def hello() =
    val currentDate = LocalDate.now.format(DateTimeFormatter.ISO_LOCAL_DATE)
    page(
      form(action := "/txn", method := "post")(
        input(`type` := "text", name := "amount", placeholder := "Amount"), br(),
        input(`type` := "text", name := "category", placeholder := "Category"), br(),
        input(`type` := "text", name := "notes", placeholder := "Description"), br(),
        input(`type` := "date", name := "date", value := currentDate), br(),
        input(`type` := "text", name := "tags", placeholder := "Tags"), br(),
        input(`type` := "submit")
      ), br(),
      span("Recent Transactions"), br(),
      recentTxns(10)
    )

  @cask.postForm("/txn")
  def txn(
    amount: String,
    category: String,
    notes: String,
    date: String,
    tags: Seq[String]
  ) = runZio:
    for
      parser <- succeed(SimpleInefficientParser())
      amt <- fromEither(parser.parseAmountWithOptionalCurrency(amount)).mapError(new Throwable(_))
      dt <- attempt(LocalDate.parse(date))
      lineItem = models.LineItem.Transaction(
        amount = amt,
        datetime = LocalDateTime.from(dt.atStartOfDay()),
        notes = notes,
        category = category,
        tags = tags.toList
      )
      _ <- cm.withConnection(db.insertLineItem(lineItem))
      js = write(lineItem, indent = 4)
    yield cask.Response(js)

  initialize()

trait Server:
  def serve: Task[Unit]

case class CaskServer(
  runtime: Runtime[Any],
  db: Database,
  auther: Auther,
  cm: SQLConnManager
) extends cask.Main with Server:
  override val port = 8080
  val allRoutes = Seq(ServerRoutes(auther, runtime, db, cm), LoginRoutes(auther))
  def serve = attemptBlockingCancelable(main(Array.empty))(unit) *> never
object CaskServer:
  val liveLayer = ZLayer.fromFunction(CaskServer(_, _, _, _))
