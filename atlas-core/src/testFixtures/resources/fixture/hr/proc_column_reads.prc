create or replace procedure proc_column_reads is
  n number;
begin
  for r in (select c.id from company c, v_company v where c.id = v.id order by id) loop null; end loop;
  for r in (select c.id from company c, v_company v union all select v.id from company c, v_company v order by id) loop null; end loop;
  for r in (select c.company_name from company c, v_company v where c.id = v.id order by nvl(company_name, 'x')) loop null; end loop;
  for r in (select c.id, v.id from company c, v_company v where c.id = v.id order by id) loop null; end loop;
  for r in (select c.id x from company c, v_company v where c.id = v.id order by id) loop null; end loop;
  merge into company d using send_queue s on (d.id = s.id)
    when not matched then insert (id, company_name) values (id, payload);
  for i in (select 'X' y from dual) loop
    select count(*) into n from company i where i.id = i.y;
  end loop;
  select count(*) into n from company i where exists (select 1 from event_log i where i.st_active = 'S');
  select count(*) into n from company i where i.no_such_column = 1;
end proc_column_reads;
/
