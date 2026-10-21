create or replace trigger trg_it_orders_bi
before insert on it_orders
for each row
begin
  it_audit('insert');
end;
/
create or replace trigger trg_it_orders_status
after update of status on it_orders
for each row
begin
  it_audit('status');
end;
/
create or replace trigger trg_it_orders_del
after delete on it_orders
begin
  it_audit('delete');
end;
/
create or replace trigger trg_it_v_io
instead of insert on it_v_orders_io
for each row
begin
  it_audit('view');
end;
/
create or replace trigger trg_it_unwritten
after insert on it_unwritten
for each row
begin
  it_audit('unwritten');
end;
/
create or replace trigger trg_it_stage
after insert on it_stage
for each row
begin
  insert into it_orders (id) values (:new.id);
end;
/
create or replace trigger trg_it_ping
after insert on it_ping
for each row
begin
  insert into it_pong (id) values (:new.id);
end;
/
create or replace trigger trg_it_pong
after insert on it_pong
for each row
begin
  insert into it_ping (id) values (:new.id);
  it_audit('pong');
end;
/
create or replace trigger trg_it_logon
after logon on schema
begin
  it_audit('logon');
end;
/
