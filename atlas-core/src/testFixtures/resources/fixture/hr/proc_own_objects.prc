create or replace procedure proc_own_objects is
  v_key ty_missing;
  n number;
begin
  n := dbms_random.value(1, 10);
  n := dbms_random.value;
  v_key := ty_missing(1);
  select seq_missing.nextval into n from dual;
end proc_own_objects;
/
