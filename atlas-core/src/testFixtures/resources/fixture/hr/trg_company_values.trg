create or replace trigger trg_company_values
before insert or update on company
for each row
declare
  v number;
begin
  :new.st_active := 'S';
  :new.company_name := upper(:new.company_name);
  if :new.dt_changed is null then :new.dt_changed := sysdate; end if;
  select seq_event.nextval into :new.id from dual;
  select 'x', sysdate into :new.company_name, :new.dt_changed from dual;
  select 1, 'y' into v, :new.company_name from dual;
  pack_process.get_event(1, :new.id, v);
  :new.st_active := '';
end;
/
