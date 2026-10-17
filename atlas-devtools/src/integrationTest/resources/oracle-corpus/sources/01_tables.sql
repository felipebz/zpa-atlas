create table t_customer (
  id number primary key,
  name varchar2(100),
  status varchar2(1)
)
/
create table t_order (
  id number primary key,
  customer_id number,
  amount number,
  status varchar2(1)
)
/
create table t_audit (
  id number,
  msg varchar2(200)
)
/
create sequence seq_order
/
create synonym syn_customer for t_customer
/
create view v_open_orders as select id, customer_id, amount from t_order where status = 'O'
/
create table t_cc_le19 (id number)
/
create table t_cc_after19 (id number)
/
