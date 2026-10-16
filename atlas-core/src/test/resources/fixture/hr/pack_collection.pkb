create or replace package body pack_collection is
  procedure q is
    n number;
  begin
    if g_tab.exists(1) then null; end if;
    g_tab.delete;
    g_tab.extend(2);
    n := g_tab(1);
    q;
  end;
end;
/
