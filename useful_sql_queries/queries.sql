select  sum(txn.amount), 
        cur.name,
        cat.name
from txn
join category cat on txn.category_id = cat.category_id
join currency cur on txn.currency_id = cur.currency_id
group by cat.name, cur.name
go


select * 
from exchange ex
go
