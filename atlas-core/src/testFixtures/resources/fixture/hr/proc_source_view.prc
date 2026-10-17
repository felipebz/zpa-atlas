create or replace procedure proc_source_view is
  n number;
begin
  select count(*) into n from v_source_only where id > 0;
  select count(*) into n from v_company_names;
end;
/
