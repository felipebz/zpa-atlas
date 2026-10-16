create or replace package "HR"."PACK_OWNED" is
  type t_n is table of number;
  g_tab t_n;
  procedure p(x number);
end;
/
