create or replace procedure proc_columns(id number, msg varchar2) is
  v number;
  company_name varchar2(100);
  cursor c_join is select c.company_name, s.record_type from company c join send_queue s on s.id = c.id;
begin
  select count(*) into v from company where id = id and company_name = proc_columns.company_name;
  select count(*) into v from hr.company where hr.company.st_active = 'S';
  select count(*) into v from company c where exists (select 1 from send_queue s where s.id = c.id and record_type = st_active);
  select count(*) into v from (select id, company_name n from company) iv where iv.n = 'x' and n is not null and iv.id > 0;
  select count(*) into v from (select * from company) iv where payload = 1;
  with w as (select id, record_type from send_queue) select count(*) into v from w where record_type = 'J' and w.id = 1;
  select count(*) into v from company c, send_queue s where c.id = s.id and id = 1;
  select count(id) into v from company join send_queue using (id);
  for r in (select company_name cn from company order by cn) loop null; end loop;
  select count(*) into v from company where rownum = 1 and rowid is not null and dt_changed < sysdate;
  for r in (select * from v_company) loop null; end loop; for r in (select c.*, s.id from company c, send_queue s) loop null; end loop;
  update company set company_name = msg, st_active = company_name where id = v;
  insert into event_log (id, msg) values (v, fn_one) returning msg into company_name;
  insert into event_log (id, msg) select id, company_name from company;
  merge into company d using send_queue s on (d.id = s.id) when matched then update set company_name = record_type where st_active = 'S' when not matched then insert (id, company_name) values (s.id, payload);
  select count(*) into v from missing_table m where m.x = 1 and y = 2;
  select count(*) into v from missing_table where fn_one = 1; select count(*) into v from missing_table where a = id and b = v and c < sysdate;
  select count(*) into v from missing_table, send_queue where zz = 1;
  select count(*) into v from table(sys.odcinumberlist(1)) where column_value = 1; select count(*) into v from company c where exists (select 1 from unknown_b b where flag = st_active);
end;
/
