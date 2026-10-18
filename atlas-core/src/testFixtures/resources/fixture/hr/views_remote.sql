create or replace view v_remote_same as
  select r.cd_req from desenv.remote_req@far.example.com r
/

create or replace view v_remote_differs as
  select r.cd_req, c.company_name from desenv.remote_req@far.example.com r, company c
/
