create or replace procedure imp_read_view is
  v_n number;
begin
  select max(n) into v_n from v_imp;
end;
/
