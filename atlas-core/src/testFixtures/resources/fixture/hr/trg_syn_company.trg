create or replace trigger trg_syn_company before update on syn_company for each row
begin
  if :old.id > 0 then null; end if;
end;
/
