package budget

import java.time.format.DateTimeFormatter
import java.time.{LocalDate, LocalDateTime}
import scala.util.Try

import scalatags.Text.all.*
import zio.*
import zio.ZIO.*

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

case class ServerRoutes()(implicit cc: castor.Context, log: cask.Logger, auther: Auther) extends cask.Routes:
  import LoginRoutes.requireLogin

  def page(content: Frag) = html(
    head(
      link(rel := "stylesheet", href := "/static/style.css"),
      script(src := "/static/scripts/htmx.js")
    ),
    body(content)
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
      )
    )

  @cask.postForm("/txn")
  def txn(
    amount: String,
    category: String,
    notes: String,
    date: String,
    tags: Seq[String]
  ) =
    val res = for
      parser <- Right(SimpleInefficientParser())
      amt <- parser.parseAmountWithOptionalCurrency(amount)
               .left.map(new Throwable(_))
      dt <- Try(LocalDate.parse(date)).toEither
      lineItem = models.LineItem.Transaction(
        amount = amt,
        datetime = LocalDateTime.from(dt.atStartOfDay()),
        notes = notes,
        category = category,
        tags = tags.toList
      )
    yield ()
    res.fold(
      err => cask.Response(err.toString, statusCode = 400),
      out => cask.Response("Success: " + out)
    )

  initialize()

trait Server:
  def serve: Task[Unit]

case class CaskServer(
  runtime: Runtime[Any],
  db: Database,
  auther: Auther
) extends cask.Main with Server:
  override val port = 8080
  given authr: Auther = auther
  val allRoutes = Seq(ServerRoutes(), LoginRoutes(auther))
  def serve = attemptBlockingCancelable(main(Array.empty))(unit) *> never
object CaskServer:
  val liveLayer = ZLayer.fromFunction(CaskServer(_, _, _))
