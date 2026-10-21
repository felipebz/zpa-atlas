create or replace trigger trg_imp
after insert on company
for each row
begin
  pack_imp_a.mid(:new.id);
end;
/
