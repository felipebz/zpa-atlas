create or replace package body pack_chains is
  type r_acct is record (f number, g number);
  g_rec r_acct;
  type t_tab is table of number index by pls_integer;
  g_tab t_tab;
  type t_nums is table of number;
  g_nums t_nums := t_nums();
  g_hidden r_acct;

  procedure take(p_in in number) is begin null; end;
  procedure put(p_out out number) is begin p_out := 1; end;

  procedure run is
    l number;
  begin
    l := g_rec.f;
    g_rec.f := 1;
    select 1 into g_rec.f from dual;
    take(g_rec.f);
    put(g_rec.g);
    l := g_tab.count;
    if g_tab.exists(l) then null; end if;
    for i in 1 .. g_tab.count loop null; end loop;
    g_tab.delete;
    g_nums.extend;
    g_nums.delete(1);
    pack_chains.g_rec.f := 2;
    l := pack_chains.g_rec.f;
    declare
      g_hidden r_acct;
    begin
      g_hidden.f := 3;
    end;
    select count(*) into l from company where id = g_rec.f;
    select g_rec.id into l from company g_rec;
  end;
end pack_chains;
/
