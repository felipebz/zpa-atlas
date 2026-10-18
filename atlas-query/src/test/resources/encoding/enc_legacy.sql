create or replace procedure enc_legacy(p_a in number) is
  v_x number;
begin
  /* ação € */ v_x := p_a; enc_legacy(v_x);
end;
/
