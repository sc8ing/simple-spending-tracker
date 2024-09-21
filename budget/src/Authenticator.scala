package budget

import zio._

trait Authenticator {
  def validateUserPass(user: String, pass: String): Task[Boolean]
}
