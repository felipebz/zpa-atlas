create or replace procedure imp_rec(p_n number) is
begin
  if p_n > 0 then
    imp_rec(p_n - 1);
  end if;
  pack_imp_a.mid(p_n);
end;
/
