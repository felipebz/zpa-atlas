create or replace procedure broken_proc is
begin
  update company set st_active = 'X' where;
end;
/
update company set company_name = 'y';
