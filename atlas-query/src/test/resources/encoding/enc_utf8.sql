create or replace procedure enc_tab(p_a in number) is
	v_x	number;
begin
	/* ação */	v_x := p_a; enc_tab(v_x);
	select /* é */ count(*) into v_x from enc_t where c = 'ção' and c = p_a;
end;
/
