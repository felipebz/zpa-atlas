create or replace package pk_p is
  type t_nums is table of number;
  type t_row is record (items t_nums);
  type t_rows is table of t_row index by pls_integer;
  procedure run(p_rows t_rows, p_rec in out t_row);
  procedure fill(p_out out number);
  cursor c_x(p_id number) is select p_id from dual;
end pk_p;
/
create or replace package body pk_p is
  procedure helper(h_a number) is
    procedure inner(i_b number) is begin null; end;
  begin
    inner(h_a);
  end;
  procedure fill(p_out out number) is
  begin
    p_out := 1;
  end;
  procedure run(p_rows t_rows, p_rec in out t_row) is
    l_n number;
  begin
    p_rec.items.delete;
    l_n := p_rows(1).items.count;
    select count(*) into l_n from dual where dummy = p_rows(1).items(1);
    fill(p_rec.items(1));
    helper(l_n);
    helper(h_a => l_n);
  end;
end pk_p;
/
create or replace function f_x(p_v number) return number is
begin
  return p_v + 1;
end;
/
