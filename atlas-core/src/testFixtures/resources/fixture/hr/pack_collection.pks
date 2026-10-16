create or replace package pack_collection is
  type t_n is table of number;
  g_tab t_n;
  procedure q;
end;
/
