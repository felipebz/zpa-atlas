create or replace package body pack_switch is
  g_level number := 0;
  g_quiet boolean := false;

  procedure run is
  begin
    $if dbms_db_version.ver_le_12 $then
      g_level := 1;
    $else
      g_level := 2;
    $end
  end;
end pack_switch;
/
