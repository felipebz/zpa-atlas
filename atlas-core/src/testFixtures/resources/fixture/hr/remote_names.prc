create or replace procedure remote_names is
  v number;
  b boolean;
  cursor c is select seq_req.nextval@far n, desenv.seq_req.currval@far.example.com m from dual;
begin
  v := next_req@far;
  v := pack_req.get_max@far.example.com;
  v := desenv.pack_req.get_max@far.example.com + 1;
  b := pack_req.is_open@far;
  v := seq_req.nextval@far;
  insert into company (id) values (seq_req.nextval@far);
  select remote_req.cd_req@far into v from remote_req@far where rownum = 1;
  pack_req.v_x@far := 1;
end;
/
