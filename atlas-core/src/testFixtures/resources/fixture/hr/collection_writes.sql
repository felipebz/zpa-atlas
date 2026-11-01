create or replace package pk_r is
  type t_nums is table of number;
  type t_row is record (items t_nums);
  type t_rows is table of t_row index by pls_integer;
  s_rows t_rows;
  s_rec t_row;
  procedure run;
  function f_row(p in number) return t_row;
end pk_r;
/
create or replace package body pk_r is
  g_rows t_rows;
  g_rec t_row;
  function f_row(p in number) return t_row is begin return g_rec; end;
  procedure run is
  begin
    g_rows(1).items.delete;
    g_rows(1).items.extend(2);
    g_rows(1).items.trim;
    g_rows(1).items.delete(1);
    g_rec.items.delete;
    g_rec.items.extend;
    pk_r.g_rows(1).items.delete;
    g_rows.delete;
    g_rows(1).items(1) := 3;
    f_row(1).items.delete;
    if g_rows(1).items.count > 0 then null; end if;
  end;
end pk_r;
/
create or replace procedure use_r is
begin
  pk_r.s_rows(1).items.delete;
  pk_r.s_rec.items.extend;
  pk_r.s_rows.delete(1);
end;
/
