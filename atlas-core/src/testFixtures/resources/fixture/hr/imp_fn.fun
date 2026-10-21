create or replace function imp_fn(p_n number) return number is
begin
  pack_imp_a.leaf(p_n);
  return p_n;
end;
/
