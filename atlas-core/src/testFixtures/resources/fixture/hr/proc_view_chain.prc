create or replace procedure proc_view_chain is
  l varchar2(100);
begin
  select label into l from v_chain2 where id = 1;
  select msg into l from v_lin_join where id = 1;
  select company_name into l from v_lin_join where rownum = 1;
end;
/
