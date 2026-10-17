create or replace trigger trg_order_invalid
before update on t_order
for each row
begin
  :new.status := :old.status;
  p_missing(:new.amount);
end;
/
