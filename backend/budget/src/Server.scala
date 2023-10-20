package budget

import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import zio._
import zio.http._

trait Server { val runServer: Task[Nothing] }
case class HttpServer(
  jwtSecretKey: String,
  clock: Clock,
  auth: Authenticator
) extends Server {
  val JwtAlg = JwtAlgorithm.EdDSA
  val JwtCookieName = "jwtcookie"

  def encodeLoginJwt(forUser: String): Task[String] =
    ZIO.logDebug("Encoding login JWT for " + forUser) *>
    clock.javaClock.map { implicit jClock =>
      val claim = JwtClaim(
        content = s"""{"username": "$forUser"}""",
        // issuer: Option[String] = None,
        // subject: Option[String] = None,
        // audience: Option[Set[String]] = None,
        // expiration: Option[Long] = None,
        // notBefore: Option[Long] = None,
        // jwtId: Option[String] = None
      ).issuedNow.expiresIn(60)
      Jwt.encode(claim, jwtSecretKey, JwtAlg)
    }

  def decodeJwt(jwt: String): Option[JwtClaim] =
    Jwt.decode(jwt, jwtSecretKey, Seq(JwtAlg)).toOption

  def requireAuth[R, E](fail: HttpApp[R, E], success: JwtClaim => HttpApp[R, E]): HttpApp[R, E] =
    Http.fromHttpZIO[Request](req =>
        ZIO.fromOption(req.headers.get(JwtCookieName).flatMap(decodeJwt))
          .map(success)
          .orElse(ZIO.succeed(fail))
    )

   val login: http.App[Any] = Http.collectZIO[Request] {
     case req @ Method.POST -> Root / "login" =>
       for {
         form <- req.body.asURLEncodedForm | Status.BadRequest.toResponse
         _ <- ZIO.logDebug(s"login form attempted with values $form")
         user <- ZIO.fromOption(form.get("username").flatMap(_.stringValue)) | Status.BadRequest.toResponse
         pass <- ZIO.fromOption(form.get("password").flatMap(_.stringValue)) | Status.BadRequest.toResponse
         authed <- auth.validateUserPass(user, pass).logError | Status.InternalServerError.toResponse
         res <- if (!authed)
                  ZIO.succeed(Status.Forbidden.toResponse)
                else
                  encodeLoginJwt(user).map(jwt =>
                    Response.ok.addCookie(Cookie.Response(name = JwtCookieName, content = jwt))
                  ).logError | Status.InternalServerError.toResponse
       } yield res
   }

  case class AuthedCreds(username: String)
  def api(creds: AuthedCreds): HttpApp[Any, Nothing] =  Http.collectZIO[Request] {
    case req @ Method.GET -> Root / "api" / "transaction" =>
      ZIO.succeed(Response(body = Body.fromCharSequence("main page")))
  }
  def lockedPages(creds: AuthedCreds): HttpApp[Any, Nothing] =  Http.collectZIO[Request] {
    case req @ Method.GET -> Root  =>
      ZIO.succeed(Response(body = Body.fromCharSequence("main page")))
  }

  val server: http.App[Any] =
    login ++ requireAuth(
      Handler.forbidden("denied but should redirect").toHttpApp,
      claim => api(AuthedCreds("")) ++ lockedPages(AuthedCreds(""))
    )

  val serverConfig =
    ZLayer.succeed(Server.Config.default.port(8080))

  val runServer =
    Server.serve(server).provide(Server.live, serverConfig)
}
object HttpServer {
  val liveLayer =
    ZLayer.fromFunction(HttpServer(_, _, _))
}
