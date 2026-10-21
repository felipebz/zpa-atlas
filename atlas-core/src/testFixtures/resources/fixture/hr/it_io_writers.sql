create or replace procedure it_io_upd(p_id number) is
begin
  update it_v_orders_io set status = 'Z' where id = p_id;
end;
/
create or replace procedure it_io_del(p_id number) is
begin
  delete from it_v_orders_io where id = p_id;
end;
/
create or replace procedure it_io2_ins(p_id number) is
begin
  insert into it_v_orders_io2 (id, status) values (p_id, 'V2I');
end;
/
create or replace procedure it_io2_upd(p_id number) is
begin
  update it_v_orders_io2 set status = 'V2U' where id = p_id;
end;
/
create or replace procedure it_iu_ins(p_id number) is
begin
  insert into it_v_orders_iu (id, status) values (p_id, 'IUI');
end;
/
create or replace procedure it_iu_upd(p_id number) is
begin
  update it_v_orders_iu set status = 'IUU' where id = p_id;
end;
/
create or replace procedure it_io_merge_upd(p_id number) is
begin
  merge into it_v_orders_io v
  using (select p_id id from dual) s on (v.id = s.id)
  when matched then update set v.status = 'MU';
end;
/
create or replace procedure it_io_merge_ins(p_id number) is
begin
  merge into it_v_orders_io v
  using (select p_id id from dual) s on (v.id = s.id)
  when matched then update set v.status = 'MXU'
  when not matched then insert (id, status) values (s.id, 'MXI');
end;
/
create or replace procedure it_io2_merge_ins(p_id number) is
begin
  merge into it_v_orders_io2 v
  using (select p_id id from dual) s on (v.id = s.id)
  when not matched then insert (id, status) values (s.id, 'M2I');
end;
/
create or replace procedure it_iu2_merge_upd(p_id number) is
begin
  merge into it_v_orders_iu2 v
  using (select p_id id from dual) s on (v.id = s.id)
  when matched then update set v.status = 'M2U';
end;
/
