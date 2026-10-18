create or replace procedure proc_writes is
  r company%rowtype;
begin
  update company set st_active = 'N', dt_changed = sysdate where id = 1;
  update company set st_active = null;
  update company set st_active = '';
  update company set (st_active, company_name) = (select 'S', 'x' from dual);
  update company set company_name = company_name || '!';
  insert into company (id, st_active) values (1, 'S');
  insert into company values (2, 'S', 'b', sysdate);
  insert into company (id, st_active) select id, 'N' from company;
  insert into company (id, st_active) select id, st_active from company union select 1, 'S' from dual;
  insert into company (id, st_active) values (3, default);
  insert into company values r;
  merge into company c using (select 1 id from dual) s on (c.id = s.id)
    when matched then update set st_active = 'S'
    when not matched then insert (id, st_active) values (s.id, 'N');
  execute immediate 'update company set st_active = ''X'' where id = :1' using 1;
  insert into company (id, st_active) (select 4, 'P' from dual);
end;
/
