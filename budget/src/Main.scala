package budget

import java.io.File
import zio._

import models._

object MainApp extends ZIOAppDefault {
  val app = for {
    _ <- Console.printLine("Running")
    _ <- Database.setupDbIfNeeded
    numLoaded <- loadTransactionsToDb
    _ <- Console.printLine(s"Loaded $numLoaded line items")
  } yield ()

  val loadTransactionsToDb = for {
    rawBlocks         <- UserInteractor.promptInput("Input transactions: ").runCollect
    maybeParsed       <- Parser.parseLineItemBlocks[String](rawBlocks.mkString("\n"))
    lineItems         <- ZIO.fromEither(maybeParsed).mapError(new Throwable(_))
    adjustedLineItems <- ZIO.foreach(lineItems)(Adjuster.adjust).map(_.reverse)
    _                 <- ZIO.foreachDiscard(adjustedLineItems)(Database.insertLineItem)
  } yield adjustedLineItems.size

  val makeDb = ZLayer.service[AppConfig].map(conf => ZEnvironment(conf.get.dbSettings)).flatMap(c => c.get match {
    case DatabaseSettings.SQLiteSettings(dbFilePath) =>
      ZLayer.make[Database](
        AppConfig.defaultLoadOrCreate >>> ZLayer.fromFunction((c: AppConfig) => c.defaultSettings),
        ZLayer.succeed(SQLiteConfig(new File(dbFilePath))),
        SQLDatabase.liveLayer,
        SQLiteConnManager.liveLayer
      )
  })

  def run =
    app.provideLayer(ZLayer.make[
      AppConfig with
      DefaultSettings with
      Database with
      Parser[String] with
      Adjuster[LineItem] with
      UserInteractor
    ](
      AppConfig.defaultLoadOrCreate >+> ZLayer.fromFunction((c: AppConfig) => c.defaultSettings),
      makeDb,
      SimpleInefficientParser.liveLayer,
      QuestionAnswerAdjuster.liveLayer,
      CLIUserInteractor.liveLayer
    ))
}
