create or replace procedure proc_standalone(p_id number) is
begin
  delete company where id = p_id;
end;
/
create sequence seq_event;
create or replace function fn_one return number is v pack_process.c_pending%rowtype;
begin
  return 1; exception when pack_process.e_failed or no_data_found then return 0; when pack_process.e_missing then return 2;
end;
/
