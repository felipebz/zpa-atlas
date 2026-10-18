create or replace package pk_remote is
  function subject(p_req in remote_req.cd_req@far.example.com%type) return remote_req.ds_subject@far.example.com%type;
end;
/
