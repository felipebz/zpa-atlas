create or replace procedure proc_val is
  n number;
begin
  n := pk_val.g_x;
  n := seq_ext.nextval;
end;
/
