package budget

import zio.*

trait Authenticator:
  def validateUserPass(user: String, pass: String): Task[Boolean]
