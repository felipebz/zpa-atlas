create table it_orders (id number primary key, status varchar2(10), amount number, note varchar2(100));
create table it_unwritten (id number);
create table it_stage (id number);
create table it_ping (id number);
create table it_pong (id number);

create or replace view it_v_orders as
  select id, status, amount, note from it_orders
/
create or replace view it_v_orders_io as
  select id, status from it_orders
/
create or replace view it_v_orders_calc as
  select id, amount + length(note) as mixed from it_orders
/
