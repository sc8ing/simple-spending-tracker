package budget

import zio.ZIO

extension [R, E, A] (f: ZIO[R, E, A])
  def |[E2](e: E2): ZIO[R, E2, A] = f.orElseFail(e)
