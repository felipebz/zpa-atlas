create or replace trigger trg_it_v_iu
instead of update on it_v_orders_iu
for each row
begin
  it_audit('view update');
end;
/
