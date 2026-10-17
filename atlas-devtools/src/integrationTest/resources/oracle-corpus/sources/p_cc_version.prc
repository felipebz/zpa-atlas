create or replace procedure p_cc_version is
  n number;
begin
  $IF dbms_db_version.ver_le_19 $THEN
    select count(*) into n from t_cc_le19;
  $ELSE
    select count(*) into n from t_cc_after19;
  $END
  $IF pk_cc_flags.debug $THEN
    n := 0;
  $END
end;
/
