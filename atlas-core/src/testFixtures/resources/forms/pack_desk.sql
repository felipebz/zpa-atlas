create table orders (
  order_id number(10),
  status varchar2(10 byte),
  note varchar2(40 byte)
);

create or replace package pk is
  subtype t_code is varchar2(4);
  g_code varchar2(6);
  type t_rec is record (code varchar2(7), note varchar2(30));
  g_rec t_rec;
end;
/

create or replace procedure use_pk is
  c pk.t_code;
  a pk.g_code%type;
  r pk.t_rec;
  f pk.g_rec.code%type;
begin
  select status into c from orders where order_id = 1;
  select status into a from orders where order_id = 1;
  select status into pk.g_code from orders where order_id = 1;
  for o in (select status from orders) loop
    pk.g_code := o.status;
  end loop;
  select status into r.code from orders where order_id = 1;
  select status, note into r from orders where order_id = 1;
  select status into pk.g_rec.code from orders where order_id = 1;
  select status into pk.g_rec.note from orders where order_id = 1;
  select status into f from orders where order_id = 1;
end;
/
