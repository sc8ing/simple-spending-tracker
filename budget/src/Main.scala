package budget

import java.io.File
import zio._

object MainApp extends ZIOAppDefault {
  val app = for {
    appConfig <- ZIO.service[AppConfig]
    _ <- Console.printLine(appConfig)
    i <- ZIO.serviceWithZIO[Database](_.insertTag("test"))
    _ <- Console.printLine(i)
  } yield ()

  val makeDb = ZLayer.service[AppConfig].map(conf => ZEnvironment(conf.get.dbSettings)).flatMap(c => c.get match {
    case DatabaseSettings.SQLiteSettings(dbFilePath) =>
      ZLayer.make[Database](
        ZLayer.succeed(SQLiteConfig(new File(dbFilePath))),
        SQLDatabase.liveLayer,
        SQLiteConnManager.liveLayer
      )
  })

  def run =
    app.provideLayer(ZLayer.make[AppConfig with Database](
      AppConfig.defaultLoadOrCreate,
      makeDb
    ))
}
