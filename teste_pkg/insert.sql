create or replace package pk_r3 is
  type t_nums is table of number;
  type t_row is record (items t_nums);

  type t_rows is table of t_row index by pls_integer;
  procedure run( g_rows t_rows,
  g_rec t_row);
end pk_r3;
/
create or replace package body pk_r3 is

  procedure run( g_rows t_rows,
  g_rec t_row) is
begin
    insert into tab (x, y) values (g_rows(1).items(1), g_rec.items(1));
end;
end pk_r3;
/
