create or replace procedure p_changed is
begin
  delete from t_audit;
end;
/
