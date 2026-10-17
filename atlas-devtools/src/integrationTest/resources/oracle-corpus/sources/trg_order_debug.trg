create or replace trigger trg_order_debug
after insert on t_order
for each row
begin
  insert into t_audit (id, msg) values (:new.id, 'debug');
end;
/
