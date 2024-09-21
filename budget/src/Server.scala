package budget

import java.util.concurrent.TimeUnit

import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import zio.*
import zio.http.*
import zio.json.*

trait Server { val runServer: Task[Nothing] }

case class HttpServer(
  jwtSecretKey: String,
  clock: Clock,
  auth: Authenticator
) extends Server {
  val JwtAlg = JwtAlgorithm.EdDSA
  val JwtCookieName = "jwtcookie"

  case class JwtContent(username: String) derives JsonCodec

  def encodeLoginJwt(forUser: String): Task[String] =
    ZIO.logInfo("Encoding login JWT for user " + forUser) *>
    clock.javaClock.map { implicit jClock =>
      val claim = JwtClaim(
        content = JwtContent(forUser).toJson,
        issuer = Some("spend tracker"),
        subject = Some("subject"),
        audience = Some(Set("audience")),
        notBefore = None,
        jwtId = None
      ).issuedNow.expiresIn(60)
      Jwt.encode(claim/*, jwtSecretKey, JwtAlg*/)
    }

  def decodeJwt(jwt: String): Task[JwtClaim] =
    ZIO.fromTry(Jwt.decode(jwt, jwtSecretKey, Seq(JwtAlg)))

  def requireAuth[R, E](fail: HttpApp[R, E], success: Http[R, E, (Request, JwtClaim), Response]): HttpApp[R, E] =
    Http.fromHttpZIO[Request](req =>
      ZIO.fromOption(req.headers.get(JwtCookieName))
        .flatMap(decodeJwt)
        .map(jwt => success.contramap[Request]((_, jwt)))
        .orElse(ZIO.succeed(fail))
    )

  extension (r: Response)
    def withJwtCookie(jwt: String) =
      r.addCookie(Cookie.Response(name = JwtCookieName, content = jwt))

   val login: http.App[Any] = Http.collectZIO[Request] {
     case req @ Method.POST -> Root / "login" =>
       for {
         form <- req.body.asURLEncodedForm | Status.BadRequest.toResponse
         _ <- ZIO.logInfo(s"login form attempted with values $form")
         user <- ZIO.fromOption(form.get("username").flatMap(_.stringValue)) | Status.BadRequest.toResponse
         pass <- ZIO.fromOption(form.get("password").flatMap(_.stringValue)) | Status.BadRequest.toResponse
         authed <- auth.validateUserPass(user, pass).logError | Status.InternalServerError.toResponse
         res <- if (!authed)
                  ZIO.succeed(Status.Forbidden.toResponse)
                else
                  encodeLoginJwt(user).map(Response.ok.withJwtCookie)
                    .logError | Status.InternalServerError.toResponse
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
      Http.fromHttpZIO[(Request, JwtClaim)]((req, claim) =>
        // Decode the JWT provided
        ZIO.fromEither(claim.content.fromJson[JwtContent])
          .logError.orElseFail(Status.BadRequest.toResponse)
          .tap(jwt => ZIO.logInfo(s"Got request with JWT $jwt"))
          .map { jwtContent =>
            val behindAuth = api(AuthedCreds("")) ++ lockedPages(AuthedCreds(""))
            // Handler to run after the request processing to update the response with
            // a new JWT if needed
            val updateJwtHandler = Handler.fromFunctionZIO[Response](res => for {
              now <- Clock.currentTime(TimeUnit.MILLISECONDS)
              expMilliTime = claim.expiration.getOrElse(0L)
              isExpired = now > expMilliTime
              resNew <- if (!isExpired)
                          ZIO.logInfo(s"""
                            JWT for ${jwtContent.username} }not expired, still has 
                            ${(now - expMilliTime) / 1000} seconds (expiration $expMilliTime, now $now)
                          """).as(res)
                        else
                          ZIO.logInfo(s"Making new JWT for ${jwtContent.username}") *>
                          encodeLoginJwt(jwtContent.username)
                            .map(res.withJwtCookie)
                            .logError | Status.InternalServerError.toResponse
            } yield resNew)
            behindAuth.contramap[(Request, JwtClaim)](_._1) >>> updateJwtHandler
          }.logError // "Succeed" by trapping any errors and just responding saying it broke
            .orElseSucceed(Http.fromHandler(Handler.fail(Status.InternalServerError.toResponse)))
      )
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
