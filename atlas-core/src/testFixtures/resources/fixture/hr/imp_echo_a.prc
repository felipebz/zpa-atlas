create or replace procedure imp_echo_a(p_n number) is
begin
  imp_echo_b(p_n);
end;
/
