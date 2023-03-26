import mill._, scalalib._
	
object budget extends ScalaModule {
  def scalaVersion = "2.13.10"
  def ammoniteVersion = "3.0.0"
  def ivyDeps = Agg(
    ivy"dev.zio::zio:2.0.10",
    ivy"org.xerial:sqlite-jdbc:3.41.2.0"
  )
}
