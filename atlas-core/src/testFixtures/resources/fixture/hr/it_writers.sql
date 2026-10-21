create or replace procedure it_p(p_id number) is
begin
  insert into it_orders (id, status) values (p_id, 'NEW');
end;
/
create or replace procedure it_q is
begin
  it_p(1);
end;
/
create or replace procedure it_set_status(p_id number) is
begin
  update it_orders set status = 'DONE' where id = p_id;
end;
/
create or replace procedure it_set_amount(p_id number) is
begin
  update it_orders set amount = 0 where id = p_id;
end;
/
create or replace procedure it_set_row(r it_orders%rowtype) is
begin
  update it_orders set row = r where id = r.id;
end;
/
create or replace procedure it_delete(p_id number) is
begin
  delete from it_orders where id = p_id;
end;
/
create or replace procedure it_merge(p_id number, p_status varchar2) is
begin
  merge into it_orders o
  using (select p_id id, p_status status from dual) s on (o.id = s.id)
  when matched then update set o.status = s.status
  when not matched then insert (id, status) values (s.id, s.status);
end;
/
create or replace procedure it_write_view_io(p_id number) is
begin
  insert into it_v_orders_io (id, status) values (p_id, 'NEW');
end;
/
create or replace procedure it_write_view(p_id number) is
begin
  insert into it_v_orders (id, status) values (p_id, 'NEW');
end;
/
create or replace procedure it_write_calc(p_id number) is
begin
  insert into it_v_orders_calc (mixed) values (p_id);
end;
/
create or replace procedure it_write_syn(p_id number) is
begin
  delete from it_orders_syn where id = p_id;
end;
/
create or replace procedure it_dyn(p_id number) is
begin
  execute immediate 'insert into it_orders (id) values (:1)' using p_id;
end;
/
create or replace procedure it_truncate is
begin
  execute immediate 'truncate table it_orders';
end;
/
create or replace procedure it_stage_load(p_id number) is
begin
  insert into it_stage (id) values (p_id);
end;
/
