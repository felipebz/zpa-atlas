create or replace procedure proc_standalone(p_id number) is
begin
  delete company where id = p_id;
end;
/
create sequence seq_event;
create or replace function fn_one return number is
begin
  return 1;
end;
/
