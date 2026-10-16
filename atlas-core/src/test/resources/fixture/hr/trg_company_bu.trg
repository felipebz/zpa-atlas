create or replace trigger trg_company_bu
before update on company
for each row
begin
  :new.dt_changed := sysdate;
  insert into event_log (id, msg) values (1, 'upd');
end;
/
