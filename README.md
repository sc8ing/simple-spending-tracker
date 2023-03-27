# budget-scala

- [x] configuration https://zio.dev/zio-config/
- [x] parsing models from text
- sub command to read in new transactions to db
- setup database with tables if none exists, prompt ("Prompter", similar to correction editor, same, perhaps)
        - useful for CLI vs FE in future
- insertion of models to db with CorrectionEditor
- sql repl to forward queries to db directly




leftover for testing:
  val testBlock = """
    | 2/12/23
    | 12.32, cat tag1 tag2, some expense, I guess
    | 123, cat, other
    |
    |3/31/22
    |33.4p, inpesos argentina, testing
    |3000 pesos, pesoslongname, asdf
    |100$ -> 2000p pesos
    |50$ -> 1000p
    """.stripMargin

    import budget._
  val parser = SimpleInefficientParser()
    parser.parseLineItemBlocks(testBlock)
