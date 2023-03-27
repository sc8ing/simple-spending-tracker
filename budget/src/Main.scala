package budget

import java.io.File
import zio._

object MainApp extends ZIOAppDefault {
  val app = for {
    appConfig <- ZIO.service[AppConfig]
    _ <- Console.printLine(appConfig)
    // i <- ZIO.serviceWithZIO[Database](_.insertTag("test"))
    // _ <- Console.printLine(i)
    _ <- ZIO.serviceWith[Parser[String]](_.parseLineItemBlocks(""))
  } yield ()

  // from zio 1.0
// val userInput: ZStream[Console, IOException, String] = 
//   ZStream.fromEffectOption(
//     zio.console.getStrLn.mapError(Option(_)).flatMap {
//       case "EOF" => ZIO.fail[Option[IOException]](None)
//       case o     => ZIO.succeed(o)
//     }
//   ) 

  val makeDb = ZLayer.service[AppConfig].map(conf => ZEnvironment(conf.get.dbSettings)).flatMap(c => c.get match {
    case DatabaseSettings.SQLiteSettings(dbFilePath) =>
      ZLayer.make[Database](
        ZLayer.succeed(SQLiteConfig(new File(dbFilePath))),
        SQLDatabase.liveLayer,
        SQLiteConnManager.liveLayer
      )
  })

  def run =
    app.provideLayer(ZLayer.make[AppConfig with Database with Parser[String]](
      AppConfig.defaultLoadOrCreate,
      makeDb,
      SimpleInefficientParser.liveLayer
    ))
}
