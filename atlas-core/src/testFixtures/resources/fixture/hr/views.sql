create or replace view v_company_list (company_id, company_label) as
  select id, company_name from company
/
create or replace view v_company_calc as
  select c.id, c.company_name label, length(c.company_name) * 2 as name_len from company c
/
create or replace view v_send_all as
  select * from send_queue
/
create or replace view v_send_q as
  select q.*, 'x' tag from send_queue q
/
create or replace view v_log_union as
  select id, msg text from event_log
  union all
  select id, payload other_text from send_queue
/
create or replace view v_active_company as
  with act as (select id, company_name from company where st_active = 'S')
  select a.id, a.company_name from act a
/
create or replace view v_company_names as
  select l.company_id, l.company_label from v_company_list l
/
create or replace view v_source_only as
  select id, msg from event_log
/
