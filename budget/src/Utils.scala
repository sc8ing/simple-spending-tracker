package budget

import java.time.LocalDateTime

import zio.ZIO

extension [R, E, A] (f: ZIO[R, E, A])
  def |[E2](e: E2): ZIO[R, E2, A] = f.orElseFail(e)

object LocalDateTimeRW {
  import upickle.default.*

  implicit val rw: ReadWriter[LocalDateTime] = readwriter[String].bimap[LocalDateTime](
    _.toString,
    LocalDateTime.parse
  )
}
