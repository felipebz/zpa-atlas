create or replace procedure zpa_probe.p_owned is
begin
  pk_customers.deactivate(2);
end;
/
create or replace procedure "ZPA_PROBE"."P_QUOTED" is
begin
  insert into t_audit (id, msg) values (0, 'quoted');
end;
/
