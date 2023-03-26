import mill._, scalalib._
	
object budget extends ScalaModule {
  def scalaVersion = "2.13.10"
  def ammoniteVersion = "3.0.0-M0-5-0af4d9e7"
  def ivyDeps = Agg(
    ivy"dev.zio::zio:2.0.10",
    ivy"dev.zio::zio-config:4.0.0-RC12",
    ivy"dev.zio::zio-config-magnolia:4.0.0-RC12",
    ivy"dev.zio::zio-config-typesafe:4.0.0-RC12",
    ivy"org.xerial:sqlite-jdbc:3.41.2.0"
  )
}
