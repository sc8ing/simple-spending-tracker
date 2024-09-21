import $ivy.`com.goyeau::mill-scalafix::0.4.1`
import $ivy.`com.lihaoyi::mill-contrib-bloop:$MILL_VERSION`
import com.goyeau.mill.scalafix.ScalafixModule
import mill._, scalalib._

object budget extends ScalaModule with ScalafixModule {
  def scalaVersion = "3.5.0"
  // def ammoniteVersion = "3.0.0-M0-5-0af4d9e7"
  def ivyDeps = Agg(
    ivy"dev.zio::zio:2.1.9",
    ivy"com.lihaoyi::cask:0.9.4",
    ivy"com.lihaoyi::scalatags:0.13.1",
    ivy"dev.zio::zio-streams:2.0.10",
    ivy"dev.zio::zio-macros:2.0.10",
    ivy"dev.zio::zio-config:4.0.0-RC12",
    ivy"dev.zio::zio-config-magnolia:4.0.0-RC12",
    ivy"dev.zio::zio-config-typesafe:4.0.0-RC12",
    ivy"org.xerial:sqlite-jdbc:3.41.2.0",
    ivy"com.github.jwt-scala::jwt-zio-json:9.4.4"
  )
  // add -Wunused:imports compiler option
  def scalacOptions = Seq("-Wunused:imports")
  def mainClass = Some("budget.Main")
}
