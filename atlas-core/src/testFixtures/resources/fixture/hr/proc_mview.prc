create or replace procedure proc_mview(o out varchar2) is
begin
  select max(company_label) into o from mv_company_log;
end;
/
