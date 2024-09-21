package budget

import java.io.File

import budget.models.*
import zio.*
import zio.ZIO.*

object Main extends ZIOAppDefault {
  enum AppMode:
    case StdInLoader, Server

  type StdInLoaderAppEnv = UserInteractor & LineLoader
  val stdinLoader: RIO[StdInLoaderAppEnv, Unit] =
    serviceWithZIO[UserInteractor](_.promptInput("Input transactions: ").runCollect)
      .flatMap(input => serviceWithZIO[LineLoader](_.loadFromString(input.mkString("\n"))))

  case class CliOptions(appMode: AppMode)
  object CliOptions:
    def fromCliArgs(args: Chunk[String]): Task[CliOptions] = args.toList match
      case "--stdin" :: Nil => succeed(CliOptions(AppMode.StdInLoader))
      case "--server" :: Nil => succeed(CliOptions(AppMode.Server))
      case _ => dieMessage("Either --stdin or --server expected")

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
      serviceWithZIO[SQLConnManager](_.withConnection(
        serviceWithZIO[Database](_.setupDbIfNeeded)
      ))
    )

  def run = preModeInit.provideSome[ZIOAppArgs](AppConfig.defaultLoadOrCreate >>> dbUtilsLayer).flatMap {
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
      succeed(Server.main(Array.empty)).fork *> ZIO.sleep(Duration.Infinity)
  }
}
