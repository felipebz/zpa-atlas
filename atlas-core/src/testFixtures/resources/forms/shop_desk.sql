create table orders (
  order_id number(10),
  status varchar2(10 byte),
  amount number(12,2)
);

create or replace procedure read_status(p_id number, p_status out varchar2) is
begin
  select status into p_status from orders where order_id = p_id;
end;
/
