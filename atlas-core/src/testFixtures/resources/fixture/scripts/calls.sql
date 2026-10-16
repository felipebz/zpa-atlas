begin
  p_pub(1);
  pack_calls.p_priv(1);
  run_job(1, 'a', 2, sysdate);
end;
/
select pack_calls.f_pub(id) from company;
