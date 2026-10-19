create materialized view mv_qstar
  refresh complete on demand
as
  select t.* from t_a t
/
