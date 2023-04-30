---------------------------------------------------------------------------------------------------
-- Monthly Reports
---------------------------------------------------------------------------------------------------
create temp table vars (varname text, varval text)
insert into vars values ('date_start', date('now', '-2 month')), ('date_stop', date('now'))

select * from vars where varval between date('now', '-1 year') and date('now', '+1 month')

update vars set varval = date('now', '-2 month') where varname = 'date_start'
update vars set varval = date('now', '-1 month') where varname = 'date_stop'

-- exchanges
select  cat.name as category,
		curout.name as given_currency,
		ex.given_magnitude,
		curin.name as received_currency,
		ex.received_magnitude,
		date(ex.created_at / 1000, 'unixepoch'),
		ex.notes 
from exchange ex
join category cat on cat.category_id = ex.category_id
join currency curout on curout.currency_id = ex.given_currency_id
join currency curin on curin.currency_id = ex.received_currency_id
where date(ex.created_at / 1000, 'unixepoch')
	between (select varval from vars where varname = 'date_start')
	and (select varval from vars where varname = 'date_stop')
order by ex.created_at desc

-- average exchange amount
select  cat.name as category,
		curout.name as given_currency,
		sum(ex.given_magnitude),
		curin.name as received_currency,
		sum(ex.received_magnitude),
		avg(ex.given_magnitude / ex.received_magnitude)
from exchange ex
join category cat on cat.category_id = ex.category_id
join currency curout on curout.currency_id = ex.given_currency_id
join currency curin on curin.currency_id = ex.received_currency_id
where date(ex.created_at / 1000, 'unixepoch')
	between (select varval from vars where varname = 'date_start')
	and (select varval from vars where varname = 'date_stop')
group by 1, 2, 4
order by ex.created_at desc

-- transactions
select  txn.txn_id,
		date(txn.created_at / 1000, 'unixepoch') as date,
		cur.name as currency,
		txn.amount,
		cat.name as category,
		group_concat(tag.name) as tags,
		txn.notes
from txn
join currency cur on cur.currency_id = txn.currency_id 
join category cat on cat.category_id = txn.category_id 
left join txn_tag tt on tt.txn_id = txn.txn_id
left join tag on tag.tag_id = tt.tag_id
where date(txn.created_at / 1000, 'unixepoch')
	between (select varval from vars where varname = 'date_start')
	and (select varval from vars where varname = 'date_stop')
group by txn.txn_id
order by date(txn.created_at / 1000, 'unixepoch') desc

-- category breakdown
select  cur.name as currency,
		cat.name as category,
		round(sum(txn.amount) / 1)
from txn
join currency cur on cur.currency_id = txn.currency_id 
join category cat on cat.category_id = txn.category_id
where date(txn.created_at / 1000, 'unixepoch')
	between (select varval from vars where varname = 'date_start')
	and (select varval from vars where varname = 'date_stop')
group by 1, 2
--having sum(txn.amount) < 0
order by cur.name, sum(txn.amount) desc

-- category-tag breakdown (tags can overlap, so sum may add to more than total spent)
select  cur.name as currency,
		cat.name as category,
		tag.name as tag,
		round(sum(txn.amount) / 1)
from txn
join currency cur on cur.currency_id = txn.currency_id 
join category cat on cat.category_id = txn.category_id 
left join txn_tag tt on tt.txn_id = txn.txn_id
left join tag on tag.tag_id = tt.tag_id
where date(txn.created_at / 1000, 'unixepoch')
	between (select varval from vars where varname = 'date_start')
	and (select varval from vars where varname = 'date_stop')
--and (tag in ('out', 'in') or tag is null)
group by 1, 2, 3
order by cat.name, sum(txn.amount) desc

---------------------------------------------------------------------------------------------------
-- individual lookups
---------------------------------------------------------------------------------------------------
select *
from txn txn
join category cat on txn.category_id = cat.category_id
join currency cur on txn.currency_id = cur.currency_id
where txn.txn_id = 1176

select tt.txn_id, tag.name 
from txn_tag tt 
join tag on tag.tag_id = tt.tag_id 
where tt.txn_id = 1176
