create or replace procedure proc_ext is
  n number;
begin
  other_s.ext.run(1);
  other_s.proc(1);
  if pkg_ext.g_tab.exists(1) then
    null;
  end if;
  select count(*) into n from emp;
  n := pk_doc.company(1);
  select 1 into n from dual;
end;
/
