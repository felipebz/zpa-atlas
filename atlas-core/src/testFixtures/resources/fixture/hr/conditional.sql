create or replace package cc_flags is
  debug constant boolean := false;
  procedure trace_on;
end;
/
create or replace package cc_nested is
  x constant boolean := false;
end;
/
create or replace package pack_cond is
$IF dbms_db_version.version >= 12 $THEN
  procedure run_new(p_id in number);
$ELSE
  procedure run_old(p_id in number);
$END
  procedure work;
end;
/
create or replace package body pack_cond is
$IF dbms_db_version.version >= 12 $THEN
  procedure run_new(p_id in number) is begin delete from send_queue where id = p_id; end;
$ELSE
  procedure run_old(p_id in number) is begin delete from event_log where id = p_id; end;
$END
  procedure work is
    n number;
  begin
    $IF dbms_db_version.ver_le_11_2 $THEN
      select count(*) into n from company;
    $ELSE
      select count(*) into n from send_queue;
    $END
    $IF cc_flags.debug $THEN
      cc_flags.trace_on;
    $END
    $IF $$trace $THEN
      select count(*) into n from event_log;
    $END
    $IF dbms_db_version.ver_le_10 $THEN
      update company set st_active = 'A';
    $ELSIF dbms_db_version.ver_le_11_2 $THEN
      update company set company_name = 'B';
    $ELSE
      update company set dt_changed = sysdate;
    $END
    $IF TRUE $THEN
      null;
    $ELSE
      $IF cc_nested.x $THEN n := 1; $END
      n := length('$END cc_nested.x');
    $END
    $IF dbms_db_version.version < 10 $THEN
      $ERROR 'pack_cond needs Oracle 10 or later, not ' || cc_nested.x $END
    $END
    $IF dbms_db_version.version >= 12 $THEN run_new(1); $ELSE run_old(1); $END
  end;
end;
/
