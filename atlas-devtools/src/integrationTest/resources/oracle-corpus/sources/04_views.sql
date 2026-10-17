create view v_order_totals as
  select o.id, c.name, o.amount * 2 total from t_order o join t_customer c on c.id = o.customer_id
/
create view v_order_pairs (order_key, order_amount) as
  select id, amount from t_order
/
create view v_order_union as
  select id first_id, amount first_amount from t_order
  union
  select id second_id, customer_id second_amount from t_order
/
create view v_big_totals as
  select t.id, t.total from v_order_totals t where t.total > 100
/
