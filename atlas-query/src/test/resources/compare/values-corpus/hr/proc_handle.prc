create or replace procedure proc_handle is
begin
  null;
exception
  when pk_val.e_fail then null;
end;
/
