create or replace procedure p_changed is
begin
  update t_customer set status = 'A';
end;
/
