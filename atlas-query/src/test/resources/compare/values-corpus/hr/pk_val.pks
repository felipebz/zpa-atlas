create or replace package pk_val is
  g_x number;
  e_fail exception;
  cursor c_all is select 1 x from dual;
end;
/
