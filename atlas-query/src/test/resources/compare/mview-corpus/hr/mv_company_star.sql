create materialized view mv_company_log
  refresh complete on demand
as
  select c.* from company c
/
