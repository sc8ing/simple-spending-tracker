CREATE TABLE category (
    category_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    name TEXT NOT NULL UNIQUE
)
GO

CREATE TABLE currency (
    currency_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    name TEXT NOT NULL UNIQUE
)
GO

CREATE TABLE currency_exchange (
    currency_exchange_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    date TEXT NOT NULL,
    sold_currency_id INTEGER NOT NULL,
    sold_amount REAL NOT NULL,
    bought_currency_id INTEGER NOT NULL,
    bought_amount REAL NOT NULL
)
GO

CREATE TABLE tag (
    tag_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    name TEXT NOT NULL UNIQUE
)
GO

CREATE TABLE txn_tags (
    txn_id INTEGER,
    tag_id INTEGER
)
GO

CREATE TABLE txn (
    txn_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    date TEXT NOT NULL,
    amount REAL NOT NULL,
    currency_id INTEGER NOT NULL,
    category_id INTEGER NOT NULL,
    description TEXT NULL
)
GO
