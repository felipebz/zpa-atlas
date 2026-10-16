create or replace procedure proc_loop is
begin
  for r in pk_val.c_all loop null; end loop;
end;
/
