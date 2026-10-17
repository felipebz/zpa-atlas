create or replace view v_inline as
  select * from (select id x, company_name from company)
/
create or replace view v_inline_nested as
  select * from (select * from (select id, msg note from event_log))
/
create or replace view v_inline_union as
  select * from (select id, msg from event_log union all select id, payload from send_queue)
/
create or replace view v_table_fn as
  select * from table(pack_process.f_rows)
/
create or replace view v_partial as
  select p.*, 1 one from partial_t p
/
