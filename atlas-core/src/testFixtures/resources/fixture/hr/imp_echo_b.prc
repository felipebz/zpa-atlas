create or replace procedure imp_echo_b(p_n number) is
begin
  if p_n > 0 then
    imp_echo_a(p_n - 1);
  end if;
end;
/
