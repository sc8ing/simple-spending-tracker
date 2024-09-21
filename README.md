# Spending Tracker

A dead simple way to record expenses from a plain text file. Just jot down what you spent or earned, use this to import it, and then be free to examine your raw data from a straightforward SQL database however you'd like.

## Installing

Right now (and probably forever) the only option is to build from source, so install [Mill](https://com-lihaoyi.github.io/mill/mill/Intro_to_Mill.html), clone the repo, and run `mill budget.assembly`, which will produce a fat jar in `out/budget/assembly.dist/out.jar` that you can then place wherever and execute with `java -jar`, passing in the file to import through stdin.

## Setup

Add a file with like the following to `~/.config/budget/conf.json`:

```jsonc
{
    "dbSettings": {
        "SQLiteSettings": {
            "dbFilePath": "/full/path/to/where/you/want/the/sqlite/database/stored"
        }
    },
    "defaultSettings": {
        "currencyName": "USdollars",
        "currencySymbol": "$"
    }
}
```

Currency name can be any string without whitespace and the symbol any non-whitespace character.

## File Import Format

Records must be written according to a simple format to be parsed correctly. Individual lines are not time-stamped, so granualarity is limited to a day-to-day basis. Each day tracked begins with a line for the date in month/day/year format (`M/d/yy`, specifically). All information for that day is in the following lines, and can take on the form of either a transaction (spending/receiving of money for something else) or an exchange (converting money into a different form of money). Lines beginning with `--` are comments and ignored when imported. All files must end in two consecutive blank lines.

### Transactional Lines

Transactions are composed using this structure, where braces represent something optional:

```
TransactionLine ::= Amount[Currency], Category[TagList], Description
Amount ::= 12.34
Currency ::= SingleCharacter | FullCurrencyName | SingleCharacter FullCurrencyName
Category ::= any string without whitespace
TagList ::= a space-separated list of strings without whitespace
Description ::= anything
```

When a currency other than the default specified in the config file is used, the program will check if that currency's symbol or name has been defined. If either the symbol or name exists, the line will be linked with that currency. If a symbol exists in the database but not a full name for it (or vice versa) the symbol or name used will be updated to what was written in the line.

Here's an example of how contents of a text file for spending tracking can be formatted:

```
1/12/23
4.12, food restaurant lunch, went to mcdonalds
80.56, food groceries, got a lot of burgers
-- a comment line, this is ignored.

1/13/23
500r rupees, food restaurant indian, spent 500 rupees on dinner
10r, travel, paid for a bus ticket in rupees

1/14/23
300rupees, rent, really want to be clear I'm spending rupees
```

### Exchange Lines

Exchange lines represent conversion between units of money. These are intended to be currencies, but the program doesn't have any idea what it represents, so could be used for shares of stock, virtual coins, or something else convenient for you.

```
ExchangeLine ::= Amount Currency -> Amount Currency[, Category[TagList][, Description]]
```

For exchanges the category is optional and will default to "exchange". If the description's not provided, it will be empty. Otherwise, they work similarly to transaction lines. Here's an example:

```
1/15/23
-- first time to Uruguay, getting some uruguayan dollars
100$ -> 200u uruguayans
-- can use the shorthand "u" symbol once defined
50$ -> 400u, offthebooks secret, in a different category exchange

3/13/23
600u -> 10$
```
TODO.md
