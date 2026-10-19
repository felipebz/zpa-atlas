create materialized view mv_order_star (id, customer_id, amount, status)
  build immediate refresh complete on demand
as
  -- every column of the order; with the column list Oracle stores the query as written
  select * from t_order
/
create materialized view mv_order_qstar
  refresh complete on demand
as
  select o.* from t_order o where o.amount > 0
/
