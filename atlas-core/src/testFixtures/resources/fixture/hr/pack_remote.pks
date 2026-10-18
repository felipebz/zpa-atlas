create or replace package pack_remote is
  r1 desenv.remote_req@far.example.com%rowtype;
  r2 remote_req@far%rowtype;
  v1 desenv.remote_req.cd_req@far.example.com%type;
  v2 remote_req.cd_req@far%type;
  type t1 is table of desenv.remote_req@far.example.com%rowtype;
  cursor c1 is select cd_req from desenv.remote_req@far.example.com;
  function subject(p_req in remote_req.cd_req@far.example.com%type) return remote_req.ds_subject@far.example.com%type;
end;
/
