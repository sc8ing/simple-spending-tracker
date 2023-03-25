import mill._, scalalib._
	
object budget extends ScalaModule {
  def scalaVersion = "3.2.2"
  def ammoniteVersion = "3.0.0"
  def ivyDeps = Agg(
    ivy"dev.zio::zio:2.0.10"
  )
}
