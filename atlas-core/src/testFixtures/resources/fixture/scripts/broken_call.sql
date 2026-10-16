create or replace procedure broken_proc2 is
begin
  update company set st_active = 'X' where;
end;
begin
  proc_standalone(1);
end;
/
