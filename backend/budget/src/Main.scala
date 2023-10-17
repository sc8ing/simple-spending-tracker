package budget

import java.io.File
import zio._

import models._

object MainApp extends ZIOAppDefault {
  enum AppMode:
    case StdInLoader, Server

  type StdInLoaderAppEnv = UserInteractor & LineLoader
  val stdinLoader: RIO[StdInLoaderAppEnv, Unit] =
    ZIO.serviceWithZIO[UserInteractor](_.promptInput("Input transactions: ").runCollect)
      .flatMap(input => ZIO.serviceWithZIO[LineLoader](_.loadFromString(input.mkString("\n"))))

  case class CliOptions(appMode: AppMode)
  object CliOptions:
    def fromCliArgs(args: Chunk[String]): Task[CliOptions] = args.toList match
      case "--stdin" :: Nil => ZIO.succeed(CliOptions(AppMode.StdInLoader))
      case "--server" :: Nil => ZIO.succeed(CliOptions(AppMode.Server))
      case _ => ZIO.dieMessage("Either --stdin or --server expected")

  val dbUtilsLayer: RLayer[AppConfig, Database & SQLConnManager] =
    ZLayer.service[AppConfig].map(conf => ZEnvironment(conf.get.dbSettings)).flatMap(c => c.get match {
      case DatabaseSettings.SQLiteSettings(dbFilePath) =>
        ZLayer.make[Database & SQLConnManager](
          AppConfig.defaultLoadOrCreate >>> ZLayer.fromFunction((c: AppConfig) => c.defaultSettings),
          ZLayer.succeed(SQLiteConfig(new File(dbFilePath))),
          SQLDatabase.liveLayer,
          SQLiteConnManager.liveLayer
        )
    })

  val preModeInit: RIO[ZIOAppArgs & SQLConnManager & Database, AppMode] =
    getArgs.flatMap(CliOptions.fromCliArgs).map(_.appMode).tap(_ =>
      ZIO.serviceWithZIO[SQLConnManager](_.withConnection(
        ZIO.serviceWithZIO[Database](_.setupDbIfNeeded)
      ))
    )

  def run = preModeInit.provideSome[ZIOAppArgs](AppConfig.defaultLoadOrCreate >>> dbUtilsLayer).flatMap:
    case AppMode.StdInLoader =>
      stdinLoader.provideLayer(ZLayer.make[StdInLoaderAppEnv](
        CLIUserInteractor.liveLayer,
        SqliteLineLoader.layer,
        dbUtilsLayer,
        SimpleInefficientParser.liveLayer,
        QuestionAnswerAdjuster.liveLayer,
        AppConfig.defaultLoadOrCreate,
        ZLayer.fromFunction((c: AppConfig) => c.defaultSettings)
      ))
    case AppMode.Server =>
      ZIO.dieMessage("server not implemented")
}
