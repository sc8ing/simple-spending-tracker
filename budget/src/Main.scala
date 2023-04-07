package budget

import java.io.File
import zio._

import models._

object MainApp extends ZIOAppDefault {
  type AppEnv = SQLConnManager with Database with UserInteractor with Parser[String] with Adjuster[LineItem]

  val app: RIO[AppEnv, Unit] = for {
    _         <- Console.printLine("Running")
    _         <- SQLConnManager.withConnection[AppEnv, Unit](Database.setupDbIfNeeded)
    numLoaded <- SQLConnManager.withConnection[AppEnv, Int](loadTransactionsToDb)
    _         <- Console.printLine(s"Loaded $numLoaded line items")
  } yield ()

  val loadTransactionsToDb = for {
    rawBlocks         <- UserInteractor.promptInput("Input transactions: ").runCollect
    maybeParsed       <- Parser.parseLineItemBlocks[String](rawBlocks.mkString("\n"))
    lineItems         <- ZIO.fromEither(maybeParsed).mapError(new Throwable(_))
    adjustedLineItems <- ZIO.foreach(lineItems)(Adjuster.adjust).map(_.reverse)
    _                 <- ZIO.foreachDiscard(adjustedLineItems)(Database.insertLineItem)
  } yield adjustedLineItems.size

  val makeDbUtils = ZLayer.service[AppConfig].map(conf => ZEnvironment(conf.get.dbSettings)).flatMap(c => c.get match {
    case DatabaseSettings.SQLiteSettings(dbFilePath) =>
      ZLayer.make[Database with SQLConnManager](
        AppConfig.defaultLoadOrCreate >>> ZLayer.fromFunction((c: AppConfig) => c.defaultSettings),
        ZLayer.succeed(SQLiteConfig(new File(dbFilePath))),
        SQLDatabase.liveLayer,
        SQLiteConnManager.liveLayer
      )
  })

  def run =
    app.provideLayer(ZLayer.make[AppEnv](
      AppConfig.defaultLoadOrCreate >+> ZLayer.fromFunction((c: AppConfig) => c.defaultSettings),
      makeDbUtils,
      SimpleInefficientParser.liveLayer,
      QuestionAnswerAdjuster.liveLayer,
      CLIUserInteractor.liveLayer
    ))
}
