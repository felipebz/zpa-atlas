create or replace view v_lin_scalar as
  select c.id, (select max(e.msg) from event_log e where e.id = c.id) last_msg from company c
/
create or replace view v_lin_case as
  select case when c.st_active = 'S' then c.company_name else 'none' end label from company c
/
create or replace view v_lin_agg as
  select c.st_active, count(*) total, max(c.dt_changed) last_change from company c group by c.st_active having count(*) > 1
/
create or replace view v_lin_analytic as
  select c.id, row_number() over (partition by c.st_active order by c.dt_changed) rn from company c where c.company_name is not null
/
create or replace view v_lin_none as
  select 1 one, rownum rn, sysdate today, seq_event.nextval next_id, upper('x') up from company
/
create or replace view v_lin_inline as
  select x.a, b from (select id a, company_name b from company) x
/
create or replace view v_lin_with as
  with base (k, label) as (select id, company_name from company),
       upper_base as (select k, upper(label) label from base)
  select u.k, u.label from upper_base u
/
create or replace view v_lin_union (k, v) as
  select id, payload from send_queue
  union all
  select * from event_log
/
create or replace view v_lin_short (a, b) as
  select id, company_name from company
/
create or replace view v_lin_with_star as
  with w as (select id, msg from event_log)
  select * from w
/
create or replace view v_lin_recursive as
  with r (n) as (select id from company union all select n + 1 from r where n < 3)
  select n from r
/
create or replace view v_lin_fn as
  select f.col_a from table(pack_process.f_rows) f
/
create or replace view v_lin_join as
  select c.id, c.company_name, e.msg from company c join event_log e on e.id = c.id
/
create or replace view v_chain1 as
  select id, company_name from company
/
create or replace view v_chain2 as
  select id, company_name label from v_chain1
/
