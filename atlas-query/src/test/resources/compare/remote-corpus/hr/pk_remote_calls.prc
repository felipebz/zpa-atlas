create or replace procedure pk_remote_calls is
begin
  pack_req.close_req@far.example.com(1);
  desenv.notify_req@far.example.com(2);
end;
/
