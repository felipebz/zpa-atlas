create or replace procedure proc_view_writes is
begin
  insert into v_company_list (company_id, company_label) values (10, 'via list');
  update v_company_names set company_label = 'via names' where company_id = 10;
  update v_company_calc set label = 'via calc' where id = 10;
  insert into v_company_ioi (id, company_name) values (11, 'via trigger');
  insert into mv_company_log (id) values (12);
end;
/
