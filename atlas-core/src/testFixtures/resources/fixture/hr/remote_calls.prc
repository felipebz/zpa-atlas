create or replace procedure remote_calls is
  v number;
begin
  notify_req@far(1, 'a');
  pack_req.close_req@far.example.com(2, p_note => null);
  desenv.pack_req.close_req@far.example.com(3);
  v := next_req@far(4);
  select next_req@far(5) into v from dual;
  pack_req.reopen@far;
  v := pack_req.c_max@far;
  select seq_req.nextval@far into v from dual;
end;
/
