create or replace procedure pk_remote_names is
  v number;
begin
  select desenv.seq_req.nextval@far.example.com into v from dual;
  v := pack_req.get_max@far.example.com;
end;
/
