package budget

case class ServerRoutes()(implicit cc: castor.Context, log: cask.Logger) extends cask.Routes:
  @cask.get("/")
  def hello() = {
    "Hello World!"
  }

  @cask.post("/do-thing")
  def doThing(request: cask.Request) = {
    request.text().reverse
  }
  initialize()

object Server extends cask.Main:
  val allRoutes = Seq(ServerRoutes())
