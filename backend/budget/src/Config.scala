package budget

import java.io.File
import java.nio.file.Paths
import zio._
import zio.config.magnolia._
import zio.config.typesafe._

import models._

sealed trait DatabaseSettings
object DatabaseSettings {
  case class SQLiteSettings(dbFilePath: String) extends DatabaseSettings
}

case class DefaultSettings(currencyName: String, currencySymbol: String)

case class AppConfig(
  dbSettings: DatabaseSettings,
  defaultSettings: DefaultSettings
)
object AppConfig {
  val zioAppConfig = deriveConfig[AppConfig]

  val defaultLoadOrCreate = for {
    home <- ZLayer(System.property("user.home").map(_.get))
    confFile <- ZLayer.succeed(Paths.get(home.get, ".config", "budget", "conf.json").toFile)
    conf <- ZLayer(ConfigProvider.fromHoconFile(confFile.get).load(zioAppConfig))
  } yield conf
}
