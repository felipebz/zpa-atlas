create or replace trigger trg_company_forms
before insert or update of st_active, company_name, no_such_col on company
referencing new as n old as o
for each row
when (n.st_active = 'S' and o.id > 0)
declare
  v_name varchar2(100);
  cursor c is select company_name from company;
begin
  v_name := :o.company_name;
  :n.dt_changed := sysdate;
  select company_name into :n.company_name from company where id = :o.id;
  open c;
  fetch c into :n.company_name;
  close c;
  pack_process.get_event(:o.id, :n.st_active, :n.id);
  pack_external.touch(:n.company_name);
  insert into event_log (id, msg) values (:n.id, :o.company_name);
  v_name := :n.no_such_col;
  v_name := :n.rowid;
end;
/
create or replace trigger trg_company_compound
for update on company
compound trigger
  before each row is
  begin
    :new.dt_changed := :old.dt_changed;
  end before each row;
end trg_company_compound;
/
create or replace trigger trg_v_company_ioi
instead of insert on v_company
for each row
begin
  insert into company (id, company_name) values (:new.id, :new.company_name);
end;
/
create or replace trigger trg_schema_logon
after logon on schema
begin
  null;
end;
/
create or replace trigger trg_schema_ddl
before create or drop on schema
begin
  null;
end;
/
create or replace trigger trg_company_stmt
after delete on company
begin
  null;
end;
/
