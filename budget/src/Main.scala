import zio.{Console, ZIOAppDefault}

object MainApp extends ZIOAppDefault:
  def run = Console.printLine("Hello, World!")
