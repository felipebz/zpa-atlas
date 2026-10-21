create or replace view it_v_orders_io2 as
  select id, status from it_v_orders_io
/
create or replace view it_v_orders_iu as
  select id, status from it_orders
/
create or replace view it_v_orders_iu2 as
  select id, status from it_v_orders_iu
/
