create or replace procedure p_broken is
  n number;
begin
  select count(*) into n from t_missing;
end;
/
