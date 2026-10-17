create or replace procedure p_own_random is
  n number;
begin
  n := dbms_random.value(1, 10);
end;
/
