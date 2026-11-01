create or replace package pk_f is
  type t_nums is table of number;
  type t_row is record (items t_nums, code varchar2(10));
  type t_rows is table of t_row index by pls_integer;
  type t_outer is record (inner t_row);
  s_rec t_row;
  function f_row return t_row;
end pk_f;
/
create or replace package body pk_f is
  g_rows t_rows;
  g_rec t_row;
  function f_row return t_row is begin return g_rec; end;
  procedure run(p_rec in out t_row) is
    l_out t_outer;
    l_n number;
  begin
    g_rec.code := 'x';
    g_rows(1).items.delete;
    l_out.inner.code := 'y';
    l_n := f_row().items.count;
    if p_rec.code = 'a' then p_rec.items := t_nums(); end if;
    select count(*) into l_n from dual where g_rec.code = 'b';
  end;
end pk_f;
/
