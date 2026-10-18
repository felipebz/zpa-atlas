create materialized view mv_company_log as
  select c.id, c.company_name, l.company_label
    from company c join v_company_list l on l.company_id = c.id
/
