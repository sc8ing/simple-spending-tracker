#!/bin/zsh

base=$(basename "$(pwd)")
if [ "$base" != "budget-scala" ]; then
    echo "not in budget-scala dev dir, exiting"
    exit 1
fi

dbName="database/testDatabase.db"

rm $dbName

for migration in database/migrations/*.sql; do
    echo "migrating $migration"
    sqlite3 $dbName < "$migration"
done
