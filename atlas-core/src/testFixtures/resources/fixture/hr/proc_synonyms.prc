create or replace procedure proc_synonyms is
  n number;
  r_tab user_tables%rowtype;
  v_name user_tables.table_name%type;
  v_sid v$session.sid%type;
begin
  select num_rows into n from user_tables where table_name = 'X';
  select t.no_such_col into n from user_tables t;
  for r in (select * from user_tables) loop null; end loop;
  for r in (select t.table_name, c.column_name from user_tables t, user_tab_columns c where t.table_name = c.table_name) loop null; end loop;
  for r in (select table_name from user_tables t, user_tab_columns c) loop null; end loop;
  select count(*) into n from v$session where username = 'HR';
  select count(*) into n from syn_company where st_active = 'S';
  select count(*) into n from hr.syn_company;
  update syn_company set st_active = 'N';
  select count(msg) into n from event_log;
  select count(*) into n from missing_view;
  select count(*) into n from syn_chain1 where payload is null;
  select count(*) into n from syn_loop_a;
end proc_synonyms;
/
